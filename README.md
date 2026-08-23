# Braga Digital Studio Mobile

*Read this in [English](README_en.md)*

O **Braga Digital Studio Mobile** é um software profissional e proprietário de monitoramento, gravação e transmissão de vídeo exclusivo para dispositivos Android.

O grande objetivo deste projeto é transformar o seu smartphone Android em um equipamento equivalente a um monitor profissional para audiovisual, aproveitando ao máximo a mobilidade, a conectividade e a tela do ecossistema Android.

## Principais Objetivos e Funcionalidades (Roadmap)

### 🖥️ Monitor Externo
Use seu dispositivo como monitor portátil via conexão USB UVC, rede Wi-Fi ou câmera Sony remota. O sistema foi projetado para alternar dinamicamente entre diferentes fontes de vídeo, tais como:
* A câmera nativa do próprio smartphone
* Câmeras via placas de captura HDMI/USB UVC
* **Câmeras Sony via Wi-Fi (Sony Camera Remote API)** ← Novo!

### 📷 Captura Remota Sony via Wi-Fi ← Novo!
Transforme a câmera Sony (α6000 e compatíveis com a Sony Camera Remote API) em uma fonte de vídeo remota:
* Descoberta automática via **SSDP** com fallback para o IP do Wi-Fi Direct (`DIRECT-xxxx`)
* Liveview MJPEG com latência ultrabaixa (~5 ms por frame) via socket TCP com `TCP_NODELAY`
* Controles remotos: ISO, velocidade do obturador, abertura (F-number), compensação de exposição, foco por toque e disparo
* Telemetria em tempo real: nível de bateria, armazenamento disponível no cartão e status de foco
* Reconexão automática sem crash ao sair do alcance do Wi-Fi Direct
* Roteamento transparente pelo `MediaGraph` — preview, gravação, NDI e LUTs funcionam sem alterações

### 🎛️ Ferramentas Avançadas de Monitoramento
Desenvolvimento focado em auxiliar criadores na exposição e enquadramento de forma precisa e em tempo real:
* Focus Peaking, False Color, Zebra
* Histograma, Waveform, Vetorscópio
* Safe Area, Grids
* Suporte à aplicação de LUTs personalizadas (.cube) somente no monitoramento

### 🎙️ Áudio e Gravação Profissional
* Entradas independentes para vídeo e áudio.
* Suporte a microfones USB.
* Gravação em 4K nos formatos H.264 / H.265 (MP4).
* Gravação direta em cartões SD ou SSDs externos.

### 📡 Streaming e NDI
* Transmissão nativa e simultânea à gravação.
* Suporte ao protocolo NDI (Transmissão local de vídeo pela rede com baixíssima latência).

## Arquitetura e Tecnologias

Este projeto foi construído sobre uma fundação modular em **Kotlin**, priorizando desempenho, baixa latência e renderização otimizada. A arquitetura segue os princípios do **Clean Architecture** e **MVVM**.

* **Interface UI:** Jetpack Compose e Material 3
* **Injeção de Dependência:** Hilt
* **Banco de Dados e Persistência:** Room e DataStore
* **Captura de Vídeo:** Camera2 API, UVC e **Sony Camera Remote API (Wi-Fi)**
* **Concorrência:** Kotlin Coroutines e StateFlow

**Estrutura de Módulos:**
- `:app`: Shell do aplicativo, navegação e splash flow.
- `:common`: UI compartilhada, rotas e modelos de domínio.
- `:core`: Contratos de persistência (Room, DataStore).
- `:core-capture`: Abstração de dispositivos de captura (`Camera2Device`, `UvcCaptureDevice`, `SonyRemoteCaptureDevice`).
- `:core-network`: Protocolo Sony Camera Remote API (SSDP discovery, cliente JSON-RPC, leitor de socket de liveview) e servidor LinkServer (NDI/REST/WebSocket).
- `:core-media`: MediaGraph, camada responsável pelo roteamento transparente de frames entre todas as fontes de captura.
- `:feature-*`: Módulos independentes focados em fluxos específicos do usuário (home, preview, settings).

## Como Compilar e Rodar

1. Abra o projeto no **Android Studio**.
2. Certifique-se de que o **JDK 17** está selecionado nas configurações do Gradle.
3. Sincronize o projeto Gradle.
4. Compile ou execute o módulo `:app` em um **dispositivo Android físico** (funcionalidades complexas de hardware de câmera e aceleração não funcionam bem em emuladores).

> Para testar a captura Sony Wi-Fi, conecte o dispositivo Android ao Wi-Fi Direct da câmera (`DIRECT-xxxx:MODEL`) e selecione **"SONY"** como fonte de vídeo nas configurações.

## Licença

Copyright © 2024-2026 Mauro Braga Filho / Braga Digital Studio. Todos os direitos reservados.

Este software é proprietário e de código fechado. É estritamente proibida a cópia, redistribuição, engenharia reversa ou modificação não autorizada de qualquer parte deste código-fonte. Consulte o arquivo [LICENSE](LICENSE) para mais detalhes.
