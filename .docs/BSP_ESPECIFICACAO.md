# BSP — Braga Stream Protocol v2 (especificação, rascunho 1)

Status: **RASCUNHO** (2026-10-07). Documento normativo para o desenvolvimento do BSP como protocolo próprio de transmissão de vídeo e áudio do celular para o computador (OBS), em substituição ao NDI. Tudo que está marcado **[ESTIMATIVA]** é um alvo de projeto, ainda **sem medição**. Tudo que está marcado **[DECISÃO]** pode ser revisto com a primeira medição real.

Palavras-chave **DEVE**, **NÃO DEVE**, **DEVERIA**, **PODE** têm o sentido usual de especificações (RFC 2119).

## 0. Estado atual (2026-10-07): Fase 1 da FONTE implementada; receptor do OBS pendente

**O que existe no app** (módulo `core-media/.../bsp/`, `domain/BspManager.kt`, tela "BSP" em `feature-settings`; ver `ARQUITETURA.md`, seção "BSP v2"):
- O celular é **servidor descobrível** (v2): anuncia `_bsp._tcp` (NSD) com os registros TXT da seção 3 **somente com o BSP habilitado**; escuta o controle TCP (porta 7070; se ocupada, uma porta livre, anunciada no mDNS); autentica por **HELLO com prova HMAC do `Kt`** do pareamento do BDSM Link (4 s para provar, máx. 2 sessões, 20 mensagens/s, linha de até 8 KiB); responde `WELCOME`, espera `READY` e então envia a mídia por UDP para `IP do TCP : udpPort`.
- Vídeo: `MediaCodec` H.264 por `Surface` (CBR, sem B-frames, GOP de 2 s), RTP com STAP-A (SPS+PPS antes de cada IDR), FU-A, payload <= 1200 B. Áudio: AAC-LC 48 kHz estéreo 96 kbps (RFC 3640 AAC-hbr, PT 97) alimentado pelo mesmo PCM do `AudioRecord` e pelo mesmo relógio de captura do vídeo; SR (NTP <-> RTP) a cada 1 s pelo controle. Criptografia **AES-128-GCM** (HKDF-SHA256) com janela anti-repetição; pacing em até 1/3 do intervalo do quadro; DSCP por `setTrafficClass`; fila limitada com descarte de quadros não-IDR.
- Retorno UDP na `feedbackPort` (padrão 7071): **PLI** (IDR, no máximo 1 por 500 ms) e **REPORT** (só alimenta as estatísticas). NACK, FEC e bitrate adaptativo: **Fase 2, não implementados** (há pontos de extensão em `BspFeedbackListener.onNack/onSrEcho`).
- O v1 (cliente TCP que o usuário apontava para um IP) foi **removido**; nunca teve receptor.
- Receptor de teste: `Braga Link/scripts-python/bsp_receiver_sim.py` (Python). **Não há ainda receptor no plugin do OBS.**

**Ciclo de vida (inalterado):** o BSP NÃO segura câmera nem microfone. Os quadros (e o PCM) só existem enquanto a sessão de captura estiver de pé: Monitor visível ou REC, como no NDI. Com o BSP habilitado e o Monitor fechado o serviço fica no ar (anúncio, controle, sessões), mas não há vídeo; os receptores recebem `STOP {reason:"monitor_closed"}` e, ao abrir o Monitor, `START` e um IDR.

**NÃO verificado em aparelho** (ver o relatório da implementação): transmissão ponta a ponta com um receptor autenticado (exige o token do pareamento), latência, consumo, perda real em Wi-Fi.

### 0.1 Esclarecimentos e desvios desta implementação (nada foi mudado em silêncio)

A especificação deixa pontos em aberto ou inconsistentes; as escolhas abaixo valem para a fonte e DEVEM ser seguidas pelo receptor:

