# BDSM - Revisão geral de UI, técnica e tecnologia (somente leitura)

> Data: 2026-10-03. Escopo: Splash, Permissões, Home, Monitor/HUD, Configurações e subtelas (NDI, LUTs, Gravações, Diagnóstico, Licenças, Link, Roleta), diálogos.
> Método: leitura de `ARQUITETURA.md`, `PLANO_DE_MELHORIA.md`, manifest, temas XML/Compose, `MainActivity`, `AppNavigation`, `SplashScreen`, `HomeScreen`, `PreviewScreen`, partes de Settings/Recordings/Diagnostics/HUD, catálogo de versões, CI e greps no repositório. Não foi executado build nem o app em aparelho.
> Legenda: **[V]** verificado no código (arquivo:linha) · **[S]** suposição/inferência (não confirmada em runtime ou dependente de dado externo). Esforço: S ≤ 1 dia, M 1-3 dias, L ≥ 1 semana.
> Limite desta revisão: não li linha a linha todos os arquivos grandes (`RecordingsScreen` 1.482 linhas, `MediaGraph`, `RecordManager`, HUD). Achados de telas secundárias vêm de amostragem + grep.

---

## 0. Números de contexto (verificados por grep)

| Métrica | Valor |
|---|---|
| `stringResource(` no código Kotlin | **0** (`app/src/main/res/values/strings.xml` tem 1 string: `app_name`) |
| Literais pt-BR em `text/title/message/subtitle = "..."` + `Text("...")` | ~**93 + 31** (subestimado: não conta `contentDescription`, `Toast`, `label`) |
| `collectAsState()` sem lifecycle | **28** ocorrências em 7 arquivos (vs. 40 `collectAsStateWithLifecycle`) |
| `@Preview` | **0** |
| Testes de UI Compose / screenshot / instrumentados | **0** (só `core/.../MigrationTest.kt` em androidTest; `ui-test-junit4` declarado no catálogo, sem uso) |
| Feedback háptico (`LocalHapticFeedback`/`performHapticFeedback`) | **0** |
| `BackHandler` | 1 (só `RecordingsScreen.kt:373`); nenhum no Monitor |
| `WindowSizeClass` / Compose adaptive | **0** (só `LocalConfiguration.orientation`) |
| Crash reporting / Timber / baseline profile / macrobenchmark | **nenhum** |
| Cores diferentes de "vermelho" no código | 5: `BdsmAccent #FF0055`, `BdsmRed #FF1744`, `HudTheme.recordActiveColor #FF3D00`, light `primary #D50032`, `error #FF5252/#BA1A1A` |

---

## 1. VISUAL / UX

### ALTO

**U1. Strings 100% hardcoded em pt-BR, sem `strings.xml` - i18n e TalkBack inviáveis** [V] - esforço L (incremental)
- 0 `stringResource` no projeto. Exemplos: `app/.../MainActivity.kt:176,185,189,212-226` (permissão e pareamento), `feature-home/.../HomeScreen.kt:159-189,221,304,309`, `SplashScreen.kt:65,76`, `PreviewScreen.kt:294,302-307`, `PreviewViewModel.kt:524-526` (Toast), `DiagnosticsScreen.kt:76,184-190`, `SettingsComponents.kt:116,337,624`.
- ViewModels emitem `String` pronta para a UI (`SettingsViewModel.events`, `LutsViewModel`, `RecordingsViewModel.messages`) e `PreviewViewModel` usa `Toast` com `Context`: o texto vive na camada errada, e não troca com locale.
- Ação: criar `strings.xml` por módulo (pt-BR como default + `values-en`), migrar tela por tela; trocar `String` de eventos por `UiText`/`@StringRes` + args. Começar por Permissões, Pareamento (segurança) e erros do Monitor. Há `README_en.md`, ou seja, existe público em inglês [S].
- Adicionar `android:localeConfig` (per-app language, Android 13+) depois da migração.

**U2. Fluxo de permissões sem explicação (onboarding inexistente)** [V] - esforço M
- `MainActivity.kt:91-101` dispara `RequestMultiplePermissions` (câmera, microfone, notificações) direto no primeiro frame, sem contexto. Sem Home/Splash por trás: o usuário vê uma tela quase vazia (`MainActivity.kt:112-121`, `162-193`) com uma frase e dois botões.
- Câmera E microfone são **ambos obrigatórios** para entrar no app (`:108`), mesmo para quem só quer configurar NDI/LUTs ou usar fonte USB/Sony sem microfone. Gravar sem mic já é um caso suportado no plano (item 1.2) [V parcial].
- `POST_NOTIFICATIONS` está no mesmo pedido; se negada, o app ainda entra, mas nenhum aviso explica que o Link/REC em segundo plano ficará sem notificação [S: não vi tratamento].
- Ação: tela de boas-vindas (3 cards: o que é, por que câmera/mic, privacidade/rede local) + pedido contextual (câmera ao abrir o Monitor; mic ao ativar gravação com áudio; notificação ao ligar Link/REC). Mostrar estado "negado permanentemente" detectando `shouldShowRequestPermissionRationale`. Permitir entrar sem mic.

