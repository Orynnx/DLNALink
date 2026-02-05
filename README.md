# DLNALink

DLNALink 是一个 Android DLNA/UPnP 投屏客户端。它会通过 SSDP 在局域网内发现
MediaRenderer 设备，然后使用 AVTransport/SOAP 将媒体链接推送到设备播放。

## 功能

- SSDP 发现 DLNA/UPnP 设备（MediaRenderer）
- 通过 AVTransport 投屏（SetAVTransportURI + Play）
- 支持输入或分享媒体链接（MP4/M3U8 等）
- 内置调试面板与日志复制

## 环境要求

- Android Studio（推荐）
- JDK 11
- Android 14+（目标 API 34+）

## 构建

```bat
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

## 使用

1. 手机与 DLNA 设备连接同一 Wi‑Fi。
2. 打开应用并授权位置权限（用于读取 SSID/网络状态）。
3. 输入或分享媒体链接。
4. 扫描设备并选择目标投屏。

## 权限说明

- `ACCESS_FINE_LOCATION`：读取 Wi‑Fi SSID/网络状态（Android 12+）
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`：SSDP 发现设备
- `INTERNET`：获取设备描述与发送控制指令

## 已编译 APK

编译产物已单独保存在：

- `artifacts/app-debug.apk`

## 目录结构

- `app/src/main/java/com/orynnx/dlnalink/`：Kotlin 源码
- `app/src/main/res/`：资源文件
- `app/src/main/AndroidManifest.xml`：清单文件
- `debug_ssdp.py` / `dlna_cast.py`：辅助调试脚本

## 许可证

MIT License。详见 `LICENSE`。
