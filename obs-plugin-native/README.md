# BDSM Link - plugin nativo (dock) para OBS Studio

Extensão de verdade para o **OBS Studio 32.x**: um plugin C++17/Qt6 que adiciona o
**dock "BDSM Link"** (acoplável, em *Exibir > Painéis (Docks)*) e mostra, para cada celular BDSM
(Braga Digital Studio Mobile) da rede:

- nome do aparelho, estado da conexão e o endereço `IP:porta`;
- **bateria** (com alerta vermelho abaixo de 20%), **lente**, **fonte** de captura, **FPS** e **REC**;
- o nome da **fonte NDI** (`BDSM (<nome>)`) e o **seletor/mapeamento da fonte NDI do OBS** por
  dispositivo (fontes do DistroAV: tipo `ndi_source`, chave `ndi_source_name`);
- o **retorno de tally** em tempo real: borda e selo **vermelho = NO AR (Programa)**,
  **verde = PRÉVIA (Preview)**, cinza = **FORA** (sempre com cor E texto). O plugin também **envia** o `TALLY_UPDATE` ao celular
  (só quando muda), considerando cenas aninhadas, grupos, itens ocultos e o Modo Estúdio;
- botões **Parear / Esquecer / Remover** por celular e **Procurar na rede (mDNS)**;
- **integração com o DistroAV** (plugin NDI do OBS): indicador "DistroAV instalado?" e o botão
  **"Adicionar fonte NDI à cena atual"**, que cria (ou reutiliza, sem duplicar) a fonte NDI do celular e já vincula o tally;
- indicador global **"x celulares NO AR"** no topo do dock.

O pareamento usa o protocolo de consentimento duplo do app (código de 4 dígitos nos dois lados,
aprovação no celular). Detalhes do protocolo: [`../.docs/BDSM_PLUGIN_OBS.md`](../.docs/BDSM_PLUGIN_OBS.md) seções 5 e 6.
Esta pasta **não altera** o script Python em [`../obs-plugin/`](../obs-plugin/), que continua funcionando.

---

## ATENÇÃO: o código ainda NÃO foi compilado

Esta rodada deixou o projeto **pronto para compilar no CI do GitHub Actions**. O PC de desenvolvimento
não tem MSVC/Qt e nada foi instalado. O que foi (e o que não foi) verificado:

| Parte | Situação |
| :-- | :-- |
| **Lógica pura** (`core/`: JSON, LinkState, tally, WebSocket RFC 6455, SHA-1/base64, pareamento, mDNS, backoff, armazenamento/DPAPI) | **Compilada e testada localmente** com g++ (MSYS2/UCRT64, `-Wall -Wextra -Wpedantic`, sem avisos): **79 testes passaram** (61 + 18 novos: transição do tally e integração DistroAV), inclusive DPAPI real do Windows. |
| Chamadas à API do OBS (`obs_scene_graph.cpp`, `obs_ndi_integration.cpp` [criar/reutilizar fonte `ndi_source`, detectar o DistroAV, transição], registro do módulo e do dock, callbacks do frontend) | **Só verificadas por sintaxe** (`g++ -fsyntax-only -Wall -Wextra`, sem avisos) contra os headers oficiais do OBS **32.2.2** baixados do GitHub (`libobs` + `frontend/api`, com um `obsconfig.h` de mentira). Não foram linkadas nem executadas. |
| Camada Qt (`link_connection`, `device_manager`, `bdsm_dock`, `mdns_discovery`, `tally_controller`, `plugin-main`) | **Nunca compilada** (não há Qt no PC). Revisão estática manual apenas. |
| CMake/presets do template com OBS 32.2.2, workflow, **instalador Inno Setup (incluindo a página de pré-requisitos em Pascal Script)** | **Nunca executados** (nem o Inno Setup existe neste PC). |
| Integração com o DistroAV real (`ndi_source`/`ndi_source_name`) | **Conferida no código-fonte do DistroAV 6.2.1** (leitura), **não testada** com o DistroAV instalado. |
| Teste no OBS real, com celular real | **Pendente** (ver o checklist no final). |

Espere **erros de compilação/link no primeiro run do CI** (ver "Pontos de maior risco" abaixo).

---

## Requisitos

