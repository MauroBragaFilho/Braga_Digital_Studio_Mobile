# BDSM — Plano de melhoria e otimização

> Base: `.docs/ANALISE_TECNICA_INICIAL_2026-10-03.md` (versão histórica; IDs A1–A10, M1–M56, B1–B60, L1–L13 referem-se a ela). Estado atual: `ANALISE_TECNICA_COMPLETA.md` (viva). **Leia primeiro o bloco "STATUS FINAL por fase" no fim deste arquivo: ele supera os status intermediários abaixo.**
> Data: 2026-10-03. Esforço: **S** ≤ 1 dia · **M** 1–3 dias · **L** 1–2 semanas.
> Princípio: cada fase termina com o app **compilando, com lint e testes verdes e testado em aparelho**. Nada de "big-bang".

---

## Fase 0 — Já feito (compila, lint e testes unitários verdes; **falta testar em aparelho** e commitar)

| Item | O que foi feito | Falta |
|---|---|---|
| A2 | `resolveSafe()` no `LutLibraryService`, validação nas rotas, upload limitado a 32 MB | testes unitários do resolvedor |
| A1 (parte) | Pareamento por token com consentimento duplo (celular + cliente), CORS aberto removido, rotas `/api/*` e `/ws/*` protegidas | opt-in persistido, indicador/lista de pareados na UI, atualizar plugin OBS e `BDSM_PLUGIN_OBS.md`, testes |
| A3 (parte) | `configChanges` + rotação das saídas congelada durante REC/NDI/BSP | tela apagada/Home/Configurações ainda encerram o take; microfone nunca para |
| A4 (parte 1) | `network_security_config` (cleartext só `192.168.122.1`) | vínculo de rede por socket |
| A5 | `.gitignore` libera o wrapper | `git add -f gradle/wrapper/gradle-wrapper.jar` e commit |
| A6 (parte) | `stopRecording` em IO + `NonCancellable`; `GalleryExporter`; `LutsViewModel.importLut` em IO | `prepareRecording*` ainda na Main |
| A7 | Um retriever por tarefa, `Semaphore(3)`, cache por bytes | — |
| A8 (parte) | `PREPEND_HEADER_TO_SYNC_FRAMES`, SPS/PPS reenviados, keyframe forçado no handshake | corridas de parada (M21) |
| §5.1 (parte) | `PreviewHud.kt` 3.081 → 432 linhas em 14 arquivos | `HudUiState`/`HudActions`, strong skipping |
| §5.2 (parte) | Galeria morta de `feature-home` removida | galeria única sobre Room |
| M5 (parte) | `ReplaceFileCorruptionHandler` | `distinctUntilChanged` nos flows |
| M16 (parte) | guarda API 29 no `ImageReader` | `SupervisorJob` + handler no `MediaGraph` |
| Lint | `longVersionCode` (crash Android 8), manifest (COARSE, `uses-feature`), 3 falsos positivos suprimidos | — |

**Ação imediata (30 min):** rodar `git diff` do `PreviewHud.kt`, do trabalho BSP e do `MediaGraph`; criar uma branch (`feature/hardening-1`) e fazer commits pequenos por tema (segurança, rotação, refatoração HUD, galeria, BSP).

---

## Fase 1 — Confiabilidade de gravação (maior risco para o produto) · ~2 semanas

Objetivo: um take nunca é perdido sem aviso.

| # | Item | Esforço | Critério de aceite |
|---|---|---|---|
| 1.1 | **A3 resto:** `keepScreenOn` (`LocalView`), bloquear/confirmar navegação (Home/Configurações) durante REC, aviso "gravação interrompida" quando sair do primeiro plano | S | Rodar 30 min gravando com a tela ligada sem apagar; sair do app mostra aviso |
| 1.2 | **A10:** muxer não depender do áudio (fila pré-muxer, timeout), PTS único para A/V, `releaseAll()` idempotente | M | Gravar sem permissão de mic produz vídeo válido; A/V sincronizado em 10 min (medir drift) |
| 1.3 | **M17:** `RecState` (Idle/Preparing/Recording/Stopping) + `Mutex`; botão REC desabilitado fora de Idle; `MediaCodecList` e fallback H.265→H.264 | M | Duplo toque em REC não vaza encoder; 4K60 sem suporte cai para perfil válido |
| 1.4 | **M15 / L1:** reagir a perda da fonte (Camera2 ERROR, USB desplugado, Sony fora de alcance) finalizando o arquivo com segurança + `StatFs` com piso 200 MB + erro do drain propagado | M | Desplugar UVC durante REC gera MP4 reproduzível e notificação |
| 1.5 | **M8:** `Camera2Device` idempotente (token de geração, handler único) | M | 100 ciclos start/stop sem vazar thread (`adb shell dumpsys` / Android Studio Profiler) |
| 1.6 | **M10:** áudio por `SharedFlow`, `stopCapture()` quando sem consumidores, `onCleared`; ordem do callback (**REC antes de NDI**, L6) | M | Microfone fora do Preview não aparece como ativo (`dumpsys audio`) |
| 1.7 | **A6 resto:** `prepareRecording*` em IO; cópia SAF fora do caminho do STOP (estado `COPYING`) | M | STOP responde em < 300 ms com pasta SAF |
| 1.8 | **M16:** `SupervisorJob` + `CoroutineExceptionHandler` + `try/catch` por coletor no `MediaGraph` | S | Exceção injetada em um coletor não derruba o app |
| 1.9 | **A3 itens 4–5 (estrutural):** sessão de captura desacoplada da tela + `CaptureForegroundService` (`camera|microphone`), áudio com contagem de referência | L | Gravação continua com tela apagada e Home; **só depois** de 1.2, 1.3, 1.5, 1.8 |

