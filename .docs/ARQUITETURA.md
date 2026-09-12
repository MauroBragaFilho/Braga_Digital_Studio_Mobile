# BDSM — Braga Digital Studio Mobile · Arquitetura do App Android

> Documento gerado a partir de análise read-only de todo o código-fonte Kotlin (todos os módulos), código nativo C++, arquivos de build, catálogo de versões e pipeline de CI.

## 1. Visão geral

Aplicativo Android de **monitor profissional para produção audiovisual**: transforma o smartphone em um monitor externo de baixa latência para câmeras (via HDMI‑USB/UVC, Wi‑Fi Sony e câmera interna), com renderização OpenGL ES nativa, ferramentas de exposição/foco (Focus Peaking, False Color, Zebra, Scopes), 3D LUTs (`.cube`), áudio desacoplado, **gravação local** (H.264/AAC via MediaCodec + MediaMuxer) e **transmissão NDI em tempo real** via SDK nativo da NewTek.

Pilares de arquitetura:

- **Multi-módulo Gradle** (9 módulos) com separação `core*` / feature.
- **UI 100% Jetpack Compose + Material 3**, single-activity com NavHost (`Navigation Compose`).
- **DI com Hilt 2.51** em todos os módulos.
- **Núcleo de mídia nativo C++/JNI** (`GlesEngine` + `NdiEngine`, C++17, GLES3/EGL).
- **Data**: Room (gravações e LUTs) + DataStore Preferences (settings) + SQLite, com repositórios no `core`.
- **Rede**: servidor Ktor (Netty/WebSockets/CORS) + descoberta/estado de link NDI.
- **Pipeline de gravação** com padrão worker single-threaded (encoders de hardware + Muxer).

## 2. Stack e versões (catálogo `gradle/libs.versions.toml`)

| Componente | Versão |
| --- | --- |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / KSP | 1.9.22 / 1.9.22-1.0.17 |
| Compose BOM / compiler | 2024.02.01 / 1.5.10 |
| Material3 · Navigation Compose | BOM · 2.7.7 |
| Hilt · hilt-navigation-compose | 2.51 · 1.2.0 |
| Room | 2.6.1 |
| DataStore Preferences | 1.0.0 |
| Coroutines / Serialization | 1.8.0 / 1.6.3 |
| Ktor server (core, netty, websockets, cors) | 2.3.8 |
| UVCAndroid (herohan) | 1.0.8 |
| Coil · AndroidX SQLite · documentfile | 2.6.0 · 2.4.0 · 1.0.1 |

Plataforma: `minSdk 26`, `targetSdk 34`, `compileSdk 34`, Java 17 / JVM target 17, `applicationId` `com.bragastudio.mobile`.