1. **Prova do HELLO.** O exemplo de 4.1 diz `HMAC-SHA256(Kt, nonceR||"BSP-HELLO")`, mas o texto normativo diz `HMAC-SHA256(Kt, nonceR || "BSP-HELLO")` com `Kt = SHA-256(token)`. Vale o texto normativo. `nonceR` são os **16 bytes decodificados** (não o texto base64). `Kt` = SHA-256 dos bytes UTF-8 do texto do token (64 caracteres hexadecimais).
2. **`sessionId` no HKDF.** `info = "BSP v2 key" || sessionId` com o `sessionId` em **4 bytes big-endian**. O HKDF-Expand produz 20 bytes: os 16 primeiros são a chave AES-128 `K` (idêntica ao pedido de só 16 bytes) e os 4 últimos o `salt32` do nonce GCM. Nonce = `salt32 || ssrc (4, BE) || índice32 (4, BE)`, com `índice32 = rollover*65536 + seq` estimado como na RFC 3711 (3.3.1); o primeiro pacote autenticado define o rollover 0.
3. **Prova da fonte (extensão).** O WELCOME traz `auth.proof = HMAC-SHA256(Kt, nonceR || nonceS || "BSP-WELCOME")` (campo opcional, 12): o receptor pode conferir que a fonte conhece `Kt`.
4. **Retorno binário, campo `len`** (6.1): não tinha largura definida; é `u16` little-endian com o **tamanho do corpo** em bytes (sem os 6 do cabeçalho `'B','F',versão,tipo,len`). REPORT tem corpo de 16 bytes, NACK 8, PLI 4.
5. **SR (6.1)** pelo controle, JSON: `{"type":"SR","ntpSec":N,"ntpFrac":N,"video":{"ssrc":N,"rtp":N,"packets":N,"octets":N},"audio":{...}}`; `ntpSec/ntpFrac` = NTP de 64 bits (segundos desde 1900 e fração de 2^-32 s) e `rtp` = o timestamp RTP que o fluxo teria nesse instante.
6. **HEARTBEAT.** A fonte envia `HEARTBEAT {id,ts}` a cada 1 s e mede o RTT no `HEARTBEAT_ACK`; também responde ao `HEARTBEAT` do receptor com um ACK que ecoa `id` e `ts`. Queda após 5 s sem **nenhuma** mensagem do receptor.
7. **START/STOP** valem nos dois sentidos: a fonte avisa a pausa/retomada (`STOP {reason:"monitor_closed"}` / `START`); o receptor pode enviar `STOP`/`START` para pausar/retomar a mídia só para ele.
8. **Anúncio.** O TXT só diz o que existe: `vc=h264` e `ac=aac` (HEVC e Opus são previstos, não implementados); `st=idle|live` muda quando entra/sai receptor pronto (re-registro do NSD).
9. **WELCOME na Fase 1:** `net.nack=false`, `net.fecK=0`; `video.sps/pps` são **omitidos** se o encoder ainda não os produziu (sem quadros, p.ex. Monitor fechado). O receptor os obtém no fluxo: STAP-A antes de cada IDR (5.2).
10. **Mídia sem criptografia.** `caps.aead=false` é recusado com `ERROR FORMAT` salvo se o usuário ligar "Permitir sem criptografia" nos detalhes da tela BSP (padrão desligado). Motivo: o HELLO só prova `nonceR`; sem esta trava, quem repetisse um HELLO capturado e pedisse `aead=false` receberia vídeo em claro.
11. **Sessões.** SSRC de vídeo/áudio e o espaço de `seq` são os mesmos para todas as sessões (um por execução); cada sessão tem **chave própria**, então não há reuso de nonce. Um cliente que reconecta **substitui** a sessão antiga dele (a queda pode não ter sido percebida), em vez de ocupar uma segunda vaga. No máximo 4 conexões TCP abertas ao mesmo tempo (2 sessões + 2 em autenticação).
12. **Pacing.** O primeiro pacote do quadro sai na hora e os demais se distribuem linearmente em 1/3 do intervalo do quadro (fps); fila de 1024 pacotes de vídeo; se o mais antigo atrasar mais de 2 quadros, descartam-se os quadros não-IDR em espera e pede-se um IDR. Vídeo e áudio usam **dois sockets** (DSCP AF41 e EF) com portas de origem diferentes e o mesmo destino `udpPort`.
13. **Relógio A/V.** Os timestamps RTP de vídeo (90 kHz) e áudio (48 kHz) saem do mesmo eixo de captura (`System.nanoTime`); o PTS do codec de vídeo é convertido se vier do relógio BOOTTIME. O atraso de priming do encoder AAC (~21 ms) **não é compensado** (medir na Fase 2).
14. **Quando há quadros.** A regra da spec ("só com o Monitor visível") é herdada do ciclo de vida do app: há quadros enquanto a sessão de captura estiver viva (Monitor, REC, ou o preview de Ajustes/LUT, que abre a câmera mas nunca o microfone). O áudio só existe com Monitor visível ou REC.
15. **Bitrate (Fase 1, fixo):** 10 Mbps em 1080p, proporcional à área, limitado a 4 a 25 Mbps. O teto "escolhido pelo usuário" e o controlador adaptativo são da Fase 2.
16. **v1 removido** (12 pedia mantê-lo atrás de um interruptor até o v2 ter receptor): custo de manter dois controles > benefício, pois o v1 nunca teve receptor e usava outro papel (cliente).
17. **Retorno sem autenticação** (limite honesto): só se aceitam datagramas de IPs de sessões prontas; PLI é limitado a 1 por 500 ms. **Repetição de HELLO:** a prova cobre só `nonceR`; repetir um HELLO capturado abre uma sessão cuja mídia é cifrada com chave derivada de `nonceS` novo (ilegível para o atacante) mas gasta uma vaga e banda. Mitigação sugerida para a Fase 2: desafio do servidor antes da prova (nonce da fonte primeiro).
18. **Tela:** o módulo "BSP" fica em Ajustes > Módulos (placement `MORE`), fora da barra e do Início, enquanto o receptor do OBS não existe.

