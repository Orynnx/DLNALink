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

### Session queue (1.5)

Share or open local videos in DLNALink, or add an HTTP(S) link. Local media is copied into private app-managed storage before it is queued, so a completed copy no longer depends on the original app's temporary file permission. The source is never moved or deleted. Remote links are queued without downloading and may still expire.

The first shared item tries the paired bridge or last discovered DLNA target; later items are appended without interrupting playback. **Play** selects an item, **Next** manually requests the following one, and **Remove** revokes only that item's hosted URL and cached copy. Multiple-video shares are queued in order. Automatic advance is deliberately absent because the TV bridge has no completion feedback.

While the session is active, a foreground notification offers **Play / Next / Stop sharing**; tapping it opens the queue. Allow notifications to see these controls. Phone hosting and completed cached files do not depend on keeping the Activity visible. **Close session / Stop sharing** closes the phone HTTP listener and clears only app-owned session cache and queue metadata; original files and TV pairing are retained. It does not send a Stop command to the TV. If an import is in progress, Stop invalidates it and cleanup finishes when the provider's read returns.

Cache limit: **2 GiB total**, with **128 MiB disk reserve**. An import that exceeds the quota or fails is not promoted from its temporary file. Imports stream to disk, not RAM. The queue survives app recreation and can be restored after process death; press Play to resume with a fresh local URL. Android/OEM process termination and network changes can still interrupt a session. Keep Wi-Fi connected and close the session when finished to release the CPU wake lock and storage. Session media and pairing preferences are excluded from backup/transfer.

Validation: JVM tests cover copy survival after source deletion, append without invalidating earlier files/URLs, quota bounds, interrupted-copy cleanup, path rejection, real HTTP HEAD body suppression, byte ranges and removed URLs. Phone content-provider, notification, screen-off and sustained TV playback checks remain required on-device.

### Paired TV VLC bridge

Install the separate `tvbridge` APK on the TV and open **DLNALink TV Bridge**. Enable the TV bridge, then choose **Scan TV pairing QR** in the phone app and scan the TV screen. The built-in camera scanner decodes locally; the app validates a versioned private-LAN address, port and key, verifies authenticated `/status`, then saves and enables paired mode. Invalid scans or failed authentication do not replace an existing pairing. Manual address/token fields remain an advanced fallback. The test does not prove media playback. Incoming media uses the paired bridge instead of built-in DLNA. The receiver accepts bounded HTTP(S) playback requests and opens the installed VLC package.

Background VLC launch requires the TV bridge's optional display-over-other-apps permission and a small visible **TV Bridge** badge. A foreground notification keeps the receiver alive. The receiver binds a Wi-Fi/Ethernet private IPv4 address on port 46901. No boot autostart is configured: reopen the bridge after reboot. QR pairing is initiated explicitly, not automatic discovery. Anyone scanning the QR obtains playback authority; keep it private. LAN HTTP is unencrypted; do not expose/forward this port. Disable the bridge on its TV screen or force-stop it; revoke overlay permission to disable background launch. Phone pairing preferences are excluded from backup and device transfer.

Device checks: unauthorized playback rejected; non-HTTP URI rejected; foreground and Home-screen dispatch opened TV VLC. One phone-hosted playback reported playing, but later attempts stopped loading; sustained repeat playback and phone UI integration remain unverified.

### Open as an external player (local enhancement)

Choose DLNALink in another app's **Open with / external player** menu for an HTTP(S) video, HLS or DASH URI. DLNALink scans for receivers and attempts playback on the last manually picked receiver. If no saved receiver is discovered, pick one in the device list; if playback fails, pick a receiver to retry. New intents are handled without opening another activity.

This sends the URI to a DLNA receiver, not to TV VLC. Remote HTTP(S) streams are fetched by the TV itself. Local `content://` and `file://` media are hosted by the phone on its Wi-Fi address using an unguessable single-item URL. The sender must grant read access. Keep the phone on Wi-Fi; use the foreground notification's **Stop sharing** action when finished. Opening another local item replaces the previous hosted item. The relay supports GET/HEAD and single byte ranges for seeking when the provider supplies a file size; unknown-size streams use chunked transfer without seeking. Permission/provider failures are reported instead of granting broad storage access. The relay is unencrypted LAN HTTP; anyone holding its URL can read the selected item while sharing is active.

DRM, remote streams requiring custom authentication headers, and local HLS/DASH playlists referencing separate segment files are not supported by this relay. Actual phone-to-TV background playback and provider compatibility still require device testing.

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

## Shared TV queue (phone 1.7 / bridge 1.3)

Paired phones share one TV queue and may play, pause, resume, skip, reorder,
remove entries, stop playback, or clear the queue. Clearing the TV queue does not
stop current playback or delete phone caches. Only one item plays at a time;
automatic advance when a video ends is not implemented.

Android Share → DLNALink appends recognized HTTP(S) video/audio/stream links
without interrupting current playback. Ordinary webpage links are not extracted
as playable media. Local shared files are copied into the sending phone's cache;
that phone must remain reachable while the TV reads them. Work-profile content
access depends on the source app granting permission and requires device testing.

The queue refreshes every two seconds while the screen is active. Concurrent
reorder conflicts refresh the queue rather than overwrite another client's order.
The bridge stores up to 100 entries privately; all paired clients have equal control.

Acceptance: concurrent append, deduplication, stale reorder rejection, offline
source retention, queue clear, and actual VLC play/pause/resume/stop verified on TV.
Phone installation, share-sheet and multi-phone UI testing remain pending.

## 许可证

MIT License。详见 `LICENSE`。