Testes obrigatórios da fase: unitários da máquina de estados (`RecState`), instrumentado de start/stop em loop, teste manual de rotação durante REC.

---

## Fase 2 — Segurança e integração OBS · ~1–2 semanas

| # | Item | Esforço | Critério de aceite |
|---|---|---|---|
| 2.1 | **A1 resto:** flag `linkEnabled` persistida (padrão: ligado após primeiro pareamento ou desligado — decidir), toggle em Configurações, lista de dispositivos pareados com "Revogar", indicador visível quando o servidor está no ar | M | Revogar um cliente derruba o WebSocket em ≤ 3 s |
| 2.2 | Notificação de pedido de pareamento quando o app está em segundo plano (hoje só aparece diálogo com o app aberto) | S | Pedido com app em background gera notificação com ação |
| 2.3 | Atualizar plugin do OBS e `BDSM_PLUGIN_OBS.md` para o fluxo `pair/request` → `pair/status` → `Bearer` | M | Plugin pareia, reconecta com token salvo e trata 401 repareando |
| 2.4 | **A9:** semântica do FGS do Link (`startForegroundService` fora do `Application.onCreate`, `stop()` assíncrono, estado `ServerState`) | M | Sem crash ao iniciar em background no Android 12+ |
| 2.5 | **M25 / M26 / M52:** `Json { encodeDefaults = true }`, `LinkStateProvider` com telemetria real (fonte, lente, fps, mic, REC), Tally via WS | M | Dashboard mostra REC real e FPS ≠ 0 |
| 2.6 | **L4:** `PartialContent` (Range) nos downloads, filtrar `IN_PROGRESS/CORRUPTED`, reconciliar Room↔disco na abertura | M | Retomar download de 2 GB; arquivo em gravação não é listado |
| 2.7 | **L3:** sincronizar LUTs após upload/delete (evento), por caminho relativo | S | LUT enviada pelo PC aparece no Preview sem abrir a tela de LUTs |
| 2.8 | **M27/M28:** hardening do Sony (SSDP, XML, `isOk()`, resync do liveview) + vínculo de rede por socket (A4 parte 2) | M | Sony conecta com dados móveis ligados |
| 2.9 | **M4:** remover permissões sem uso/herdadas (`MANAGE_EXTERNAL_STORAGE`, etc.) com `tools:node="remove"`; `dataExtractionRules` | S | Merged manifest sem permissões de armazenamento amplas |
| 2.10 | Testes: rotas do `LinkServer` (401 sem token, 400 em path inválido, 413), `LinkAuthManager` (expiração, anti-spam, token único) | M | Cobertura das regras de auth |

---

## Fase 3 — Build, release e CI · ~1 semana (pode rodar em paralelo à Fase 1)

| # | Item | Esforço | Critério de aceite |
|---|---|---|---|
| 3.1 | **A5:** commitar o wrapper; `gradle/actions/wrapper-validation`; `distributionSha256Sum` | S | Clone limpo roda `./gradlew assembleDebug` |
| 3.2 | **M6:** `ci.yml` em push/PR com `assembleDebug lintDebug testDebugUnitTest` (lint hoje já passa) | S | PR bloqueado se lint/teste falhar |
| 3.3 | **M1:** em tag, falhar sem os 4 secrets de assinatura; nunca publicar com chave de debug | M | Tag sem secrets falha o job |
| 3.4 | **M2:** R8 + `shrinkResources` no `app`, regras Netty/Ktor/JNI/UVC, `abiFilters` (arm64-v8a + armeabi-v7a), arquivar `mapping.txt` | M | APK ≤ 40 MB; app abre e grava com minify (testar NDI, Link, UVC) |
| 3.5 | **M3:** `-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` / NDK r28, `-fvisibility=hidden`; resolver os `.so` do UVCAndroid | M | Lint `Aligned16KB` zerado |
| 3.6 | Convention plugins (`build-logic`) + catálogo único (tirar `documentfile` hardcoded), unificar JVM target (core-network ainda 1.8) | M | `build.gradle.kts` dos módulos < 30 linhas |
| 3.7 | `gradle.properties`: `org.gradle.parallel`, `caching`, `configuration-cache`; remover Jetifier se nada depender | S | Build incremental ≥ 30% mais rápido (medir) |
| 3.8 | `baseline` de lint + `detekt`/`ktlint` (ou `spotless`) no CI | S | Novos avisos falham o PR |

