# BDSM — Braga Digital Studio Mobile · Arquitetura do App Android

> Revisão final de 2026-10-03 contra o código real (estado vivo e status de todos os achados em `ANALISE_TECNICA_COMPLETA.md`; a versão histórica está em `ANALISE_TECNICA_INICIAL_2026-10-03.md`). Documento originalmente gerado a partir de análise read-only de todo o código-fonte Kotlin (todos os módulos), código nativo C++, arquivos de build, catálogo de versões e pipeline de CI.

## 1. Visão geral

Aplicativo Android de **monitor profissional para produção audiovisual**: transforma o smartphone em um monitor externo de baixa latência para câmeras (via HDMI‑USB/UVC e câmera interna; a fonte Wi‑Fi Sony está desativada por flag, ver §5 "Fontes de vídeo"), com renderização OpenGL ES nativa, ferramentas de exposição/foco (Focus Peaking, False Color, Zebra, Scopes), 3D LUTs (`.cube`), áudio desacoplado, **gravação local** (H.264/HEVC + AAC via MediaCodec + MediaMuxer, em MP4) e **transmissão NDI em tempo real** via SDK nativo da NewTek.

Pilares de arquitetura:

- **Multi-módulo Gradle** (9 módulos) com separação `core*` / feature.
- **UI 100% Jetpack Compose + Material 3**, single-activity com NavHost (`Navigation Compose`).
- **DI com Hilt 2.58** em todos os módulos.
- **Núcleo de mídia nativo C++/JNI** (`GlesEngine` + `NdiEngine`, C++17, GLES3/EGL).
- **Data**: Room (gravações e LUTs) + DataStore Preferences (settings) + SQLite, com repositórios no `core`.
- **Rede**: servidor Ktor (somente engine Netty; WebSockets e PartialContent; sem CORS, pois o dashboard é same-origin) com autenticação por pareamento, descoberta NSD e estado de link.
- **Pipeline de gravação** no `RecordManager` (core-media): encoders de hardware (MediaCodec) + `MediaMuxer`; o áudio vem do `AudioCaptureService` (core-capture).

## 2. Stack e versões (catálogo `gradle/libs.versions.toml`)

| Componente | Versão |
| --- | --- |
| Android Gradle Plugin | 8.13.2 (última 8.x) |
| Kotlin / KSP | 2.3.21 / 2.3.12 |
| Compose BOM / compilador | 2025.12.01 / plugin org.jetbrains.kotlin.plugin.compose (= versão do Kotlin) |
| Material3 · Navigation Compose | BOM · 2.9.8 |
| Hilt · hilt-navigation-compose | 2.58 · 1.3.0 |
| Room | 2.8.5 |
| DataStore Preferences | 1.2.1 |
| Coroutines / Serialization | 1.11.0 / 1.11.0 |
| Ktor server (core, netty, websockets, partial-content; `cors` ainda declarado mas sem uso) | 2.3.13 |
| UVCAndroid (herohan) | 1.0.8 |
| Coil · AndroidX SQLite · documentfile | 2.7.0 · 2.4.0 · 1.1.0 |
| Lifecycle (runtime-ktx, runtime-compose, viewmodel) · DataStore · profileinstaller | 2.10.0 · 1.2.1 · 1.4.1 |
| Testes unitários: JUnit, MockK, Turbine, coroutines-test, org.json, ktor-server-test-host | 4.13.2, 1.14.11, 1.2.1, 1.11.0, 20231013, 2.3.13 |
| Análise estática | detekt 1.23.8 (baselines em `config/detekt/`), ktlint 1.8.0 (plugin 14.2.0, baselines por módulo) |
| Gradle | 8.14.5 (wrapper com `distributionSha256Sum`) |

Plataforma: `minSdk 26`, `targetSdk 35`, `compileSdk 36`, Java 17 / JVM target 17 em todos os módulos (inclusive `core-network`), `applicationId` `com.bragastudio.mobile`.

Nativo: C++17, NDK **27.0.12077973** (fixado em `core-media/build.gradle.kts`; o r27 é o mínimo que entende `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON`, usado para o alinhamento de 16 KB), CMake 3.22.1. ABIs empacotadas por padrão: `arm64-v8a` e `armeabi-v7a`. Para emulador x86: `-Pbdsm.abis=arm64-v8a,x86_64` (os `libndi.so` de x86/x86_64 continuam em `jniLibs`, mas só entram no APK se pedidos). Biblioteca nativa: `libbdsm-media.so` (mais `libndi.so`).

## 3. Módulos e grafo de dependências

| Módulo | Namespace | Responsabilidade | Depende de |
| --- | --- | --- | --- |
| `app` | `com.bragastudio.mobile` | `@HiltAndroidApp`, `MainActivity`, NavHost | todos os demais |
| `common` | `com.bragastudio.mobile.common` | Tema (Color/Theme) e `SettingsComponents` (sem rotas nem modelos de domínio; as rotas são strings no `:app`) | — (apenas AndroidX/Compose) |
| `core` | `com.bragastudio.mobile.core` | Entities `Recording`/`Lut`, DAOs Room, repositórios, settings (DataStore), `HardwareMonitorService`, módulos DI | — (apenas AndroidX) |
| `core-capture` | `com.bragastudio.mobile.corecapture` | `CameraDiscoveryEngine`, dispositivos de captura (`Camera2Device`, `UvcCaptureDevice`, `SonyRemoteCaptureDevice` [desativado por flag]), `AudioCaptureService`, `RecordingRepositoryImpl`. Não contém motor de gravação: a pasta `recording/` está vazia | `core`, `core-network`, UVCAndroid |
| `core-media` | `com.bragastudio.mobile.coremedia` | `GlesEngine` e `NdiEngine` (C++), `NativeRenderer`, `NdiManager`, `BspManager` e `bsp/` (BSP v2: fonte descobrível com H.264/AAC sobre RTP/UDP e AES-GCM; ver "BSP v2"), `AudioManagerService`, `MediaGraph`, **`RecordManager` (gravação)**, `LutParser`/`LutRepositoryImpl` (`LutManager` é código morto) | `core-capture`, `core`, `core-network`, Room, SQLite, documentfile |
| `core-network` | `com.bragastudio.mobile.network` | `LinkServer` (Ktor/Netty), `service/LinkServerService` (foreground service), `auth/LinkAuthManager` (pareamento), `LinkState`/`LinkTelemetry` (telemetria e tally), `DiscoveryService` (NSD), `sharing/` (LUTs, mídia, device info) e `sony/` (Camera Remote API e liveview) | `core` |
| `feature-home` | `com.bragastudio.mobile.featurehome` | Splash e Home (a galeria Room deste módulo foi removida; a galeria ativa está em `feature-settings`) | `common`, `core`, Coil, documentfile |
| `feature-preview` | `com.bragastudio.mobile.featurepreview` | `PreviewScreen`, `PreviewViewModel`, HUD dividido em arquivos `Hud*.kt` (ver §4), painel Sony (oculto com a fonte Sony Wi-Fi desativada) | `core-media`, `core-capture`, `core-network`, `common`, `core` |
| `feature-settings` | `com.bragastudio.mobile.featuresettings` | Ajustes, LUTs, setup NDI, diagnósticos, galeria de gravações (lote) | `common`, `core`, `core-media`, `core-capture`, `core-network`, documentfile |

```
app
├── common
├── core
├── core-capture ─────→ core, core-network
├── core-media ───────→ core-capture, core, core-network
├── core-network ─────→ core
├── feature-home ─────→ common, core
├── feature-preview ──→ core-media, core-capture, core-network, common, core
└── feature-settings ─→ common, core, core-media, core-capture, core-network
```

> **Nota**: `core-network`/`core-capture` usam `implementation` (não `api`), logo não expõem dependências transitivamente — por isso `core-media` e `feature-preview` declaram `core-network` explicitamente (há comentários no Gradle, p.ex. `MediaGraph`/`PreviewHud` referenciam `SonyCameraStatus`).

## 4. Fluxo do aplicativo e navegação

- Única `MainActivity` (`@AndroidEntryPoint`) + classe `BdsmApplication` (`@HiltAndroidApp`).
- **Navegação gerada pelo registro de módulos (UX v3)**: o `AppNavigation` não lista telas. Cada tela de nível superior é um `BdsmModule` (`common/module/BdsmModule.kt`) contribuído com Hilt `@IntoSet`; o `BdsmModuleRegistry` (ids únicos, ordem, feature flags) alimenta a barra de navegação, os cartões da Home, a seção "Módulos" de Ajustes e o `NavHost` (`registerAllRoutes`).
  - **Barra inferior** (retrato/telas estreitas) ou **NavigationRail** (largura >= 600 dp, via `bdsmWidthClass()`; nunca só orientação): **Início | NDI | Mídia | Ajustes**, na ordem dos módulos `BOTTOM_BAR`. A barra só aparece em rotas que pertencem a uma aba (`registry.showsNavigation(route)`, `selectionRoutes`); o **Monitor NÃO está na barra**: abre pelo cartão ABRIR MONITOR da Home, em tela cheia e sem barra (via `rememberMonitorEntry`, permissões antes), e volta com `onNavigateHome`.
  - `Mídia` = Gravações + LUTs (seletor no topo; as rotas `recording` e `luts` continuam existindo e mantêm a aba Mídia selecionada). `Ajustes` abre direto a lista de 7 categorias de Configurações (`settings` -> `settings/{category}`; Sobre é uma categoria) e, abaixo, uma seção "Módulos" gerada pelos módulos `MORE` (módulos futuros). Não existe tela "Mais"; NDI = tela simples (`ndi_setup`) e `ndi_advanced`.
  - Trocar de aba usa `popUpTo(home){saveState}` + `restoreState` (uma volta sempre leva ao Início); transições do `NavHost` só com deslocamento (Início <-> Monitor usa fade).
  - **Módulo x ferramenta**: módulo = item de navegação/Home (NDI, Mídia, Configurações...). Ferramenta = função interna de um módulo (scopes, zebra, peaking, false color, LUT, grade, aspecto do Monitor): **não** vira item global; continua no dock do Monitor.