Nativo: C++17, NDK 25.1.8937393, CMake 3.22.1, ABIs `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.

## 3. Módulos e grafo de dependências

| Módulo | Namespace | Responsabilidade | Depende de |
| --- | --- | --- | --- |
| `app` | `com.bragastudio.mobile` | `@HiltAndroidApp`, `MainActivity`, NavHost | todos os demais |
| `common` | `com.bragastudio.mobile.common` | Tema (Color/Theme), componentes de UI (Settings components, dialog) | — (apenas AndroidX) |
| `core` | `com.bragastudio.mobile.core` | Entities `Recording`/`Lut`, DAOs Room, repositórios, settings (DataStore), módulos DI | — (apenas AndroidX) |
| `core-capture` | `com.bragastudio.mobile.corecapture` | Descoberta de câmeras, captura UVC, Sony remote, RecordingEngine (H264/AAC + Muxer), áudio | `core`, `core-network`, UVCAndroid |
| `core-media` | `com.bragastudio.mobile.coremedia` | `GlesEngine` (C++), `NdiEngine` (C++), NdiManager, AudioManager, MediaGraph, pipeline LUT | `core-capture`, `core`, `core-network`, Room, SQLite, documentfile |
| `core-network` | `com.braga.bdsm.network` | Descoberta/links NDI, estado de link, telemetria/metadados, **servidor Ktor**, parser Sony liveview | `core` |
| `feature-home` | `com.bragastudio.mobile.featurehome` | Splash, Home, galeria de gravações | `common`, `core`, Coil |
| `feature-preview` | `com.bragastudio.mobile.featurepreview` | Preview + HUD (peaking, false color, zebra, scopes), painel Sony | `core-media`, `core-capture`, `core-network`, `common`, `core` |
| `feature-settings` | `com.bragastudio.mobile.featuresettings` | Ajustes, LUTs, setup NDI, diagnósticos, galeria de gravações (lote), extra | `common`, `core`, `core-media`, `core-capture` |

```
app
├── common
├── core
├── core-capture ─────→ core, core-network
├── core-media ───────→ core-capture, core, core-network
├── core-network ─────→ core
├── feature-home ─────→ common, core
├── feature-preview ──→ core-media, core-capture, core-network, common, core
└── feature-settings ─→ common, core, core-media, core-capture
```

> **Nota**: `core-network`/`core-capture` usam `implementation` (não `api`), logo não expõem dependências transitivamente — por isso `core-media` e `feature-preview` declaram `core-network` explicitamente (há comentários no Gradle, p.ex. `MediaGraph`/`PreviewHud` referenciam `SonyCameraStatus`).

## 4. Fluxo do aplicativo e navegação

- Única `MainActivity` (`@AndroidEntryPoint`) + classe `App` (`@HiltAndroidApp`).
- `NavHost` com rotas: **Home → Preview** e **Home → Settings**, com sub-rotas aninhadas de Settings (**LUTs, NDI Setup, Diagnósticos, Gravações** e extra).
- Percursos de feature:
  - `feature-home`: `SplashScreen` → `HomeScreen` (atalhos para Live View, LUTs, gravações, transmissão NDI).
  - `feature-preview`: `CameraPreviewScreen` + `PreviewHud` (overlays de exposição/foco) + `SonyRemoteControlPanel`.
  - `feature-settings`: ajustes com `SettingsSection`/`SettingsItem`/`SettingsItemToggle`/`OptionsDialog` do `common`.
- ViewModels scoped via `hiltViewModel()` (`collectAsState` + Flows do repositório/Database).

## 5. Pipeline de captura → render → gravação → NDI

```
Câmera interna (CameraX/Camera2)
        │
Câmera HDMI‑USB (UVC / UVCAndroid) ──┐
        │                            ├──► frame source (YUV/buffers)
Sony (Wi‑Fi liveview, parsing) ──────┘        │
                                              ▼
                          GlesEngine (C++/GLES3 + shaders)
                          • transformação/de-squeeze • 3D LUT
                          • Focus Peaking • False Color • Zebra
                                              │
            ┌─────────────────────────────────┼──────────────────────────────┐
            ▼                                 ▼                              ▼
   Surface da tela (60fps)         NdiEngine (C++, libndi)        RecordingEngine (Kotlin)
                                   transmissão NDI em rede        MediaCodec H264 + AAC
                                                                  MediaMuxer → .mp4
                                                                  (worker single‑threaded)