---

## Fase 4 — Desempenho e latência (medir antes, otimizar depois) · ~2 semanas

**Regra:** cada item abaixo só entra com uma medição "antes/depois" (Perfetto, Layout Inspector, GPU profiler, `dumpsys gfxinfo`, Macrobenchmark).

| # | Item | Esforço | Métrica |
|---|---|---|---|
| 4.1 | **M29 / M54:** HUD — `collectAsStateWithLifecycle`, estados de alta frequência (timecode ~33 Hz, VU, FPS) lidos só nas folhas via lambdas, `HudUiState @Immutable`, strong skipping; scopes em `Dispatchers.Default` | M | Recomposições/s da raiz do HUD; jank no `gfxinfo` (alvo < 2% frames lentos) |
| 4.2 | **L7 / M18:** NDI — `eglSwapInterval(0)` por surface, `acquireLatestImage`, remover `memcpy`, mutex de leitura/escrita; avaliar UYVY em vez de RGBA | M | CPU% e temperatura em 10 min de NDI 1080p30 |
| 4.3 | **M56:** cache de `GLint` de uniforms, `eglPresentationTimeANDROID` para REC/BSP, pular passe NDI sem receptor | M | Tempo de frame na GPU; jitter do PTS |
| 4.4 | **M19:** repassar ajustes de zebra/peaking ao GL em tempo real + `distinctUntilChanged` (M5) | S | Slider muda a imagem imediatamente |
| 4.5 | **M55:** `HardwareMonitorService` com `stateIn(WhileSubscribed)` (hoje acorda a CPU a cada 2 s) | S | Wakeups/hora com app em background |
| 4.6 | **M9:** FPS/resolução realmente aplicados na câmera (`CONTROL_AE_TARGET_FPS_RANGE`, `SENSOR_FRAME_DURATION`) | M | 60 fps efetivos medidos no preview |
| 4.7 | Baseline Profile + Macrobenchmark de startup (Application.onCreate pesado: injeta cascata do Link) | M | Cold start −20% |
| 4.8 | `StrictMode` em debug (disco/rede na main) para pegar regressões | S | Zero violações no fluxo principal |
| 4.9 | **M23:** scopes e exposição em Rec.709 (`0.2126/0.7152/0.0722`), `highp`, peaking independente de zebra/false color | M | Comparar com chart de referência |
| 4.10 | **M20:** LUT correta (meio texel, `RGBA16F`), parser robusto e com teto de tamanho | M | LUT identidade gera imagem idêntica à original |

---

## Fase 5 — Arquitetura e manutenção · contínuo

| # | Item | Esforço |
|---|---|---|
| 5.1 | **§5.2 galeria única sobre Room:** sincronização com importação, favoritos persistidos, `LazyColumn` com `key`, confirmação/desfazer na exclusão (M38/M40/M41) | L |
| 5.2 | **§5.4 / M49:** camada de domínio — parar de expor internos do `MediaGraph`; interfaces (`RecordingController`, `StreamOutput` comum a NDI e BSP) | L |
| 5.3 | **§5.3:** `api` vs `implementation`; `SonyCameraStatus` para `:core` para tirar a dependência de `core-media → core-network` | M |
| 5.4 | **§5.7 / M48:** enums (`Resolution`, `VideoSource`, `RecordingStatus`) no lugar de strings; fatiar `SettingsViewModel` | L |
| 5.5 | **L2 / M36:** fonte única da verdade para controles manuais e lentes (singleton expõe `StateFlow`) | M |
| 5.6 | **M24:** camada nativa — lock de leitura/escrita em `nativePtr`, `ANativeWindow` sem vazamento, `std::atomic` | M |
| 5.7 | **M21 resto:** BSP — `use{}` em sockets, `stop()` suspend com `cancelAndJoin`, RTT real; só então commitar o BSP | M |
| 5.8 | Padronizar nomes: `BSM`→`BDSM`, pacote `com.braga.bdsm.network`→`com.bragastudio.mobile.network`; documentar a sigla BSP | S |
| 5.9 | **L12:** revisar licenças (NDI, libusb/UVCAndroid, hutool 5.6.3 trazido pelo UVCAndroid) e adicionar tela de licenças | M |
| 5.10 | Atualizar `ARQUITETURA.md`/`BDSM_VISAO_TECNICA.md` (divergências listadas na Parte 1 §7) | S |

---

