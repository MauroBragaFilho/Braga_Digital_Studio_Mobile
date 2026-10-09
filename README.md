# Braga Digital Studio Mobile

*Read this in [English](README_en.md)*

O **Braga Digital Studio Mobile** é um software profissional e proprietário de monitoramento, gravação e transmissão de vídeo exclusivo para dispositivos Android.

O grande objetivo deste projeto é transformar o seu smartphone Android em um equipamento equivalente a um monitor profissional para audiovisual, aproveitando ao máximo a mobilidade, a conectividade e a tela do ecossistema Android.

## Principais Objetivos e Funcionalidades (Roadmap)

### 🖥️ Monitor Externo
Use seu dispositivo como monitor portátil via conexão USB UVC (placa de captura HDMI-USB) ou pela câmera do celular. O sistema foi projetado para alternar dinamicamente entre diferentes fontes de vídeo, tais como:
* A câmera nativa do próprio smartphone
* Câmeras com saída HDMI (como a Sony α6000) via placas de captura HDMI/USB UVC

### 📷 Câmeras HDMI (Sony α6000 etc.) como fonte de vídeo
Ligue a saída HDMI limpa da câmera a uma placa de captura HDMI→USB **UVC** (aparece como webcam) e conecte ao celular por USB OTG; escolha **"Câmera USB"** em Ajustes > Câmera. Na α6000, desligue "Info. HDMI" (Menu > Configurações HDMI > Info. HDMI > Desligado) para a saída limpa. Placas que exigem driver próprio (ex.: Elgato HD60 S) não funcionam no Android. Preview, gravação, NDI e LUTs funcionam a partir dessa fonte pelo `MediaGraph`.

> A antiga fonte "Sony Camera (Wi-Fi)" (Camera Remote API, JPEG de baixa resolução) está **desativada** por um interruptor (`CaptureFeatureFlags.SONY_WIFI_ENABLED`, hoje `false`); o código continua no repositório e pode ser religado (ver `.docs/ARQUITETURA.md`).

### 🎛️ Ferramentas Avançadas de Monitoramento
Desenvolvimento focado em auxiliar criadores na exposição e enquadramento de forma precisa e em tempo real:
* Focus Peaking, False Color, Zebra
* Histograma, Waveform, Vetorscópio
* Grids e marcadores de proporção (Safe Area e de-squeeze anamórfico são planejados)
* Suporte à aplicação de LUTs personalizadas (.cube) somente no monitoramento

### 🎙️ Áudio e Gravação Profissional
* Entradas independentes para vídeo e áudio.
* Suporte a microfones USB.
* Gravação em 4K nos formatos H.264 / H.265 (MP4).
* Destino da gravação: armazenamento do app, Galeria (Android 10+) ou pasta escolhida (SAF, inclusive SD/SSD externo). O take é gravado localmente e copiado ao destino no fim.
* A sessão de captura é independente da tela: com gravação, NDI ou BSP ativos, ela continua com a tela apagada ou o app na Home (serviço em primeiro plano `camera|microphone`).

### 📡 Streaming e NDI
* Transmissão nativa e simultânea à gravação.
* Suporte ao protocolo NDI (Transmissão local de vídeo pela rede com baixíssima latência). Na rede a fonte aparece como `BDSM (nome do aparelho)`.
* BSP (H.264 sobre RTP/UDP): protocolo próprio, **em desenvolvimento**.

### 🔗 BDSM Link (OBS e rede local)
Servidor HTTP/WebSocket embutido (porta 8080, mDNS `_bdsm._tcp`) com **pareamento por token e aprovação no celular**: sem token, toda rota de dados responde 401. Telemetria em tempo real (REC, fonte, lente, fps, bateria, microfone), tally do OBS (borda vermelha/verde no monitor), envio de LUTs e download de gravações com retomada (Range). Ligar/desligar e revogar dispositivos em Configurações. Protocolo em [`.docs/BDSM_PLUGIN_OBS.md`](.docs/BDSM_PLUGIN_OBS.md).