- Rotas de infraestrutura fora do registro: `onboarding` (guia/permissões). `BdsmRoutes` mantém as strings antigas (`preview`, `recording`, `luts`, `ndi_setup`, `settings`, `diagnostics`, `licenses`, `easter_egg`): nenhuma rota foi renomeada; não há deep links externos (a notificação do serviço de captura só reabre a Activity existente).
- Percursos de feature:
  - `feature-home`: `HomeScreen` (saudação, grade 2x2 de cartões `HOME_CARD`, cartão do Monitor ao alcance do polegar).
  - `feature-preview`: `PreviewScreen` + HUD (`CameraHUDOverlay` em `PreviewHud.kt`, que compõe os arquivos `Hud*.kt`) + `SonyRemotePanel` (não exibido enquanto a fonte Sony Wi-Fi está desativada). O HUD foi dividido por região: `HudInfoStrip` (faixa de informações + overflow), `HudToolsCluster` (`ToolsDock`), `HudRecControls` (REC + chips de lente), `HudManualControls` (`ExposureTiles` + conversões), `HudDialPopovers`, `HudMenus`, `HudAudioMeters`, `HudStatusOverlays` (tally/REC), além de `HudTheme`, `HudFormatters`, `HudLogic` e `HudLayoutLogic` (lógica pura testada). `CameraHUDOverlay` ainda recebe ~110 parâmetros (pendência). **O Monitor não foi alterado na UX v3.**
  - `feature-settings`: NDI (`NdiScreen`, `NdiAdvancedScreen`), Mídia (`MediaScreen`, `RecordingsScreen`, `LutsContent`), Configurações (`SettingsScreen` + `SettingsCategoryScreen`, `SettingsCatalog` com a busca pura), diagnósticos e licenças.
- ViewModels scoped via `hiltViewModel()`; o Preview coleta com `collectAsStateWithLifecycle` e lê valores de alta frequência (timecode, VU) só nas folhas; Home, NDI, Mídia e Configurações também usam `collectAsStateWithLifecycle`.

### Layout compacto do HUD do Monitor (paisagem e retrato)

- **Faixa de informações** (`HudInfoStrip`, topo, gradiente translúcido, nunca se oculta; só esmaece após ~6 s sem toque): ponto REC + timecode monoespaçado, tiles tocáveis (`4K · 30 · H.265`, `ISO`, `SS` com ângulo do obturador, `WB`, `AF/MF`; AUTO discreto, manual em âmbar), armazenamento restante e bateria. Largura >= 600 dp = uma linha; mais estreito = duas (status em cima, tiles embaixo).
- Tocar num tile abre o dial EXISTENTE logo abaixo dele (`HorizontalDialPopover`); o tile de formato abre um painel com resolução/fps/bitrate/codec. Fonte USB não mostra tiles de exposição; na Sony (só com o flag ligado) aparecem íris/ISO/SS.
- **Overflow (⋮)**: Home, Configurações, fonte de vídeo, microfone, lanterna, estabilização, HDR, NDI e "Ocultar interface" (clean feed; tocar na imagem volta). Chips NDI/Wi-Fi sem conexão só aparecem quando relevantes; o chip Sony só com a fonte Sony ativa (nunca aparece com o flag desligado). A lista de fontes do overflow e o ciclo rápido de fonte oferecem só Camera e USB com o flag desligado.
- **Dock de ferramentas** (`ToolsDock`): só ícones (visual 40 dp, alvo 48 dp). Lateral esquerda em paisagem, faixa horizontal acima do REC em retrato. Mostra Scopes, Zebra, Peak, LUT e um "+" que expande False Color, Grid e Aspect. Toque liga/desliga; segurar abre o ajuste (zebra, peak, LUT, grade, aspect). É a ÚNICA parte que se oculta sozinha (5 s; 3 s gravando); tocar na imagem a traz de volta e popovers abertos impedem o ocultamento (`PreviewHud` agrega os popovers; regras puras em `HudLayoutLogic`).
- **REC + lentes** (`RecAndLensControls`): REC de 60 dp à direita (paisagem) ou centro inferior (retrato), com chips de lente (0.5x/1x/2x) ao lado. **VU** (`AudioMetersOverlay`): duas barras finas L/R na borda inferior com pico e clip. Scopes: overlay pequeno e arrastável sob a faixa. Layout decidido por constraints (largura > altura), respeitando `safeDrawing` (cutout); tally continua em borda total.

## 5. Pipeline de captura → render → gravação → NDI

```
Câmera interna (Camera2)
        │
Câmera HDMI‑USB (UVC / UVCAndroid) ──┐
        │                            ├──► frame source (YUV/buffers)
[Sony Wi‑Fi: DESATIVADA por flag] ───┘        │
                                              ▼
                          GlesEngine (C++/GLES3 + shaders)
                          • transformação • 3D LUT (com intensidade `uLutMix`)
                          • Focus Peaking • False Color (3 faixas) • Zebra
                          (um passe por saída, nesta ordem: preview, REC, BSP e
                           NDI por último, pulado sem receptor; REC/BSP/NDI
                           saem limpos, overlays só no preview)
                                              │
            ┌─────────────────────────────────┼──────────────────────────────┐
            ▼                                 ▼                              ▼
   Surface da tela (60fps)         NdiEngine (C++, libndi)        RecordManager (core-media)
                                   ImageReader RGBA + memcpy      MediaCodec H264/HEVC + AAC
                                   transmissão NDI em rede        MediaMuxer → .mp4
```

- **Frame source** (`core-capture`): `CameraDiscoveryEngine` (interna/UVC), `Camera2Device`, `UvcCaptureDevice` e `SonyRemoteCaptureDevice` (cliente/parser Sony em `core-network/sony/`, desativado por flag); estados/categorias em `domain/`.
- **Render nativo** (`core-media`): `GlesEngine.cpp` renderiza em OpenGL ES 3.0, um passe por saída; os shaders são strings dentro do `GlesEngine.cpp` (`cpp/shaders/` está vazio). Os scopes são reduzidos na GPU para 256x144 e contados em laços de CPU na thread GL. Não há de-squeeze anamórfico nem safe areas.
- **Gravação** (`core-media/domain/RecordManager.kt`): MediaCodec + `MediaMuxer`, só MP4, bitrates 25/50/100 Mbps; grava em `getExternalFilesDir` e copia ao destino SAF (`documentfile`) no fim. O áudio vem do `AudioCaptureService` (core-capture). Não existem `RecordingEngine`, `H264Encoder`, `AacEncoder`, `MediaMuxerWrapper` nem `AudioController`.
- **Concorrência**: render, NDI e gravação consomem os mesmos frames com sincronização por fila/worker dedicado, sem comprometer a latência de monitoração.
- **Ciclo de vida (sessão desacoplada da tela)**: o `MediaGraph` (@Singleton) é dono da câmera, do GL, de REC/NDI/BSP e do áudio; a `TextureView` do Preview é só um consumidor opcional (`attachPreviewSurface` idempotente, reanexa a um renderer vivo sem reiniciar a câmera).
  - Só REC (Preparing/Recording/Stopping) segura a sessão fora do Monitor (`SessionConsumers.holdsSession`): com REC, `detachPreviewSurface` (ON_STOP, Home, tela apagada, Configurações) solta só a surface; sem REC faz o desligamento completo (mesmo com NDI/BSP ligados), também quando o REC termina em segundo plano.
  - O `CaptureForegroundService` (`camera|microphone`, notificação com tempo e ação "Parar") é só a âncora de processo: é pedido no início do REC, com a Activity visível, exige as permissões do tipo (Android 14+) e se encerra sozinho quando `MediaGraph.foregroundWanted` fica false; se o sistema recusar, vale o comportamento antigo.
  - O sink de áudio (REC antes de NDI) pertence ao grafo; o microfone roda com o Monitor visível (VU e áudio do NDI) ou durante REC. A política pura está em `CaptureSessionPolicy` (testada em JVM); a notificação reabre a Activity existente (`SINGLE_TOP | REORDER_TO_FRONT`).

### Fontes de vídeo: Sony Wi-Fi desativada por flag e fluxo HDMI -> UVC

**Estado (2026-10-07)**: a fonte "Sony Camera (Wi-Fi)" (Camera Remote API, JPEG de baixa resolução em `http://192.168.122.1`, descoberta SSDP) está **desativada**. A câmera Sony (a6000 etc.) entra no app como fonte de vídeo pela saída HDMI limpa -> placa de captura HDMI-USB UVC -> celular (USB OTG) -> fonte "Câmera USB" (UVC). O código Sony continua no repositório, só não é alcançável.