## Fase 6 — Atualização de plataforma e features · depois das fases 1–3

Ordem segura: (1) `compileSdk/targetSdk` 35/36 tratando edge-to-edge, tipos de FGS e restrições de start em background; (2) Kotlin 2.x com o plugin do compilador Compose + KSP2; (3) Compose BOM, Hilt, Room, DataStore (1.0.0 → 1.1.x), lifecycle, navigation; (4) só depois AGP 9/Gradle 9. Dependabot/Renovate.

Features prometidas e ausentes (por valor para um monitor profissional): FPS real (4.6) → timecode/tempo restante → Tally e telemetria (2.5) → áudio USB com canal/ganho/monitor, Bluetooth SCO (L8) → MOV/HEVC alto bitrate → lentes físicas/`CONTROL_ZOOM_RATIO` (M13) → câmera frontal (L9) → split de arquivo.

---

## Testes: o que criar primeiro (hoje existem 3 unitários, só 1 útil)

1. `LutPathResolver`/`resolveSafe` (`../`, absoluto, NUL, extensão, symlink).
2. `LinkAuthManager` (expiração, anti-spam, token entregue uma vez, revogação).
3. `H264RtpPacketizer` (single NAL, FU-A, SPS/PPS, marker, sequência).
4. `LutParser` (tab, `DOMAIN_*`, tamanho absurdo).
5. `SettingsRepositoryImpl` com DataStore corrompido.
6. `RecState` e `RecordManager` com codec falso.
7. Rotas do `LinkServer` com `testApplication` do Ktor.
8. Compose test do diálogo de pareamento e do HUD (semântica/acessibilidade).

## Riscos e armadilhas já mapeadas (não repetir)

- Não usar `collectLatest` nos coletores de settings/NDI/BSP do `MediaGraph` (cancelaria a troca no meio) e não pôr `withLock` dentro de `restartSession` (mutex não reentrante).
- Não apagar o arquivo local da gravação sem migrar a biblioteca para `contentUri`.
- Não reduzir o timer do timecode para 4 Hz (quebra o campo de frames).
- Não usar `MediaMuxer(FileDescriptor)` direto em SAF de nuvem.
- Comportamentos do Android 14 (tipos de FGS) não são reproduzíveis no aparelho de teste atual (Android 13).

## Cronograma sugerido

| Semana | Foco |
|---|---|
| 1 | Fase 0 (commits) + Fase 3.1–3.3 + Fase 1.1, 1.8 |
| 2–3 | Fase 1 (1.2–1.7) + Fase 2.1–2.4 |
| 4 | Fase 2.5–2.10 + Fase 3.4–3.8 |
| 5–6 | Fase 4 (com medições) |
| 7+ | Fase 1.9 (sessão em FGS), Fase 5, Fase 6 |

---

## Status ao fim da onda 2 (2026-10-03)

Verificado por execução: `assembleDebug`, `assembleRelease` (R8 ligado, APK ≈ 18,8 MB), `lintDebug` e `testDebugUnitTest` (264 testes, 0 falhas). **Nada foi testado em aparelho.**

Implementado: Fase 1 (exceto 1.9), Fase 2 (exceto UI de lista no OBS/plugin externo), Fase 3 (exceto convention plugins, detekt/ktlint e `distributionSha256Sum`), Fase 4 (4.1–4.6, 4.9, 4.10; faltam 4.7 Baseline Profile e 4.8 StrictMode) e Fase 5 (5.1 galeria sobre Room, 5.5, 5.6 parcial, 5.7, 5.10 docs).

Pendente:
- **1.9** sessão de captura em foreground service (`camera|microphone`): gravar com a tela apagada/Home.
- **Fase 6** atualização de plataforma e `build-logic`.
- **Fase 5:** 5.2 camada de domínio/`StreamOutput`, 5.3 `api` vs `implementation`, 5.8 renomear BSM→BDSM e pacote `com.braga.bdsm.network`.
- **Testes em aparelho** (roteiro na seção 3.4 do relatório) e validação do release com R8 (NDI, Link, UVC, Room/Hilt via reflexão).

Implementado depois (aguarda compilação/teste em aparelho):
- **5.9 / L12:** tela `LicensesScreen` (rota `licenses`, entrada "Licenças de código aberto" em Configurações > Sobre) com a lista em `OpenSourceLicenses.kt` (ordem alfabética garantida por teste), nota LGPL (libusb substituível, código-fonte no site do projeto) e aviso de marca do NDI. Revisar a lista a cada dependência nova.
- **Cópia exportada para a Galeria no fim do take:** `MediaStoreExporter` (core-media) chamado no pós-processamento do `RecordManager` quando o destino é Galeria (sem pasta SAF, `saveToGallery=true`, take não corrompido, Android 10+). A Uri do MediaStore fica em `contentUri` do registro (sem migração). `GALLERY_AUTO_EXPORT_SUPPORTED=true`; a opção Galeria só aparece habilitada no Android 10+ (em 26–28 o MediaStore exigiria `WRITE_EXTERNAL_STORAGE`, removida do manifesto). O padrão de `saveToGallery` passou de `true` para `false` para não duplicar takes sem escolha do usuário. A exportação manual (`GalleryExporter`) continua em Gravações.


