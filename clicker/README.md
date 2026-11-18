# 连点器（安卓）

自动点击工具：多个点按顺序点击，每一步可以设置自己的间隔（预设 0.1 秒 ～ 1 分钟，含 30 秒、40 秒，也能自定义），
支持长按、滑动、循环次数 / 运行时长、定时开始、随机间隔和随机位置、多套方案、音量下键紧急停止。不需要 root。

## 安装

在手机浏览器打开：
https://github.com/BlooSim/notes/releases/download/clicker-latest/clicker.apk

下载后点开安装。第一次要在系统设置里开启「连点器」的无障碍服务（App 里有按钮和说明）。

## 构建

推送到 `clicker/` 目录后，GitHub Actions 自动编译，APK 发布到 `clicker-latest` 这个 Release。
签名使用目录里固定的 `clicker-signing.keystore`，保证每次构建都能覆盖安装。
