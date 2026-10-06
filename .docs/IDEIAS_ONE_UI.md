# Ideias: interface inspirada no One UI (Samsung)

Status: SOMENTE IDEIAS (2026-10-04). Nada foi implementado. Decisões do usuário: manter o **vermelho** como cor de destaque, usar só a estrutura do One UI (cantos, cabeçalhos, listas), trabalhar **por etapas** começando por uma tela de referência.

## 1. O que o DESIGN.md da Samsung é
Fonte consultada: `design-md/samsung/DESIGN.md` do repositório awesome-design-md (commit d0effc4f). Ele descreve o **site samsung.com**, não o One UI de celular; é uma referência curta e aproximada para agentes de IA. Pontos úteis:
- botões em pílula (raio 999 px); cartões com raio de 16 a 24 px;
- profundidade "plana mas separada": contraste entre blocos em vez de sombras (sombra só sutil, `0 8px 24px rgba(0,0,0,.08)`);
- espaçamento interno grande (40 a 64 px entre seções no web); tipografia forte;
- azul da marca usado com moderação; evitar raios pequenos em componentes grandes e poluição visual.
Não se aplica: fontes (Samsung Sharp Sans/SamsungOne) e paleta azul (`#1428a0`, `#0072ea`). O One UI de celular tem princípios próprios que esse arquivo não cobre (abaixo, de conhecimento geral das diretrizes do One UI; **não foram buscados nesta análise**, vale conferir em developer.samsung.com/one-ui antes de implementar):
- área de visualização (topo) e área de interação (metade inferior), para uso com uma mão;
- título grande que encolhe ao rolar;
- listas agrupadas em contêineres bem arredondados;
- fundo preto com cartões cinza-escuro no tema escuro.

## 2. Estado atual do app (lido no código)
- Tokens em `common/ui/theme/BdsmTokens.kt`: formas 8/12/20 dp (small/medium/large) e pílula; espaçamento 2/4/8/12/16/24/32 dp; alvo de toque 48 dp; largura máxima de conteúdo 720 dp.
- Componentes: `BdsmV3.kt` (rail, `BdsmBigButton`, `ModuleCard`, `SettingsCategoryRow`, `BdsmBottomSheet`, `DetailLine`) e `SettingsComponents.kt` (`BdsmCard`, `BdsmScreen`, `BdsmLazyScreen`, `SettingsSection`, `SettingsItem`, diálogos, `SegmentedChoice`, `StatusChip`, estados vazio/erro/carregando).
- Já alinhado ao One UI: barra inferior com indicador em pílula, botões grandes em pílula, cartões de 20 dp, profundidade por tonalidade, tema claro e escuro, ações principais na metade de baixo (Home e NDI), Configurações em categorias com busca.