---

## Build: convention plugins, detekt/ktlint, Baseline Profile e wrapper (onda 3)

- **3.6 `build-logic`:** included build (`pluginManagement { includeBuild("build-logic") }`) com `bdsm.android.library` (library + kotlin-android, SDK 34/26, JVM 17, consumer rules, `isReturnDefaultValues`), `bdsm.android.compose` (compose + compilador 1.5.10 + BOM + ui/material3), `bdsm.android.hilt` (ksp + hilt + deps) e `bdsm.android.test` (junit, coroutines-test, mockk, turbine, org.json, androidx-junit/espresso). Versoes vem do `gradle/libs.versions.toml` (o `build-logic/settings.gradle.kts` o importa como `libs`). Os `build.gradle.kts` dos modulos ficam so com namespace e dependencias proprias. `:app` usa compose/hilt/test e mantem assinatura/versionamento.
- **3.8 detekt + ktlint:** `./gradlew detekt ktlintCheck` (analise estatica, nao compila; roda no `ci.yml` apos o lint). Config em `config/detekt/detekt.yml` e `.editorconfig`. Achados antigos congelados em `config/detekt/baseline-<modulo>.xml` e `<modulo>/ktlint-baseline.xml`; regerar com `./gradlew detektBaseline` e depois `./gradlew ktlintGenerateBaseline` (nao juntos na mesma invocacao). Regra `argument-list-wrapping` do ktlint 1.0.1 desligada (excecao interna em `PreviewViewModel.kt`); reativar ao subir o ktlint.
- **4.7 Baseline Profile:** `app/src/main/baseline-prof.txt` escrito a mao (startup: Application, MainActivity, navegacao, Splash, Home, tema, Preview) + `androidx.profileinstaller` no app. Falta o modulo Macrobenchmark para gerar um perfil medido e medir o cold start.
- **`distributionSha256Sum`:** hash oficial do Gradle 8.13 obtido de https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256 e gravado em `gradle/wrapper/gradle-wrapper.properties`. Ao trocar de versao: `curl -sL https://services.gradle.org/distributions/gradle-<versao>-bin.zip.sha256` (ou `Invoke-WebRequest`) e atualizar junto com `distributionUrl` (ou `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <hash>`).
- **Configuration cache:** ligado em `gradle.properties` (problemas na execucao viram aviso). Validado ate o grafo de tarefas; confirmar com dois `assembleDebug` seguidos.

---

## Fase 6 — Atualização de plataforma (executada em 2026-10-03)

Verificado por execução: `assembleDebug assembleRelease lintDebug testDebugUnitTest detekt ktlintCheck` (289 testes, 0 falhas). **Nada foi testado em aparelho.**

| Item | Antes | Depois |
|---|---|---|
| compileSdk / targetSdk | 34 / 34 | 36 / 35 |
| Kotlin | 1.9.22 | 2.3.21 |
| Compose compiler | `kotlinCompilerExtensionVersion` 1.5.10 | plugin `org.jetbrains.kotlin.plugin.compose` (versão do Kotlin) |
| KSP | 1.9.22-1.0.17 | 2.3.12 (KSP2) |
| Compose BOM | 2024.02.01 | 2025.12.01 |
| Hilt / hilt-navigation-compose | 2.51 / 1.2.0 | 2.58 / 1.3.0 |
| Room | 2.6.1 | 2.8.5 (schemas 1–3 idênticos; 4.json regerado) |
| DataStore | 1.0.0 | 1.2.1 |
| lifecycle / navigation / activity / core-ktx | 2.7.0 / 2.7.7 / 1.8.2 / 1.12.0 | 2.10.0 / 2.9.8 / 1.12.4 / 1.17.0 |
| coroutines / serialization | 1.8.0 / 1.6.3 | 1.11.0 / 1.11.0 |
| Ktor / Coil | 2.3.8 / 2.6.0 | 2.3.13 / 2.7.0 (últimos 2.x) |
| mockk / turbine / profileinstaller / documentfile | 1.13.10 / 1.0.0 / 1.3.1 / 1.0.1 | 1.14.11 / 1.2.1 / 1.4.1 / 1.1.0 |
| detekt / ktlint (plugin / engine) | 1.23.5 / 12.1.0 / 1.0.1 | 1.23.8 / 14.2.0 / 1.8.0 (baselines regerados) |
| Gradle | 8.13 | 8.14.5 (sha256 atualizado) |
| AGP | 8.13.2 | 8.13.2 (já é a última 8.x; AGP 9 não adotado) |