## 1. Objetivos e não objetivos

Objetivos:
1. Substituir o NDI no caso de uso "celular → OBS" na mesma rede local, com **bitrate de 6 a 20 Mbps** em 1080p (contra mais de 100 Mbps do NDI completo) **[ESTIMATIVA]**.
2. **Baixo consumo no celular:** entrada por `Surface` direto no codificador de hardware, sem cópia pela CPU; um único thread de envio; sem timers frequentes.
3. **Bom comportamento em Wi-Fi:** tolerar perda em rajada e variação de capacidade com retransmissão seletiva (NACK), FEC leve e bitrate adaptativo.
4. **Latência ponta a ponta de 60 a 150 ms** com jitter buffer pequeno **[ESTIMATIVA]**, configurável (modo baixa latência x modo estável).
5. **Fonte descobrível** na rede (mDNS) e **autenticada** (reuso do pareamento do BDSM Link).
6. Áudio sincronizado (AAC-LC por padrão, Opus opcional).

Não objetivos (v2.0): internet/NAT traversal; vários receptores simultâneos além de 2; multicast; HDR 10 bits; DRM; interoperar com receptores genéricos (isso fica para uma saída de compatibilidade RTSP/SRT na Fase 3).

## 2. Arquitetura

Papéis:
- **Fonte (Source):** o celular com o app BDSM. É SERVIDOR: anuncia-se por mDNS, aceita conexão de controle TCP e envia a mídia por UDP.
- **Receptor (Receiver):** o plugin do OBS (Braga Link) ou, no futuro, outro cliente. É CLIENTE: descobre a fonte, conecta, recebe e decodifica.

Planos:
| Plano | Transporte | Conteúdo |
|---|---|---|
| Descoberta | mDNS/DNS-SD (UDP 5353) | anúncio `_bsp._tcp` |
| Controle | TCP, uma conexão por receptor, JSON de uma linha | sessão, parâmetros, estatísticas lentas, metadados |
| Mídia | UDP, fonte → receptor | vídeo, áudio, FEC |
| Retorno | UDP, receptor → fonte (porta de retorno da fonte) | NACK, relatórios, pedido de keyframe |