- **Interruptor único**: `CaptureFeatureFlags.SONY_WIFI_ENABLED` (`core/src/main/java/com/bragastudio/mobile/core/domain/VideoSources.kt`, hoje `false`). `VideoSources` (mesmo arquivo) concentra as regras puras: `available()` (fontes oferecidas), `normalize()` (fonte efetiva) e `next()` (ciclo rápido do Monitor).
- **O que respeita o flag**:
  - `SettingsRepositoryImpl`: `videoSettings` devolve a fonte já normalizada (`VideoSettings.withEffectiveSource()`): um "SONY" persistido vira "Camera" (câmera do celular, Camera2) sem crash, sem apagar o valor salvo (religar o flag restaura a escolha) e sem tocar nas demais configurações; `setVideoSource("SONY")` é ignorado enquanto desligado. Como Monitor, Home (`CameraStatusProvider`), Ajustes e `MediaGraph` leem esse mesmo Flow, todos veem "Camera".
  - Seletores: Ajustes > Câmera (`VideoSourceOption.available()`), overflow do Monitor (`HudInfoStrip`, `VideoSources.available()`) e ciclo rápido (`PreviewViewModel.toggleCameraSource`) não oferecem Sony. `PreviewViewModel.isSonyActive` é `false`, então chip Sony, painel Sony e tiles de íris/ISO/SS Sony não são compostos (nenhuma mudança de layout do Monitor).
  - Rede: `BdsmApplication` não chama `SonyNetwork.init`; `SonyNetwork.acquire` (requestNetwork), `SonyCameraDiscovery.discoverCamera` (SSDP + fallback HTTP), `SonyCameraClient.performRpc` (único ponto HTTP), `SonyLiveviewSocketReader.startStreaming` e `SonyRemoteCaptureDevice.start/stop` retornam antes de abrir qualquer socket/requisição. `MediaGraph` só instancia (Hilt) o `SonyRemoteCaptureDevice`, que no construtor não faz I/O; a seleção da fonte passa por `VideoSources.normalize`.
  - `CameraStatusLogic.source` trata "SONY" como câmera do celular com o flag desligado.
- **Manifesto**: com o flag desligado foram removidas ACCESS_FINE_LOCATION e ACCESS_COARSE_LOCATION (maxSdk 32), NEARBY_WIFI_DEVICES (neverForLocation), CHANGE_WIFI_STATE e CHANGE_NETWORK_STATE, e o cleartext para 192.168.122.1 (`network_security_config.xml` voltou a `base-config cleartextTrafficPermitted="false"` sem exceções). Mantidas, porque NDI/Link/UI usam: INTERNET, ACCESS_NETWORK_STATE (`HardwareMonitorService`/`LocalIpMonitor`), ACCESS_WIFI_STATE e CHANGE_WIFI_MULTICAST_STATE (`MulticastLock` do NDI/descoberta/receptor), WAKE_LOCK (wake/Wi-Fi lock do NDI e gravação).
- **Como religar**: (1) trocar `SONY_WIFI_ENABLED` para `true`; (2) restaurar no `app/src/main/AndroidManifest.xml`:
  ```xml
  <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="32" />
  <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" android:maxSdkVersion="32" />
  <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />
  <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
  <uses-permission android:name="android.permission.CHANGE_NETWORK_STATE" />
  ```
  (3) restaurar em `network_security_config.xml` o `<domain-config cleartextTrafficPermitted="true"><domain includeSubdomains="false">192.168.122.1</domain></domain-config>`; (4) ajustar `SonyDisabledTest` e `VideoSourcesTest` (testam o estado "desligado"), atualizar `PUBLICACAO_GOOGLE_PLAY.md` (permissões e rede) e a descrição da loja. A permissão de tempo de execução da Sony (NEARBY_WIFI_DEVICES/localização) continua sendo pedida pelo `PreviewScreen` ao escolher a fonte.
- **Fluxo HDMI -> UVC (Sony a6000 etc.)**: ligar a câmera a uma placa de captura HDMI->USB que seja UVC (aparece como webcam), conectar ao celular por USB OTG, escolher "Câmera USB" em Ajustes > Câmera (ou no overflow do Monitor). Na câmera desligar "Info. HDMI" para a saída limpa (a6000: Menu > Configurações HDMI > Info. HDMI > Desligado). Placas que exigem driver próprio (ex.: Elgato HD60 S) não funcionam no Android. O texto de ajuda aparece em Ajustes > Câmera quando a fonte USB está escolhida e no seletor de fonte (strings `settings_source_usb_help`/`settings_source_usb_hint`).
- **Caminho UVC (`UvcCaptureDevice`, UVCAndroid 1.0.8)**: o `USBMonitor` não usa mais `DeviceFilter` (o filtro por classe do dispositivo descartava placas compostas de classe 0xEF que só anunciam a classe de vídeo 0x0E na interface; a seleção é por interface em `isUvcCandidate`). A negociação foi corrigida (pela leitura do bytecode do UVCAndroid; sem placa para confirmar em hardware): `setPreviewSize(w, h, tipo, fps)` do UVCAndroid exige o `Size.type` (subtipo do descritor de quadro: 7 = MJPEG, 5 = YUYV) e um fps que conste na lista do aparelho (o overload de 3 argumentos fixa 30 fps e recusava os modos), e o código antigo passava `FRAME_FORMAT_*` (0/1) e comparava com `UVC_VS_FORMAT_MJPEG` (6), então todo modo seria recusado com "invalid preview size". Agora `UvcFormatPicker.rank` ordena os modos anunciados (cabe em 1080p, MJPEG preferido, maior fps), `pickFps` pede 30 se existir ou o anunciado mais próximo (placas baratas: 1080p30 MJPEG sem 1080p60; só 60; 25/50) e o Monitor tenta até 6 modos em sequência (recusa na negociação ou no `startPreview`) antes de falhar. Falha ao registrar o `USBMonitor` vira erro, não crash.
- **Mensagens (Monitor, texto apenas)**: `UsbSourceMessages` troca o texto do overlay "Aguardando USB..." (sem dispositivo / pedindo permissão / desconectada) e dos três textos do overlay de erro quando a fonte é USB (permissão negada x falha ao abrir/formato); fonte Camera mantém os textos antigos. Estados vêm de `UvcCaptureDevice.status` -> `MediaGraph.usbStatus` -> `PreviewViewModel.usbStatus`.
- **Áudio da placa**: o app lista as entradas de áudio do sistema (`AudioManagerService`: microfone interno, USB, USB headset, Bluetooth SCO, headset P2). Se a placa expõe um microfone/entrada de áudio USB (UAC), ele aparece como "Microfone USB" em Ajustes > Áudio e no overflow do Monitor, mas o padrão continua sendo o microfone interno: o usuário precisa escolher a entrada USB (não há troca automática ao plugar, de propósito; a escolha não é persistida e volta ao interno se o dispositivo sair). O `AudioRecord` usa a fonte `MIC` com dispositivo externo e registra no log se o Android roteou para outro mic.
- **NÃO verificado em hardware** (sem placa de captura/a6000 disponível): enumeração real de modos da placa e fps, `startPreview` com MJPEG 1080p30, hot-plug/permissão USB num aparelho real, desconexão durante REC, áudio USB da placa, latência, e se a placa específica é UVC "de verdade".

### Ciclo de vida de câmera e microfone

**Regra**: câmera, microfone e `CaptureForegroundService` só ficam ativos (a) com o MONITOR visível ou (b) enquanto houver GRAVAÇÃO (REC: Preparing/Recording/Stopping). NDI e BSP NÃO seguram a captura fora do Monitor: ao sair do Monitor sem REC a sessão é encerrada por inteiro (CameraDevice, AudioRecord stop+release, SCO, `stopForeground`, wake locks), mesmo com NDI/BSP "ligados". A exceção do REC existe porque gravar é ação explícita e perder um take em silêncio seria perda de dados; a notificação "Gravando — BDSM" e o indicador do sistema continuam visíveis.

- **Mudança de comportamento (1.1.2) NDI/BSP**: continuam "habilitados" (estado persistido, botão da tela NDI mostra Ativo) mas só enviam quadros enquanto o Monitor estiver aberto. Ao reabrir o Monitor (ou ligar NDI/BSP dentro dele) a sessão sobe e o envio volta sozinho: o `NativeRenderer` guarda as surfaces de NDI/BSP e as reaplica em `prepareRenderer` (log "NDI Surface atrelado" só aparece ao (re)iniciar o sender). O áudio do NDI só é enviado com o Monitor visível (ou durante REC). Antes (1.1.1) "NDI ativo" mantinha câmera, mic e FGS fora do Monitor.
- **Android 14+**: `camera|microphone` exigem o FGS quando o app vai para segundo plano COM captura ativa, o que agora só ocorre em REC; o FGS é pedido no início do REC (Activity visível).

- **Causa raiz (2026-10-07)**: o NDI persistido como ligado (`NdiSettings.isEnabled`) era iniciado pelo coletor do `MediaGraph` ao abrir o app, ainda na Home, sem sessão de câmera. Isso contava como "consumidor ativo": subia o `CaptureForegroundService` (camera|microphone) e o `audioDemand` abria o `AudioRecord` (NDI com áudio), acendendo o indicador verde. Medido no A51: Home recém-aberta com FGS ativo e `RECORD_AUDIO ... (running)`. Além disso o preview de Ajustes/LUT (`LutsViewModel`) contava como preview e abria o microfone, e attach/detach assíncronos podiam se inverter (entrar/sair rápido do Monitor).
- **Política pura** (`core-media/domain/CaptureLifecyclePolicy`, testada): `shouldHoldCapture(monitorVisible, recording)` = monitor || rec; `shouldRunForegroundService(sessionLive, recording)` = live && rec; `shouldCaptureAudio(sessionLive, monitorVisible, recording)` = live && (monitor || rec). `CaptureSessionPolicy` usa `SessionConsumers.holdsSession` (= recording) em `detachAction`, `shouldShutdownSession`, `planForeground` e nas notificações. NDI/BSP não entram em nenhuma decisão.
- **Ligação**: `MediaGraph` expõe `sessionLive` e `monitorVisible` como StateFlow; `foregroundWanted` e `audioDemand` usam a política. `PreviewViewModel.attachSurface` chama `attachMonitorSurfaceAsync` (monitor=true); attach/detach vão por uma fila única (`previewCommands`), em ordem. Preview de Ajustes/LUT usa `attachPreviewSurface(surface)` (monitor=false): abre a câmera, nunca o microfone. `AudioCaptureService.startCapture/stopCapture` entram na fila do mutex na ordem da chamada (UNDISPATCHED). Logs com a tag `CaptureLifecycle`.
- **Sondagens breves**: `withBriefProbe` (`core-capture/domain/BriefProbe.kt`) abre, lê e fecha em `finally`, com timeout. Hoje nenhum código sonda a câmera/mic (CameraStatusProvider só enumera).