Decisões e limites:
- **Android 15:** `LinkServerService` ganhou `onTimeout(startId, fgsType)` (limite de ~6 h do FGS `dataSync`; encerra o Link). O app já era edge-to-edge (`setDecorFitsSystemWindows(false)` + modo imersivo); em `Theme.kt` `statusBarColor/navigationBarColor` só são aplicados abaixo da API 35. targetSdk 36 não adotado (mudanças de orientação/redimensionamento e back preditivo ainda não validadas em aparelho).
- **compileSdk 36:** exigido pelas libs AndroidX atuais compatíveis com AGP 8.x. As versões mais novas (BOM 2026.x, navigation 2.10, activity 1.13) exigem compileSdk 37 e AGP 9.1, por isso ficaram de fora.
- **Kotlin 2.3.21 e não 2.4.x:** o Hilt 2.58 (último compatível com AGP 8; 2.59+ exige AGP 9) lê metadados Kotlin até 2.3.0; com Kotlin 2.4 o `hiltJavaCompile` falha. Room 2.6.1 não funciona com KSP2 ("unexpected jvm signature V"), daí o salto para 2.8.5.
- **Compilador:** `kotlinOptions` do `:app` trocado por `kotlin { compilerOptions }`; adicionado `-Xannotation-default-target=param-property` (aviso KT-73255 sobre `@ApplicationContext val`).
- **Pendente:** `hiltViewModel` e `LocalLifecycleOwner`/Locale já tratados ou avisados; migrar `hiltViewModel` para `androidx.hilt.lifecycle.viewmodel.compose` (aviso de depreciação, 6 telas); reativar a regra `argument-list-wrapping` do ktlint; AGP 9 + Hilt 2.59+ + Kotlin 2.4 + compileSdk 37 como próximo passo conjunto; testar em aparelho (Android 14/15).


---

## STATUS FINAL por fase (verificado contra o código e por execução em 2026-10-03)

> Esta seção **supera** os blocos "Status ao fim da onda 2", "onda 3" e "Fase 6" acima onde eles divergirem. Detalhe item a item: `ANALISE_TECNICA_COMPLETA.md` §4.

**Validação executada nesta data:** `assembleDebug` OK (43,3 MB); `assembleRelease` OK com R8 (19,3 MB = 18,4 MiB); `lintDebug` 0 erros / 97 avisos; `testDebugUnitTest` 294 testes, 0 falhas; `detekt` OK; **`ktlintCheck` FALHA** em `:core` (2) e `:core-media` (16), em código do nome NDI posterior ao baseline (corrigir com `./gradlew ktlintFormat`). Nada foi commitado. Veja a seção "Evidência em aparelho" da análise para o que foi e o que **não** foi testado no Galaxy S20 FE (Android 13).

Legenda: FEITO · PARCIAL · PENDENTE. "(aparelho)" = validado também em aparelho; sem a marca, só compilação/testes JVM.

### Fase 0 — FEITO (sem commit)
Todos os itens implementados. Pendente a **ação imediata**: criar branch e commits temáticos; `git add -f gradle/wrapper/gradle-wrapper.jar` (o jar continua `??`).

### Fase 1 — Confiabilidade de gravação: FEITO (1.9 validada em parte no aparelho)
| Item | Status | Nota |
|---|---|---|
| 1.1 keepScreenOn / aviso | FEITO | `keepScreenOn` na tela de Preview; bloquear navegação deixou de ser necessário (sessão desacoplada) |
| 1.2 muxer sem depender do áudio | FEITO | `MuxerGate`, `RecordingTimeline`; MP4 íntegro (aparelho) |
| 1.3 `RecState` + `MediaCodecList` | FEITO | `RecStateMachine`, `VideoPlanning`; testes |
| 1.4 perda da fonte / espaço | FEITO (parcial em M15) | `SourceLost`, `DiskFull`, `EncoderError`; **sem split/fMP4**; NDI/BSP sem política de perda |
| 1.5 `Camera2Device` idempotente | FEITO | token `generation`, handler único |
| 1.6 áudio por sinks, REC antes de NDI | FEITO | NDI de áudio ainda síncrono na thread do mic (L6 parcial) |
| 1.7 `prepareRecording*` em IO, SAF fora do STOP | FEITO | `COPYING` assíncrono |
| 1.8 `SupervisorJob` + handler | FEITO | `collectGuarded` |
| 1.9 sessão em FGS | FEITO | Home durante REC (aparelho); **rotação durante REC NÃO verificada** |

