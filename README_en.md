# Braga Digital Studio Mobile

*Leia isso em [Português](README.md)*

**Braga Digital Studio Mobile** is a professional and proprietary video monitoring, recording, and streaming software exclusively built for Android devices.

The core goal of this project is to transform your Android smartphone into a high-end, professional monitoring tool for audiovisual production, fully leveraging the mobility, connectivity, and display quality of the Android ecosystem.

## Main Objectives and Features (Roadmap)

### 🖥️ External Monitor
Use your device as a portable monitor via USB UVC connection, Wi-Fi network, or Sony remote camera. The system is architected to dynamically switch between different video sources, such as:
* The smartphone's native camera
* Cameras via HDMI/USB UVC capture cards
* **Sony cameras via Wi-Fi (Sony Camera Remote API)** ← New!

### 📷 Sony Wi-Fi Remote Capture ← New!
Turn a Sony camera (α6000 and any camera compatible with the Sony Camera Remote API) into a remote video source:
* Automatic discovery via **SSDP** with fallback to Wi-Fi Direct IP (`DIRECT-xxxx`)
* Ultra-low latency MJPEG liveview (~5 ms per frame) over a raw TCP socket with `TCP_NODELAY`
* Remote controls: ISO, shutter speed, F-number, exposure compensation, touch-to-focus and shutter trigger
* Real-time telemetry: battery level, card storage remaining, and focus status
* Automatic reconnection without crashes when leaving Wi-Fi Direct range
* Fully transparent routing through `MediaGraph` — preview, recording, NDI and LUTs work without any changes

### 🎛️ Advanced Monitoring Tools
Focused on assisting creators with framing and exposure accurately and in real-time:
* Focus Peaking, False Color, Zebra
* Histogram, Waveform, Vectorscope
* Safe Area, Grids
* Support for custom LUTs (.cube files) for monitoring only

### 🎙️ Professional Audio & Recording
* Independent inputs for video and audio.
* Support for USB microphones.
* 4K Recording in H.264 / H.265 formats (MP4).
* Direct recording to SD cards or external SSDs.

### 📡 Streaming and NDI
* Native streaming simultaneously while recording.
* NDI protocol support (Ultra-low latency local video transmission over the network).

## Architecture & Technology Stack

This project is built on a highly modular foundation using **Kotlin**, prioritizing low latency, high performance, and optimized rendering. The architecture follows **Clean Architecture** and **MVVM** principles.

* **UI:** Jetpack Compose and Material 3
* **Dependency Injection:** Hilt
* **Database and Persistence:** Room and DataStore
* **Video Capture:** Camera2 API, UVC, and **Sony Camera Remote API (Wi-Fi)**
* **Concurrency:** Kotlin Coroutines and StateFlow

**Modular Structure:**
- `:app`: Application shell, navigation, and splash flow.
- `:common`: Shared UI, navigation routes, and domain models.
- `:core`: Persistence contracts (Room, DataStore).
- `:core-capture`: Capture device abstractions (`Camera2Device`, `UvcCaptureDevice`, `SonyRemoteCaptureDevice`).
- `:core-network`: Sony Camera Remote API protocol (SSDP discovery, JSON-RPC client, liveview socket reader) and the LinkServer (NDI/REST/WebSocket).
- `:core-media`: MediaGraph, the layer responsible for transparent frame routing across all capture sources.
- `:feature-*`: Independent modules focused on specific user flows (home, preview, settings).

## How to Compile and Run

1. Open the project in **Android Studio**.
2. Make sure **JDK 17** is selected in Gradle settings.
3. Sync the Gradle project.
4. Build or run the `:app` module on a **physical Android device** (Complex camera hardware and acceleration features do not work well on emulators).

> To test Sony Wi-Fi capture, connect the Android device to the camera's Wi-Fi Direct network (`DIRECT-xxxx:MODEL`) and select **"SONY"** as the video source in the app settings.

## License

Copyright © 2024-2026 Mauro Braga Filho / Braga Digital Studio. All rights reserved.

This software is proprietary and closed-source. Any unauthorized copying, redistribution, reverse engineering, or modification of any part of this source code is strictly prohibited. See the [LICENSE](LICENSE) file for more details.
