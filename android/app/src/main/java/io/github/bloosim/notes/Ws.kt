package io.github.bloosim.notes

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A small WebSocket client (RFC 6455): just what Deepgram's live transcription needs. */
class Ws private constructor(private val sock: Socket, private val inp: InputStream, private val out: OutputStream) {
    /** the server answered the upgrade request with an HTTP error */
    class HandshakeErr(val code: Int, val body: String) : IOException("HTTP $code")

    @Volatile var closeCode = 0
        private set
    @Volatile var closeReason = ""
        private set
    @Volatile private var sentClose = false
    private val rnd = SecureRandom()

    companion object {
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** Blocking. Opens the connection and completes the WebSocket handshake. */
        fun connect(url: String, headers: Map<String, String>, timeoutMs: Int): Ws {
            val u = URI(url)
            val secure = u.scheme.equals("wss", ignoreCase = true)
            val host = u.host
            val port = if (u.port > 0) u.port else if (secure) 443 else 80
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(host, port), timeoutMs)
                raw.soTimeout = timeoutMs
                raw.tcpNoDelay = true
                val s: Socket = if (secure) {
                    val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true) as SSLSocket
                    try {
                        val p = ssl.sslParameters
                        p.serverNames = listOf(SNIHostName(host))
                        ssl.sslParameters = p
                    } catch (_: Exception) { }
                    ssl.startHandshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) throw SSLException("certificate is not for $host")
                    ssl
                } else raw
                val inp = BufferedInputStream(s.getInputStream(), 16384)
                val out = s.getOutputStream()
                val key = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
                val path = (u.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
                val req = StringBuilder()
                req.append("GET ").append(path).append(" HTTP/1.1\r\n")
                req.append("Host: ").append(host).append(if (u.port > 0) ":$port" else "").append("\r\n")
                req.append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                req.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                req.append("Sec-WebSocket-Version: 13\r\n")
                headers.forEach { (k, v) -> req.append(k).append(": ").append(v).append("\r\n") }
                req.append("\r\n")
                out.write(req.toString().toByteArray(Charsets.UTF_8))
                out.flush()

                val status = readLine(inp)
                val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                val hdr = HashMap<String, String>()
                while (true) {
                    val l = readLine(inp)
                    if (l.isEmpty()) break
                    val i = l.indexOf(':')
                    if (i > 0) hdr[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
                }
                if (code != 101) {
                    s.soTimeout = 3000
                    val len = hdr["content-length"]?.toIntOrNull() ?: 2048
                    val body = ByteArray(len.coerceIn(0, 4096))
                    var got = 0
                    try {
                        while (got < body.size) { val r = inp.read(body, got, body.size - got); if (r < 0) break; got += r }
                    } catch (_: IOException) { }
                    throw HandshakeErr(code, String(body, 0, got, Charsets.UTF_8))
                }
                val expect = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.UTF_8)))
                if (hdr["sec-websocket-accept"] != expect) throw IOException("bad websocket handshake")
                return Ws(s, inp, out)
            } catch (e: Exception) {
                try { raw.close() } catch (_: Exception) { }
                throw e
            }
        }

        private fun readLine(inp: InputStream): String {
            val b = ByteArrayOutputStream()
            while (true) {
                val c = inp.read()
                if (c < 0) throw EOFException("connection closed")
                if (c == '\n'.code) break
                if (c != '\r'.code) b.write(c)
                if (b.size() > 8192) throw IOException("header too long")
            }
            return b.toString("UTF-8")
        }
    }

    /** how long a read may wait before the connection counts as dead */
    var readTimeoutMs: Int
        get() = sock.soTimeout
        set(v) { sock.soTimeout = v }

    @Synchronized private fun frame(op: Int, data: ByteArray) {
        val n = data.size
        val ext = when { n < 126 -> 0; n < 65536 -> 2; else -> 8 }
        val buf = ByteArray(2 + ext + 4 + n)
        buf[0] = (0x80 or op).toByte()
        var o = 2
        when (ext) {
            0 -> buf[1] = (0x80 or n).toByte()
            2 -> { buf[1] = (0x80 or 126).toByte(); buf[2] = (n ushr 8).toByte(); buf[3] = n.toByte(); o = 4 }
            else -> { buf[1] = (0x80 or 127).toByte(); for (i in 0 until 8) buf[2 + i] = (n.toLong() ushr (8 * (7 - i))).toByte(); o = 10 }
        }
        val mask = ByteArray(4).also { rnd.nextBytes(it) }
        System.arraycopy(mask, 0, buf, o, 4)
        o += 4
        for (i in 0 until n) buf[o + i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        out.write(buf)
        out.flush()
    }

    fun sendText(s: String) = frame(1, s.toByteArray(Charsets.UTF_8))
    fun sendBinary(b: ByteArray) = frame(2, b)

    /** starts the closing handshake */
    fun close(code: Int = 1000) {
        if (sentClose) return
        sentClose = true
        try { frame(8, byteArrayOf((code shr 8).toByte(), code.toByte())) } catch (_: Exception) { }
    }

    fun shutdown() {
        try { sock.close() } catch (_: Exception) { }
    }

    private fun rd(): Int {
        val c = inp.read()
        if (c < 0) throw EOFException("connection closed")
        return c
    }

    private fun readFully(b: ByteArray) {
        var got = 0
        while (got < b.size) {
            val r = inp.read(b, got, b.size - got)
            if (r < 0) throw EOFException("connection closed")
            got += r
        }
    }

    /** Blocking. Hands each text message to [onText] until the server closes the connection. */
    fun readLoop(onText: (String) -> Unit) {
        val msg = ByteArrayOutputStream()
        var msgOp = 0
        while (true) {
            val b0 = rd()
            val b1 = rd()
            val fin = (b0 and 0x80) != 0
            val op = b0 and 0x0F
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((rd() shl 8) or rd()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or rd().toLong() } }
            if (len < 0 || len > 16_000_000) throw IOException("frame too big")
            val mask = if ((b1 and 0x80) != 0) ByteArray(4).also { readFully(it) } else null
            val data = ByteArray(len.toInt())
            readFully(data)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
            when (op) {
                8 -> {
                    closeCode = if (data.size >= 2) ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF) else 1005
                    closeReason = if (data.size > 2) String(data, 2, data.size - 2, Charsets.UTF_8) else ""
                    close(if (closeCode == 1005) 1000 else closeCode)
                    return
                }
                9 -> try { frame(10, data) } catch (_: Exception) { }
                10 -> { }
                1, 2 -> { msg.reset(); msgOp = op; msg.write(data, 0, data.size) }
                0 -> msg.write(data, 0, data.size)
            }
            if (fin && (op == 0 || op == 1 || op == 2)) {
                if (msgOp == 1) onText(msg.toString("UTF-8"))
                msg.reset()
            }
        }
    }
}