### Fase 2 — Segurança e integração OBS: FEITO, exceto o plugin externo
| Item | Status | Nota |
|---|---|---|
| 2.1 liga/desliga, pareados, revogar | FEITO | `LinkServerController`, `LinkSettingsSection`; padrão **ligado** (decisão) |
| 2.2 notificação de pareamento | FEITO | `PairingNotifier` + `PairingActionReceiver`; diálogo (aparelho); notificação em background NÃO verificada |
| 2.3 plugin OBS / doc | PARCIAL | `BDSM_PLUGIN_OBS.md` revisado (campo do tally é `type`); o plugin externo não está neste repositório |
| 2.4 FGS do Link | FEITO | (aparelho) |
| 2.5 telemetria real e tally | FEITO | `LinkTelemetry`, `TALLY_UPDATE` |
| 2.6 Range, filtro de status | FEITO | `HttpRange`, 416; sync do Room só ao abrir a galeria (L4 parcial) |
| 2.7 sync de LUTs por evento | FEITO | `LutLibraryService.changes` |
| 2.8 Sony hardening + rede por socket | FEITO | NÃO verificado em aparelho; callers ignoram o `Boolean` (M28 parcial) |
| 2.9 permissões / backup | FEITO | `usb.host` mesclado ainda `required=true` (L11) |
| 2.10 testes do Link | FEITO | `LinkServerRoutesTest`, `LinkAuthManagerTest`, `LutLibraryServiceTest` |

### Fase 3 — Build, release e CI: FEITO, com 3 ressalvas
| Item | Status | Nota |
|---|---|---|
| 3.1 wrapper + validação + sha256 | PARCIAL | jar ainda não rastreado |
| 3.2 `ci.yml` | FEITO | |
| 3.3 release exige keystore | FEITO | |
| 3.4 R8, ABIs, mapping | FEITO | release com R8 **não testado em aparelho** |
| 3.5 16 KB | PARCIAL | só `libjpeg-turbo212.so` (UVCAndroid) falta |
| 3.6 convention plugins | FEITO | `build-logic` |
| 3.7 `gradle.properties` | FEITO | configuration cache ligado (`problems=warn`) |
| 3.8 detekt/ktlint | PARCIAL | configurados com baseline, mas o **ktlint está falhando hoje** (18 violações novas) |

### Fase 4 — Desempenho: FEITO, exceto medição
| Item | Status |
|---|---|
| 4.1 HUD / recomposição | PARCIAL (providers nas folhas e lifecycle feitos; `HudUiState @Immutable`/strong skipping e medição não) |
| 4.2 NDI | FEITO (sem `memcpy`, `acquireLatestImage`, pula sem receptor; UYVY pendente, L7) |
| 4.3 GL (uniforms, PTS) | PARCIAL (cache de uniforms e `eglPresentationTime`; ainda até 4 passes) |
| 4.4 zebra/peaking em tempo real | FEITO |
| 4.5 `HardwareMonitorService` | FEITO |
| 4.6 FPS efetivo | FEITO (60 fps medido: NÃO verificado em aparelho) |
| 4.7 Baseline Profile | PARCIAL (arquivo à mão + `profileinstaller`; **falta Macrobenchmark**) |
| 4.8 StrictMode em debug | FEITO (`BdsmApplication`) |
| 4.9 scopes Rec.709 | FEITO (calibração NÃO verificada) |
| 4.10 LUT correta | FEITO |

### Fase 5 — Arquitetura: PARCIAL
| Item | Status | Nota |
|---|---|---|
| 5.1 galeria sobre Room | FEITO | `RecordingsGalleryViewModel`, `RecordingLibrary` |
| 5.2 domínio / `StreamOutput` | PARCIAL | `StreamOutput` feito; `MediaGraph` ainda expõe `recordManager`, `settingsRepository`, `lutRepository`, `context` |
| 5.3 `api` vs `implementation` / `SonyCameraStatus` | PARCIAL | `SonyCameraStatus` em `:core`; `core-media -> core-network` permanece (`LinkTelemetry`) |
| 5.4 enums / fatiar `SettingsViewModel` | PARCIAL | enums na UI; repositório ainda com Strings; VM com 315 linhas |
| 5.5 fonte única de controles (L2) | PARCIAL | ISO/obturador reconstruídos; WB/foco/lente ainda não |
| 5.6 camada nativa | FEITO | `ReentrantReadWriteLock`, atômicos |
| 5.7 BSP | FEITO | `use{}`, `cancelAndJoin`, RTT real (BSP não verificado em aparelho) |
| 5.8 renomear BSM -> BDSM e pacote | FEITO | `com.bragastudio.mobile.network`, `BdsmApplication`, `BdsmDatabase`; **mantidos** o arquivo `bsm_database` e o DataStore `bsm_settings` para não perder dados |
| 5.9 licenças | FEITO | `LicensesScreen`; `excludes` de META-INF ainda removem avisos |
| 5.10 docs | FEITO | revisão final desta data |