Fluxo resumido: descobrir → conectar controle → autenticar (HELLO/WELCOME) → receptor abre a porta UDP e confirma (READY) → fonte inicia a mídia → retorno contínuo → BYE ou queda.

## 3. Descoberta (mDNS)

- Serviço `_bsp._tcp.local.`, nome de instância = nome de exibição do aparelho (o mesmo de `NdiNaming`, p.ex. `BDSM (Scorpio)`), porta de controle (padrão 7070, pode mudar).
- Registros TXT (todos opcionais para o receptor, obrigatórios para a fonte publicar): `v=2`, `id=<uuid do aparelho>`, `name=<nome>`, `vc=h264,hevc` (vídeo suportado), `ac=aac,opus`, `res=1920x1080@30` (modo atual), `auth=link` (exige pareamento), `link=8080` (porta do BDSM Link), `st=idle|live`.
- A fonte só se anuncia **enquanto o BSP estiver habilitado**; sai da rede (retira o anúncio) ao desabilitar.
- Alternativa manual: `IP:porta` informado pelo usuário. O dock mostra apenas o nome da fonte, nunca o IP (mesma regra do NDI).

## 4. Canal de controle

TCP na porta anunciada. Mensagens: um objeto JSON por linha (`\n`), UTF-8, tamanho máximo **8 KiB** por linha (acima disso: erro e fechamento). Todo objeto tem `type`. Campos desconhecidos DEVEM ser ignorados (extensível).

### 4.1 Sessão e autenticação
`HELLO` (receptor → fonte):
```json
{"type":"HELLO","protocolVersion":2,"clientId":"obs-<uuid>","clientName":"OBS (PC)","nonce":"<16 bytes b64>",
 "caps":{"video":["h264","hevc"],"audio":["aac","opus"],"nack":true,"fec":true,"aead":true},
 "udpPort":50123,"auth":{"scheme":"link-token","proof":"<b64 HMAC-SHA256(Kt, nonceR||'BSP-HELLO')>"}}
```
- A autenticação reutiliza o **token de pareamento do BDSM Link** (o receptor já o tem, protegido por DPAPI no plugin). A fonte guarda **apenas `SHA-256(token)`** (é assim que o `LinkAuthManager` armazena), então a chave comum é `Kt = SHA-256(token)`: o receptor a calcula do token, a fonte a lê do armazenamento do cliente pareado. `proof = HMAC-SHA256(Kt, nonceR || "BSP-HELLO")`. O token **NÃO DEVE** trafegar nem ser registrado em log; `Kt` também não. A fonte localiza o cliente pelo `clientId` do HELLO e compara a prova em tempo constante.
- Sem `proof` válido: `ERROR {code:"AUTH"}` e fechamento (uma conexão sem autenticar vive no máximo 4 s). Máximo de **2 sessões** simultâneas; a terceira recebe `ERROR {code:"BUSY"}`.
- Pareamento inicial continua sendo o do Link (duplo consentimento no celular e no OBS). O BSP não cria outro fluxo de pareamento.

`WELCOME` (fonte → receptor), escolha final dos parâmetros:
```json
{"type":"WELCOME","protocolVersion":2,"sessionId":305419896,"deviceName":"Scorpio","nonce":"<16 bytes b64>",
 "video":{"codec":"h264","width":1920,"height":1080,"fps":30,"bitrateKbps":10000,"ssrc":1111,"pt":96,"sps":"<b64>","pps":"<b64>","orientation":0},
 "audio":{"codec":"aac","rate":48000,"channels":2,"ssrc":2222,"pt":97,"config":"<b64 AudioSpecificConfig>"},
 "net":{"mtu":1200,"latencyMs":80,"fecK":0,"nack":true,"feedbackPort":7071,"aead":true},
 "keyframeIntervalMs":2000}
```
`READY` (receptor → fonte): `{"type":"READY","udpPort":50123}` — a fonte começa a enviar para `IP_do_receptor:udpPort` (IP observado na conexão TCP).
Outras: `START`/`STOP` (a fonte pode pausar sem encerrar a sessão, p.ex. Monitor fechado), `BYE`, `ERROR {code,message}`, `HEARTBEAT {id,ts}` / `HEARTBEAT_ACK {id,ts}` (1 por segundo; queda após 5 s sem resposta; RTT = tempo até o ACK).

