# Braga Digital Studio Mobile

*Leia isso em [Português](README.md)*

**Braga Digital Studio Mobile** is a professional and proprietary video monitoring, recording, and streaming software exclusively built for Android devices.

The core goal of this project is to transform your Android smartphone into a high-end, professional monitoring tool for audiovisual production, fully leveraging the mobility, connectivity, and display quality of the Android ecosystem.

## Main Objectives and Features (Roadmap)

### 🖥️ External Monitor
Use your device as a portable monitor via USB UVC connection (HDMI-USB capture card) or the phone camera. The system is architected to dynamically switch between different video sources, such as:
* The smartphone's native camera
* HDMI cameras (such as the Sony α6000) via HDMI/USB UVC capture cards

### 📷 HDMI cameras (Sony α6000 etc.) as a video source
Connect the camera's clean HDMI output to a **UVC** HDMI→USB capture card (it shows up as a webcam) and plug it into the phone over USB OTG; choose **"Câmera USB"** (USB camera) in Settings > Camera. On the α6000, turn "Info. HDMI" off (Menu > HDMI Settings > Info. HDMI > Off) for a clean output. Capture cards that need their own driver (e.g. Elgato HD60 S) do not work on Android. Preview, recording, NDI and LUTs work from this source through `MediaGraph`.

> The old "Sony Camera (Wi-Fi)" source (Camera Remote API, low-resolution JPEG) is **disabled** by a single switch (`CaptureFeatureFlags.SONY_WIFI_ENABLED`, currently `false`); the code stays in the repository and can be turned back on (see `.docs/ARQUITETURA.md`).

### 🎛️ Advanced Monitoring Tools
Focused on assisting creators with framing and exposure accurately and in real-time:
* Focus Peaking, False Color, Zebra
* Histogram, Waveform, Vectorscope
* Grids and aspect-ratio markers (Safe Area and anamorphic de-squeeze are planned)
* Support for custom LUTs (.cube files) for monitoring only

### 🎙️ Professional Audio & Recording
* Independent inputs for video and audio.
* Support for USB microphones.
* 4K Recording in H.264 / H.265 formats (MP4).
* Recording destination: app storage, Gallery (Android 10+) or a chosen folder (SAF, including external SD/SSD). The take is recorded locally and copied to the destination at the end.
* The capture session is independent of the screen: with recording, NDI or BSP active it keeps running with the screen off or the app on the Home screen (`camera|microphone` foreground service).

### 📡 Streaming and NDI
* Native streaming simultaneously while recording.
* NDI protocol support (Ultra-low latency local video transmission over the network). On the network the source shows up as `BDSM (device name)`.
* BSP (H.264 over RTP/UDP): in-house protocol, **under development**.

### 🔗 BDSM Link (OBS and local network)
Embedded HTTP/WebSocket server (port 8080, mDNS `_bdsm._tcp`) with **token pairing approved on the phone**: without a token every data route answers 401. Real-time telemetry (REC, source, lens, fps, battery, microphone), OBS tally (red/green border on the monitor), LUT upload and resumable recording downloads (Range). Turn it on/off and revoke devices in Settings. Protocol in [`.docs/BDSM_PLUGIN_OBS.md`](.docs/BDSM_PLUGIN_OBS.md) (Portuguese).

## Architecture & Technology Stack

This project is built on a highly modular foundation using **Kotlin**, prioritizing low latency, high performance, and optimized rendering. The architecture follows **Clean Architecture** and **MVVM** principles.

* **UI:** Jetpack Compose and Material 3
* **Dependency Injection:** Hilt
* **Database and Persistence:** Room and DataStore
* **Video Capture:** Camera2 API and UVC (the Sony Camera Remote API Wi-Fi source is disabled by a flag)
* **Concurrency:** Kotlin Coroutines and StateFlow

**Modular Structure:**
- `:app`: Application shell (`BdsmApplication`, `MainActivity`), navigation, manifest, signing and R8.
- `:common`: Shared theme and UI components.
- `:core`: Persistence (Room, DataStore), `NdiNaming`, recording library and shared models (`SonyCameraStatus`).
- `:core-capture`: Capture devices (`Camera2Device`, `UvcCaptureDevice`, `SonyRemoteCaptureDevice`), lens discovery and audio capture.
- `:core-network`: Package `com.bragastudio.mobile.network`: Link server (Ktor/Netty, pairing, telemetry, mDNS), LUT/media services and the Sony Camera Remote API protocol. NDI lives in `:core-media`.
- `:core-media`: `MediaGraph` (owner of the capture session), OpenGL ES/NDI engine in C++, recording (`RecordManager`), NDI, BSP and `CaptureForegroundService`.
- `:feature-home`, `:feature-preview`, `:feature-settings`: Splash/Home, monitor with HUD, and Settings/NDI/LUTs/Recordings.
- `build-logic/`: Gradle convention plugins (`bdsm.android.library`, `.compose`, `.hilt`, `.test`).

## How to Compile and Run

1. Open the project in **Android Studio**.
2. Make sure **JDK 17** is selected in Gradle settings.
3. Sync the Gradle project.
4. Build or run the `:app` module on a **physical Android device** (Complex camera hardware and acceleration features do not work well on emulators).

Command line (Gradle 8.14.5 via the wrapper, `minSdk 26`, `compileSdk 36`, `targetSdk 35`, NDK 27.0.12077973):

```
./gradlew assembleDebug                  # debug APK
./gradlew assembleRelease                # release with R8 (signed with the debug key if no keystore; on a tag CI requires the 4 secrets)
./gradlew lintDebug testDebugUnitTest    # lint and unit tests (294 tests)
./gradlew detekt ktlintCheck             # static analysis (versioned baselines)
```

By default the APK ships `arm64-v8a` and `armeabi-v7a`; for an x86 emulator use `-Pbdsm.abis=arm64-v8a,x86_64`. Run `lintDebug` and `assembleRelease` in separate invocations (they share the KSP output directory).

> To use an HDMI camera (Sony α6000 etc.), use a UVC HDMI→USB capture card and select **"Câmera USB"** in Settings > Camera. The Sony Wi-Fi source is disabled (to turn it back on, see `.docs/ARQUITETURA.md`, "Fontes de vídeo").

## License

Copyright © 2024-2026 Mauro Braga Filho / Braga Digital Studio. All rights reserved.

This software is proprietary and closed-source. Any unauthorized copying, redistribution, reverse engineering, or modification of any part of this source code is strictly prohibited. See the [LICENSE](LICENSE) file for more details.