### Fase 6 — Plataforma: FEITO até onde o AGP 8 permite
compileSdk 36, **targetSdk 35**, Kotlin 2.3.21, KSP2, Compose BOM 2025.12.01, Hilt 2.58, Room 2.8.5, DataStore 1.2.1, Gradle 8.14.5. **Pendentes:** AGP 9 + Hilt 2.59+ + Kotlin 2.4 + compileSdk 37 (conjunto); **targetSdk 36**; `hiltViewModel` -> `androidx.hilt.lifecycle.viewmodel.compose`; regra `argument-list-wrapping` do ktlint; testar em Android 14/15.

### Pendências consolidadas (ordem sugerida)
1. Commits temáticos + `git add` do wrapper.
2. `ktlintFormat` e `ktlintCheck` verde.
3. `usb.host required="false"` efetivo (`tools:node="replace"`), pedir `BLUETOOTH_CONNECT` em runtime.
4. Testes em aparelho listados na seção 8 da análise (rotação durante REC, release com R8, BSP, Sony, UVC, HDR, Galeria, Android 14/15).
5. Macrobenchmark + Baseline Profile medido; compose compiler reports; `HudUiState`.
6. WB/foco manual e lente na fonte única (L2); `StreamingMode` NDI/BSP; gate do card Dev; limpeza (`ktor-server-cors`, Room em `core-media`).
7. Plataforma: AGP 9/Hilt 2.59+/Kotlin 2.4/compileSdk 37 e targetSdk 36.

---

## Adendo final (2026-10-03, após o levantamento acima)

Itens resolvidos ou comprovados depois da seção de status. **Estes valem sobre o que estiver dito acima.**

| Item | Antes | Agora | Evidência |
|---|---|---|---|
| N9 `ktlintCheck` | FALHA (18 violações) | **PASSA** | `ktlintFormat` aplicado; baselines regerados (`detektBaseline` e depois `ktlintGenerateBaseline`); `detekt ktlintCheck` OK |
| N14 / L11 `usb.host` | `required="true"` no manifest mesclado de release | **CORRIGIDO** | `tools:node="replace"` no `uses-feature` do app; merged manifest de release mostra `required="false"` |
| Rotação **durante** o REC | NÃO VERIFICADA em aparelho | **VERIFICADA** (S20 FE, Android 13) | Take 4K H.265 de 1:35 com o aparelho girado para todos os lados (`ROTATION_270 → 0 → 270 → 90`): processo e Activity não foram recriados, arquivo fechou com `moov`, decodifica inteiro, e a resolução permaneceu **3840×2160 do início ao fim** (rotação das saídas congelada no GL) |
| Nome do NDI | `LOCALHOST (BDSM - modelo)` | **`BDSM (MNBF - F.E)`** | `NdiNaming` + `ndi-config.v1.json` (`ndi.machinename`) via `NDI_CONFIG_DIR`; confirmado pelo usuário no OBS. O nome entre parênteses é o nome do aparelho ou o definido pelo usuário; o prefixo legado `BDSM - ` é removido; testes em `NdiNamingTest` |
| Gravação com NDI simultâneo + giro | não testado | **VERIFICADO** | NDI ligado durante giros e Home; log `Preview solto; sessão mantida` |
| Memória do Gradle | `-Xmx4g`, daemon ~4,1 GB de RAM, ocioso por 3 h | `-Xmx3g`, Kotlin daemon `-Xmx2g`, `org.gradle.daemon.idletimeout=600000` (10 min) | `gradle.properties`; build completo (debug + 294 testes + lint + release) executado com os novos limites sem `OutOfMemoryError` |

**Observação sobre o take gravado:** o `ffmpeg` aponta DTS repetido no vídeo (a cada ~117 quadros). Um take de setembro, **anterior a todas as mudanças**, tem o mesmo aviso, então não é regressão; vale investigar o arredondamento de PTS do encoder HEVC em outro momento. Os primeiros quadros do take têm pequenas lacunas de aquecimento do encoder.

**Validação final executada:** `assembleDebug` OK; `testDebugUnitTest` 294 testes, 0 falhas; `lintDebug` OK; `assembleRelease` OK (R8, 19,3 MB); `detekt` e `ktlintCheck` OK. Rodar `assembleRelease` e `lintDebug` na mesma invocação pode falhar por corrida de arquivos no Windows (bloqueio de `classes.jar`); rodados em sequência, passam. Se o bloqueio aparecer, `./gradlew --stop` resolve.

**Ainda NÃO verificado em aparelho:** BSP (receptor tardio/SPS-PPS), Sony Wi-Fi e cleartext, UVC/HDMI, release com R8 instalado, Bluetooth SCO (a permissão `BLUETOOTH_CONNECT` ainda não é pedida em tempo de execução), exportação automática para a Galeria, Android 14/15, HDR, calibração de peaking/scopes/LUT, 60 fps efetivo, notificação de pareamento em segundo plano, revogação derrubando o WebSocket, `SourceLost` e disco cheio.
