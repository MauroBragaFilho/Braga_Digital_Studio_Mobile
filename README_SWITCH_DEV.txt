BSP — Switch de Desenvolvimento (NDI ↔ BSP) — funcionando de verdade
========================================================================

COMO USAR
---------
Descompacte na pasta RAIZ do projeto BDSM (mesma altura do
settings.gradle.kts), sobrescrevendo os arquivos existentes quando pedido.
Este pacote contém tanto arquivos NOVOS quanto EDIÇÕES em 8 arquivos que já
existiam no seu projeto — todos aplicados de verdade, não é mais só um plano.

Depois de descompactar: build normal (Android Studio ou
./gradlew assembleDebug). Abra o app → Configurações → ⚙ ícone de
diagnóstico (rota "diagnostics", já existente no seu Settings) → vai
aparecer um card vermelho "Protocolo de Transmissão (Dev)" no topo, acima
da lista de câmeras.

O QUE O SWITCH FAZ DE VERDADE
-------------------------------
Não é um toggle visual solto — ele:

1. Escreve em `SettingsRepository` (DataStore) qual protocolo deve estar
   ativo (`setBspEnabled`/`setNdiEnabled`), desligando o outro primeiro.
2. O `MediaGraph` já observa os dois flows (`ndiSettings` e `bspSettings`)
   e reage de verdade: para/inicia o `NdiManager` ou o `BspManager`
   automaticamente quando o valor muda — mesmo mecanismo que liga o NDI
   hoje em produção, só que agora replicado pro BSP.
3. Quando BSP fica ativo, o card mostra em tempo real: estado da conexão
   (Desconectado/Conectando/Conectado/Reconectando/Erro), RTT, bitrate e
   perda de pacote — vindos direto do `BspManager` via `SettingsViewModel`.

Pra testar: digite o IP do computador rodando o `bsp_receiver` (o receptor
standalone de teste de uma etapa anterior) no campo de texto, toque no ✓
pra salvar, depois toque em "BSP". O app deve conectar e a janela do
receptor deve abrir sozinha.

ARQUIVOS NOVOS (não existiam antes)
-------------------------------------
  core-media/.../coremedia/bsp/H264RtpPacketizer.kt
  core-media/.../coremedia/bsp/BspControlChannel.kt
  core-media/.../coremedia/domain/BspManager.kt

    O núcleo do BSP: encoder H.264 (MediaCodec via Surface, mesmo padrão do
    RecordManager), empacotamento RTP, handshake/heartbeat TCP.

ARQUIVOS EDITADOS (já existiam — este zip sobrescreve com a versão nova)
----------------------------------------------------------------------------

  core-media/src/main/cpp/GlesEngine.h
  core-media/src/main/cpp/GlesEngine.cpp

    Adicionado o slot `bspSurface`/`bspWindow`, espelhando exatamente como
    `ndiSurface`/`ndiWindow` já funcionava: criação da EGLSurface,
    desenho (`drawPass`) a cada frame, limpeza no destroy, e o wrapper JNI
    `nativeSetBspSurface`. Nenhuma lógica de NDI/gravação foi alterada —
    só adicionado um terceiro slot de saída ao lado dos dois que já
    existiam.

  core-media/.../coremedia/graphics/NativeRenderer.kt

    Adicionado `cachedBspSurface`, `setBspSurface(surface)` e a external
    fun `nativeSetBspSurface` — mesmo padrão do `setNdiSurface`.

  core/.../core/domain/SettingsRepository.kt
  core/.../core/data/SettingsRepositoryImpl.kt

    `data class BspSettings` (isEnabled, cameraName, targetHost,
    resolution, fps) — mesmo molde do `NdiSettings` — persistida no mesmo
    DataStore (`bsm_settings`), com chaves próprias (bsp_enabled,
    bsp_name, bsp_target_host, bsp_resolution, bsp_fps).

  core-media/.../coremedia/domain/MediaGraph.kt

    - `BspManager` injetado no construtor.
    - `errorEvents` agora soma também `bspManager.errorEvents`.
    - `startBsp()`/`stopBsp()` adicionados (mesmo formato de
      `startNdi`/`stopNdi`, reaproveitando `resolveNdiResolution`).
    - Novo `scope.launch { settingsRepository.bspSettings.collect { ... } }`
      que liga/desliga o BSP automaticamente — é ISSO que faz o switch da
      tela de dev funcionar de verdade: mudar o `BspSettings.isEnabled`
      dispara a transmissão real, não só um valor salvo.

  feature-settings/.../SettingsViewModel.kt

    Injeta `BspManager`, expõe `bspSettings` + os StateFlows de status
    (`bspConnectionState`, `bspRttMs`, `bspBitrateMbps`,
    `bspPacketLossPercent`), os setters (`setBspEnabled`,
    `setBspTargetHost`, etc), e a função central do switch:
    `setActiveStreamingProtocol(useBsp: Boolean)` — desliga um protocolo e
    liga o outro, nessa ordem, pra nunca ter os dois competindo pela mesma
    situação de output ao mesmo tempo.

  feature-settings/.../DiagnosticsScreen.kt

    Card novo "Protocolo de Transmissão (Dev)" no topo da tela de
    diagnóstico já existente (rota "diagnostics", acessível em
    Configurações). Botões NDI/BSP, campo de IP do receptor, indicador de
    status ao vivo. Nenhum conteúdo anterior da tela (lista de câmeras,
    relatório) foi removido — só adicionado acima.

O QUE AINDA NÃO ESTÁ AQUI
----------------------------
- Discovery automático (IP ainda é digitado manualmente).
- Tela de configuração "de produção" do BSP (a `BspSetupScreen.kt`
  planejada — esse switch é deliberadamente uma ferramenta de
  desenvolvedor, não a UI final pro usuário comum).
- O receptor de produção / plugin OBS (o `bsp_receiver` standalone de uma
  etapa anterior já serve pra testar isso agora).

NÃO CONSEGUI COMPILAR AQUI
------------------------------
Assim como o pacote anterior: não há toolchain Android/NDK neste ambiente
pra rodar um build real. As edições seguem exatamente os padrões já
existentes no seu código (mesma estrutura JNI, mesmo shape de Compose,
mesmo padrão de DataStore) — mas o primeiro build depois de aplicar este
zip é o que vai confirmar que compila limpo. Se algo não bater (ex.: nome
de import diferente numa versão mais nova do projeto), o erro do
compilador vai apontar a linha exata — me manda que eu ajusto.
