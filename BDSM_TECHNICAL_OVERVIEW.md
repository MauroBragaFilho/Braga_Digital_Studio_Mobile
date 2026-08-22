# BDSM - Braga Digital Studio Mobile
## Visão Técnica Geral, Arquitetura e Guia para Análise

---

## 1. Visão do Produto e Propósito

O **Braga Digital Studio Mobile (BDSM)** é um software profissional de monitoramento, gravação e transmissão de vídeo para a plataforma Android.

O objetivo do BDSM é transformar um smartphone Android em um equipamento de alta performance equivalente a monitores de referência de estúdio e campo (como **Atomos Ninja / Shogun**, **Blackmagic Video Assist** e **Feelworld**), aproveitando a flexibilidade e o poder de processamento gráfico dos chipsets modernos e a conectividade Android.

### Pilares Fundamentais:
1. **Monitor Externo Profissional:** Monitoramento em tempo real para câmeras DSLR, Mirrorless, filmadoras e consoles via captura HDMI USB (UVC), além de câmeras internas do smartphone.
2. **Renderização GPU de Baixa Latência:** Pipeline OpenGL ES 3.0 / NDK C++ para processamento visual em tempo real sem sobrecarregar a CPU.
3. **Ferramentas de Exposição e Foco:** Focus Peaking, False Color, Zebra, Scopes (Histograma, Waveform, Vetorscópio), 3D LUTs (.cube), Anamorphic De-squeeze, Grids e Safe Areas.
4. **Áudio Desacoplado:** Entradas de áudio USB, P2/P3, Bluetooth e microfones internos independentes do sinal de vídeo, com VU meters em tempo real.
5. **Gravação de Alta Fidelidade:** Gravação em H.264 / H.265 (MP4/MOV) em armazenamento interno, cartões SD ou SSDs externos via USB-C.
6. **Transmissão e Conectividade de Estúdio:** NDI 6 nativo para envio de vídeo pela rede local com baixa latência, e servidor HTTP/WebSocket embutido (LinkServer) para controle remoto e sincronização de assets (LUTs, mídias, telemetria).
7. **Filosofia Sem IA no Runtime:** O aplicativo não utiliza SDKs de Inteligência Artificial/Machine Learning em tempo de execução para garantir previsibilidade, determinismo e foco em renderização/latência zero.

---

## 2. Stack Tecnológica

| Camada / Função | Tecnologias Utilizadas |
| :--- | :--- |
| **Linguagem Principal** | Kotlin 1.9+ / Kotlin Coroutines & Flow |
| **Linguagem Nativa (Core Media)** | C++17, Android NDK (CMake, Clang) |
| **Interface de Usuário (UI)** | Jetpack Compose, Material Design 3, Compose Navigation |
| **Injeção de Dependências** | Google Dagger Hilt 2.51+ |
| **Renderização Gráfica** | OpenGL ES 3.0, EGL, GLSL Shaders, SurfaceTexture / OES Textures |
| **Captura de Vídeo** | Android Camera2 API, USB Video Class (UVC / V4L2) |
| **Codificação de Vídeo/Áudio** | Android MediaCodec (H.264 / HEVC / AAC), MediaMuxer |
| **Streaming / Protocolos** | NDI 6 SDK (libndi C++), Ktor Server (Netty/CIO HTTP & WebSockets), mDNS/NSD |
| **Persistência de Dados** | Room Database (SQLite), DataStore Preferences |
| **Serialização** | Kotlinx Serialization (JSON) |
| **Build System** | Gradle Kotlin DSL (`build.gradle.kts`), Android Gradle Plugin (AGP 8.4+) |

---

## 3. Arquitetura do Sistema e Modularização

O projeto adota princípios de **Clean Architecture**, **MVVM (Model-View-ViewModel)** e **Modularização por Camadas/Features**:

```mermaid
graph TD
    App[":app"] --> FPreview[":feature-preview"]
    App --> FHome[":feature-home"]
    App --> FSettings[":feature-settings"]
    
    FPreview --> CMedia[":core-media"]
    FPreview --> CCapture[":core-capture"]
    FPreview --> Core[":core"]
    FPreview --> Common[":common"]
    
    FSettings --> CNetwork[":core-network"]
    FSettings --> Core[":core"]
    FSettings --> CMedia[":core-media"]
    
    CCapture --> Core[":core"]
    CMedia --> Core[":core"]
    CNetwork --> Core[":core"]
```

### Detalhamento dos Módulos:

### 3.1 `:core-media` (Coração Gráfico e Multimídia)
- **Engine Nativa C++ (`GlesEngine.cpp`, `GlesEngine.h`):**
  - Gerenciamento de contexto EGL e Surfaces nativas (`ANativeWindow`).
  - Textura OES externa para captura de vídeo em zero-copy.
  - Shaders customizados GLSL ES 3.0:
    - **Focus Peaking:** Detecção de arestas por matriz Laplaciana / filtro Sobel em tempo real, com thresholds ajustáveis e cores configuráveis (Vermelho, Verde, Azul, Amarelo, Branco).
    - **False Color:** Mapeamento de luminância IRE (0 a 100) em cores falsas para calibração de exposição e tons de pele.
    - **Zebra Pattern:** Linhas diagonais estáticas ou animadas com limiar de IRE customizável para identificar superexposição.
    - **3D LUT (Look-Up Table):** Textura 3D (`sampler3D`) carregando cubos de calibração `.cube` (17x17x17, 33x33x33, 65x65x65) com interpolação trilinear por hardware.
    - **Transformações:** Rotação (0°, 90°, 180°, 270°), correção de espelhamento, Pan & Zoom (Pinch-to-zoom) e De-squeeze anamórfico (1.33x, 1.5x, 1.66x, 1.8x, 2.0x).
- **NDI Nativo (`NdiEngine.cpp`, `libndi.so`):**
  - Integração C++ com o NDI 6 SDK.
  - Envio de frames RGBA capturados diretamente da GPU via PBO/Framebuffers para a rede local em altíssima qualidade e baixa latência.
- **MediaGraph (`MediaGraph.kt`):**
  - Orquestrador central de mídia. Garante que qualquer entrada de vídeo (`CaptureDevice`) seja roteada concorrentemente para a preview local, para o gravador (`RecordManager`) e para o transmissor NDI (`NdiManager`).

### 3.2 `:core-capture` (Captura de Vídeo e Áudio)
- **`CaptureDevice` (Interface Abstrata):**
  - Permite plugar qualquer fonte de vídeo sem mudar a arquitetura.
- **`Camera2Device.kt`:**
  - Controle manual absoluto da câmera do smartphone: ISO manual, Shutter Speed (tempo de exposição), Balanço de Branco (temperatura Kelvin), Foco manual e seleção de lentes (Ultra Wide, Wide, Telefoto, Macro).
- **`UvcCaptureDevice.kt`:**
  - Gerenciamento de placas de captura HDMI USB / Webcams via protocolo USB Video Class e UVC nativo.
- **`RecordingEngine.kt`, `H264Encoder.kt`, `AacEncoder.kt`, `MediaMuxerWrapper.kt`:**
  - Pipeline de gravação em thread dedicada com MediaCodec, gerando arquivos MP4/MOV sincronizados com áudio PCM/AAC.

### 3.3 `:core-network` (BDSM Link & Sincronização)
- **`LinkServer.kt`:**
  - Servidor Ktor embutido rodando no dispositivo, expondo APIs REST e WebSockets para integração com computadores na mesma rede (ex: Plugin OBS, painel web de controle).
- **`LutLibraryService.kt` / `MediaLibraryService.kt` / `DeviceInfoService.kt`:**
  - Sincronização bidirecional de arquivos `.cube` (LUTs).
  - Listagem, download e streaming de gravações realizadas.
  - Telemetria de bateria, temperatura, CPU e espaço livre no armazenamento.
- **`DiscoveryService.kt`:** Anúncio do serviço via mDNS/NSD (Network Service Discovery).

### 3.4 `:core` e `:common` (Modelos, Banco e Base)
- **Room Database (`BsmDatabase.kt`):**
  - `RecordingDao` / `RecordingEntity`: Metadados das gravações (resolução, bitrate, fps, duração, codec, thumbnail).
  - `LutDao` / `LutEntity`: Biblioteca de LUTs instaladas, metadados e estado ativo.
- **Hardware Telemetry (`HardwareMonitorService.kt`):**
  - Monitoramento de temperatura de bateria/CPU, memória e taxas de quadros (FPS).
- **Video Scopes Logic (`VideoScopes.kt`):**
  - Estruturas de dados para Histogramas (RGB/Luma), Waveform e Vetorscópio.

### 3.5 `:feature-preview` (Interface do Monitor)
- **`PreviewScreen.kt` & `PreviewHud.kt`:**
  - Interface do monitor de câmera com controles rápidos em overlay.
  - Barra de ferramentas profissionais (Peaking, False Color, Zebra, LUT, Grids, Aspect Ratios, Scopes).
  - Gestos de toque para foco manual, zoom e arrasto (pan).
- **Scopes Components (`HistogramScope.kt`, `WaveformScope.kt`, `VectorscopeScope.kt`, `ScopesOverlay.kt`):**
  - Desenho vetorial de histogramas e osciloscópios sobre a imagem.

### 3.6 `:feature-home` e `:feature-settings`
- Galeria de gravações com player e exportação.
- Gerenciamento de LUTs (importação `.cube`, preview, remoção).
- Configurações de NDI (nome do stream, resolução, framerate).
- Diagnósticos de sistema e parâmetros de captura.

---

## 4. O Fluxo de Dados de Vídeo (Pipeline de Baixa Latência)