- **OBS Studio 32.x** (Windows x64, Qt6). Alvo de compilação: 32.2.2.
- **DistroAV** (ex-obs-ndi; versão consultada: **6.2.1**, 24/04/2026) instalado **e carregado**, para o OBS receber o
  vídeo do celular como fonte NDI. O BDSM Link **não** inclui nem redistribui o DistroAV nem o NDI (licenças): você instala
  por conta própria. Releases: <https://github.com/DistroAV/DistroAV/releases/latest> · guia:
  <https://github.com/DistroAV/DistroAV/wiki/1.-Installation> (ou `winget install --exact --id DistroAV.DistroAV`).
- **NDI Runtime** (NDI Tools / Runtime): <https://ndi.video/tools/>. O DistroAV 6.2.1 lista o NDI Runtime **6.3 ou superior**
  como requisito. Sem o runtime o DistroAV **não carrega** e a fonte "NDI Source" não existe no OBS.
- Celular com o app BDSM com o **LinkServer ligado** (padrão), na mesma rede (porta 8080), e o **NDI ligado** no app.
- **Não** use ao mesmo tempo o script Python `bdsm_link_obs.py` (ver "Migrar do script Python").

O BDSM Link só faz **telemetria, tally e a criação da fonte**: o vídeo/áudio NDI é recebido pelo DistroAV.

## Versões escolhidas (consultadas em 04/10/2026) e por quê

| Item | Versão | Motivo |
| :-- | :-- | :-- |
| OBS Studio (headers/libobs/obs-frontend-api) | **32.2.2** (última estável, 14/08/2026) | É a série do usuário (32.x). A 33.0.0 ainda está em beta. |
| obs-deps (pré-compilado) e Qt6 | **2026-07-15** (Qt **6.11.1**) | São exatamente as versões fixadas no `CMakePresets.json` do OBS 32.2.2; compilar contra o mesmo Qt do OBS evita incompatibilidade de ABI. Hashes SHA-256 copiados de lá. |
| `obs-plugintemplate` | `master` (último commit 09/12/2025) | Estrutura CMake/presets/`buildspec.json`/`cmake/`/scripts do template oficial. O `buildspec.json` do template ainda aponta para o OBS 31.1.1; aqui foi atualizado para 32.2.2 (o hash do `.zip` do código-fonte foi calculado localmente). |
| CMake / compilador | CMake 3.28+, Visual Studio 2022 (runner `windows-2022`; o `windows-latest` agora traz só o VS 2026), **C++17** | Exigências do template/OBS (SDK do Windows 10.0.20348+). |
| DistroAV (só referência, não embutido) | **6.2.1** (24/04/2026; `README` lista NDI Runtime 6.3+) | Código-fonte lido na tag `6.2.1` (`src/ndi-source.cpp`, `src/plugin-main.cpp`, `src/ndi-finder.cpp`): fonte de entrada de id **`ndi_source`**; configuração **`ndi_source_name`** (string, nome NDI completo `MAQUINA (nome)`; outras chaves: `ndi_bw_mode`, `ndi_sync`, `latency`, `ndi_behavior`, `ndi_audio`...); o `NDIFinder` lista as fontes com `show_local_sources = true` (por isso aparecem `LOCALHOST (...)`); o DistroAV lê o NDI Runtime pela variável **`NDI_RUNTIME_DIR_V6`** (macro `NDILIB_REDIST_FOLDER` do SDK) e, se não carregar a biblioteca, o módulo não carrega. |
| Plataformas | **Somente Windows x64** | macOS/Linux exigem hashes/dependências que não foram preparados; o template os suporta, mas ficaram fora (ver "macOS e Linux"). |

**Descoberta importante:** o Qt6 pré-compilado do OBS (obs-deps) contém só `qtbase`, `qtsvg`,
`qtimageformats`, `qtmultimedia`, `qtshadertools` e `qttools`. **Não existe o módulo Qt WebSockets**
(`QWebSocket`). Por isso o WebSocket (RFC 6455) foi implementado em `core/ws_codec.*` (puro, testado)
sobre `QTcpSocket` (Qt Network, que faz parte do `qtbase`). O pareamento HTTP usa `QNetworkAccessManager`.

## Como o CI compila

Workflow: [`../.github/workflows/obs-plugin-native.yml`](../.github/workflows/obs-plugin-native.yml)
(precisa estar na raiz do repositório para o GitHub executá-lo).