### 4.2 Metadados (opcionais)
- Fonte → receptor `META`: `{"type":"META","orientation":90,"lens":"1x","battery":73,"rec":false,"thermal":0}` quando mudam (no máximo 2 por segundo).
- Receptor → fonte `TALLY`: `{"type":"TALLY","state":"PROGRAM|PREVIEW|OFF"}`. O tally principal continua pelo BDSM Link; este é um atalho para uso sem o Link.
- Mudança de formato (resolução, codec, orientação com quadro novo): `FORMAT` (mesmos campos de `video`/`audio` do WELCOME) + novo SPS/PPS; o receptor DEVE reinicializar o decodificador.

### 4.3 Códigos de erro
`AUTH`, `BUSY`, `VERSION` (versão não suportada; a mensagem lista as suportadas), `CODEC` (sem codec em comum), `FORMAT`, `INTERNAL`, `RATE` (receptor excedeu o limite de mensagens).

## 5. Transporte de mídia (UDP)

### 5.1 Pacote
Cabeçalho **RTP padrão (RFC 3550), 12 bytes**, para que ferramentas existentes (Wireshark, FFmpeg com SDP) possam inspecionar: V=2, P=0, X=0, CC=0, M (último pacote do quadro), PT dinâmico, `seq` 16 bits por SSRC, `timestamp` (vídeo 90 kHz; áudio = taxa de amostragem), SSRC (vídeo e áudio distintos). Em seguida o payload.

- Tamanho máximo do datagrama UDP: **1252 bytes** (12 de RTP + 1200 de payload + 16 da tag AEAD, quando houver) para evitar fragmentação IP no Wi-Fi.
- Sem extensão de cabeçalho no v2.0.
- Pacotes de vídeo e áudio usam portas de destino iguais (`udpPort`) e SSRC diferentes (demultiplexação por SSRC).

### 5.2 Empacotamento
- **H.264:** RFC 6184, modo não entrelaçado (packetization-mode=1): NAL único, **STAP-A** para SPS+PPS, **FU-A** para NAL grandes. SPS/PPS DEVEM ser reenviados antes de cada IDR (a fonte já faz com `PREPEND_HEADER_TO_SYNC_FRAMES`).
- **HEVC:** RFC 7798 (NAL único, AP, FU). VPS/SPS/PPS antes de cada IRAP.
- **Áudio AAC-LC:** RFC 3640, modo `AAC-hbr` (`sizeLength=13; indexLength=3; indexDeltaLength=3`), 1024 amostras por quadro, 1 quadro por pacote. O `AudioSpecificConfig` vai no WELCOME.
- **Áudio Opus (opcional):** RFC 7587, quadros de 20 ms. Só se ambos declararem `opus` e o aparelho tiver codificador Opus por hardware/sistema.
- **FEC:** payload type próprio (ver 6.2).

### 5.3 Criptografia (AEAD)
- Quando `aead=true` (padrão se houver pareamento): payload cifrado com **AES-128-GCM**; o cabeçalho RTP (12 bytes) é o **AAD**; a tag de 16 bytes vai ao final.
- Chave por sessão: `K = HKDF-SHA256(ikm = Kt, salt = nonceR || nonceS, info = "BSP v2 key" || sessionId)` → 16 bytes. Nonce GCM de 12 bytes: `salt32(4 bytes derivados do HKDF) || ssrc(4) || seq(4 = rollover*65536 + seq)`.
- Proteção contra repetição: janela deslizante de 1024 pacotes por SSRC; rollover de 32 bits derivado do `seq` crescente.
- A criptografia é **opcional** e pode ser desligada (`aead=false`) para depuração; sem ela, a autenticação do controle continua exigida.
- Limites honestos: não há sigilo futuro (forward secrecy), pois a chave deriva de `Kt` compartilhado (e quem ler o armazenamento do celular conhece `Kt`); o modelo de ameaça é a LAN doméstica/de estúdio.