## 3. Diferenças em relação ao One UI
1. **Cabeçalho:** título pequeno e fixo (`BdsmTopBar`). One UI: título grande que ocupa o alto da tela e colapsa ao rolar, empurrando o conteúdo para a metade de baixo.
2. **Cantos:** 20 dp nos cartões, 12 dp nos itens. One UI: ~24 a 28 dp nos contêineres, itens internos menos arredondados.
3. **Listas:** linhas dentro de cartões soltos. One UI: grupos contínuos com divisores recuados (iniciando após o ícone) e espaço maior entre grupos, rótulo de grupo pequeno e discreto.
4. **Respiro:** margens laterais atuais menores e escala densa/técnica. One UI: margens de ~20 a 24 dp, mais espaço vertical.
5. **Hierarquia tipográfica:** o ganho vem de contraste de tamanhos (título de tela grande, rótulos de grupo pequenos), não de trocar de fonte (no Samsung a fonte do sistema já é a One UI Sans).
6. **Cor:** One UI usa azul; o app mantém o vermelho (BdsmAccent #E6003E escuro, #D50032 claro). Decisão do usuário.

## 4. Ideias, por etapa (nada feito)
### Etapa 0 — Conferir referências — CONCLUÍDA (2026-10-04)
Ver a seção 7 ("Resultado da Etapa 0") com as medidas oficiais encontradas, as lacunas e como elas mudam as etapas seguintes.

### Etapa 1 — Tokens (baixo risco)
- Formas: contêiner grande 28 dp, médio 20 dp, item 16 dp, chip 12 dp; manter pílula nos botões.
- Margens laterais 20 a 24 dp; espaçamento entre grupos 24 dp; altura de botão principal 56 dp.
- Tipografia: título de tela grande (~30 sp em Medium/SemiBold), rótulo de grupo ~12 sp em cor secundária; nada abaixo de 11 sp.
- Tudo sem tocar no Monitor (tokens de forma não são usados pelo HUD).

### Etapa 2 — Componentes novos
- `BdsmLargeTitleScaffold`: cabeçalho grande que colapsa ao rolar (LazyColumn + nested scroll), com ações do topo (busca, mais).
- `BdsmGroupedList`: contêiner único arredondado com linhas e divisores recuados; `BdsmGroupLabel`.
- Revisar `ModuleCard`, `SettingsCategoryRow` e `BdsmBigButton` para os novos tokens.
- Cuidar de desempenho no A51: sem `IntrinsicSize.Min`, sem sombras/blur, leituras de scroll na fase de desenho.

### Etapa 3 — Tela de referência: Ajustes
- Aplicar tudo em Ajustes primeiro (lista de categorias + uma categoria), nos temas claro e escuro, e validar no A51 com capturas antes de estender. O usuário aprova o estilo aqui.

### Etapa 4 — Demais telas (após aprovação)
- NDI (radar no centro, botão grande embaixo), Mídia (Gravações/LUTs), Home (manter a mescla v4), onboarding.
- Home: avaliar título grande de marca e o cartão do Monitor na zona do polegar (já está).

### Etapa 5 — Polimento
- Animações curtas de colapso e transições (só deslocamento), háptico em toggles, acessibilidade (TalkBack, fonte 1.3, contraste AA), tablet/paisagem com rail e duas colunas.

## 5. Riscos e perguntas em aberto
- Cantos maiores e mais espaço = menos itens por tela: pode piorar a densidade no A51 (tela de 6,5"); testar.
- Cabeçalho colapsável custa um pouco de desempenho; medir em release (o debug distorce).
- O Monitor permanece fora do escopo e deve continuar idêntico.
- **Decisões do usuário (2026-10-04):**
  - O título grande que colapsa aparece **somente em Ajustes**. NDI, Mídia e Home mantêm o cabeçalho atual (compacto).
  - **Fundo preto puro (`#000000`) no tema escuro**, por economia de energia e melhor contraste em telas OLED. Cartões e contêineres seguem em cinza-escuro (ex.: `#121212` a `#1C1C1C`) para separar por tonalidade; revisar `BdsmBackground`/`surface*` em `Color.kt` e `AppTheme.kt` e conferir contraste AA dos textos secundários sobre preto.
  - Atenção: no preto puro o "smearing" de pixels em OLED aparece ao rolar; testar no A51 e no S20 FE (ambos Super AMOLED).

## 6. Regras para a futura implementação
- Mudança por etapas, com captura no A51 antes de seguir; não refazer tudo de uma vez.
- Nunca desinstalar o app nem limpar dados no aparelho de teste; instalar com `install -r`.
- Não descartar mudanças não commitadas (sem checkout/restore/reset/stash/clean).
- Ao final de cada bloco com builds, limpar os caches (regra do projeto).

## 7. Resultado da Etapa 0 (referências oficiais conferidas)
Fontes: [One UI Design Guidelines (PDF oficial, Mobile UX Center)](https://design.samsung.com/global/contents/one-ui/download/oneui_design_guide_eng.pdf) (texto extraído localmente; é o guia clássico, anterior ao One UI 7, então os visuais mais novos podem diferir), e as páginas [Basic layout](https://developer.samsung.com/one-ui/layout/basic.html), [App bar](https://developer.samsung.com/one-ui/comp/app-bar.html), [Color system](https://developer.samsung.com/one-ui/color/system.html), [Grid system](https://developer.samsung.com/one-ui/layout/grid.html). As páginas web trazem princípios e poucos números; os números abaixo vêm quase todos do PDF.

### Medidas e regras oficiais encontradas
- **Área de visualização x interação:** o limite é a altura que o dedo alcança com facilidade. Em cima (visualização): conteúdo sem interação, como o título. Embaixo (interação): botões de função, navegação (abas) e diálogos que exigem ação.
- **App bar expansível:** altura expandida = **39,67% da altura da tela no celular** (18,78% em tablet), com o título **centralizado** quando expandida. Só há dois estados (expandida ou recolhida), sem meio-termo, e ao soltar o dedo no meio ela **encaixa (snap)** conforme o limiar. Rolar para cima recolhe; rolar para baixo (no topo da lista) expande. Os títulos expandido e recolhido podem ser diferentes; a área expandida pode mostrar informação útil da tela.
- **Quando usar / não usar:** exibir na primeira tela principal; em telas de 2º nível em diante, recolhida por padrão (expansível). **Não** usar quando um controle ocupa a tela toda, quando a rolagem tem componentes adicionais, ou quando imagem/vídeo do usuário pode ser cortado. **Em celular em paisagem em tela cheia não se aplica** (só em janela múltipla/dobráveis/DeX com altura > 580 dp). Isso apoia a decisão de usar o título grande só em Ajustes (NDI, Mídia e Home têm radar, grade ou vídeo e mantêm cabeçalho compacto) e exige cabeçalho compacto em paisagem.
- **App bar (ações):** preferir ícones nas ações, no máximo **3 botões** incluindo o título; sem ações, não mostrar botões; o resto vai para "Mais opções". Se houver busca essencial, a barra de busca some ao rolar e reaparece depois.
- **Bottom bar:** até **5** botões de ação de maior prioridade, ícone + texto; não usar "mais opções" nela; sem rolagem horizontal. (Nossa barra tem 4 itens: OK.)
- **Margens:** no mínimo **24 dp de cada lado**, dentro da área segura (cantos arredondados e telas curvas).
- **Focus block (cartão):** contêiner com cantos grandes, com alto contraste entre o fundo do bloco e o espaço atrás; aceita texto, imagem ou vídeo; vale para cartões e listas.
- **Raio de miniatura/focus block:** **26 dp, 26 dp, 20 dp e 12 dp**, conforme a grade e o alvo (as figuras do guia não deixam claro qual grade usa qual valor; a ordem sugere de 1 coluna/blocos grandes para 3 colunas/miniaturas pequenas).
- **Tema escuro:** fundo escuro/preto, blocos e diálogos em tons escuros para manter a tela inteira escura; fundo preto funde a borda da tela com a moldura em aparelhos de moldura preta. No PDF a cor "preto" do tema escuro é **`#080808`** (no claro, `#000000`; branco `#fafafa` nos dois).
- **Cores primárias do One UI:** azul `#0381fe`; primary dark `#0072de` (claro) e `#3e91ff` (escuro); control activated `#3e91ff`. Vermelho = aviso/perigo/proibição; verde = segurança/bom; azul = eficiência. Não aplicamos o azul (decisão: manter o vermelho).
- **Tipografia:** fonte padrão Roboto no guia (hoje One UI Sans no Samsung); primeira letra maiúscula só no início de cada título/frase/botão ("Dialog button"), o resto minúsculo. Tabela de tamanhos do guia sem valores legíveis na extração.
- **Contraste (acessibilidade):** texto pequeno ≥ **4,5:1**; texto grande (≥ 18 dp normal ou ≥ 14 dp negrito) ≥ **3:1**.
- **Ícones:** traços de terminação arredondada, cantos do traço nítidos para contrastar com os cantos arredondados do restante.

### O que NÃO foi encontrado (e fica como decisão nossa)
- Altura de linha de lista, espaço entre grupos, raio de itens internos, altura de botões, tamanho exato do título e divisores recuados: o guia não traz números legíveis. Usar nossas escalas (itens 16 dp, chips 12 dp, botão 56 dp, título grande ~30 sp) e validar visualmente no A51/S20 FE.
- Colunas e gutters da grade por largura de janela: as páginas oficiais não trazem.
- Cor exata de cartões no escuro: não especificada (o guia só pede tons escuros). Sugestão: cartões `#121212` a `#1C1C1C` sobre fundo `#000000`.

### Impacto nas etapas seguintes
1. **Cantos (Etapa 1):** ajustar a escala para os valores oficiais: contêiner grande **26 dp** (em vez de 28), cartões médios 20 dp, miniaturas 12 dp; itens de lista e chips seguem nossa escala. Raios menores em componentes que ficam dentro de contêineres grandes.
2. **Margens (Etapa 1):** subir para **24 dp** (mínimo oficial) respeitando a área segura; hoje a Home usa menos.
3. **Fundo escuro:** a decisão do usuário é `#000000` (OLED). O One UI usa `#080808`; a diferença é pequena. Manter `#000000` e registrar como escolha própria.
4. **Cabeçalho de Ajustes (Etapa 2):** implementar como o guia: dois estados, **39,67% da altura** expandida, título centralizado, snap pelo limiar, recolhe ao rolar para cima; recolhido por padrão em subtelas (categorias); em paisagem, sempre compacto; no máximo 3 ações (busca, mais); a busca some ao rolar.
5. **Contraste:** conferir 4,5:1 (texto pequeno) e 3:1 (texto grande) sobre preto puro nos tons secundários (`onSurfaceVariant`).
6. **Barra inferior:** manter 4 itens com ícone + texto; sem botão "mais opções".
7. **Capitalização:** padronizar rótulos: só a primeira letra maiúscula (hoje usamos caixa alta em botões como "ABRIR MONITOR"; decidir se manteremos caixa alta como identidade própria ou seguiremos o guia).

## 8. Resultado da Etapa 1 (tokens) — 2026-10-04
Status: código, testes, lint, detekt, ktlint e `assembleDebug` OK. **NÃO verificado no aparelho** (A51 desconectado): nenhuma captura foi feita.

### Tokens novos
- Formas `BdsmTheme.shapes`: `container` 26 dp, `card` 20 dp, `item` 16 dp, `chip` 12 dp, `pill` (antigos `small/medium/large` removidos). Mapeamento: diálogos, folhas, `BdsmCard`, cartões de Home e Diagnóstico = container; botões grandes, `ModuleCard`, `LutCard`, cartões de gravação, blocos de ícone = card; linhas, campos de texto, segmentos = item; chips, miniaturas, indicadores = chip.
- Espaçamento: `screenMargin` 24 dp, `groupGap` 24 dp; `BdsmBigButton` com altura mínima 56 dp (era 64). Alvos de toque 48 dp mantidos; `contentMaxWidth` 720 mantido; a área segura vem dos insets do Scaffold.
- Tipografia (`BdsmType`): `screenTitle` 30 sp SemiBold (só Ajustes, Etapa 2), `groupLabel` 12 sp Medium; `overline` sem caixa alta (letterSpacing 0,4). Escala geral inalterada, mínimo 11 sp.
- Cores escuras: background/surface/surfaceDim/surfaceContainerLowest `#000000`; surfaceContainerLow `#0A0A0A`, surfaceContainer/card `#121212`, surfaceContainerHigh/raised/surfaceVariant `#1C1C1C`, surfaceContainerHighest `#262626`, surfaceBright `#2C2C2C`, outlineVariant/cardBorder/divider `#2A2A2A`, outline `#808080`, onSurfaceVariant/accentNeutral `#A8A8A8`, ledOff `#6E6E6E`, recording/tallyProgram `#FF2D55` (antes `#FF1744`, não passava 4,5:1 sobre `#1C1C1C`). Acento `#E6003E`/`#D50032` e tema claro inalterados.
- Monitor: `AppTheme.monitorColorScheme()/monitorTokens()` congelam a paleta escura antiga para `BdsmDarkSurfaceTheme`. SHA1 de todos os arquivos de `feature-preview/src` idêntico antes e depois.

### Arquivos tocados
common/ui/theme: BdsmTokens.kt, Color.kt, AppTheme.kt, MonitorTheme.kt; common/components: BdsmDesign.kt, BdsmUx.kt, BdsmV3.kt, SettingsComponents.kt; app: MainActivity.kt, OnboardingScreen.kt, MonitorEntry.kt; feature-home: HomeScreen.kt, strings.xml; feature-settings: DiagnosticsScreen, LicensesScreen, LutManagementScreen, MediaScreen, NdiNetworkScreen, NdiSetupScreen, RecordingDetailSheet, RecordingsScreen. Também `.docs/ARQUITETURA.md` (8.1).

### Contraste calculado (WCAG), dark
| Cor | #000000 | card #121212 | raised/diálogo #1C1C1C | campo #262626 |
|---|---|---|---|---|
| onSurface E6E8EB | 17,11 | 15,26 | 13,88 | 12,33 |
| onSurfaceVariant A8A8A8 (era A0A7B1) | 8,83 | 7,88 | 7,17 | 6,36 |
| primary E6003E | 4,43 | 3,96 | 3,60 | 3,20 |
| error FF5252 | 6,58 | 5,87 | 5,34 | 4,74 |
| recording FF2D55 (era FF1744) | 5,76 | 5,14 | 4,67 | 4,13 |
| success 00E676 | 12,58 | 11,22 | 10,21 | 9,07 |
| warning FFC107 | 12,88 | 11,49 | 10,45 | 9,28 |
| info 64B5F6 | 9,48 | 8,46 | 7,70 | 6,83 |
| accents (video/audio/storage/monitor/network/library/system) | 7,72 a 12,13 | 6,89 a 10,82 | 6,27 a 9,85 | 5,56 a 8,74 |
| outline 808080 (não texto) | 5,32 | 4,74 | 4,32 | 3,83 |
Falhas/limites: `primary #E6003E` como texto pequeno não atinge 4,5:1 (4,43 no preto, 3,96 no card): só usar em texto grande/negrito ou ícone; texto pequeno em vermelho deve usar `error`/`recording`. Branco sobre o botão primário: 4,74. `cardBorder`/`divider` (1,3:1) são só decorativos (a separação vem da tonalidade card vs preto); `ledOff` agora 3,3:1 no preto.

### Strings convertidas
`home_open_monitor` e `home_open_monitor_caps` ("Abrir monitor"), "Compartilhar gravação" (chooser); removido `uppercase()` do rótulo de `MetricCard` e do sobretítulo do onboarding. Mantidos em caixa alta: siglas e o logotipo "BRAGA/DIGITAL/STUDIO MOBILE" da Home (marca, `HomeScreen` BrandLine/product). Monitor/HUD intocados. Nenhum teste dependia dos textos.

### Não verificado / pendências
- Nada visto no A51 (Home, NDI, Mídia, Ajustes, onboarding; escuro/claro; retrato/paisagem; Monitor). Revisar: margens de 24 dp (cortes/truncamento em textos longos, chips, cartões de gravação em grade), preto puro (smearing ao rolar, separação cartão x fundo, bordas), diálogos com 26 dp, botão grande 56 dp, Monitor idêntico.
- Contraste de `primary` em texto pequeno (TextButton, links) a revisar visualmente.
- Etapa 2: cabeçalho colapsável, `BdsmGroupedList`/`BdsmGroupLabel` (usar `groupLabel`/`screenTitle`), revisar `ModuleCard`/`SettingsCategoryRow`; `HomeScreen` linha ~412 ainda usa padding `lg` (16) interno, e `ktlintFormat` rodou no projeto todo (conferir diff de arquivos fora do escopo).

## 10. Paisagem: rail à direita e auditoria (2026-10-04)
Feedback de uso: o rail ficava à esquerda (lado contrário da mão). Agora fica à direita por padrão, com opção "Esquerda" em Ajustes > Aplicativo (DataStore, `LandscapeNavSideTest`). Corrigido: radar do NDI sem altura (agora duas colunas), cabeçalho da Mídia ocupando 1/3 da altura (agora compacto em 2 linhas), folhas sem rolagem, falta de insets horizontais na Mídia. Detalhes e limites do teste em ARQUITETURA.md > "Paisagem". Telas só verificadas por captura: Início, NDI, Mídia (Gravações), Ajustes (lista e Aplicativo), claro/escuro, retrato (Ajustes). NÃO verificadas em paisagem: LUTs, folha de detalhes, preview imersivo NDI, onboarding, Diagnóstico, Licenças, Roleta, diálogos, teclado.

## 9. Resultado das Etapas 2 a 5 (componentes, Ajustes, demais telas, polimento) — 2026-10-04
Status: código, testes, lint, detekt, ktlint e `assembleDebug`/`assembleRelease` conforme a seção "Verificação" abaixo. **NÃO verificado no aparelho** (A51/S20 FE offline): nenhuma captura foi feita; tudo o que depende de olhar a tela está na lista "Revisar no aparelho".

### Etapa 2: componentes (`common/components/BdsmOneUi.kt`, `BdsmV3.kt`, `SettingsComponents.kt`)
- **`BdsmLargeTitleScaffold`** (app bar expansível do guia). Dois estados (fração `collapse` 0 = expandida, 1 = recolhida), altura expandida = **39,67%** da altura da tela no celular e **18,78%** no tablet (menor lado >= 600 dp), descontada a barra de status; recolhida = barra de 56 dp. Título **centralizado** quando expandida (30 sp, `screenTitle`); título recolhido separado (`collapsedTitle`, 1 linha, cabeçalho semântico para o TalkBack). A barra **acompanha o dedo** e **encaixa ao soltar**: limiar de 50% ou, num arremesso a partir de 600 px/s, pela direção (para cima recolhe, para baixo expande); animação `tween` de 200 ms só na altura. Rolar para cima recolhe antes de a lista andar; rolar para baixo expande só depois de a lista chegar ao topo. **Celular na horizontal** (largura > altura e altura <= 580 dp) = SEMPRE compacta. Subtelas usam `startCollapsed = true`. Ações no topo via `actions` (no máximo 3; Ajustes usa só a lupa). A busca é o primeiro item da lista, então some ao rolar; a lupa rola ao topo, expande e foca o campo (`BdsmSearchField(focusRequester)`).
- **Desempenho (A51, Exynos 9611, 60 Hz)**: `LazyColumn` com `key`/`contentType`; a fração é um `mutableFloatStateOf` lido SÓ em `Modifier.layout` (altura do cabeçalho) e `graphicsLayer` (opacidade dos dois títulos), então nada recompõe a lista por quadro; sem `IntrinsicSize`, sombra ou blur; animações só de altura/deslocamento (e opacidade de dois textos). O que muda por quadro durante o arrasto: medida do cabeçalho + remedição da viewport da `LazyColumn` (poucos itens). Estado salvo com `rememberSaveable` (sobrevive à rotação).
- **`BdsmGroupedList` + `BdsmGroupLabel`/`BdsmGroupDivider`/`BdsmGroupInset`**: contêiner único de **26 dp**, tonal (sem sombra e sem borda), rótulo de grupo 12 sp (`groupLabel`, semântica de título), divisores recuados (`Icon` 60 dp = 16+32+12; `Category` 76 dp = 16+44+16), **24 dp** entre grupos (vem do `verticalArrangement` da lista). Linhas (`SettingsItem`, `SettingsSwitchItem`, `SettingsCategoryRow`) usam `bdsmGroupRow()`: recuo lateral de 4 dp e cantos de **16 dp** no destaque de toque, conteúdo alinhado em 16 dp úteis. `SettingsSection` passou a usar o grupo (todas as categorias e telas que a usam mudam junto); `BdsmCard` ficou tonal (sem borda).
- **Revisões**: `ModuleCard` = 20 dp, tonal, sem borda; `SettingsCategoryRow` com semântica mesclada e descrição opcional; `BdsmBigButton` = altura mínima **56 dp**, cantos 20 dp, rótulos em minúsculas (guia); `BdsmBottomSheet` com cantos de cima de 26 dp; `BdsmLazyScreen` com margem de 24 dp (antes 16); `HomeScreen`: botão-pílula do hero com 24 dp horizontais (antes 16, linha ~412), hero com 24 dp internos, sem bordas no hero e na faixa de métricas.

### Etapa 3: Ajustes (tela de referência)
`SettingsScreen` (lista de categorias + módulos extras + resultados da busca) e `SettingsCategoryScreen` (uma categoria, começa recolhida) em `BdsmLargeTitleScaffold`, nos dois temas. Em largura Medium/Expanded (`bdsmWidthClass()`) as categorias ficam em **2 colunas** (`SettingsCatalog.splitColumns`, metade maior na primeira) e o conteúdo vai até 960 dp. Seletor de tema ganha "check" no segmento selecionado (estado não só por cor).

### Etapa 4: demais telas (sem título grande)
NDI (cabeçalho compacto, radar no centro, botão grande embaixo; rótulos sem caixa alta), Mídia (Gravações/LUTs), Home (mescla v4 mantida, marca BRAGA/DIGITAL/STUDIO MOBILE em caixa alta como logotipo), onboarding (indicador + botão grande de 56 dp na zona do polegar, "Pular" abaixo; cartões de 26 dp), Diagnóstico, Licenças, Link, Roleta e diálogos/folhas (todos os `AlertDialog` com 26 dp; folha com cantos de cima de 26 dp) herdam os grupos/cartões/botões novos. Capitalização do guia em NDI, LUTs e Gravações (antes `INICIAR TRANSMISSÃO`, `APLICAR`, `EXCLUIR`...).

### Etapa 5: polimento
- Transições: o `NavHost` já usa só deslocamento (220 ms); o app bar encaixa em 200 ms.
- Háptico (`rememberBdsmHaptics`): voltar do app bar, lupa, troca de abas das Gravações, abrir/recolher grupos, toque/toque longo em vídeos, caixa "apagar também a cópia", cartões de licença; toggles, segmentos, diálogos e botões grandes já tinham.
- Acessibilidade: título do app bar com `heading()`; título grande só visual (`clearAndSetSemantics`); linhas de categoria como um único botão; alvos >= 48 dp (`IconButton`, linhas >= 48/72 dp); textos longos com `heightIn(min)`/quebra, sem alturas fixas no texto (fontScale 1,3 a verificar no aparelho); estados com ícone/texto além da cor (segmento com check, "Selecionado"/"Não selecionado" falados).
- Tablet/paisagem: rail e 2 colunas via `bdsmWidthClass()` (Home e Ajustes); app bar compacta em celular na horizontal.

### Pendências (a) a (d)
**(a) Contraste do vermelho como texto pequeno.** `primary #E6003E` continua (ícones, preenchimentos, botão primário, indicador). Novo token `BdsmTheme.colors.primaryText`, aplicado em `BdsmTextButton` (substitui todos os `TextButton` fora do Monitor), `bdsmTextFieldColors()` (rótulo focado e cursor) e link de licenças.

| Cor | #000000 | cartão #121212 | elevado/diálogo #1C1C1C | campo #262626 |
|---|---|---|---|---|
| `primary` E6003E (texto: NÃO usar) | 4,43 | 3,96 | 3,60 | 3,20 |
| **`primaryText` escuro FF4D73** | **6,56** | **5,85** | **5,32** | **4,73** |
| (alternativas: FF5C7E / FF6B88) | 7,08 / 7,71 | 6,32 / 6,88 | 5,75 / 6,26 | 5,11 / 5,56 |

Tom escolhido: **`#FF4D73`**, o menor deslocamento de luminosidade que passa em TODOS os fundos escuros (>= 4,5:1) e ainda lê como o mesmo vermelho. Tema claro: `primary #D50032` dá 5,42 sobre branco, 4,84 sobre o fundo `#F1F2F4`, 4,75 sobre `#EEF0F3`, 4,53 sobre `#E8EBEF` e **4,25 (falha) sobre `#E1E4E9`** (campos/segmentos); por isso `primaryText` claro = **`#C70030`**: 6,05 / 5,40 / 5,30 / 5,06 / **4,75**. Branco sobre `#D50032` = 5,42 e sobre `#E6003E` = 4,74. A marca BRAGA/DIGITAL da Home (logotipo, 10 sp) continua em `primary` (logotipo é isento do critério). Testado em `ContrastTest`.

**(b) Strings.** Para `strings.xml`: Diagnóstico (título, relatório, protocolo BSP, campos, rótulos de especificações), Licenças, Link (revogar), Gravações (diálogo de exclusão, seleção, mensagens, abas, descrições de acessibilidade), Cassino (título/botão), NDI/LUTs/Gravações em minúsculas. Novo `common/components/UiText` (recurso + argumentos ou texto pronto) usado nas mensagens do `LutsViewModel` (erro/info) e do `RecordingsGalleryViewModel` (mensagens e "desfazer"); a tela resolve com `asString(context)`. Mantidos fora: Monitor, relatório copiado para a área de transferência (inglês técnico) e os prêmios da Roleta (conteúdo do easter egg).

**(c) Testes.** Novos: `OneUiAppBarTest` (frações oficiais, celular x tablet, compacta na horizontal, altura, rolagem/limites, snap por limiar e por arremesso), `ContrastTest` (primaryText nos 4 fundos escuros e 5 claros, acento da marca, texto secundário), `UiTextTest`, e em `SettingsCatalogTest` (todos os termos, `splitColumns`).

**(d) Documentação**: esta seção e §8.1/§8.4 de `ARQUITETURA.md`.

### Revisar no aparelho (NÃO verificado)
Escuro/claro, retrato/paisagem, A51 e S20 FE: (1) Ajustes: altura do título grande (39,67%), título centralizado, arraste e encaixe (sem tremor, sem salto ao soltar), recolher/expandir ao rolar, categorias começando recolhidas, lupa (rola, expande, abre o teclado), desempenho (release, `gfxinfo`); (2) paisagem de celular: app bar compacta, nada cortado por cutout; (3) tablet: 2 colunas, rail, 960 dp; (4) grupos de 26 dp, destaque de toque de 16 dp, divisores recuados alinhados com o texto, margem de 24 dp (truncamentos), preto puro (smearing); (5) texto vermelho `primaryText` (TextButton, links, rótulo de campo) e botão primário; (6) fontScale 1,3 e TalkBack (ordem, cabeçalhos, "Selecionado"); (7) onboarding com o botão grande embaixo; (8) háptico; (9) Monitor idêntico (SHA1 de `feature-preview/src` conferido por arquivo antes/depois).

### Verificação (`testDebugUnitTest lintDebug detekt ktlintCheck assembleDebug assembleRelease -Pbdsm.abis=arm64-v8a --continue`)
- Testes unitários: 434, 0 falhas (common 25, feature-settings 59, feature-home 16, app 8, core 30, core-capture 35, core-media 132, core-network 60, feature-preview 69). detekt, ktlintCheck, `assembleDebug` e `assembleRelease`: OK, sem baseline novo.
- `lintDebug`: 1 erro que NÃO vem destas mudanças: `local.properties:4` (`storeFile=C:/Users/...` precisa de `C\:/...`, regra `PropertyEscape`); o arquivo é do usuário e não foi tocado. Sem nenhum aviso novo nos arquivos desta tarefa.
- Monitor: SHA1 agregado de `feature-preview/src` idêntico antes e depois (`3498f47e...`).
