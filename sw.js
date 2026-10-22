/*
 * Keeps the page itself available, so the home-screen icon opens instantly and still works
 * with a bad connection. Network first, so a new version always lands when there is a network;
 * the cached copy is only used when the network fails. Only our own files pass through here —
 * recognition and translation go to other sites and are never touched.
 */
const CACHE = 'biji-shell-v1';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).catch(() => { }).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  let url;
  try { url = new URL(req.url); } catch (err) { return; }
  if (url.origin !== self.location.origin) return; // other sites: left alone
  e.respondWith((async () => {
    try {
      const net = await fetch(req);
      if (net && net.ok && net.type === 'basic') {
        const c = await caches.open(CACHE);
        c.put(req, net.clone()).catch(() => { });
      }
      return net;
    } catch (err) {
      const hit = await caches.match(req, { ignoreSearch: req.mode === 'navigate' });
      if (hit) return hit;
      if (req.mode === 'navigate') {
        const idx = await caches.match('./index.html') || await caches.match('./');
        if (idx) return idx;
      }
      throw err;
    }
  })());
});
