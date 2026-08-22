# Braga Studio Mobile (BSM) - Roadmap

## Visão Geral

O objetivo do BSM é transformar um smartphone Android em uma central
profissional de monitoramento, gravação e transmissão de vídeo,
suportando tanto a câmera do próprio dispositivo quanto fontes HDMI por
meio de placas de captura USB UVC.

### Arquitetura de Alto Nível

``` text
CaptureDevice
      │
      ├── Camera2Device
      ├── UvcCaptureDevice
      ├── NdiInputDevice (futuro)
      ├── RtspDevice (futuro)
      └── VirtualDevice (futuro)
              │
              ▼
        Media Pipeline
              │
      ┌───────┼────────┬───────────┬──────────┐
      ▼       ▼        ▼           ▼          ▼
  Preview  Recording Streaming Analysis Plugins
```

------------------------------------------------------------------------

# Fase 1 --- Base da aplicação e captura da câmera do celular

## Objetivo

Criar um MVP funcional e compilável.

### Entregas

-   Estrutura modular
-   Clean Architecture
-   MVVM
-   Hilt
-   Jetpack Compose
-   Camera2 API
-   Preview em tempo real
-   Alternância entre câmera frontal/traseira
-   Configurações iniciais
-   Interface CaptureDevice
-   Implementação Camera2Device
-   Media Pipeline
-   Navegação
-   Tema Dark
-   DataStore
-   Room

### Resultado esperado

Aplicativo instalado e funcional utilizando a câmera do smartphone.

------------------------------------------------------------------------

# Fase 2 --- Renderização em Vulkan

## Objetivo

Criar o pipeline gráfico de baixa latência.

### Entregas

-   Vulkan Renderer
-   Shader Manager
-   Texture Manager
-   Command Buffers
-   Render Pipeline
-   Overlay Renderer
-   Sincronização de Frames

### Resultado esperado

Toda renderização do aplicativo passa a utilizar Vulkan.

------------------------------------------------------------------------

# Fase 3 --- Ferramentas profissionais

## Objetivo

Adicionar ferramentas de monitoramento profissional.

### Entregas

-   Histograma
-   Waveform
-   RGB Parade
-   Vetorscópio
-   False Color
-   Focus Peaking
-   Zebra
-   LUT (.cube)
-   Safe Area
-   Grid
-   Zoom
-   Pixel to Pixel

### Resultado esperado

O aplicativo oferece monitoramento semelhante a monitores profissionais.

------------------------------------------------------------------------

# Fase 4 --- Gravação

## Objetivo

Adicionar gravação de vídeo.

### Entregas

-   MP4
-   MOV
-   H.264
-   H.265
-   Snapshot
-   Proxy Recording
-   Timecode
-   SSD Externo
-   Cartão SD
-   Sincronização de áudio

### Resultado esperado

Gravação local com desempenho otimizado.

------------------------------------------------------------------------

# Fase 5 --- Streaming

## Objetivo

Adicionar transmissão ao vivo.

### Entregas

-   FFmpeg
-   RTMP
-   SRT
-   RTSP
-   RTP
-   UDP
-   Estrutura para NDI
-   Perfis de streaming
-   Configuração de bitrate
-   Configuração de resolução

### Resultado esperado

Streaming para plataformas e servidores personalizados.

------------------------------------------------------------------------

# Fase 6 --- Captura USB UVC

## Objetivo

Adicionar suporte a placas de captura USB compatíveis com Android.

### Entregas

-   UsbUvcCaptureDevice
-   Detecção automática
-   Hot Plug
-   Seleção de dispositivo
-   Áudio HDMI
-   Troca dinâmica entre Camera2 e UVC
-   Integração ao Media Pipeline

### Resultado esperado

O aplicativo passa a aceitar câmeras HDMI conectadas por placas UVC sem
alterar os demais módulos.

------------------------------------------------------------------------

# Roadmap Futuro

## Versão 2.x

-   NDI Output
-   NDI Input
-   Teleprompter
-   Controle remoto via Wi-Fi
-   Sistema de Plugins
-   OBS WebSocket
-   REST API
-   MQTT
-   Multicâmera
-   Controle de câmeras compatíveis

## Princípios do Projeto

-   Arquitetura modular
-   Baixa latência
-   Independência da origem do vídeo
-   Renderização acelerada por GPU
-   Escalabilidade
-   Código limpo
-   SOLID
-   Clean Architecture
-   MVVM