### 5.4 Envio espaçado (pacing) e prioridade
- Os pacotes de um quadro NÃO DEVEM sair em rajada única: distribuir ao longo de no máximo **1/3 do intervalo do quadro** (p.ex. ~11 ms a 30 fps).
- `DatagramSocket.setTrafficClass` com DSCP **AF41** (vídeo interativo) e **EF** (áudio); o efeito depende do roteador e do WMM do ponto de acesso **[não garantido]**.
- Buffer de envio limitado: se o envio travar por mais de 2 quadros, descartar quadros não-IDR antigos e pedir IDR (a fila NÃO DEVE crescer sem limite).

## 6. Recuperação de perda

### 6.1 Retorno (receptor → fonte, UDP para `feedbackPort`)
Pacote binário little-endian (cabeçalho comum `magic 'B','F'`, `version 2`, `type`, `len`):
| type | nome | corpo |
|---|---|---|
| 1 | REPORT | `ts_ms u32`, `highestSeqV u16`, `lossFracV u8 (0-255)`, `jitterMs u16`, `delayGradUs i16` (tendência do atraso de chegada), `bufferMs u16`, `recvKbps u16`, `lossFracA u8` (a cada **250 ms**) |
| 2 | NACK | `ssrc u32`, `baseSeq u16`, `bitmask u16` (RTCP Generic NACK) |
| 3 | PLI | `ssrc u32` (pede IDR) |
| 4 | SR_ECHO | eco do relatório de remetente da fonte para medir RTT/sincronismo |

A fonte envia, a cada 1 s, um **SR** no controle ou na própria mídia (mapeamento `NTP ↔ timestamp RTP` de vídeo e de áudio) para o receptor sincronizar A/V.

### 6.2 NACK (retransmissão seletiva)
- A fonte mantém um **anel de retransmissão** com os últimos **250 ms** de pacotes de vídeo (≈ 300 pacotes a 10 Mbps) e de áudio.
- Retransmissão = o MESMO datagrama (mesmo `seq`, mesmo AEAD) reenviado. Duplicatas são descartadas pelo receptor.
- O receptor DEVE enviar NACK **somente se o pacote ainda pode ser usado**: `tempo restante até o instante de reprodução > RTT/2 + 5 ms`. No máximo **2 NACK por pacote**, intervalo mínimo de **RTT + 10 ms**, e no máximo **200 pacotes/s** de NACK.
- A fonte limita retransmissões a **15% do bitrate** de saída (excedido: ignora NACK e pede IDR ao encoder se a perda persistir).

### 6.3 FEC (paridade XOR)
- Grupos de **K pacotes de vídeo consecutivos** (padrão **K=10**, faixa 4–16, ajustável pela fonte; 0 = desligado) geram **1 pacote de paridade** (XOR dos payloads, tamanhos normalizados) com PT próprio (98). Recupera **1 perda por grupo**; sobrecarga 1/K.
- Cabeçalho do pacote FEC (no início do payload): `baseSeq u16`, `K u8`, `lengthRecovery u16`, `tsRecovery u32`, `mRecovery u8`.
- Perdas em rajada: opcional **entrelaçamento de profundidade D=2** (dois grupos intercalados). **[DECISÃO]** validar com medição.
- A fonte liga o FEC **somente** quando a perda recente passa de **1%** e desliga após 10 s abaixo de 0,5% (para não gastar banda e bateria à toa).

### 6.4 Quadro-chave (IDR)
- Intervalo padrão de **2 s** (`keyframeIntervalMs`).
- `PLI` do receptor força IDR, no máximo **1 por 500 ms** (limite na fonte). O receptor só pede PLI quando um quadro não pôde ser reconstruído e o próximo IDR agendado está a mais de 300 ms.
- Intra-refresh periódico (sem IDR grande) **PODE** ser usado no futuro se o codificador suportar.

## 7. Adaptação de bitrate (na fonte)

Entrada: REPORT a cada 250 ms (perda, jitter, tendência de atraso, bufferMs), RTT do heartbeat. Saída: `MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)`.