## Arquitetura e Tecnologias

Este projeto foi construído sobre uma fundação modular em **Kotlin**, priorizando desempenho, baixa latência e renderização otimizada. A arquitetura segue os princípios do **Clean Architecture** e **MVVM**.

* **Interface UI:** Jetpack Compose e Material 3
* **Injeção de Dependência:** Hilt
* **Banco de Dados e Persistência:** Room e DataStore
* **Captura de Vídeo:** Camera2 API e UVC (a fonte Sony Camera Remote API por Wi-Fi está desativada por flag)
* **Concorrência:** Kotlin Coroutines e StateFlow

**Estrutura de Módulos:**
- `:app`: Shell do aplicativo (`BdsmApplication`, `MainActivity`), navegação, manifest, assinatura e R8.
- `:common`: Tema e componentes de UI compartilhados.
- `:core`: Persistência (Room, DataStore), `NdiNaming`, biblioteca de gravações e modelos compartilhados (`SonyCameraStatus`).
- `:core-capture`: Dispositivos de captura (`Camera2Device`, `UvcCaptureDevice`, `SonyRemoteCaptureDevice`), descoberta de lentes e captura de áudio.
- `:core-network`: Pacote `com.bragastudio.mobile.network`: servidor Link (Ktor/Netty, pareamento, telemetria, mDNS), serviços de LUT/mídia e protocolo Sony Camera Remote API. O NDI fica em `:core-media`.
- `:core-media`: `MediaGraph` (dono da sessão de captura), motor OpenGL ES/NDI em C++, gravação (`RecordManager`), NDI, BSP e `CaptureForegroundService`.
- `:feature-home`, `:feature-preview`, `:feature-settings`: Splash/Home, monitor com HUD e Configurações/NDI/LUTs/Gravações.
- `build-logic/`: convention plugins Gradle (`bdsm.android.library`, `.compose`, `.hilt`, `.test`).

## Como Compilar e Rodar

1. Abra o projeto no **Android Studio**.
2. Certifique-se de que o **JDK 17** está selecionado nas configurações do Gradle.
3. Sincronize o projeto Gradle.
4. Compile ou execute o módulo `:app` em um **dispositivo Android físico** (funcionalidades complexas de hardware de câmera e aceleração não funcionam bem em emuladores).

Linha de comando (Gradle 8.14.5 pelo wrapper, `minSdk 26`, `compileSdk 36`, `targetSdk 35`, NDK 27.0.12077973):

```
./gradlew assembleDebug                  # APK de debug
./gradlew assembleRelease                # release com R8 (assina com a chave de debug se não houver keystore; em tag o CI exige os 4 secrets)
./gradlew lintDebug testDebugUnitTest    # lint e testes unitários (294 testes)
./gradlew detekt ktlintCheck             # análise estática (baselines versionados)
```

Por padrão o APK leva `arm64-v8a` e `armeabi-v7a`; para emulador x86 use `-Pbdsm.abis=arm64-v8a,x86_64`. Rode `lintDebug` e `assembleRelease` em invocações separadas (as duas compartilham o diretório do KSP).

> Para usar uma câmera HDMI (Sony α6000 etc.), use uma placa de captura HDMI→USB UVC e selecione **"Câmera USB"** em Ajustes > Câmera. A fonte Sony Wi-Fi está desativada (para religá-la, veja `.docs/ARQUITETURA.md`, "Fontes de vídeo").

## Licença

Copyright © 2024-2026 Mauro Braga Filho / Braga Digital Studio. Todos os direitos reservados.

Este software é proprietário e de código fechado. É estritamente proibida a cópia, redistribuição, engenharia reversa ou modificação não autorizada de qualquer parte deste código-fonte. Consulte o arquivo [LICENSE](LICENSE) para mais detalhes.