```
 Home/outras telas        Monitor visível               Fora do Monitor
 [sem sessão] --entra--> [câmera+mic abertos] --sai--> sem REC: FECHA tudo (NDI/BSP ligados, sem quadros)
      ^                         |                       com REC: mantém (FGS "Gravando")
      |                         v                              |
      +------- fecha <---- REC termina fora do Monitor <-------+
```

**Roteiro de teste no aparelho** (NÃO verificado: ver relatório): instalar release assinado (`-PforceReleaseVersion=1.1.2`, `adb install -r`). Em cada passo: `dumpsys media.audio_flinger | findstr -i record`, `dumpsys media.camera`, `dumpsys activity services io.github.maurobragafilho.bdsm`, `cmd appops get <pkg> RECORD_AUDIO` / `CAMERA`, `screencap` e `logcat -s CaptureLifecycle`.
1. Abrir o app (Home) com NDI persistido ligado: sem FGS, sem mic/câmera em uso, sem indicador verde.
2. Percorrer NDI, Mídia, Ajustes e voltar: idem (LUT preview pode abrir câmera, nunca mic). Ligar o NDI na tela NDI (fora do Monitor): nenhuma captura.
3. Entrar no Monitor: câmera, mic e NDI (quadros) ativos, sem FGS; sair para a Home com NDI "Ativo": em até ~3 s tudo para (sem `running`, sem `CaptureForegroundService`).
4. Reabrir o Monitor: câmera e NDI voltam sozinhos.
5. REC de ~6 s no Monitor, ir à Home: a gravação continua (FGS "Gravando"); voltar e parar: arquivo gravado; parar o REC fora do Monitor: após ~1,5 s tudo fecha. Entrar/sair rápido do Monitor várias vezes: sem câmera/mic presos.

### Orientação das saídas (REC, NDI, BSP)

**Regra**: girar o aparelho NUNCA reinicia gravação/NDI/BSP (arquivo MP4 contínuo, PTS e timeline intactos); o que gira é o conteúdo, não o quadro.

- **Causa do bug antigo** (2026-10-04): `GlesEngine` congelava `outputRotationDegrees` enquanto havia REC/NDI/BSP e o quadro era sempre paisagem (`ResolutionTable`). Camera2 entrega a imagem em pé na orientação natural (retrato, aspecto 9:16 num buffer 16:9, porque a matriz da `SurfaceTexture` já inclui o `sensorOrientation`); com ângulo congelado em 0 num quadro paisagem a imagem saía esticada e, ao girar, ficava de lado/distorcida; na paisagem invertida (ROTATION_270 depois de ROTATION_90) ficava de cabeça para baixo. UVC/Sony (`sensorOrientation` 0, buffer paisagem) pareciam normais porque o ângulo congelado 0 num quadro paisagem não distorce.
- **Separação tamanho x rotação** (`core-media/domain/OutputOrientation.kt`, funções puras testadas em `OutputOrientationTest`):
  - **Tamanho do quadro**: escolhido UMA vez no início de cada saída (`NativeRenderer.chooseOutputFrameSize`): retrato (`1080x1920`) se o conteúdo, na orientação atual, é retrato; senão paisagem. Fixo até a saída parar (codec/`ImageReader` não são recriados). `VideoPlanner.plan(portraitFrame)` troca largura/altura; a consulta de capacidade do encoder segue com as dimensões da tabela.
  - **Rotação do conteúdo**: `outputRotationDegrees` agora é definido pelo Kotlin e acompanha o display (mesmo ângulo do preview: 0/270/180/90 para ROTATION_0/90/180/270), então o mundo fica em pé nas 4 posições e na paisagem invertida (180 só muda o ângulo, não o encaixe). Fontes externas (UVC/Sony; `captureDevice !== camera2Device`) não giram com o aparelho: ângulo 0.
  - **Encaixe com barras (contain)**: quando a classe de orientação muda (retrato <-> paisagem) o conteúdo girado é encaixado no quadro fixo por uma escala do quad (`uQuadScale` no vertex shader, viewport limpo em preto): pillarbox/letterbox, sem esticar (aspecto do quad em pixels = aspecto do conteúdo girado). Fonte 4:3 num quadro 16:9 também ganha barras em vez de esticar.
  - **Aspecto da fonte**: `sourceAspect(bufferW, bufferH, sensorOrientation)` troca os eixos do buffer para sensor 90/270 (Camera2). `MediaGraph.refreshSourceGeometry()` repassa buffer + `sensorOrientation` + "gira com o aparelho" ao `NativeRenderer` ao abrir a sessão, ao ficar `READY` e ao trocar de lente.
- **NDI**: escolhemos o MESMO comportamento da gravação (quadro fixo + barras) em vez de trocar a resolução em tempo real. Motivo: o `NdiEngine` aceita `xres/yres` variáveis, mas mudar o `ImageReader` com a thread GL desenhando e receptores (OBS/DistroAV) já conectados é arriscado sem teste em aparelho (re-criação da texture no receptor, piscadas, EGLSurface descartada). O quadro NDI segue a orientação do aparelho no INÍCIO da transmissão; parar/iniciar o NDI reavalia. Evolução possível: recriar o `ImageReader` ao mudar a classe e trocar a surface (`setNdiSurface`) com o NDI ativo.
- **Lente frontal**: não existe no app (`CameraDiscoveryEngine` descarta `LENS_FACING_FRONT`; `setSourceMirrorX` não tem chamadores). Se for habilitada, o espelhamento (`uMirrorX`) é um flip horizontal aplicado DEPOIS da rotação no shader e não interfere em ângulo/escala; os testes cobrem sensor 90 (traseira) e 270 (frontal).
- **Fora do escopo**: o HDR 10-bit direto câmera→encoder (Caminho A) não passa pelo GL e segue sempre com quadro paisagem e sem rotação (como antes). Monitor/HUD (`feature-preview`) não foram alterados.

#### Roteiro de teste no aparelho (NÃO verificado no aparelho: A51 e S20 estavam offline)

Pré-requisitos: `adb devices` com o aparelho; instalar com `adb install -r` (nunca `uninstall`/`pm clear`); `C:/Users/mauri/AppData/Local/ffmpeg/bin` no PATH; OBS com DistroAV recebendo o NDI. Ativar auto-girar. Para cada início, anote: retrato, paisagem (ROTATION_90), paisagem invertida (ROTATION_270) e retrato invertido (ROTATION_180, se o aparelho permitir).

1. **REC, 4 giros (Camera2 traseira)**
   - Inicie a gravação em RETRATO. Gire para paisagem, paisagem invertida, retrato invertido e volte a retrato (~5 s em cada). Pare.
   - Esperado: o app não pisca nem reinicia; o mundo fica sempre em pé no arquivo; em retrato o quadro é 1080x1920 e nas paisagens a imagem 16:9 aparece centralizada com barras em cima/embaixo, sem esticar.
   - Repita iniciando em PAISAGEM: quadro 1920x1080; em retrato a imagem aparece com barras laterais.
   - `adb pull /sdcard/Android/data/<applicationId>/files/Movies/BDSM_*.mp4` e confira `ffprobe -v error -show_entries stream=codec_name,width,height,r_frame_rate,nb_frames,duration -of default=nw=1 BDSM_*.mp4`: um único arquivo, `width/height` constantes (1080x1920 ou 1920x1080), duração = tempo gravado, sem saltos (`ffprobe -show_frames -select_streams v -show_entries frame=pts_time` com PTS crescente).
2. **NDI, 4 giros**: inicie o NDI em retrato e depois em paisagem (reinicie entre os testes); com o OBS conectado gire nas 4 posições. Esperado: o stream não reconecta, a resolução recebida no OBS é constante (retrato = 1080x1920, paisagem = 1920x1080), imagem em pé em todas as posições e barras quando a orientação difere da inicial; sem distorção e sem cabeça para baixo na paisagem invertida.
3. **BSP**: mesma sequência com BSP ligado e um receptor conectado (ver "BSP v2"; hoje só o simulador Python); confira que o decodificador do destino recebe o quadro fixo e a imagem em pé.
4. **REC + NDI + BSP juntos**: inicie os três em retrato e gire pelas 4 posições. Esperado: nenhum reinício, os três em pé.
5. **UVC/USB**: repita REC/NDI com a fonte USB nos 4 giros. Esperado: imagem como antes (ângulo 0, quadro paisagem, sem barras).
6. **Troca de lente** durante o REC/NDI: sem reinício e com o mesmo comportamento acima.
7. Logs: `adb logcat -s BDSM-RENDER MediaGraph NativeRenderer` (sem `Erro ao renderizar frame`); se algo falhar, anotar aparelho, posição inicial, posição final e o que apareceu.

### BSP v2 (fonte no celular, Fase 1)

Protocolo próprio de vídeo+áudio do celular para o OBS (norma: `.docs/BSP_ESPECIFICACAO.md`, com os desvios e esclarecimentos em 0.1). Nesta fase só existe a **fonte** (o app); o receptor do OBS ainda não existe (teste com `Braga Link/scripts-python/bsp_receiver_sim.py`). O app é **servidor descobrível** e **substituiu o cliente v1** (que apontava para um IP digitado e nunca teve receptor).

**Papéis e portas**

| Plano | Porta | Quem | Conteúdo |
| --- | --- | --- | --- |
| Descoberta | mDNS `_bsp._tcp` | fonte anuncia, só com o BSP habilitado | nome `BDSM (nome do aparelho/NDI)` + TXT `v,id,name,vc,ac,res,auth,link,st` |
| Controle | TCP **7070** (se ocupada, uma livre, anunciada) | receptor conecta | JSON de uma linha (<= 8 KiB): HELLO/WELCOME/READY, HEARTBEAT, START/STOP, META, SR, BYE, ERROR |
| Mídia | UDP, fonte -> `IP do TCP : udpPort` do READY | fonte envia | RTP: vídeo H.264 (PT 96) e áudio AAC (PT 97), AES-128-GCM |
| Retorno | UDP **7071** (`feedbackPort`) | receptor envia | PLI (IDR, 1 por 500 ms) e REPORT (só estatísticas); NACK/FEC/ABR = Fase 2 |