Dispara em: push em `main`/`master` e PRs que mexam em `obs-plugin-native/**`, tags `obs-plugin-v*` e
execução manual. Jobs:

1. **core-tests** (Ubuntu): preset `tests-only` (sem OBS/Qt) + `ctest`. Rápido; falha cedo se a lógica quebrar.
2. **windows-x64** (`windows-2022`, MSVC): usa os scripts do template
   (`.github/scripts/Build-Windows.ps1`: preset `windows-ci-x64`, baixa e valida por SHA-256 o código do OBS 32.2.2,
   obs-deps e Qt6, compila libobs/obs-frontend-api e o plugin), roda o **CTest**, empacota o **ZIP**
   (`Package-Windows.ps1`) e gera o **instalador** com **Inno Setup**
   (`installer/bdsm-link.iss`). Publica artefatos em todo push/PR: ZIP, `*-setup.exe` e PDBs.
3. **release** (só em tag `obs-plugin-v*`): cria a Release do GitHub com o ZIP e o instalador. A versão do
   plugin vem da tag (`obs-plugin-v1.2.3` vira `1.2.3`).

Observação: o preset de CI do template liga `CMAKE_COMPILE_WARNING_AS_ERROR`; aqui ele fica **desligado**
(`windows-ci-x64` em `CMakePresets.json`) até o primeiro build limpo. Depois do primeiro verde, volte para `true`.

## Instalar (usuário final)

