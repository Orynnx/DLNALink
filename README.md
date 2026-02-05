# DLNALink

DLNALink is an Android DLNA/UPnP casting client.

> ⚠️ **Vibe Coding Notice**
>
> This project is a result of **Vibe Coding** powered by **Claude Code**.
> The focus is on solving a specific personal itch with AI-generated efficiency.

---

## Background

The motivation behind this app is simple: **To help my mom cast web videos to the TV easily.**

Most existing casting tools are either filled with ads or too complicated for non-tech-savvy users. I needed something dead simple that works directly via the Android "Share" menu—perfect for the "Mom Test."

## Features

- **Mom-Friendly UX**: Designed for simplicity. Cast directly by "Sharing" a link from your browser.
- **SSDP Discovery**: Automatically finds DLNA/UPnP devices (MediaRenderer) on the local network.
- **AVTransport Casting**: Casts media using standard protocols (SetAVTransportURI + Play).
- **Media Support**: Supports common streaming formats like MP4 and M3U8.
- **Debug Tools**: Built-in debug panel and log copying functionality.

## Requirements

- Android Studio (Recommended)
- JDK 11
- Android 14+ (Target API 34+)

## Build

```bat
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

## Usage

1. Connect both your phone and the DLNA device to the **same Wi-Fi network**.
2. Open the app and grant **Location permission** (required to read SSID/Network State).
3. **(Core Workflow)** In your browser or video app, find a video link, tap **Share**, and select **DLNALink**.
4. Scan for devices and tap the target to start playback.

## Permissions

- `ACCESS_FINE_LOCATION`: Required for reading Wi-Fi SSID and Network State (Android 12+ constraint).
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`: Required for SSDP device discovery.
- `INTERNET`: Required for fetching device descriptions and sending control commands.

## Directory Structure

- `app/src/main/java/com/orynnx/dlnalink/`: Kotlin source code
- `app/src/main/res/`: Resource files
- `app/src/main/AndroidManifest.xml`: Manifest file

## License

MIT License. See `LICENSE` for details.


# DLNALink

DLNALink 是一个 Android DLNA/UPnP 投屏客户端。

> ⚠️ **Vibe Coding 声明**
>
> 本项目是使用 **Claude Code** 进行 **Vibe Coding**（直觉编程）的产物。
> 代码主要由 AI 生成，主打一个“能跑就行”和“解决痛点”。

---

## 项目背景

写这个 App 的初衷非常简单：**为了让我妈能方便地把网页上的视频（你懂的）投屏到电视上看。**

现有的投屏工具要么广告多，要么操作太复杂。我需要一个极致简单、只要点击“分享”就能投屏的工具，没有任何多余的按钮，完美契合长辈的使用直觉。

## 功能

- **极简交互**：专为长辈设计，支持从浏览器直接“分享”链接进行投屏。
- **SSDP 发现**：自动发现局域网内的 DLNA/UPnP 设备（MediaRenderer）。
- **AVTransport 投屏**：使用标准协议投屏（SetAVTransportURI + Play）。
- **链接支持**：支持 MP4、M3U8 等常见流媒体格式。
- **调试功能**：内置调试面板与日志复制功能（虽然大概率用不上）。

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
3. **（核心用法）** 在浏览器或其他视频 App 中找到视频链接，点击“分享”，选择 **DLNALink**。
4. 扫描设备并点击目标，即可开始播放。

## 权限说明

- `ACCESS_FINE_LOCATION`：读取 Wi‑Fi SSID/网络状态（Android 12+ 限制）。
- `ACCESS_WIFI_STATE` / `CHANGE_WIFI_MULTICAST_STATE`：SSDP 发现设备。
- `INTERNET`：获取设备描述与发送控制指令。

## 目录结构

- `app/src/main/java/com/orynnx/dlnalink/`：Kotlin 源码
- `app/src/main/res/`：资源文件
- `app/src/main/AndroidManifest.xml`：清单文件

## 许可证

MIT License。详见 `LICENSE`。