**Autenticação.** `LinkAuthManager` (core-network) ganhou a API mínima `clientKey(clientId)` (devolve `Kt = SHA-256(token)`, o hash que ele já guardava, em cópia) e `pairedClient(clientId)` (nome). O HELLO traz `HMAC-SHA256(Kt, nonceR || "BSP-HELLO")`; a fonte compara em tempo constante (mesmo sem pareamento calcula o HMAC com chave de enchimento), tem 4 s totais para a prova (watchdog; vale mesmo com envio byte a byte), aceita 2 sessões (a 3ª leva `BUSY` só depois de provar), e limita 20 mensagens/s. Revogar o pareamento no Link derruba as sessões do cliente. Token, `Kt`, chaves e IP não vão a log nem à UI (só o nome do pareamento).

**Código** (`core-media`, pacote `coremedia.bsp`, sem Android quando possível):

```
BspManager (domain, @Singleton)        estado p/ a UI, NSD, jobs (META 2/s, SR 1/s, stats), WifiLock com receptor pronto
 |- BspControlServer + BspSession      TCP, HELLO/WELCOME/READY, sessões, heartbeat/RTT      (BspProtocol, BspSessionPolicy)
 |- BspDiscovery                       NsdManager `_bsp._tcp` + TXT (BspTxt)
 |- BspFeedbackReceiver                UDP 7071: PLI/REPORT (BspFeedbackParser), pontos de extensão da Fase 2
 `- BspMediaPipeline                   vídeo: BspVideoEncoder (MediaCodec/Surface) -> H264RtpPacketizer (STAP-A/FU-A)
                                       áudio: PCM do AudioRecord -> BspAudioEncoder (AAC-LC) -> AacRtpPacketizer (RFC 3640)
                                       -> PacedPacketQueue (pacing 1/3 do quadro, fila limitada) -> BspSender (1 thread,
                                          AEAD por sessão BspStreamSealer, 2 sockets: DSCP AF41 vídeo / EF áudio)
```
Relógio: vídeo e áudio usam o mesmo eixo de captura (`System.nanoTime`; `PtsDomain` converte o PTS do vídeo se vier em BOOTTIME; `RtpClock` por fluxo; `AudioTimestampSmoother` alisa o áudio); o SR NTP<->RTP sai a cada 1 s no controle. Sem alocação por pacote nos empacotadores, na fila e no envio (buffers e `DatagramPacket` reaproveitados; a JCA aloca internamente por `Cipher.init`).

**Ciclo de vida (inalterado).** O BSP é uma saída como o NDI: `MediaGraph.startBsp/stopBsp` (mutex próprio) sobem e descem a fonte quando `BspSettings.isEnabled` muda (ou o nome do NDI muda) e o `NativeRenderer` guarda a surface do encoder. O BSP **não segura câmera nem microfone**: quadros e PCM só existem com a sessão de captura viva (Monitor, REC, ou o preview de Ajustes/LUT, que nunca abre o microfone; o áudio só com Monitor ou REC). `MediaGraph` apenas informa `setCaptureFlowing(sessionLive)` ao gerente, que avisa os receptores (`STOP {reason:"monitor_closed"}` / `START` + IDR). `CaptureLifecyclePolicy`/`CaptureSessionPolicy` não mudaram. O encoder usa MediaCodec **assíncrono** (HandlerThread): sem quadros a thread dorme (nenhum sondador). O anúncio mDNS e o servidor ficam no ar com o BSP habilitado, mesmo com o Monitor fechado.

**Orientação.** Nada mudou: o encoder é criado com o quadro fixo escolhido por `NativeRenderer.chooseOutputFrameSize` (retrato ou paisagem no início) e a rotação do conteúdo segue a regra de "Orientação das saídas"; a orientação atual vai no `META`/`WELCOME`.

**UI.** Módulo "BSP" (`BspModule` em `feature-settings/SettingsModules.kt`, `@IntoSet`, placement `MORE` = Ajustes > Módulos; rota `BdsmRoutes.BSP`). Não foi criado `feature-bsp` (a tela reaproveita ViewModels, componentes e padrão da tela NDI no `feature-settings`). Tela no estilo NDI: círculo de estado (Desativado / Iniciando / **Aguardando receptor** / Aguardando o Monitor / **Transmitindo** / erro), nome anunciado, nota fixa "O vídeo só é enviado com o Monitor aberto", lista de receptores (**só nomes**), botão grande de 56 dp e "Detalhes" (RTT, taxa, fps, perda, segurança e a chave de depuração "Permitir sem criptografia"). Estado habilitado persistido em DataStore (`bsp_enabled`). Strings em `feature-settings/res/values/strings_bsp.xml`. O antigo switch NDI/BSP do Diagnóstico foi removido.

**Testes automáticos** (JVM): empacotadores H.264/AAC com vetores, HKDF (RFC 5869), HMAC (RFC 4231), AES-GCM (McGrew-Viega), AEAD (adulteração, repetição, virada de sequência), parser de controle (limites, JSON malformado, campos desconhecidos), servidor de controle em loopback (autenticação, 4 s, BUSY, RATE, substituição), política de sessões, pacing/fila, retorno binário (fuzz), TXT, relógio; Python: `scripts-python/tests/test_bsp_receiver_sim.py`.

**Pendente (Fase 2):** retorno binário completo, NACK + anel de retransmissão, FEC, bitrate adaptativo (REPORT -> `setParameters`), estado térmico, HEVC/Opus opcionais, métricas na tela, desafio do servidor no HELLO, autenticação do retorno, calibração do atraso de priming do AAC e as medições da seção 13 da especificação.

## 6. Grafo de mídia / NDI nativo

- Bindings Kotlin → JNI: `NdiManager`, `NdiNative` ("Ver na rede") e `MediaGraph` (telemetria Sony) em `core-media` chamam `libbdsm-media.so` (compila `GlesEngine.cpp` + `NdiEngine.cpp` + `NdiNetwork.cpp`) e `libndi.so` (SDK NDI pré‑compilada em `jniLibs/<ABI>/`).
- `CMakeLists.txt` (`core-media/src/main/cpp`):
  - `ndi` como `SHARED IMPORTED` → `../jniLibs/${ANDROID_ABI}/libndi.so`; include em `ndi/Include/` (headers `Processing.NDI.*.h`).
  - `bdsm-media` (target) linka `ndi`, `log`, `android`, `GLESv3`, `EGL`; C++17.
- **Áudio**: `AudioCaptureService` (core-capture) e `AudioManagerService` (core-media), com VU meters ao vivo no HUD, encaminhados à gravação/NDI. Seleção de canal, ganho e monitor por fone ainda não existem.

## 7. Camada de dados e estado

- **Room** no `core`: `BdsmDatabase` v4 (arquivo `bsm_database`, nome mantido para não perder dados; migrações 1→4 testadas em `MigrationTest`, instrumentado), entidades **`Recording`** (status em `RecordingStatus`) e **`Lut`** (índice único em `filePath`), DAOs e repositórios. `core-media` ainda declara Room/SQLite sem uso (limpeza pendente). O DataStore também mantém o nome `bsm_settings`.
- **LUTs** (`core-media`): `LutParser` (`.cube`) e `LutRepositoryImpl`; LUTs importadas via `ActivityResultContracts.GetContent` ou enviadas pelo PC (`/api/luts/upload`); aplicadas via shader, só no monitor, com controle de intensidade (`LutManager` é código morto). Partes da lib viva:
  - `LutManagementScreen`/`LutsViewModel` (feature-settings) → lista todas as LUTs, ativa/remove, importa por seletor de arquivos.
- **Settings**: DataStore Preferences — exposição, áudio, NDI, formato de captura, rede. Enums persistidos por `name`, com `valueOf` tolerante.
- **Telemetria de hardware** (`HardwareMonitorService`, `core`): bateria, temperatura da bateria, armazenamento e Wi-Fi (CPU/GPU foram removidos).
- **Rede/telemetria** (`core-network`): `LinkState`/`LinkTelemetry` (telemetria real: REC, fonte, lente, fps, microfone, nome NDI, tally), `MetadataCollector` (bateria real; só coleta com clientes WS), `LinkAuthManager` (pareamento, ver `BDSM_PLUGIN_OBS.md` §5) e mini‑servidor **Ktor (Netty + WebSockets + PartialContent, autenticado por pareamento, sem CORS)**; payloads serializados com kotlinx‑serialization.
- **Gravações** (feature-settings): galeria única sobre **Room** (`RecordingsGalleryViewModel` + `RecordingLibrary` em `core/recording`): importa arquivos do disco, reconcilia órfãos, favoritos persistidos em lote, busca e ordenação, confirmação de exclusão (sem desfazer), `LazyVerticalGrid/LazyColumn` com `key`, miniaturas do Room com `LruCache` por bytes e exportação via `MediaStore`. O player é externo (`ACTION_VIEW`).

## 8. Build & CI

- Versionamento no `app/build.gradle.kts`: `baseVersionName` + `semanticVersionCode` (`major*10000 + minor*100 + patch`; 1.0.0 = 10000); `-PforceReleaseVersion` sobrescreve o nome; debug com sufixo `- Versão de Desenvolvimento`.
- **Assinatura de release**: keystore por `local.properties` (`storeFile`, ...) ou por env (`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Em tag (`GITHUB_REF_TYPE=tag`) ou com `-PrequireReleaseSigning=true`, `assemble/package/bundleRelease` falham sem keystore (gate em `taskGraph.whenReady`); localmente, sem keystore, o release cai na chave de debug (só para teste).
- **R8**: `isMinifyEnabled` e `isShrinkResources` só no `:app` (release); regras em `app/proguard-rules.pro` e nos `consumer-rules.pro` de `core-network` (Netty/Ktor/serialization), `core-media` (JNI) e `core-capture` (UVC). O `mapping.txt` é arquivado como artefato no CI.
- **Testes**: todos os módulos têm JUnit, MockK, Turbine, coroutines-test e org.json em `testImplementation`, e `unitTests.isReturnDefaultValues = true`.
- **CI** (`.github/workflows/release.yml`, GitHub Actions):
  - `ci.yml` (push em `main`, PR, manual): validação do wrapper → JDK 17 → `./gradlew assembleDebug lintDebug testDebugUnitTest -Pbdsm.abis=arm64-v8a` → upload de relatórios de lint e testes.
  - `release.yml` (tag `v*` e `workflow_dispatch`): em tag, exige os 4 secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) e tag no padrão `vMAJOR.MINOR.PATCH`; decodifica o keystore para `$RUNNER_TEMP` (exporta `KEYSTORE_FILE`) → `assembleRelease` → artefatos do APK e do `mapping.txt` → Release no GitHub (só em tag).
  - O `gradle-wrapper.jar` precisa estar versionado (o `.gitignore` tem a exceção `!gradle/wrapper/gradle-wrapper.jar`); ambos os workflows validam o wrapper.