```

- **Frame source** (`core-capture`): `CameraDiscovery` (interna/UVC), cliente/parser Sony e `UvcCapture`; estados/categorias em `domain/` (modos de captura, tipo de lente, formato de áudio).
- **Render nativo** (`core-media`): `GlesEngine.cpp` renderiza em OpenGL ES 3.0 em um único passe com overlays; shaders em `cpp/shaders/`.
- **Gravação** (`core-capture/recording/`): `H264Encoder`, `AacEncoder`, wrapper `MediaMuxer`, `RecordingEngine` e `AudioController`; persiste via SAF (`documentfile`).
- **Concorrência**: render, NDI e gravação consomem os mesmos frames com sincronização por fila/worker dedicado, sem comprometer a latência de monitoração.

## 6. Grafo de mídia / NDI nativo

- Bindings Kotlin → JNI: `NdiManager` e `MediaGraph` (telemetria Sony) em `core-media` chamam `libbsm-media.so` (compila `GlesEngine.cpp` + `NdiEngine.cpp`) e `libndi.so` (SDK NDI pré‑compilada em `jniLibs/<ABI>/`).
- `CMakeLists.txt` (`core-media/src/main/cpp`):
  - `ndi` como `SHARED IMPORTED` → `../jniLibs/${ANDROID_ABI}/libndi.so`; include em `ndi/Include/` (headers `Processing.NDI.*.h`).
  - `bdsm-media` (target) linka `ndi`, `log`, `android`, `GLESv3`, `EGL`; C++17.
- **Áudio desacoplado**: entrada independente (USB/P2/BT/interno) com VU meters ao vivo, injetados no HUD e encaminhados à gravação/NDI.

## 7. Camada de dados e estado

- **Room** no `core`: DB com entidades **`Recording`** e **`Lut`**, DAOs e repositórios; `core-media` também declara Room/SQLite (persistência de LUTs na máquina nativa).
- **LUTs** (`core-media`): `LutManager` + parser `.cube` (3D LUT); LUTs built-in e importadas via `ActivityResultContracts.GetContent`; ativação com **intensidade** (0–1) e persistência; aplicadas via shaders. Partes da lib viva:
  - `LutManagementScreen`/`LutsViewModel` (feature-settings) → lista todas as LUTs, ativa/remove, importa por seletor de arquivos.
- **Settings**: DataStore Preferences — exposição, áudio, NDI, formato de captura, rede.
- **Rede/telemetria** (`core-network`): `LinkStateManager` (estado do link NDI), metadados e mini‑servidor **Ktor (Netty + WebSockets + CORS)**; payloads serializados com kotlinx‑serialization.
- **Gravações** (feature-settings): galeria com busca, filtros, favoritos, **ações em lote** (salvar na galeria, favoritar, excluir), geração de thumbnails (`MediaMetadataRetriever` + `LruCache`), exportação via `MediaStore`.

## 8. Build & CI

- Versionamento calculado no `app/build.gradle.kts`: função `incrementVersionPatch` (bump de PATCH), `-PforceReleaseVersion` sobrescreve o nome; debug com sufixo `- Versão de Desenvolvimento`; `versionCode` fixo em 1.
- **CI** (`.github/workflows/release.yml`, GitHub Actions):
  - Triggers: push de tag `v*` e `workflow_dispatch`.
  - Steps: checkout → JDK 17 Temurin → Gradle com cache → `./gradlew assembleDebug --no-daemon` → artifact do APK → Release no GitHub (quando por tag).
  - **Estado atual**: sem `signingConfigs` de release — APK assinado com debug key. O workflow já documenta a evolução (keystore → `assembleRelease` + secrets `KEYSTORE_BASE64`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`).
- Config compartilhada: versão do AGP/Kotlin/KSP centralizada no `libs.versions.toml`; `settings.gradle.kts` com `FAIL_ON_PROJECT_REPOS` e inclusão dos 9 módulos.

## 9. Achados e observações de arquitetura

1. **Coesão limpa**: `core` (dados), `core-capture` (aquisição/gravação), `core-media` (render/NDI), `core-network` (rede/telemetria); features só consomem — bom isolamento.
2. **Acoplamento não transitivo**: `implementation` exige declarações repetidas de `core-network` em `core-media`/`feature-preview` — funcionante, mas frágil; `api` reduziria ruído para exposição intencional.
3. **Resíduos de histórico**: artefatos `.cxx` (CMake) e `.vs/.vscode` apontam para diretórios de outras cópias ("Quase Final", "AntiGravity"); há easter-egg/extra (RouletteScreen) e comentários desatualizados — candidatos a limpeza no VCS.
4. **Recurso crítico**: SDK NDI proprietário via `libndi.so`; builds exigem as 4 ABIs em `jniLibs`; headers em `cpp/ndi/Include`.
5. **Testes**: apenas boilerplate JUnit/Espresso; pipeline complexa sem testes instrumentados dedicados.
6. **Evolução**: release exige keystore; `versionCode` fixo 1 (importante para distribuição futura — idealmente derivado do `versionName`).

---

*Documento de referência: `BDSM_VISAO_TECNICA.md` (visão do produto) e `BDSM_PLUGIN_OBS.md` (projeto OBS).*