Algoritmo (constantes iniciais, a calibrar **[DECISÃO]**):
1. `piso = 2 Mbps` (1080p) e `teto = bitrate escolhido pelo usuário` (padrão 10 Mbps).
2. **Redução multiplicativa:** se `lossFrac > 5%` **ou** `delayGrad > 0` por 2 relatórios seguidos **ou** `bufferMs` do receptor < 20 ms: `bitrate = max(piso, 0,85 × bitrate)`.
3. **Aumento:** se 12 relatórios seguidos (3 s) sem perda e sem tendência de atraso: `bitrate = min(teto, 1,05 × bitrate)`.
4. Mudanças no máximo **1 por segundo**; ao reduzir mais de 30% em 5 s, enviar um IDR.
5. **Estado térmico:** se `PowerManager.currentThermalStatus ≥ MODERATE`, reduz o teto em 30%; `≥ SEVERE`: 50%, e avisa o receptor via `META`.
6. Resolução/fps adaptativos (reduzir o quadro) ficam para depois da v2.0.

## 8. Áudio e sincronismo
- Padrão **AAC-LC 48 kHz estéreo, 96 kbps** (codificador do sistema, universal). **Opus** quando disponível e negociado.
- A fonte captura o áudio do `AudioRecord` (já existe, com seleção de dispositivo) e usa o mesmo relógio de tempo do vídeo (PTS em nanossegundos monotônicos → timestamps RTP).
- Receptor: alinha vídeo e áudio pelo SR (NTP↔RTP) e reproduz com **um atraso de reprodução comum** (o `latencyMs` do WELCOME ou o do jitter buffer adaptativo, o maior).

## 9. Receptor (requisitos mínimos)
- **Jitter buffer** adaptativo: alvo `max(minDelay, 2 × jitter + 5 ms)` limitado a [40, 250] ms. Presets: *baixa latência* (mín. 40 ms), *equilibrado* (80 ms, padrão), *estável* (150 ms).
- Reordenação: janela de **64 pacotes**; pacote atrasado além do instante de reprodução é descartado.
- Decodificação: libavcodec (H.264/HEVC por software ou aceleração do sistema) e AAC/Opus; troca de SPS/PPS ou `FORMAT` reinicializa o decodificador sem fechar a sessão.
- Entrega ao OBS: como **fonte assíncrona de vídeo e áudio** (`obs_source_output_video/audio`) com timestamps derivados do relógio da sessão; sem quadros por mais de 2 s: mostra "sem sinal" e tenta reconectar com recuo exponencial (1 s a 15 s).
- A fonte NÃO DEVE reiniciar a sessão ao girar o aparelho: a regra de orientação do app (quadro fixo com barras, `OutputOrientation`) vale também para o BSP; mudanças que alterem o tamanho do quadro usam `FORMAT`.

## 10. Segurança (resumo)
- Controle só com autenticação por prova HMAC do token de pareamento; 4 s para autenticar; até 2 sessões; limite de 20 mensagens/s por conexão.
- Mídia criptografada por padrão com AEAD; janela anti-repetição.
- O servidor NÃO DEVE responder a nenhuma mensagem de controle antes de autenticar, exceto `ERROR`.
- Entradas externas (JSON, binário de retorno, RTP) DEVEM passar por parsers com limites de tamanho e testes de fuzz.
- Registro (log) NUNCA contém token, chave ou IP completo do receptor.

## 11. Consumo e CPU (diretrizes para a fonte)
- Fluxo de vídeo: câmera → GL → `Surface` do `MediaCodec`; empacotamento em buffers reutilizados (sem alocação por pacote); 1 thread de envio; 1 thread de retorno.
- `WifiLock` em modo de alto desempenho **apenas** enquanto houver transmissão ativa; liberar ao parar.
- Estatísticas e relatórios a cada 250 ms em uma corrotina única; sem `Handler` por pacote.
- Prioridade: HEVC reduz a banda (menos tempo de rádio) à custa de um pouco mais de energia no codificador; **comparar por medição** (`dumpsys batterystats`, `dumpsys thermalservice`) antes de tornar HEVC o padrão.
- Metas **[ESTIMATIVA]**: ≤ 10% a mais de CPU do app do que a gravação 1080p30; bateria equivalente a uma gravação 1080p30 em HEVC.