- Config compartilhada: versões no `libs.versions.toml`; `settings.gradle.kts` com `FAIL_ON_PROJECT_REPOS`, os 9 módulos e `includeBuild("build-logic")`. O `build-logic` fornece os convention plugins `bdsm.android.library` (SDK 36/26, JVM 17, consumer rules), `bdsm.android.compose`, `bdsm.android.hilt` e `bdsm.android.test`; os `build.gradle.kts` dos módulos só têm namespace e dependências próprias. `gradle.properties`: heap de 4 GB, `parallel`, `caching` e `configuration-cache` ligados (`problems=warn`); Jetifier ligado até verificar o UVCAndroid. Análise estática: `./gradlew detekt ktlintCheck` (no `ci.yml`); baselines em `config/detekt/baseline-*.xml` e `<módulo>/ktlint-baseline.xml`. **Estado em 2026-10-03: o `ktlintCheck` falha em `:core` e `:core-media` (formatação de código do nome NDI posterior ao baseline).** Baseline Profile em `app/src/main/baseline-prof.txt` (escrito à mão; Macrobenchmark pendente).

## 8.1 Temas e design tokens (`common/ui/theme`)

- **Modo**: `ThemeMode` (`core/domain`: `SYSTEM`/`LIGHT`/`DARK`, `resolveDark(systemDark)`, `fromName` tolerante) persistido no DataStore (`theme_mode`, mais `dynamic_color`, desligado por padrão) via `SettingsRepository.themeMode/dynamicColorEnabled`. A `MainActivity` lê o valor salvo uma vez (síncrono) para não piscar no tema errado e depois coleta o Flow; o seletor fica em Configurações > Aparência (`SettingsViewModel.appearance`).
- **Tema**: `BragaStudioMobileTheme(darkTheme, dynamicColor, appTheme)`. `AppTheme` (interface) = paletas clara/escura + tokens; `BdsmDefaultTheme` é o padrão e novos temas entram como novos objetos `AppTheme`. Material You (Android 12+) só troca o `ColorScheme`; os tokens continuam do `AppTheme`.
- **Tokens**: `BdsmTheme.colors` (success/warning/info/recording/tally, `card`/`cardBorder`, acentos por categoria `accentVideo/Audio/Storage/Monitor/Network/Library/System/Neutral`), `BdsmTheme.spacing` (múltiplos de 4/8, `touchTarget` 48 dp, `contentMaxWidth`) e `BdsmTheme.shapes`. Telas não usam `Color(0x...)`; usam `MaterialTheme.colorScheme` ou os tokens.
- **Componentes** (`common/components/`): `SettingsComponents.kt` (`BdsmScreen`/`BdsmLazyScreen`, `BdsmCard`, `SettingsSection/Item/SwitchItem`, `IconBadge`, `SegmentedChoice`, `StatusChip`, `EmptyState`, `ErrorState`, `LoadingState`, `ChoiceDialog`, `ConfirmDialog`), `BdsmDesign.kt`/`BdsmUx.kt` (botões, `BdsmNavigationBar`, `StatusRow`, busca, `showUndo`) e `BdsmV3.kt` (UX v3: `BdsmNavigationRail`, `BdsmBigButton`, `ModuleCard`, `SettingsCategoryRow`, `BdsmBottomSheet`, `DetailLine`), `BdsmOneUi.kt` (Etapas 2-5: `BdsmLargeTitleScaffold`, `BdsmGroupedList`/`BdsmGroupLabel`/`BdsmGroupDivider`, `OneUiAppBar`) e `UiText.kt` (texto de ViewModel sem `Context`: recurso + argumentos ou texto pronto).
- **Tokens One UI (Etapa 1)**: formas `BdsmTheme.shapes` = `container` 26 / `card` 20 / `item` 16 / `chip` 12 dp + `pill`; `spacing.screenMargin` = `groupGap` = 24 dp (margem lateral dentro da área segura); `BdsmType.screenTitle` (30 sp, só Ajustes, Etapa 2) e `groupLabel` (12 sp). Tema escuro em preto puro `#000000` (background/surface/surfaceDim) com cartões `#121212`/`#1C1C1C`; claro inalterado. Rótulos seguem a regra do guia (só a primeira letra maiúscula, exceto siglas).
- **One UI, Etapas 2-5**: `BdsmLargeTitleScaffold` (só em Ajustes e categorias) = app bar expansível com 2 estados (39,67% da altura da tela no celular, 18,78% no tablet; título centralizado; snap pelo limiar de 50% ou por arremesso; categorias começam recolhidas; SEMPRE compacta em celular na horizontal; a fração de recolhimento é lida só em `layout`/`graphicsLayer`, sem recompor a lista). `BdsmGroupedList` = contêiner único de 26 dp, tonal (sem sombra e sem borda), linhas com destaque de 16 dp recuado 4 dp, divisores recuados (`BdsmGroupInset.Icon` 60 dp / `Category` 76 dp), 24 dp entre grupos; `SettingsSection` já usa. Token novo `BdsmTheme.colors.primaryText` (vermelho para texto pequeno: escuro `#FF4D73`, claro `#C70030`; o `primary` da marca fica para ícones e preenchimentos) e `BdsmTextButton`/`bdsmTextFieldColors()` que o aplicam. Ver `IDEIAS_ONE_UI.md` §9.
- **Paleta do Monitor congelada**: `AppTheme.monitorColorScheme()/monitorTokens()` (usados por `BdsmDarkSurfaceTheme`) mantêm a paleta escura anterior (#08090B etc.), independente do escuro do app.
- **Monitor sempre escuro**: o `PreviewScreen` é envolvido por `BdsmDarkSurfaceTheme` (`MonitorTheme.kt`), que força o esquema escuro sem tocar na janela; o HUD usa o `HudTheme` próprio.
- **XML**: `values/` (claro) e `values-night/` (escuro), com variantes `-v27`/`-v31`; `splash_bg` muda com o tema para evitar flash. Limitação: com modo forçado diferente do sistema, o primeiro quadro da janela segue o tema do sistema.

## 8.2 Desempenho de UI (jank)

- **Partida**: `installSplashScreen()` + `setKeepOnScreenCondition { !themeReady }`; tema/cor dinâmica lidos de forma assíncrona (`lifecycleScope`) antes do `setContent`. Sem `runBlocking` na Main e sem espera fixa (a antiga splash Compose gastava ~2,2 s). Tema da janela: `Theme.BragaStudioMobile.Starting` -> `postSplashScreenTheme`.
- **Coleta de estado**: sempre `collectAsStateWithLifecycle()` nos módulos de UI (não coletar com a tela em segundo plano).
- **Monitor**: estados de alta frequência (tempo, VU, picos, metadados, zoom/pan) ficam como `State` e são lidos só em lambdas/leaf composables (`ZoomControlHost`, providers do HUD).
- **Listas**: `LazyColumn`/`LazyVerticalGrid` com `key` e `contentType`; miniaturas decodificadas em IO com `inSampleSize`.
- **Imagens**: `camera_lens` em `drawable-nodpi` (WebP 512 px, ~22 KB; antes JPG 1024 px/526 KB em mdpi, ~30 MB decodificados em xxhdpi).
- **Baseline Profile**: `app/src/main/baseline-prof.txt` (escrito à mão, empacotado pelo AGP; `profileinstaller` ativo). Medição de estabilidade: `-I` com init script que define `composeCompiler.reportsDestination` (com *strong skipping* todos os composables são skippable).

- **Diagnóstico (A51, Mali-G72 + Exynos 9611, 1080x2400/60 Hz, release `speed-profile`; Perfetto com FrameTimeline + `gfxinfo`)**:
  - O "jank" de rolagem do `gfxinfo` (Configurações/LUTs/NDI: mediana 17-22 ms, 2-5 % janky) é **latência de pipeline**, não quadro perdido: no Perfetto, 319 de 400 quadros de rolagem em Configurações tiveram jank `None`, 73 `Buffer Stuffing` (fila cheia, sem perda visual) e só 7 `App Deadline Missed` (~2 %). Por quadro: UI ~2 ms, issue-draw ~4-5 ms, GPU ~9 ms (GPU a 260 MHz quase o tempo todo: `time_in_state` e `utilization` ~35 %, o governor não sobe o clock). Não há sombra/blur/graphicsLayer pesado no código; tokens já são `staticCompositionLocalOf` + `@Immutable`.
  - **Causa raiz dos quadros realmente perdidos: composição + medição da tela inteira no 1º quadro de cada navegação.** Cada entrada em uma tela gera 1 quadro de 50-150 ms em `Choreographer#doFrame` (`Recomposer:recompose` 55-120 ms e `AndroidOwner:measureAndLayout` 30-50 ms, porque o `Scaffold` compõe o conteúdo via subcomposição durante o layout); o retorno para a Home recompõe/mede a Home do zero (35-75 ms; `IntrinsicSize.Min` nos cartões). Configurações era uma `Column` não-lazy com ~45 linhas + ~25 ícones compostas de uma vez (pior caso: 149 ms). Compilar tudo AOT (`-m speed`) não muda isso (não é JIT frio). Um serviço de acessibilidade ativo no aparelho (Microsoft Link to Windows) soma ~4-5 ms por quadro em `checkForSemanticsChanges`/`sendAccessibilitySemanticsStructureChangeEvents` durante a rolagem.
  - Transições do `NavHost` com `fadeIn/fadeOut` em tela cheia forçam camada offscreen (2 por transição): issue-draw 10-14 ms e GPU 12-18 ms nos quadros de entrada/retorno.
  - Overdraw: janela + `Surface` raiz + `Scaffold` = 3 camadas de tela cheia (medido com `debug.hwui.overdraw`).
- **Mudanças**: (1) Configurações em `LazyColumn` (`BdsmLazyScreen`, `item(key, contentType)` por seção); (2) transições do `NavHost` só com deslocamento (sem alpha) exceto Home<->Monitor (fade); (3) `Surface` raiz do `MainActivity` transparente (uma camada de overdraw a menos; a janela e cada `Scaffold` desenham o fundo).
- **Números (gfxinfo, mesmo roteiro: Home -> tela -> 5 rolagens ida/volta -> voltar; baseline = 2 execuções do APK release original)**: rolagem Config p50/p90/p95/p99 17/21-22/22/24-28 ms, 1,8-2,3 % janky; NDI 15-17/20/21/22, ~2 %; LUTs 21/22-23/23-24/29-30, ~2 %; entrada em Config 61 % e 14 % janky (p99 73-150 ms), NDI 15-19 %, LUTs 17 %; retorno à Home 31-92 % janky (p90 69-109 ms). **Não foi possível medir o build final** (o aparelho bloqueou a tela com keyguard seguro durante a sessão); medições intermediárias com só as mudanças (2) e (3) variaram demais entre execuções (rolagem Config 2-13 % janky, entrada 3-17 %) para concluir ganho. Repetir o roteiro com o build final (`scripts` no histórico da sessão: `gfxinfo reset` + `input swipe` + `dumpsys gfxinfo`) antes de afirmar ganho.
- **Pendências sugeridas**: trocar `IntrinsicSize.Min` na Home por altura fixa/`weight`; manter a Home viva (ou `rememberSaveable`/pré-composição) ao navegar; medir Monitor (tempo até o 1º frame) e Gravações com conteúdo.

## 8.3 Como adicionar um módulo

Um módulo é uma tela de nível superior (aba da barra, cartão da Home, entrada em Ajustes > Módulos). Para criar um novo (ex.: **BSP**) basta: (1) criar a implementação de `BdsmModule`; (2) contribuí-la com `@Provides @IntoSet`; (3) opcionalmente criar um novo `feature-xxx` com o plugin `id("bdsm.android.feature")` (já traz Compose, Hilt, testes, `:common`, `:core` e navegação) e declará-lo como dependência do `:app`. **Home, barra de navegação e Ajustes > Módulos não são editadas**: são geradas do registro. Strings do módulo ficam no próprio `feature-xxx`. O módulo **BSP** real não ganhou `feature-bsp`: a tela e o `BspModule` ficam em `feature-settings` (reaproveitam ViewModels, componentes e o padrão da tela NDI), com placement `MORE`.

```kotlin
// feature-bsp/src/main/java/.../BspModule.kt
object BspModule : BdsmModule {
    override val id = "bsp"                       // único; duplicado falha cedo (DuplicateModuleIdException)
    override val titleRes = R.string.module_bsp
    override val subtitleRes = R.string.module_bsp_sub
    override val icon: ImageVector get() = Icons.Filled.Sensors
    override val category = ModuleCategory.NETWORK
    override val order = 50                       // ordem dentro de cada posição
    override val placements = setOf(ModulePlacement.MORE, ModulePlacement.HOME_CARD)  // ou BOTTOM_BAR (use 3 a 5)
    override val route = "bsp"
    override val featureFlag: String? = "bsp"     // opcional: FeatureFlags desliga sem remover código

    override fun registerRoutes(builder: NavGraphBuilder, navController: NavController) {
        builder.composable(route) { BspScreen(onBack = { navController.popBackStack() }) }
    }

    @Composable
    override fun statusLabel(): String? = null     // uma linha de estado para o cartão da Home
}

@Module @InstallIn(SingletonComponent::class)
object BspFeatureModules { @Provides @IntoSet fun bsp(): BdsmModule = BspModule }
```

Posições (`ModulePlacement`): `BOTTOM_BAR` (aba; hoje 4: Início, NDI, Mídia, Ajustes), `HOME_PRIMARY` (ação principal da Home), `HOME_CARD` (grade 2x2), `MORE` (seção "Módulos" em Ajustes), `SETTINGS_ADVANCED` (seção em Configurações > Aplicativo via `SettingsContent()`). `selectionRoutes` mantém uma aba selecionada em sub-rotas (Mídia usa `media`, `recording`, `luts`). Teste: `BdsmModuleRegistryTest` (ids únicos, ordem, visibilidade, abas por rota).

## 8.4 UX v3 (One UI, uso com uma mão)

Regra: **mostrar primeiro o que o usuário precisa; esconder a complexidade até ser necessária.** Topo = contexto/título/estado; centro = conteúdo; fundo = ações e navegação. Alvos >= 48 dp (botões principais 56 dp), cantos generosos (tokens `BdsmShapes`: 26/20/16/12 dp), háptico leve (`rememberBdsmHaptics`) nas ações principais, vermelho = ação principal/gravação/seleção, verde = ativo, âmbar = atenção, vermelho de erro = exclusão/erro; estado nunca só por cor (ícone + texto).

- **Início**: saudação curta + "Braga Digital Studio"; grade 2x2 (NDI, Gravações, LUTs, Ajustes) com uma linha de estado; cartão do Monitor no fundo ("Nenhuma câmera ativa" ou "Câmera ativa" + "4K • 30 FPS", e "Gravando 00:12" quando grava) com o botão grande ABRIR MONITOR. O estado da câmera vem de `CameraStatusProvider` (core-capture, singleton): no `Application.onCreate` agenda, fora da Main, a enumeração de câmeras (`CameraManager.cameraIdList`, `UsbManager`) e a leitura do formato salvo, SEM abrir a câmera nem iniciar preview; Home e NDI leem o mesmo `StateFlow` (Loading com esqueleto; pronta; sem permissão, com a ação "Conceder permissão"; indisponível; a fonte Sony mostra só a fonte configurada). Em telas largas vira duas colunas. Estados: carregando, pronto, ativo (câmera/gravação), atenção (pouco espaço/bateria/calor).
- **NDI**: um círculo de estado (DESATIVADO / ATIVO / "Inicializando NDI..." / erro "Não foi possível iniciar a transmissão"), o nome do stream (`BDSM (nome)` via `NdiNaming`) e UM botão grande (INICIAR / PARAR / TENTAR NOVAMENTE). "DETALHES DA TRANSMISSÃO" abre uma folha (nome, IP, estado, rede, qualidade); "Configurações avançadas" abre `ndi_advanced` (passo a passo, som, nome, tamanho da imagem, métricas). O estado vem de `NdiScreenLogic.phase` (ligado x sender no ar x falha/tempo de 8 s).
- **Mídia**: seletor Gravações | LUTs. Gravações = galeria com seções "Hoje", "Ontem", "2 de outubro"... (`RecordingDateLabels`), grade de miniaturas com duração; tocar abre a folha (prévia, duração, REPRODUZIR no player do sistema, nome/data) com compartilhar, renomear (`RecordingLibrary.rename`) e excluir (confirmação + Desfazer); seleção múltipla por toque longo. LUTs = grade "Minhas LUTs" com a prévia do efeito, [+ Adicionar LUT], folha com prévia grande, Intensidade 0-100% e APLICAR / EDITAR (renomear) / EXCLUIR (confirmação + Desfazer).
- **Ajustes** (aba da barra): **Configurações** (título grande que colapsa; em tela larga as categorias ficam em 2 colunas): título + "Pesquisar configurações" (a barra some ao rolar; a lupa do topo volta ao início e foca o campo; filtra categorias e itens), 7 categorias com ícone/título/descrição (Câmera, Áudio, Monitor, NDI, Gravação, Aplicativo, Sobre) e nenhum interruptor na primeira tela; cada categoria abre os itens que já existiam. Presets Econômica/Equilibrada/Máxima ficam em Gravação, com "Avançado" recolhido (codec, taxa de dados, HDR).
- **Monitor**: não foi redesenhado (tela cheia, sem barra, HUD e lógica intactos).

- **NDI > Ver na rede (radar embutido)**: a tela NDI tem, de cima para baixo, o estado compacto da transmissão (círculo pequeno, ATIVO/DESATIVADO e `BDSM (nome)`), o radar ou a lista das fontes NDI da rede (centro; alternância Radar | Lista e ordenação por proximidade/tipo só com ícones) e o botão grande INICIAR/PARAR com "Detalhes da transmissão". Código: `core-media/ndi/` (`NdiNative` + `NdiNetwork.cpp`, `NdiDiscovery`, `NdiProbe`, `NdiReceiver`, `NdiSource` com classificação/ordenação/faixas, tudo puro e testado) e `feature-settings` (`NdiNetworkSection`, `NdiRadar`, `NdiNetworkLogic`, `NdiNetworkViewModel`, `NdiPreviewScreen`/`NdiPreviewViewModel`). A descoberta (NDIlib_find + MulticastLock) só roda com a tela NDI visível. Tocar numa fonte abre um cartão com o NOME (nunca o IP, que só existe no objeto interno `NdiSource.address`) e "Ver dispositivo"/"Abrir preview". O preview é a rota imersiva `ndi_preview/{name}` (fora de abas, sem barra): `NdiReceiver` (NDIlib_recv, thread nativa dedicada, RGBX -> `ANativeWindow`, teto 720p, banda lowest com térmico >= MODERATE) abre ao ficar visível e fecha ao sair; 1 receptor por vez. Veja `PLANO_NDI_RADAR.md` para limites (proximidade = latência de rede, licença do SDK).

**O que foi escondido e onde fica**

| Antes (visível) | Agora |
| --- | --- |
| IP, rede e estado do Link/OBS na Home ("Ver detalhes do equipamento") | IP/rede em NDI > Detalhes da transmissão; Link/OBS em Configurações > Aplicativo; bateria/espaço/temperatura só como aviso no cartão do Monitor quando precisam de atenção; diagnóstico em Configurações > Aplicativo > Diagnóstico |
| Formato "1080p · 30 · H.264" e bitrate na Home | Só "4K • 30 FPS" com a câmera ativa; codec/bitrate em Configurações > Gravação > Avançado |
| Interruptor, passo a passo, nome, qualidade e métricas na tela NDI | Uma tela com um botão; o resto em Detalhes e em NDI > Configurações avançadas |
| Linhas de codec/resolução/fps/tamanho em cada vídeo | Só a duração e o tamanho na grade; formato completo na folha de detalhes |
| Lista de LUTs com intensidade sempre à mostra | Grade de cartões; intensidade e ações só na folha da LUT |
| Ajustes em uma rolagem longa com "Avançado" | 7 categorias; busca; "Avançado" recolhido em Gravação |

### Home v4 (mescla Home original + UX v3)

Layout retrato: marca "BRAGA DIGITAL | STUDIO MOBILE" (+ saudação pequena) e faixa de métricas no topo; grade 2x2 de módulos no centro; hero do Monitor no fundo, colado à barra inferior. Em tela larga (`bdsmWidthClass() != Compact`): marca, métricas e hero à esquerda; grade à direita.

- **Veio da original**: identidade (marca em 2 linhas vermelhas + "STUDIO MOBILE" em negrito), hero do Monitor com a lente (WebP de 512 px) e botão pílula "Abrir Monitor →", faixa de métricas (temperatura, espaço, bateria, Wi-Fi), cartões com ícone grande colorido + descrição + seta (NDI ciano, Gravações violeta, LUTs laranja, Ajustes azul; módulos novos caem na cor da categoria).
- **Ficou da v3**: uso com uma mão, estado real da câmera (`CameraStatusProvider`: "Câmera ativa" + "1080p • 30 FPS" / "Nenhuma câmera ativa" / "Conceder permissão"), grade gerada do registro, cronômetro de gravação, sem IP/porta/codec, sem `IntrinsicSize`, sombras ou blur.
- **Por que o hero fica embaixo**: na original o botão ficava a ~33% da altura (fora do polegar); no fundo ele cai a ~83%, a uma faixa da barra inferior. Dados só de leitura (marca, métricas) ficam no topo, onde o polegar não precisa chegar.
- **Métricas**: uma linha, ícone + valor, sem rótulos; só destaca o que pede atenção (bateria <= 20% sem carregar, espaço <= 10 GB, >= 40 °C, sem Wi-Fi) com pastilha âmbar + triângulo + descrição falada (nunca só cor). Regras puras em `HomeStatus.metricAlerts`.
- **Sem carrossel nem pontos** (decisão do usuário): o hero é só o Monitor. **Tipografia** própria da Home (`HomeType`: marca 10/17 sp, títulos de cartão 15 sp semibold, descrições 12-13 sp, métricas 12 sp, botão 14 sp), sem mexer nos tokens globais; alvos >= 48 dp. A faixa de métricas é um cartão fino logo abaixo da marca, acima do hero. O botão de captura decorativo da original foi descartado (não fazia nada).

### Home v5 (cabeçalho grande + saudação + cards; substitui a v4)

A Home usa `BdsmLargeTitleScaffold` como Ajustes: título grande "Braga Digital Studio" (centralizado, recolhe ao rolar para a barra compacta; 39,67% da altura no celular, compacta em paisagem de celular/telas baixas). A lista (`LazyColumn` com `key`/`contentType`) tem, em ordem: saudação ("Bom dia/Boa tarde/Boa noite, <nome>" por hora local 05-11:59 / 12-17:59 / 18-04:59), cartão do Monitor e grade 2x2 de módulos gerada do registro. Em tela larga (`bdsmWidthClass() != Compact`, incl. paisagem de celular) o Monitor fica à esquerda e a grade à direita, conteúdo até 960 dp.

- **Nome de exibição**: o app não tem conta. `DisplayName` (`core/domain`): padrão = nome do aparelho (`NdiNaming.deviceName(context)`, mesma origem do nome NDI; `DeviceNames` é `internal` ao core-network e tem fallback diferente); editável em Ajustes > Aplicativo > Perfil > "Seu nome" (diálogo, limite 24, sem quebras de linha, espaços juntados); vazio = volta ao padrão. Persistido no DataStore (`display_name`, `SettingsRepository.displayName/setDisplayName`), só local, nunca enviado. Nome genérico ("Android", "unknown", vazio) é omitido: só a saudação. `HomeStatus.greeting(saudação, nome)` formata.
- **Removido**: faixa de métricas (temperatura, armazenamento, bateria, Wi-Fi) e seus alertas; marca em 2 linhas da v4; `HomeStatus` perdeu níveis/saúde/headline/tempo restante/`sourceKind`/`formatSummary`; `HomeUiState` perdeu métricas, IP, NDI/Link/LUT/bitrate; `HomeViewModel` não depende mais de `HardwareMonitorService`, `LinkServerController`, `LutRepository`; strings `home_*` não usadas foram apagadas.
- **Monitor**: cartão com estado da câmera + botão pílula de 56 dp (cartão inteiro clicável), lente reduzida a 72 dp. Feature-preview e captura não mudaram.

### Paisagem (celular e telas largas)

- **Rail à direita por padrão** (lado da mão/polegar). `AppNavigation(railSide)` usa `LandscapeNavSide` (`core/domain`, DataStore `landscape_nav_side`, padrão `END`; canhotos: `START` em Ajustes > Aplicativo > Navegação em paisagem). "Start/End" respeitam RTL. O rail absorve `safeDrawing` (cutout + barra de gestos) do SEU lado e o `NavHost` consome esses insets; o outro lado é tratado pelas telas. O rail só aparece com `bdsmWidthClass() != Compact`; itens com ícone + texto, mesma ordem da barra inferior.
- **Regras por tela**: `bdsmIsShortLandscape()` (largura > altura e altura <= 580 dp; nunca só orientação). NDI: estado + ações à esquerda (40%), radar/lista à direita (60%) em altura total (antes o radar ficava sem altura). Mídia: título e seletor Gravações|LUTs na mesma linha; contagem/busca/ordenar e abas Todas|Favoritas na mesma linha; insets horizontais (cutout) aplicados. Folhas (`BdsmBottomSheet`): rolagem vertical e largura 880 dp; detalhe de gravação com prévia à esquerda e ações à direita. Início e Ajustes já usavam 2 colunas por largura. App bar compacta em paisagem de celular (confirmada no aparelho).
- Teste no A51 com `wm size 2160x1080` (o `wm` limita a proporção): o cutout continua EM CIMA (faixa preta de ~176 px), diferente da paisagem real em que ele fica num lado; cutout lateral real não foi verificado.

## 9. Achados e observações de arquitetura

1. **Coesão limpa**: `core` (dados), `core-capture` (aquisição/gravação), `core-media` (render/NDI), `core-network` (rede/telemetria); features só consomem — bom isolamento.
2. **Acoplamento não transitivo**: `implementation` exige declarações repetidas de `core-network` em `core-media`/`feature-preview`/`feature-settings` (`LinkTelemetry`, tally). `SonyCameraStatus` já mora em `:core`; `MediaGraph` ainda expõe `recordManager`, `settingsRepository`, `lutRepository` e `context`.
3. **Resíduos de histórico**: artefatos `.cxx` (CMake) e `.vs/.vscode` apontam para diretórios de outras cópias ("Quase Final", "AntiGravity"); há easter-egg/extra (RouletteScreen) e comentários desatualizados — candidatos a limpeza no VCS.
4. **Recurso crítico**: SDK NDI proprietário via `libndi.so` em `jniLibs/<ABI>` (as 4 ABIs existem no repositório, mas o APK leva só arm64-v8a e armeabi-v7a por padrão); headers em `cpp/ndi/Include`. A máquina NDI é fixada em `BDSM` por `ndi-config.v1.json` + `NDI_CONFIG_DIR` (`NdiNaming`); o nome visto na rede é `BDSM (nome do sender)`.
5. **Testes**: 294 testes unitários JVM (core 20, core-capture 30, core-media 98, core-network 60, feature-preview 61, feature-settings 25) mais 1 teste instrumentado de migração do Room. Cobrem só lógica pura e as rotas do Link (`testApplication`); não há teste de `MediaGraph`, `RecordManager` com codec real, ViewModels ou UI/Compose.
6. **Evolução**: release exige keystore em tag; o `versionCode` é derivado do `versionName`.

---

*Documento de referência: `BDSM_VISAO_TECNICA.md` (visão do produto) e `BDSM_PLUGIN_OBS.md` (projeto OBS).*