Layout recomendado pela [documentação do OBS](https://obsproject.com/kb/plugins-guide) (o nome da DLL = nome da pasta):

```
C:\ProgramData\obs-studio\plugins\bdsm-link\bin\64bit\bdsm-link.dll
C:\ProgramData\obs-studio\plugins\bdsm-link\data\locale\en-US.ini  (e pt-BR.ini)
```

O local antigo (`C:\Program Files\obs-studio\obs-plugins\64bit`) está **obsoleto** e não é usado.

### Com o instalador (recomendado)

1. Baixe `bdsm-link-<versão>-windows-x64-setup.exe` na página de *Releases* (ou nos artefatos do run do CI).
2. **Feche o OBS Studio** e execute o instalador. Ele pede administrador e instala, por padrão, em
   `%PROGRAMDATA%\obs-studio\plugins\bdsm-link\` (todos os usuários). Para instalar **sem administrador**, escolha
   **"Instalar somente para mim"** (*Install for me only*) na janela inicial: vai para
   `%APPDATA%\obs-studio\plugins\bdsm-link\`. As duas pastas são varridas pelo OBS 28+ (inclusive o 32).
3. A página **Pré-requisitos** (só aparece se algo faltar ou o OBS estiver aberto) verifica **OBS Studio**, **DistroAV** e
   **NDI Runtime** e mostra os links oficiais. É **só um aviso: você pode continuar** e instalar o resto depois.
4. Abra o OBS. O dock aparece em **Exibir > Painéis (Docks) > BDSM Link** (ligue-o se estiver oculto) e pode ser
   arrastado/acoplado onde quiser.

Desinstalar: *Configurações > Aplicativos > BDSM Link para OBS Studio* (o desinstalador é registrado pelo instalador).
O token pareado fica em `%APPDATA%\obs-studio\plugin_config\bdsm-link\bdsm_link.json` e **não** é apagado; remova a pasta
para esquecer tudo.

### Manualmente (ZIP)

1. Baixe `bdsm-link-<versão>-windows-x64.zip`. Ele contém a pasta `bdsm-link\` já no layout acima (sem `.pdb`).
2. Com o OBS **fechado**, extraia a pasta `bdsm-link` dentro de `C:\ProgramData\obs-studio\plugins\` (todos os usuários,
   precisa de administrador) **ou** de `%APPDATA%\obs-studio\plugins\` (só você). Resultado esperado:
   `...\plugins\bdsm-link\bin\64bit\bdsm-link.dll` e `...\plugins\bdsm-link\data\locale\*.ini`.
   (`ProgramData` é uma pasta oculta: digite o caminho na barra do Explorer.)
3. Instale o **DistroAV** e o **NDI Runtime** (links em "Requisitos") e abra o OBS.

Formato legado (não recomendado): a DLL em `Program Files\obs-studio\obs-plugins\64bit` e a pasta `data` em
`...\obs-studio\data\obs-plugins\bdsm-link` também funciona se copiado à mão.

## Usar o dock

1. No celular, abra o app BDSM (LinkServer ligado, **NDI ligado**). No dock, digite o **IP** (ex.: `192.168.0.20` ou
   `192.168.0.20:8080`) e clique **Adicionar**, ou clique **Procurar na rede (mDNS)**.
2. Clique **Parear**. O dock mostra um **código de 4 dígitos** e o celular mostra um diálogo com o mesmo código:
   **confira que são iguais** e aprove no celular (o pedido expira em 90 s).
3. Conectado: o cartão mostra bateria, lente, fonte, FPS, REC e o nome do NDI, atualizados a ~2 Hz.
4. **Adicionar a fonte NDI no OBS.** Cada cartão mostra **"DistroAV: instalado / não encontrado"** e o estado da fonte
   NDI daquele celular. O botão **"Adicionar fonte NDI à cena atual"**:
   - **cria** a fonte "NDI Source" do DistroAV chamada `BDSM - <aparelho>` com o nome NDI `BDSM (<nome do NDI>)`
     (o que o celular anuncia) e a coloca na **cena atual** (no **Modo Estúdio**, na cena em **preview**, que é a que
     você está editando). Se o nome já estiver em uso, vira `BDSM - <aparelho> (2)`;
   - se a fonte **já existe** em alguma cena (casando pelo nome NDI, sem diferenciar maiúsculas/espaços e ignorando o
     prefixo da máquina, p. ex. `LOCALHOST (...)`), o cartão **mostra em quais cenas ela está** e o botão só a
     **reutiliza** (a mesma fonte entra na cena atual; nada é duplicado). Se já está na cena atual, o botão fica
     desabilitado;
   - **registra sozinho o mapeamento** celular → fonte do OBS (o mesmo do seletor "Fonte NDI do OBS") para o tally;
   - se o DistroAV **não** estiver carregado, o botão fica **desabilitado** com a explicação e aparece **"Como instalar o
     DistroAV"** (abre a página de instalação no navegador).
   O tamanho/posição da fonte na cena é o padrão do OBS (ajuste como qualquer fonte).
5. **Tally** (retorno ao celular, **só quando muda** e **reenviado ao reconectar**). Por celular o dock mostra **cor e
   texto**: vermelho **NO AR**, verde **PRÉVIA**, cinza **FORA**; no topo, **"x celulares NO AR"**. O plugin descobre qual
   fonte do OBS é o celular casando a fonte NDI do DistroAV (`ndi_source_name`, ex.: `BDSM (Galaxy S20 FE)`) com o
   `ndiStreamName` que o celular publica; se não casar, escolha a fonte no seletor **Fonte NDI do OBS** (mapeamento manual
   salvo por celular, tem prioridade). Regras:
   - **PROGRAM > PREVIEW > OFF.** Uma cena "conta" se a fonte estiver **visível**, mesmo dentro de **cena aninhada** ou
     **grupo**; item **oculto** não conta. **PREVIEW só existe no Modo Estúdio** (a cena em preview).
   - **Transição em andamento** (Fade, Stinger etc.): quem está na cena de **origem** continua **NO AR** até a transição
     terminar; quem está só na cena de **destino** fica em **PRÉVIA** e vira **NO AR** quando ela termina (o OBS já chama o
     destino de "programa" no início; o plugin reavalia a cada ~120 ms durante a transição e no evento de fim). Sem
     informação confiável da origem (p. ex. vindo do preto), vale a regra normal.
   - Ao fechar o OBS ou **Remover** o celular, o plugin envia `OFF` antes de desconectar (a borda do celular apaga).
6. **Esquecer** apaga o token (volta a "Não pareado"); **Remover** tira o celular da lista (e o token). Se o operador
   **revogar** o acesso no celular, o plugin apaga o token e pede novo pareamento automaticamente.
7. **Bateria < 20%:** o valor fica vermelho com "BATERIA BAIXA", aparece um aviso no topo do dock e uma linha no log do OBS.

O token **nunca** é exibido nem escrito em log. No Windows ele é guardado com **DPAPI** (escopo do usuário).

**Formato do tally (conferido no app Android, `core-network/.../LinkModule.kt`):** o plugin envia
`{"type":"TALLY_UPDATE","state":"PROGRAM|PREVIEW|OFF"}` (~45 caracteres); o servidor ignora mensagens com mais de
**1.024 caracteres** ou de outro tipo, e fecha a conexão com quadros WebSocket acima de **8 KB**.

## Migrar do script Python (`obs-plugin/`)

- **NÃO use o script Python e o plugin nativo ao mesmo tempo:** os dois calculam e **enviam `TALLY_UPDATE`**
  para o mesmo celular e brigariam pelo estado do tally (a borda do celular piscaria entre valores). O plugin não detecta
  o script: **remova** o script antes de usar o dock.
- Para migrar: em *Ferramentas > Scripts*, **remova** `bdsm_link_obs.py`; instale o plugin; adicione/pareie os celulares
  de novo no dock.
- Os dois **não compartilham** configuração: o script guarda em `plugin_config\bdsm_link\`, o plugin em
  `plugin_config\bdsm-link\`, cada um com seu próprio `clientId`. O celular vai listar os dois clientes pareados;
  revogue o antigo (do script) no app se não for mais usá-lo.

## Solução de problemas

| Sintoma | O que verificar |
| :-- | :-- |
| O dock não aparece | O plugin deve estar em `...\obs-studio\plugins\bdsm-link\bin\64bit\bdsm-link.dll`. Veja *Ajuda > Arquivos de log > Ver o log atual* e procure `[bdsm-link]` / erros de carregamento (DLL do Qt ausente ou versão incompatível do OBS). Ative em *Exibir > Painéis (Docks)*. |
| "Erro: Sem conexão com IP:8080" | Celular e PC na mesma rede/VLAN? LinkServer ligado no app? Firewall do PC bloqueando saída? Teste `http://IP:8080/` no navegador. |
| "HTTP 429" ao parear | Já existe pedido pendente deste PC, 3 pedidos pendentes, ou o pedido foi recusado há menos de 5 s. O plugin espera 6/12/24 s e tenta de novo (até 3 vezes). |
| "Pedido recusado" / "expirou" | Aprove no celular em até 90 s e confira o código de 4 dígitos. |
| "Aprovado, mas o token não foi entregue" | O token só é entregue uma vez; clique **Parear de novo**. |
| Fica "Desconectado... nova tentativa em N s" | Reconexão automática com backoff 1, 2, 4... até 30 s. O app pode estar fechado ou o aparelho em modo de economia de energia. |
| Voltou a "Não pareado" sozinho | O operador revogou o acesso (fechamento WS 1008 / HTTP 401). Pareie de novo. |
| Tally sempre FORA | O NDI está ligado no celular? A fonte do DistroAV existe e está **visível** na cena do Programa? O nome da fonte NDI no OBS é `MÁQUINA (nome)`? Senão, use o seletor **Fonte NDI do OBS** no cartão. Prévia exige Modo Estúdio. |
| O seletor de fonte NDI está vazio | DistroAV não instalado ou nenhuma fonte "NDI Source" criada no OBS. Use **Adicionar fonte NDI à cena atual**. |
| Cartão diz "DistroAV: não encontrado" | O DistroAV precisa estar **instalado e carregado**: ele não carrega sem o **NDI Runtime** (<https://ndi.video/tools/>). Veja o log do OBS (procure `[distroav]`), instale o runtime e **reinicie o OBS**. O botão "Como instalar o DistroAV" abre o guia oficial. |
| O botão "Adicionar fonte NDI" está desabilitado | Sem DistroAV; ou o NDI está desligado no celular (sem `ndiStreamName`); ou a fonte já está na cena atual (o cartão diz qual). |
| A fonte foi criada mas está sem vídeo | Confira em *Propriedades* da fonte (DistroAV) se o nome NDI aparece na lista (celular e PC na mesma rede, NDI ligado, firewall/mDNS liberados). O nome criado é `BDSM (<nome>)`; se o NDI do seu celular anuncia outra máquina, escolha a fonte certa na lista do DistroAV (o tally continua casando pelo "(nome)"). |
| Tally "preso" em PRÉVIA durante um fade | É o esperado: o destino só vira NO AR quando a transição termina (ver "Usar o dock", item 5). |
| "Procurar na rede" não acha nada | O app registra `_bdsm._tcp` por NSD; redes que bloqueiam multicast/mDNS (guest Wi-Fi, alguns roteadores) não funcionam. Digite o IP. |
| Tally piscando | Provavelmente o script Python **também** está carregado. Remova-o. |

## Desenvolvimento

```
obs-plugin-native/
  CMakeLists.txt  CMakePresets.json  buildspec.json   (template oficial adaptado)
  cmake/  .github/scripts/                              (template oficial)
  core/        lógica pura, só std (sem OBS, sem Qt): json, link_state, tally, ndi_link (decisão de
               criar/reutilizar a fonte DistroAV), ws_codec, pairing_proto, mdns, secret_store (DPAPI),
               util (backoff, endpoint)
  tests/       79 testes (mini-framework próprio, sem dependências): casos do Python (tally, LinkState,
               backoff, WebSocket, mDNS, storage) + transição do tally + integração DistroAV (ndi_link)
  src/         camada Qt/OBS: plugin-main, bdsm_dock, device_manager, link_connection,
               mdns_discovery, obs_scene_graph, obs_ndi_integration, tally_controller
  data/locale/ en-US.ini, pt-BR.ini
  installer/   bdsm-link.iss (Inno Setup)
  tools/       run-core-tests.sh
```

Arquitetura: tudo roda na **thread da UI do Qt** (I/O assíncrono: `QTcpSocket`, `QNetworkAccessManager`,
`QUdpSocket`, `QTimer`); nenhuma thread própria. Os callbacks do `obs-frontend-api` só enfileiram um slot
(`Qt::QueuedConnection`); o cálculo do tally lê o grafo de cenas por **nome** e libera toda referência `obs_*`.
Tally recalculado nos eventos de cena/prévia/Modo Estúdio/coleção/lista de cenas/fim de transição e por um timer de
segurança de 1 s (a visibilidade de itens não gera evento).

Rodar os testes da lógica pura **sem OBS e sem Qt** (precisa só de um compilador C++17):

```
# Windows com MSYS2/MinGW (Git Bash):   bash obs-plugin-native/tools/run-core-tests.sh
# ou com CMake (qualquer SO):
cmake --preset tests-only && cmake --build --preset tests-only && ctest --preset tests-only
```

Observação (Git Bash no Windows): se o `.exe` de teste "não rodar" (código 127), é o PATH sem as DLLs do MinGW;
adicione `C:\msys64\ucrt64\bin` ao PATH (o script já tenta).

Compilar o plugin localmente exige Visual Studio 2022 + CMake 3.28+; o CMake baixa OBS/deps/Qt para `.deps/`:
`cmake --preset windows-x64` e `cmake --build --preset windows-x64`.

Criar uma release: `git tag obs-plugin-v1.0.0 && git push origin obs-plugin-v1.0.0` (o workflow publica ZIP e instalador).

### macOS e Linux

Fora do escopo: o `buildspec.json` só tem os hashes do Windows x64 e os presets de macOS/Linux foram removidos.
A lógica (`core/`) é portável e roda os testes no Linux no CI (job `core-tests`). O token no macOS/Linux ficaria
em `bdsm_link.json` com `chmod 600` como **stub claramente marcado** (`plain:` base64, não é criptografia); o ideal
seria Keychain/libsecret. Para suportar: adicionar hashes `macos`/`ubuntu` ao `buildspec.json`, trazer os presets e
scripts do template e trocar o armazenamento do token.

## Pontos de maior risco de erro (revisão estática) e o que o 1º run do CI deve revelar

Conferidos contra os headers do OBS 32.2.2 (assinaturas): `obs_frontend_add_dock_by_id(id, title, void *widget)`,
`obs_frontend_remove_dock` (não usada: o OBS destrói o dock com a janela), `obs_frontend_add/remove_event_callback`,
`obs_frontend_get_current_scene`/`get_current_preview_scene` (referência a liberar com `obs_source_release`),
`obs_frontend_preview_program_mode_active`, `obs_scene_enum_items(scene, bool(*)(obs_scene_t*, obs_sceneitem_t*, void*), void*)`,
`obs_sceneitem_visible/is_group/group_get_scene/get_source`, `obs_enum_sources(bool(*)(void*, obs_source_t*), void*)`,
`obs_source_get_unversioned_id`, `obs_source_get_settings`, `obs_data_get_string`, `obs_module_config_path`,
`OBS_DECLARE_MODULE`/`OBS_MODULE_USE_DEFAULT_LOCALE`. As unidades de tradução que usam só a API do OBS passaram em
`-fsyntax-only` com g++. **Mesmo assim, o que pode falhar:**

1. **Registro do dock no OBS 32** (maior risco funcional): `obs_frontend_add_dock_by_id` é chamado em
   `obs_module_post_load` (não em `obs_module_load`) com um `QWidget*` (o OBS cria o `QDockWidget`). Se o dock não
   aparecer ou o layout salvo não restaurar, teste chamar em `obs_module_load` ou no evento `FINISHED_LOADING`.
   O `id` é fixo (`bdsm-link-dock`) e não pode repetir.
2. **Qt6 do `obs-deps`**: compila-se contra Core/Widgets/**Network** (existem no `qtbase`). `QNetworkRequest::setTransferTimeout`
   está com `std::chrono::milliseconds` (forma atual do Qt 6.11; se o CI reclamar, troque por `int`). AUTOMOC dos
   headers com `Q_OBJECT` (mesmo nome do `.cpp`) deve funcionar; se aparecer "undefined reference to vtable", confira
   `target_sources`/AUTOMOC.
3. **WebSocket manual sobre `QTcpSocket`** (substitui o `QWebSocket`, indisponível): a lógica do protocolo é testada,
   mas o transporte (handshake pelo `connected`/`readyRead`, fechamento, timeouts de 5 s/15 s) só vale com um celular
   real. Atenção ao handshake `Sec-WebSocket-Accept` e ao fechamento `1008` (revogado).
4. **DPAPI** (`CryptProtectData`/`CryptUnprotectData`, `crypt32`): testado no Windows local (round-trip OK com g++/MinGW);
   no MSVC o risco é só de link (`crypt32` já vem de `target_link_libraries(bdsm-core PUBLIC crypt32)`).
5. **Dependências do template com OBS 32.2.2**: o `buildspec.json` foi atualizado à mão (versões/hashes do próprio
   `CMakePresets.json` do OBS 32.2.2). Se o download falhar na verificação SHA-256, confira os hashes; se o passo de
   compilar o libobs falhar, pode ser um desencontro entre os scripts do template (31.x) e o OBS 32.
6. **Avisos do MSVC** (`/W3`): `std::filesystem::u8path`, conversões `size_t` -> `int`. Não são erros enquanto
   `CMAKE_COMPILE_WARNING_AS_ERROR` estiver `false`.
7. **Ciclo de vida no encerramento**: o dock (dono: OBS) guarda `QPointer` para o gerenciador; o gerenciador para tudo
   em `OBS_FRONTEND_EVENT_EXIT`. Se o OBS travar ao fechar, é aqui que se olha.
8. **Instalador**: o runner do GitHub já traz o Inno Setup 6; se não, o workflow instala via Chocolatey. O arquivo de
   idioma `BrazilianPortuguese.isl` precisa existir na instalação do Inno (vem no instalador oficial). O `.iss` agora tem
   uma seção `[Code]` (Pascal Script) com a página de pré-requisitos: **nunca foi compilada**; erros de sintaxe aparecem
   no passo do ISCC. O arquivo é salvo em UTF-8 **com BOM** (necessário para os acentos). O passo "Teste de fumaca do
   instalador" instala (`/VERYSILENT /CURRENTUSER`) numa pasta temporária, confere o layout e desinstala.
9. **Integração DistroAV (API do OBS)**: `obs_source_create("ndi_source", ...)` + `obs_scene_add` só foi validado por
   sintaxe. Dependências de comportamento: (a) o DistroAV registra `ndi_source` **somente** se o NDI Runtime carregar;
   (b) o nome NDI criado é `BDSM (<ndiStreamName>)` — se o celular, na prática, anunciar outra "máquina" (a parte antes dos
   parênteses), a fonte criada ficará sem sinal até o usuário escolher o nome na lista do DistroAV (o tally não é afetado);
   (c) a fonte entra com tamanho/posição padrão; (d) o detector "DistroAV instalado" usa `obs_enum_source_types`, não arquivos.
10. **Tally em transição**: assume-se (conferido no código do OBS 32.2.2, `OBSBasic_Transitions.cpp`) que o OBS troca
    "cena do programa" para o destino **no início** da transição e que o canal de saída 0 é a transição
    (`obs_transition_is_active` + lado A = origem). No Modo Estúdio com "duplicar cenas" a origem é uma cópia privada, não
    resolvível por nome: nesse caso vale a regra normal (sem PREVIEW antecipado). Só o OBS real confirma.

## Checklist de testes manuais no OBS real

- [ ] Instalador (administrador, padrão) cria `C:\ProgramData\obs-studio\plugins\bdsm-link\bin\64bit\bdsm-link.dll` e `data\locale\*.ini`.
- [ ] Instalador com "Instalar somente para mim" cria `%APPDATA%\obs-studio\plugins\bdsm-link\...` sem pedir administrador.
- [ ] Página **Pré-requisitos**: aparece se faltar DistroAV/NDI Runtime ou com o OBS aberto; deixa continuar; links corretos.
- [ ] Desinstalar pelo Windows remove os arquivos (e não apaga o token).
- [ ] O OBS abre sem erro; o log mostra `[bdsm-link] plugin carregado` e `dock registrado`.
- [ ] *Exibir > Painéis (Docks) > BDSM Link* existe; o dock pode ser acoplado/flutuante e **o layout persiste** após reiniciar.
- [ ] Strings em português (e em inglês trocando o idioma do OBS).
- [ ] **Adicionar** IP válido e inválido (mensagem de erro); **Procurar na rede** acha o celular (ou falha sem travar a UI).
- [ ] **Parear:** código de 4 dígitos igual nos dois lados; aprovar conecta; **recusar** mostra "Pedido recusado"; deixar
      expirar mostra "expirou"; clicar de novo logo após recusar trata o 429 (espera e tenta).
- [ ] Reiniciar o OBS: reconecta sozinho **sem** novo pareamento (token DPAPI lido).
- [ ] Telemetria atualiza a ~2 Hz: trocar lente, fonte (Câmera/USB/Sony), FPS e iniciar/parar gravação no celular.
- [ ] **Bateria < 20%** (simule/espere): valor vermelho, aviso no topo, linha no log; só uma vez (histerese 25%).
- [ ] **Adicionar fonte NDI à cena atual** (DistroAV instalado): cria `BDSM - <aparelho>`; o vídeo aparece; clicar de novo
      **não duplica**; em outra cena **reutiliza** a mesma fonte (o cartão lista as cenas); sem DistroAV: botão desabilitado + "Como instalar".
- [ ] Fonte NDI do DistroAV `BDSM (<nome>)` na cena do Programa: celular acende **vermelho**; trocar de cena: apaga.
- [ ] **Transição (fade)** em Modo Estúdio e fora dele: origem fica NO AR até o fim; destino fica PRÉVIA e vira NO AR no fim.
- [ ] Indicador global "x celulares NO AR" confere; fechar o OBS apaga a borda do celular (envia OFF).
- [ ] **Modo Estúdio:** cena em Prévia: **verde**; cortar: vermelho. Cena **aninhada**, **grupo** e item **oculto** (não conta).
- [ ] **Mapeamento manual** da fonte NDI no seletor funciona e persiste após reiniciar.
- [ ] Dois celulares simultâneos com tallys independentes.
- [ ] **Revogar** o acesso no celular: o dock volta a "Não pareado" e (auto) pede novo pareamento.
- [ ] Desligar o Wi-Fi do celular / fechar o app: "Desconectado... reconectando"; ao voltar, reconecta e **reenvia o tally atual**.
- [ ] **Esquecer** e **Remover** apagam o token (o arquivo `bdsm_link.json` não contém o token em texto puro).
- [ ] O token **não** aparece no log do OBS nem na UI.
- [ ] Fechar o OBS sem travar; reabrir.
- [ ] Com o script Python **removido**, confirmar que só o plugin envia tally.

## Licença

GPL-2.0-or-later (o plugin é vinculado ao libobs, GPL-2.0-or-later). Texto em [`LICENSE`](LICENSE).
Estrutura de build derivada do [obs-plugintemplate](https://github.com/obsproject/obs-plugintemplate) (GPL-2.0).