```
[ Camera2 / UVC HDMI Capture ]
             │
             ▼ (SurfaceTexture OES)
     ┌───────────────┐
     │  GlesEngine   │ (C++ OpenGL ES 3.0)
     └───────┬───────┘
             │
 ┌───────────┼──────────────────────────┐
 │ (Render)  │ (glReadPixels / PBO)     │ (Surface Input)
 ▼           ▼                          ▼
[ EGL Surface ]  [ NdiEngine / libndi.so ]   [ MediaCodec Encoder ]
(Tela/HUD)       (Transmissão NDI Rede)     (Gravação MP4/MOV)
```

1. A entrada de vídeo gera frames diretamente em uma textura OES externa gerenciada pelo `GlesEngine`.
2. Os shaders aplicam em tempo real (em 1 único passe ou passes otimizados) transformações de matriz, de-squeeze, 3D LUT, Focus Peaking, False Color e Zebras.
3. O frame processado é apresentado na Surface da tela do dispositivo em 60fps constantes.
4. Concorrentemente, se a transmissão NDI estiver ativa, os buffers são enviados ao `NdiEngine` via NDK.
5. Se a gravação estiver ativa, o `RecordingEngine` consome os quadros no codificador de hardware (`MediaCodec`) e grava via `MediaMuxer`.

---

## 5. Pontos Críticos para Análise do Claude e Próximas Melhorias

Ao analisar o código para propor correções e melhorias, os seguintes pontos prioritários devem ser avaliados:

### 5.1 Melhorias na Interface do Monitor (`:feature-preview`)
1. **Ergonomia e Design de Monitor Profissional:**
   - Redesenhar o HUD para seguir a usabilidade dos monitores Atomos / SmallHD / Blackmagic: botões minimalistas com feedback tátil, barras laterais colapsáveis, acesso rápido a ferramentas essenciais (Peaking, Zebra, False Color, LUT, Scopes) sem poluir a área do vídeo.
   - Indicador de Gravação (Tally Red Border / Tally Light) bem visível em volta do quadro.
   - VU meters estéreo responsivos no HUD com indicadores de pico e clipping (-inf a 0 dB).
   - Timecode em tempo real (HH:MM:SS:FF) com contador de tempo restante de gravação baseado no espaço em disco.
2. **Interação por Gestos:**
   - Aprimorar o Pinch-to-Zoom e Double Tap para zoom 100% (Pixel-to-Pixel) e 200% com indicador de viewport miniatura (quadro de navegação).
   - Sliders de ajuste rápido de Peaking Sensitivity e Zebra Threshold diretamente na tela com toque contínuo.

### 5.2 Scopes de Vídeo em Tempo Real
1. **Otimização de Performance:**
   - Garantir que o cálculo dos Scopes (Histograma, Waveform e Vetorscópio) seja executado via GPU (Compute Shader ou amostragem reduzida em FBO/Render-to-Texture) para não provocar gargalos de CPU na thread principal.
   - Opções de exibição de Scopes: Luma Waveform, RGB Parade, Histograma RGB e Vetorscópio com escala IRE.

### 5.3 Pipeline de Gravação e Áudio
1. **Formato MOV e Codecs Profissionais:**
   - Suporte nativo à gravação em container `.mov` além de `.mp4`.
   - Suporte a H.265 (HEVC) com bitrates altos configuráveis (50 Mbps a 150 Mbps para 4K).
2. **Gerenciamento de Áudio:**
   - Suporte robusto a interfaces de áudio USB externas (Focusrite, Rode, Boya, etc.), permitindo selecionar canal L/R, ganho e monitoramento com fone de ouvido em baixa latência (OpenSL ES / AAudio).

### 5.4 Estabilidade e Detecção UVC (Placas de Captura HDMI)
1. **Hotplug de Dispositivos USB:**
   - Garantir reconexão automática sem crash quando uma placa de captura HDMI USB for conectada ou desconectada.
   - Suporte a seleção de formatos MJPEG e YUY2 / NV12 negociados com a placa UVC.

---

## 6. Convenções e Regras Obrigatórias para Desenvolvimento

1. **Nunca adicionar dependências de IA/ML ao app** (OpenAI, Gemini, TensorFlow Lite, ML Kit, etc.). O app deve permanecer 100% nativo, determinístico e de alta performance.
2. **Manter o isolamento do `MediaGraph`:** Telas e ViewModels não devem se comunicar diretamente com APIs de baixo nível (Camera2, UVC, OpenGL, NDI). Toda a orquestração passa pelo `MediaGraph` e seus repositories/services.
3. **Respeitar a Clean Architecture e Injeção com Hilt:** StateFlows expostos para a UI, UseCases e Repositories bem definidos.
4. **Foco em Latência Mínima:** Evitar alocações de memória ou leituras síncronas bloqueantes dentro do loop de renderização da GPU.