**U3. Monitor/HUD: tipografia mínima 11sp e alvos densos** [V] - esforço M
- `HudTheme.kt:51-55`: `fontSizeMin/Small/Medium = 11.sp`, `Normal = 12.sp`. Abaixo do mínimo recomendado (12sp) e o HUD fica ilegível com `fontScale` alto ou sob sol. 138 usos de `Color.*`/`Color(0x..)` literal em `feature-preview` ignoram o tema.
- Texto sobre vídeo com `Color.Gray` (`HudDialPopovers.kt:219,224`, `SonyRemotePanel.kt:124`): contraste insuficiente sobre cenas claras (cinza #888 sobre fundo variável) [S: depende da cena].
- Ação: base 12sp, rótulos críticos (REC, tempo, bateria, armazenamento) 14sp; sombra/scrim sob texto sobre vídeo; modo "HUD grande" nas configurações (escala 1.0/1.25/1.5) para uso externo.

**U4. Estados de erro do Monitor: emoji, `Color.Red` e texto fixo** [V] - esforço S
- `PreviewScreen.kt:298-311`: overlay de erro com "⚠️" no texto, `Color.Red` sobre preto (contraste ok, mas fora do tema), tamanhos `24.sp/16.sp/14.sp` fixos, sem ícone/`semantics { liveRegion }`, sem botão "Voltar/Trocar fonte" (só "Tentar novamente").
- `:292-296`: "Aguardando USB..." sem instrução (conectar cabo, permitir USB), sem spinner, sem timeout, sem botão para trocar de fonte.
- Ação: componente `MonitorStatusOverlay(icon, title, message, primaryAction, secondaryAction)` reutilizável (USB aguardando, erro de sensor, Sony fora de alcance, perda de fonte durante REC). Aplicar `liveRegion = Polite/Assertive` para o TalkBack anunciar.

**U5. Acessibilidade geral: poucas semânticas e `contentDescription` hardcoded** [V] - esforço M
- 95 usos de semantics/Role no projeto todo, mas `.clickable` sem `role`/`onClickLabel` em `HudDialPopovers.kt:82,114,165`, `HudMenus.kt:92`, `ScopesOverlay.kt:16`, `LicensesScreen.kt:87`, `RouletteScreen.kt:175,230`. 29 `contentDescription = null` (verificar se todos são decorativos; ícones clicáveis sem descrição são bug).
- Gesto de pinça/duplo toque do Monitor (`PreviewScreen.kt:223-236`) não tem alternativa acessível (botão de zoom/reset) [V: não encontrei].
- Itens do HUD (REC, NDI, tools) provavelmente não anunciam estado ligado/desligado (`stateDescription`) [S: amostrado].
- Ação: padronizar `Modifier.bdsmClickable(role, onClickLabel)`; `stateDescription` em toggles do HUD; ações customizadas de acessibilidade para zoom; testar com TalkBack e `fontScale` 2.0 nas telas de Configurações (já têm `verticalScroll` na Home [V `HomeScreen.kt:95`]).

### MÉDIO

**U6. Cores de marca inconsistentes entre app e HUD** [V] - esforço S
- Tema novo usa `#FF0055` (`Color.kt:8`). O HUD mantém `recordActiveColor #FF3D00` (laranja-avermelhado), `buttonActiveColor #2979FF` (azul, `HudTheme.kt:29`), `ndiActiveColor #00C853`. `BdsmRed #FF1744` (gravação) e `BdsmAccent #FF0055` (marca) convivem: risco de o operador confundir "destaque da marca" com "estado de gravação".
- `onPrimary = White` sobre `#FF0055` (dark) dá contraste ≈3,9:1 (calculado por mim) - **abaixo de 4,5:1 (AA) para texto normal** de botões (`labelLarge` 14sp). No claro, `#D50032` com branco ≈5,4:1 passa. [V cálculo, S sobre percepção]
- Ação: escolher `onPrimary = Color.Black`? (dá ~5,4:1) ou escurecer levemente o primário do escuro (`#F0004F`~`#E6004C` ≈ 4,5:1+) ou usar botões `FilledTonal`/texto 16sp bold (large text exige 3:1). Definir regra: vermelho de marca = ação/destaque; vermelho de gravação = só estado REC; unificar HUD em `BdsmTheme.colors` (hoje o HUD tem tema próprio `HudTheme`/`ModernHudTheme`).

**U7. Splash: 3 camadas e 3,2 s artificiais** [V] - esforço S
- `SplashScreen.kt:32-54`: animação 0,8 s + fade 1 s + `delay(1200)` = ~3 s mínimo antes da Home, sempre, sem esperar nada real. Há também a Splash do sistema (XML `windowSplashScreenBackground` só em v31, `values-v31/themes.xml:12`) sem ícone animado nem `androidx.core:core-splashscreen` para API < 31 [V: dependência não existe].
- Ação: adotar `core-splashscreen` (instalSplashScreen + `setKeepOnScreenCondition` ligado à leitura do tema, que hoje é `runBlocking` na Main - `MainActivity.kt:55-56`) e **remover a SplashScreen Compose** ou reduzi-la a <600 ms. Economiza ~2,5 s por abertura (relevante para quem abre o app para gravar).

**U8. Ícone do app: PNGs legados, sem adaptive nem monochrome** [V] - esforço S
- `app/src/main/res/mipmap-*/ic_launcher*.png` (inclusive `mipmap-v26/` com PNG, o que é incomum) e nenhum `mipmap-anydpi-v26/ic_launcher.xml` nem `<monochrome>`. No Android 13+ não há themed icon; no Android 8+ a máscara do launcher corta/encaixa o PNG.
- Ação: criar `ic_launcher_foreground` vetorial (lente da marca), `ic_launcher_background` (#0A0A0A ou degradê), `ic_launcher_monochrome`; `mipmap-anydpi-v26`. Remover os PNGs.

**U9. Tablet, dobráveis e paisagem sem estratégia adaptativa** [V] - esforço M-L
- `Home` alterna só por `orientation` (`HomeScreen.kt:75-76`), com `widthIn(max=960.dp)`; `RecordingsScreen.kt:362-363` idem (lista em retrato, grade em paisagem). Settings é coluna única, sem lista-detalhe. Nenhum `WindowSizeClass`.
- Manifest `android:screenOrientation="fullSensor"` + `configChanges` amplos (`AndroidManifest.xml:~95`) está correto para o Monitor, mas desliga recriação da Activity: então recursos por `layout-sw600dp` não se aplicam; tudo precisa ser reativo em Compose.
- Android 16 (targetSdk 36) ignora restrições de orientação/aspect em telas ≥600dp - hoje o app não restringe [V], logo está pronto nesse ponto; falta adaptar o layout (tablet em retrato com `contentMaxWidth`) [S].
- Ação: `androidx.compose.material3.adaptive` (`NavigableListDetailPaneScaffold` para Configurações e Gravações) e `NavigationRail` em ≥600dp; testar em Fold (postura mesa: HUD na parte inferior) via `WindowInfoTracker` [S].

**U10. Sem feedback háptico** [V] - esforço S
- 0 usos. Para um app de operação "às cegas" (REC, tally, tools) faz diferença: `HapticFeedbackType.LongPress` ao iniciar/parar REC, `Confirm/Reject` (API 30+) em ligar/desligar NDI, `TextHandleMove`/`SegmentTick` em dials/sliders.
- Ação: `LocalHapticFeedback` em `HudRecControls`, toggles de ferramentas e dials; opção "Vibração" nas configurações.

**U11. Navegação e voltar** [V] - esforço S-M
- Monitor sem `BackHandler`: voltar durante REC sai do Monitor sem confirmação (o plano 1.1 prevê confirmar; sessão desacoplada via `CaptureForegroundService` mitiga a perda do take [V manifest + `PreviewScreen.kt:187-191`], mas o usuário não é avisado).
- Sem `android:enableOnBackInvokedCallback` no manifest (predictive back não habilitado em API 33-35; fica ativo por padrão no targetSdk 36) [V]. Transições usam `slideIn` 1/4 + fade: ficam coerentes, mas não acompanham o gesto.
- Ação: `BackHandler` no Monitor (REC ativo -> dialogo "Continuar em segundo plano / Parar e sair"); ativar predictive back e usar `NavHost` com `predictivePopExit/popEnter` (Navigation 2.8+/Compose 1.7+ já suportam).

**U12. Estados vazios/erro/carregando irregulares** [V parcial] - esforço M
- Existem: `EmptyState` (`RecordingsScreen.kt:801`, `DiagnosticsScreen.kt:100`), "Nenhum dispositivo pareado." (`LinkSettingsSection.kt:119`), loading com semantics (`SettingsComponents.kt:624`).
- Faltam/são frágeis [S, amostrado]: LUTs sem nenhuma LUT importada (CTA "Importar .cube" + link de ajuda), NDI com Wi-Fi desconectado/sem rede, Link ligado sem rede local (mostrar IP/URL e QR para o dashboard), Gravações sem espaço/pasta SAF inacessível, falha de importação com mensagem acionável.
- Padronizar: `Snackbar` para eventos efêmeros (Gravações/LUTs já usam), mas há 4+ `Toast` (`PreviewViewModel.kt:524-526`, `SettingsScreen.kt:~85`, `DiagnosticsScreen.kt:76`, `GalleryExporter.kt:38`) - Toast em ViewModel com `Context` vaza responsabilidade; trocar por evento de UI + Snackbar com ação "Ver".

### BAIXO

- **U13.** Animações: `AnimatedVisibility/animate*AsState/Crossfade` aparecem só ~10 vezes. Falta transição ao abrir diálogos de escolha, ao alternar painéis do HUD (fade+scale curtos), `animateContentSize` nos cards da Home, shared element Home -> Monitor (Compose 1.7+) [S sobre ganho]. Esforço M.
- **U14.** `Diagnostics`: `LazyColumn items(cameras)` sem `key` (`DiagnosticsScreen.kt:109`); `HudMenus.kt:83` `items(items.size)` sem `key`. Esforço S.
- **U15.** Roleta (easter egg, `Routes.EASTER_EGG`): rota pública no grafo, aberta por toques no "Sobre" (`SettingsScreen.kt:60-61`); `Color.White` fixo (`RouletteScreen.kt:237`) e `clickable` sem papel (`:175,230`). Decidir se entra em release (peso/segurança nulo, mas é superfície a mais de a11y/QA). Esforço S.
- **U16.** `HomeScreen` mistura `collectAsState()` (`:74`) e tem textos de marca ("BRAGA/DIGITAL/STUDIO MOBILE") como `Text` separados: leitor de tela lê 3 vezes; juntar com `semantics(mergeDescendants)`/`clearAndSetSemantics { contentDescription = "Braga Digital Studio Mobile" }`. Esforço S.
- **U17.** Sem tema Material You para o ícone/splash, e `dynamicColor` desligado por padrão com opção em Configurações [V `Theme.kt:174-182`]: ok, mas com cor dinâmica o `primary` perde a marca e os estados semânticos (recording/tally vêm de `BdsmColors`, bom).

---

## 2. TÉCNICO

### ALTO

**T1. `runBlocking` na Main antes do primeiro frame** [V] - esforço S - **FEITO (2026-10-03)**: `core-splashscreen` + leitura assíncrona; splash Compose removida
- `MainActivity.kt:55-56` lê `themeMode` e `dynamicColorEnabled` com `runBlocking{ first() }` no `onCreate`: duas leituras sequenciais do DataStore bloqueiam a Main (com `ReplaceFileCorruptionHandler` em falha/IO lento pode dar ANR/jank na abertura [S: normalmente poucos ms]).
- Ação: `installSplashScreen().setKeepOnScreenCondition { !themeLoaded }` + um único `combine(themeMode, dynamicColor).first()` assíncrono; ou ler os dois em uma só chamada (`data.first()`), e cachear o último tema em `SharedPreferences` (leitura síncrona barata) para o primeiro frame.

**T2. Lógica de permissões e diálogo de pareamento dentro de `MainActivity`** [V] - esforço M
- `MainActivity.kt:58-125` mistura estado de permissões, launcher, tema e roteamento; usa FQN completos (`androidx.compose...`) por todo o arquivo (ruído; indica código colado). Hardcoded `delay(2000)` em loop (`:203-207`) para `auth.refresh()` em vez de o `LinkAuthManager` expirar pedidos por conta própria (timer interno ou `Flow` com `delay`).
- `LinkPairingHost` só existe depois das permissões (`:108-111`), ou seja, **pedido de pareamento não aparece se faltar permissão de câmera/mic** [V], e a notificação com Permitir/Recusar existe como fallback (`PairingActionReceiver`) [V manifest].
- Ação: extrair `PermissionGate`, `AppRoot` e `LinkPairingHost` para arquivos próprios; desacoplar o diálogo de pareamento do gate de permissões (fica no `NavHost` raiz).

**T3. `collectAsState()` sem lifecycle (28 usos)** [V] - esforço S - **FEITO (2026-10-03)**: 0 ocorrências restantes
- Arquivos: `MainActivity.kt:59-60,201`, `HomeScreen.kt:74`, `DiagnosticsScreen.kt` (7), `LutManagementScreen.kt` (6), `NdiSetupScreen.kt` (3), `RecordingsScreen.kt` (2), `SettingsScreen.kt` (8). Continuam coletando com a tela em segundo plano (Diagnostics e NDI têm métricas de alta frequência) -> bateria e CPU desnecessárias; o Preview já usa `collectAsStateWithLifecycle` (40 usos) - inconsistência.
- Ação: substituir em massa (regra do Android Lint `FlowOperatorInvokedInComposition`/detekt custom ou `lint` do Compose); ESLint equivalente: adicionar regra detekt `compose:lambda-param-in-effect` e `slack-compose-rules`.

**T4. Monitor: 40+ `collectAsStateWithLifecycle` na raiz do `PreviewScreen`** [V] - esforço M-L - **PARCIAL (2026-10-03)**: zoom/pan lidos só no leaf (`ZoomControlHost`); métricas do compilador mostram todos os composables skippable (strong skipping), `HudUiState/HudActions` completo NÃO feito
- `PreviewScreen.kt:60-131` declara dezenas de estados no mesmo composable e repassa a `CameraHUDOverlay` (dezenas de parâmetros). Já tem mitigação para tempo/áudio (`:62-68`, leitura adiada por lambda), mas qualquer um dos ~35 estados restantes recompõe a raiz inteira.
- O plano (`§5.1`) cita `HudUiState/HudActions` e *strong skipping* como pendência [V no plano]. O Kotlin 2.3 habilita strong skipping por padrão, mas classes de domínio de outros módulos (`VideoSettings`, `MonitorSettings`, `Lut`, `HardwareMetrics`) sem `@Immutable`/`@Stable` e listas `List<Lut>` (interface instável) continuam recompondo se a instância mudar [S: nenhum relatório de compiler metrics foi gerado].
- Ação: (1) `HudUiState` imutável + `HudActions` (interface estável), um único `StateFlow<HudUiState>` agregando os flows de baixa frequência com `distinctUntilChanged`; (2) gerar `compose_compiler_metrics/reports` no CI e olhar `unstable` em `feature-preview`; (3) `kotlinx-collections-immutable` para listas. Medir com Layout Inspector (contagem de recomposição) antes/depois.

**T5. Zero testes de UI, screenshot e instrumentados; sem testes de ViewModel** [V] - esforço L
- 36 arquivos de teste unitário, todos de lógica pura (parsers, formatters, mappers, rotas Ktor, máquina `RecState`). Nenhum teste dos 6 ViewModels (`PreviewViewModel` 558 linhas, `SettingsViewModel`, `NdiSetupViewModel`, `LutsViewModel`, `RecordingsViewModel`, `DiagnosticsViewModel`) apesar de MockK/Turbine/coroutines-test já estarem no catálogo [V]. Nenhum teste Compose apesar de `ui-test-junit4` no catálogo.
- Ação (ordem de custo/benefício): (1) testes de ViewModel com Turbine para fluxos críticos (REC, NDI toggle, troca de fonte); (2) `ComposeTestRule` em Home/Settings (`SettingsSwitchItem`, `ChoiceDialog`) para semantics/a11y; (3) screenshot tests com **Roborazzi** ou Paparazzi (JVM, sem emulador) nos temas claro/escuro × fontScale 1.0/2.0 × retrato/paisagem - pega regressão de contraste e de quebra de layout; (4) um instrumentado "smoke" no CI com GMD (Gradle Managed Devices) para Splash -> Home -> Monitor com câmera falsa.

**T6. Sem observabilidade em produção** [V] - esforço M
- Nenhum Crashlytics/Sentry/ACRA e nenhum logger estruturado (grep vazio). Release usa R8 + `mapping.txt` arquivado no CI [V `release.yml:99-102`], mas sem coletor de crash o mapping não é usado. Para um app que grava vídeo (falha de encoder, perda de USB, thermal), o crash silencioso é o maior ponto cego.
- Ação: se for distribuído fora da Play/sem Google Services: ACRA ou Sentry (self-hosted) com opt-in; se Play: Crashlytics + ANR. Adicionar `ApplicationExitInfo` (API 30+) lido na próxima abertura (mostrar "o app foi encerrado pelo sistema durante a gravação") e um `Timber`/wrapper de log com buffer circular exportável pela tela Diagnóstico (já existe "Relatório copiado" - `DiagnosticsScreen.kt:76`) [V].

### MÉDIO

**T7. CI sem instrumentação, sem verificação de dependências e sem build release** [V] - esforço M
- `ci.yml:43,48`: `assembleDebug lintDebug testDebugUnitTest` e `detekt ktlintCheck --continue`. Faltam: `assembleRelease` em PR (R8 quebra só no release; o `missing_rules.txt` é citado em `proguard-rules.pro`), `:core:connectedDebugAndroidTest` (MigrationTest nunca roda no CI), Dependabot/Renovate (Kotlin 2.3.21, AGP 8.13.2, Ktor 2.3.13...), `dependency-review`/OSV-scanner, verificação de `.so` 16 KB (`zipalign -c -P 16` / `check_elf_alignment.sh`) já que o app embarca `libndi.so` terceiro [V arquitetura] e Play exige 16 KB para novos envios (nov/2025) [S sobre data atual da política].
- Ação: job `release-check` (`assembleRelease` com keystore de debug + `checkElfAlignment`), job `androidTest` via Gradle Managed Device (API 34 `aosp-atd`), `renovate.json`, `gradle/actions/dependency-submission`.

**T8. Estado de diálogos com `remember`, não `rememberSaveable`** [V] - esforço S
- `SettingsScreen` (`showResolutionDialog`, `showSourceDialog`, `showFpsDialog`...) usa `remember`; como a Activity trata `configChanges` isso sobrevive a rotação, mas **não** a morte de processo (comum com Monitor aberto + RAM baixa) [V total `rememberSaveable` = 7 vs `remember {` = 65]. Impacto baixo, mas a Home/Navigation voltam bem (NavHost salva a pilha).
- `LinkAuthManager.pending` e sessões em memória: ao morrer o processo, pareamentos pendentes somem [S: tokens aprovados vão para DataStore? não verificado].

**T9. `PreviewViewModel` com `Context` e Toast; acoplamento a `MediaGraph` direto** [V] - esforço M
- `PreviewViewModel.kt:9,524-526`: uso de `Toast` e `context` no VM. `saveSnapshotToGallery(bmp)` roda em `viewModelScope` (Main?) - o encode/PNG/JPEG tem que estar em `Dispatchers.IO` [S: não li a função].
- Ação: `SnapshotUseCase` no core-media com `Dispatchers.IO`; VM emite `UiEvent.SnapshotSaved(uri)`.

**T10. Navegação por strings (`Routes`) + lambdas por tela** [V] - esforço M
- `AppNavigation.kt:30-41`: rotas `const val` string, sem argumentos tipados; todas as ações são lambdas repassadas (`HomeScreen` recebe 5 callbacks). Navigation 2.9.8 já suporta `@Serializable` type-safe (ver §3).
- A transição Splash -> Home usa `popUpTo(SPLASH){inclusive}` (correto). `PREVIEW` `onNavigateHome` tem fallback robusto (`:116-123`) - bom.

**T11. Segurança** [V parcial] - esforço M
- Bom: `allowBackup=false`, `network_security_config` só com cleartext `192.168.122.1`, `FileProvider` não exportado, pareamento por token com consentimento duplo (`LinkAuthManager`), permissões amplas de armazenamento removidas via `tools:node="remove"` [V manifest].
- Pontos de atenção: (1) `LinkServer` serve HTTP **sem TLS** em Wi-Fi local (porta 8080) - o token trafega em claro [S: não vi TLS no Ktor 2.3 config; confirmar]. Em rede pública/hotel é interceptável. Opção: TLS com certificado autoassinado + pinning no plugin OBS, ou ao menos WS com token por mensagem e expiração; (2) `LinkDashboard.kt` (420 linhas) serve HTML/JS embutido: CSP e escape de `clientName` (que o usuário digita no OBS e aparece no diálogo - `MainActivity.kt:215`) devem ser verificados [S]; (3) `MainActivity` `exported=true` sem verificação de intents extras (só LAUNCHER - ok); (4) `android:usesCleartextTraffic` não está setado, mas rede local com `NsdManager` é permitida pelo config [V]; (5) segredo de assinatura vem de `local.properties`/env: ok.
- Ação: threat model curto do Link; rate-limit de tentativas de pareamento (conferir `LinkAuthManagerTest`) e `network.TLS`.

**T12. R8/performance de startup** [V/S] - esforço M
- `isMinifyEnabled=true` + `isShrinkResources=true` [V]. Falta: **Baseline Profile** + `profileinstaller` (a dependência `profileinstaller 1.4.1` já está no catálogo [V], mas sem módulo `:baselineprofile`/macrobenchmark - grep vazio), perfil de startup para Splash -> Home -> Monitor. Hilt com 9 módulos: `Provider` pesados no `Application.onCreate` (`BdsmApplication.kt`) [S, não li].
- Ação: módulo `:baselineprofile` (plugin `androidx.baselineprofile`) com jornada Splash->Home->Monitor->Settings; Macrobenchmark de startup (cold) e *jank* do Monitor; `-Xlint` de `R8 full mode` (padrão AGP 8) já ativo. Esperado: -20 a -30% no cold start [S, valor típico].

**T13. Core ainda concentra arquivos gigantes** [V] - esforço L
- `RecordingsScreen.kt` 1.482 linhas (UI + listas + diálogos + utilitários), `MediaGraph.kt` 1.220, `RecordManager.kt` 1.086, `Camera2Device.kt` 840, `HomeScreen.kt` 773. O plano já tratou `PreviewHud.kt` (3.081 -> 432 linhas, 14 arquivos). Aplicar o mesmo padrão em `RecordingsScreen` (separar `RecordingsList/Grid/Item/Dialogs/Header`).

### BAIXO

- **T14.** `core-network` tem dependência `cors` declarada e sem uso [V `ARQUITETURA.md §2`]; `LutManager` é código morto [V arquitetura]; `jetifier` ligado sem necessidade comprovada (`gradle.properties`) -> testar desligar (`:app:checkDebugDuplicateClasses`). Esforço S.
- **T15.** `targetSdk = 35` com `compileSdk = 36` (`app/build.gradle.kts`): a Play exige targetSdk 36 para novas versões a partir de 31/08/2026 [S sobre prazo]; hoje (out/2026) isso já deve estar vigente se o app for publicado na Play. Subir exige revisar: predictive back obrigatório, `edge-to-edge` (já tratado), restrições de orientação ignoradas ≥600dp, intents de segurança e *16 KB page size* (feito).
- **T16.** `BdsmApplication.kt` aparece como `D`/`??` no `git status` do início da sessão (arquivo `BsmApplication.kt` deletado; `BdsmApplication.kt` existe e o manifest aponta para `.BdsmApplication` [V]): confirmar que o rename está completo e commitado.
- **T17.** `MainActivity` aplica `applyImmersiveMode` a cada foco (`:135-139`) em **todas** as telas, inclusive Configurações/Gravações (onde barras visíveis ajudam navegação por gestos e leitura de hora/bateria). Considerar imersivo só no Monitor. Esforço S.
- **T18.** `PreviewScreen.kt:138-140` mantém `hasCameraPermission = true`/`hasAudioPermission = true` fixos e bloco `if (...)` morto (`:218`): remover. Esforço S.

---

## 3. TECNOLOGIA (custo/benefício)

| # | Item | Benefício | Custo | Recomendação |
|---|---|---|---|---|
| G1 | **Navigation type-safe** (`@Serializable` routes, Navigation 2.8+; o app já está na 2.9.8) | Elimina `Routes` string, habilita argumentos tipados (ex.: abrir Gravações já com um item selecionado, atalhos) | S-M | **Adotar já** (sem upgrade de lib) |
| G2 | **Predictive back** (manifest `enableOnBackInvokedCallback`, `PredictiveBackHandler`) | UX moderna Android 14+; obrigatório de fato no targetSdk 36 | S | **Adotar já** (junto com U11) |
| G3 | **`androidx.core:core-splashscreen`** | Splash consistente API 26+, remove 3 s artificiais (U7) | S | **Adotar já** |
| G4 | **Compose Material3 Adaptive** (`adaptive`, `adaptive-layout`, `adaptive-navigation`, `WindowSizeClass`) | Tablet, Fold, desktop mode (Samsung DeX/Chromebook é público provável para monitor externo [S]) | M-L | Adotar em Configurações/Gravações/Home (U9) |
| G5 | **Baseline Profile + Macrobenchmark** | Cold start e jank (T12) | M | **Adotar**; alto retorno. PARCIAL (2026-10-03): `baseline-prof.txt` à mão revisado (sem a splash removida), profileinstaller ativo; módulo macrobenchmark NÃO criado |
| G6 | **Crash reporting** (Crashlytics ou ACRA/Sentry) + `ApplicationExitInfo` | Visibilidade em produção (T6) | M | **Adotar** |
| G7 | **Roborazzi/Paparazzi** (screenshot tests) | Regressão de tema/contraste/fontScale | M | Adotar após U1/U3 |
| G8 | **Thermal API** (`PowerManager.addThermalStatusListener`, API 29+; `getThermalHeadroom`) | Gravar/transmitir 4K com HEVC/NDI aquece o aparelho: avisar no HUD ("Aquecimento: reduzir resolução/bitrate") e fazer *throttle* automático antes do SO derrubar clocks. `HardwareMonitorService` hoje mostra bateria/armazenamento [V Home metrics]; térmico [S: não vi] | S-M | **Adotar** (alto valor para produção ao vivo) |
| G9 | **Health/Battery**: `BatteryManager` (corrente/capacidade) já usado em parte; ligar a *wake lock* parcial + `PowerManager.isPowerSaveMode` para avisar | Evita parar take por economia de energia | S | Complementa G8 |
| G10 | **Jetpack Glance widget** (atalho "Abrir Monitor" / status Link) | Baixo: usuário de monitor abre o app para gravar; widget agrega pouco. Útil só para estado do Link/NDI | M | Adiar |
| G11 | **App Shortcuts** (estáticos + `ShortcutManager` dinâmicos: "Abrir Monitor", "Gravações", "NDI") | Acesso em 1 toque pelo ícone; barato, combina com Navigation type-safe | S | **Adotar** |
| G12 | **Photo Picker / Selected Photos Access** | Não relevante: o app não importa fotos; LUTs `.cube` usam SAF (`OpenDocument`) - manter. Exportação de vídeo usa `MediaStore` [V `GalleryExporter`] | - | Não adotar |
| G13 | **CameraX vs Camera2** | Camera2 direto dá controle manual (ISO, shutter, WB, foco, HLG10 recém-adicionado - commit `34dc225`) e o pipeline GL próprio já usa `ImageReader`/Surface. CameraX Camera2Interop perderia controle e adicionaria camada; benefício só em compatibilidade de dispositivos (quirks) | L | **Manter Camera2.** Considerar só `androidx.camera:camera-camera2` de referência para workarounds de OEM |
| G14 | **Media3** (Transformer/Player/`InAppMp4Muxer`) | (a) **Transformer** para exportação/corte/LUT offline das gravações (hoje exportar = cópia) [S]; (b) **Player** in-app no lugar de `ACTION_VIEW` externo (`RecordingsScreen.kt:1446`) com timeline e proxies; (c) `MediaMuxer` próprio pode ser trocado pelo `Mp4Muxer` do Media3 (fragmentado: **MP4 recuperável em queda de energia**, requisito do plano 1.4) | M-L | Adotar (b) e (c) por último; (c) resolve problema real de take perdido |
| G15 | **Kotlin 2.3.x / AGP 8.13.2 / Hilt 2.58 / Room 2.8.5** | Já recentes [V catálogo]. AGP 9 e Hilt 2.59+ existem [S, depende da data]. Navigation 2.9.8, Lifecycle 2.10, activity-compose 1.12 estão atuais | S | Manter; ativar Renovate; migrar **Ktor 2.3.13 -> 3.x** (breaking: `ktor-server-*` pacotes, `ContentNegotiation`) |
| G16 | **Coil 2.7.0 -> Coil 3** (multiplataforma, `coil-video` para thumbnails de MP4) | Hoje thumbnails de vídeo passam por `MediaMetadataRetriever` com semáforo e cache próprio (plano A7) [V]; `coil-video` unifica cache de disco/memória | M | Opcional |
| G17 | **SRT / WebRTC além do NDI** | **SRT** (libsrt + FFmpeg/`srt-android`): tolerante a perda, uso por internet (transmissões remotas), amplo suporte (OBS, vMix, Larix). **WebRTC** (livekit-android / `stream-webrtc`): latência <300 ms no navegador (sem plugin), bom para o `LinkDashboard`. O app já tem `BspManager` (H.264/RTP/UDP em desenvolvimento) [V arquitetura]: avaliar migrar o BSP para **SRT** em vez de manter protocolo próprio | L | **SRT** (custo L, alto valor: NDI é LAN-only); WebRTC só se o dashboard virar "monitor remoto". NDI SDK também exige licença/atribuição (conferir `LicensesScreen`) |
| G18 | **Android 15/16** | (a) 16 KB page size: feito [V]; (b) `ACTION_CAPTURE_..`/`ServiceInfo` FGS timeout: **FGS tipo `dataSync` tem limite de 6 h/24 h no Android 15** - o `LinkServerService` roda `dataSync` indefinidamente (`AndroidManifest.xml`) [V tipo; S sobre comportamento]; precisa tratar `onTimeout()` e avaliar `connectedDevice`/`mediaProjection`/`specialUse`; (c) Android 15 restringe iniciar FGS `camera` em background; já há `CaptureForegroundService` (core-media) - testar o caso REC com tela apagada; (d) Android 16 **Live Updates**/`ProgressStyle` para notificação de REC (tempo/tamanho) e cartão de bloqueio [S]; (e) `Display.rotation` -> `requestedOrientation` em ≥600dp | M | Tratar (b) e (c) **antes** de subir targetSdk |
| G19 | **Notificação de gravação rica** (`MediaStyle`/chronometer + ação Parar) | Controle fora do app durante take; `CaptureForegroundService` já existe [V] | S | Adotar |
| G20 | **Compose 1.8+/1.9 features**: `Modifier.pointerInput` -> `transformable` (zoom/pan), `Modifier.focusable`/teclado (suporte a teclado USB/Bluetooth: atalhos REC, F, Z) | Operador com teclado/teclado Bluetooth no tablet [S] | S-M | Opcional |

---

## 4. Plano sugerido (ordem de execução)

1. **Semana 1 (S)**: T1/G3 splash + tema assíncrono; T3 `collectAsStateWithLifecycle` em massa; U4 overlay de erro; U6 contraste do botão; U8 ícone adaptativo; G2 predictive back + U11 BackHandler no Monitor; U10 háptico; U14 `key`s.
2. **Semana 2-3 (M)**: U1 strings (Permissões, Pareamento, Monitor primeiro); U2 onboarding/permissões contextuais; T5 testes de ViewModel + 1ª rodada de screenshot; T6 crash reporting + ApplicationExitInfo; G8 Thermal.
3. **Mês 2 (M-L)**: T4 `HudUiState`/`HudActions` + métricas Compose; G1 navegação tipada + G11 shortcuts; G5 Baseline Profile; T7 CI (release, androidTest GMD, Renovate); U9/G4 adaptive.
4. **Trimestre (L)**: G14(c) Mp4Muxer fragmentado; G17 SRT; G18 FGS timeout + targetSdk 36; T11 TLS no Link; T13 quebrar `RecordingsScreen`.

---

## 5. Itens que dependem de verificação em aparelho (não confirmados)

- Contraste real do botão primário no escuro (3,9:1 calculado) e legibilidade do HUD a 11sp sob sol.
- Comportamento do `LinkServerService` (`dataSync`) após 6 h no Android 15.
- Existência de TLS/CSP no `LinkServer`/`LinkDashboard` (não li os arquivos).
- Thread de `saveSnapshotToGallery`.
- Ganhos de startup com Baseline Profile.
- Que os 29 `contentDescription = null` sejam todos decorativos.