## 12. Compatibilidade e versões
- `protocolVersion=2`. A fonte DEVE rejeitar `HELLO` de outra versão com `ERROR {code:"VERSION", supported:[2]}`.
- O v1 (push, cliente TCP) foi removido do código (ver 0.1, item 16).
- Extensões futuras entram como campos opcionais em `caps`/`WELCOME` e novos `type` (ignorados por quem não os conhece).

## 13. Métricas e plano de testes
Medições obrigatórias antes de declarar a Fase 2 concluída:
1. **Latência ponta a ponta** (vidro a vidro): cronômetro/LED na cena filmada, captura de tela no OBS; repetir em 5 GHz e 2,4 GHz, parado e andando pela casa.
2. **Perda e recuperação:** emular perda (1%, 3%, 10%, rajadas de 5 pacotes) com um emulador de rede no PC ou no roteador; medir quadros decodificados corretamente com NACK desligado, NACK, NACK + FEC.
3. **Adaptação:** degradar o canal (afastar do roteador, saturar com outro download) e registrar o tempo para reduzir o bitrate e voltar.
4. **Consumo:** `dumpsys batterystats --reset`, 30 min de transmissão 1080p30, comparar com a gravação; temperatura.
5. **Estabilidade:** 2 h contínuas; reconexão após queda do Wi-Fi; rotação do aparelho durante a transmissão.
6. **Segurança:** conexão sem prova, prova errada, repetição de pacote, JSON malformado (fuzz).
Testes automáticos: empacotador/desempacotador (H.264, HEVC, AAC) com vetores, FEC (recuperação de 1 perda por grupo em todas as posições), anel de retransmissão, política de NACK por prazo, controlador de bitrate (série sintética de relatórios), parser de controle e de retorno (fuzz), anti-repetição AEAD.

## 14. Fases e entregas
**Fase 1 — fazer funcionar** (FONTE implementada em 2026-10-07; receptor do OBS pendente)
- Fonte (app): servidor de controle (substitui o cliente v1), anúncio mDNS, autenticação por token do Link, WELCOME/READY, vídeo H.264 em RTP (reaproveitando o empacotador), áudio AAC, STAP-A para SPS/PPS, AEAD, tela do módulo "BSP" (ligar/desligar, estado, receptores conectados) no mesmo estilo da tela NDI.
- Receptor (OBS): fonte "BDSM (BSP)" no plugin Braga Link: descoberta, autenticação com o token já pareado, jitter buffer, decodificação (libavcodec), áudio, sincronismo, "sem sinal"/reconexão, botão no dock para criar a fonte na cena atual (como o botão da fonte NDI) e indicador de estado.
- Testes unitários dos blocos puros; teste de integração com um receptor simulado (Python) na pasta `scripts-python/` do Braga Link.
**Fase 2 — otimizar o Wi-Fi:** retorno binário, NACK, FEC, bitrate adaptativo, pacing, DSCP, estado térmico, HEVC opcional, métricas na tela, medições da seção 13.
**Fase 3 — compatibilidade (opcional):** saída RTSP ou SRT a partir do mesmo codificador para players sem o plugin.

## 15. Decisões em aberto
1. Opus por padrão em vez de AAC? (depende do codificador Opus nos aparelhos-alvo; padrão AAC-LC até medir.)
2. Entrelaçamento do FEC (D=2) vale o atraso extra? (medir.)
3. HEVC como padrão? (medir bateria e decodificação no PC; OBS/FFmpeg decodificam HEVC.)
4. Janela de retransmissão (250 ms) e limite de 15% do bitrate (calibrar.)
5. Porta de controle fixa (7070) ou dinâmica anunciada pelo mDNS? (v2.0: fixa por padrão, anunciada no mDNS.)
6. Receptor também como app de desktop independente do OBS? (fora do escopo até a Fase 3.)
