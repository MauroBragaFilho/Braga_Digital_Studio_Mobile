# Elgato Game Capture HD60 S (não-UVC) no Android

A HD60 S original (USB `0FD9:004F/005E/0074/0076`) **não é UVC**: não aparece como webcam e não tem
driver de Linux/Android. A HD60 S+ (`0FD9:006A`) é UVC e segue pela trilha normal (`UVCCamera`).

## Estado atual

| Parte | Situação |
|---|---|
| Acesso USB cru por usbfs (`UsbDevice`) e anel de URBs bulk (`BulkStream`) | pronto |
| Parser do fluxo da EP 0x83 (`StreamParser`: linhas YUY2, campos, áudio embutido) | pronto, com teste de host |
| Conversor YUY2 → RGBA (BT.709/BT.601, limitado/full) | pronto, com teste de host |
| Sessão (`Session`): thread de controle, liga/desliga do transporte, renderização na `Surface` | pronta |
| Integração em `UvcCaptureDevice` (VID/PID, status `NO_SIGNAL`/`FRONT_END_MISSING`) | pronta |
| Front-end **revisões 1-3** (MStar MST3367; PID 004F/005E/0074): arranque a frio, init do chip, detecção de modo, portão de vídeo | portado do driver Linux; **arranque e detecção validados na HD60 S 005E** (ver abaixo) |
| Front-end **revisão 4** (ITE IT6802E; PID 0076) | não implementado (`FRONT_END_MISSING`) |
| Saída HDMI loop-through (transmissor IT66121), áudio, ajustes de imagem | fora do escopo (no-ops) |

## Licença (decisão registrada em 2026-10-19)

O front-end das revisões 1-3 é derivado do driver Linux
[`dougg3/hd60s-linux-driver`](https://github.com/dougg3/hd60s-linux-driver), **GPL-2.0**
(Copyright (c) 2026 Doug Brown). O dono do app optou por seguir com esse código mesmo assim. Os
arquivos derivados, todos em `core-capture/src/main/cpp/`, levam o cabeçalho GPL e continuam sob a
GPL-2.0 (texto em `LICENSE-HD60S-GPL2.txt`):

- `Hd60sPortTypes.h`, `Hd60sPortModes.cpp`, `Hd60sPortMstar.cpp` (trechos copiados quase sem alteração)
- `Hd60sMstarFrontEnd.cpp` (plano de controle e arranque a frio, baseado em `hd60s-core.c`)

O restante (usbfs, parser, conversor, sessão, JNI, integração Kotlin) é código próprio do app.
**Risco em aberto:** distribuir o app com esse código ligado pode obrigar a publicar o fonte do app sob
GPL-2.0 (o `LICENSE` do repositório é proprietário). Resolver isso (aconselhamento jurídico ou
reescrita independente) antes de qualquer distribuição pública, inclusive Google Play.

## Como o front-end foi portado

`Hd60sPortTypes.h` é uma camada de compatibilidade (tipos do kernel, macros `dev_*`, locks vazios, um
`hd60s_dev` mínimo) que permite compilar as funções do driver quase literalmente; o resto é reescrito.
Foram deixados como no-ops: transmissor HDMI loop-through, conversor de áudio auxiliar (`0xD4`
continua sendo inicializado), HDCP downstream. Para atualizar contra uma versão nova do driver, repetir a
extração por nome das funções (lista em `names_mstar.txt`/`names_modes.txt` na sessão que fez o porte:
`mstar_*`, `hd60s_find_mode`, `hd60s_mstar_decode`, `hd60s_csc_matrix` etc.).

## Transporte (independente de revisão)

- Interface 0, EP `0x83`. Em **USB 2.0** só existem os alts 0-2, todos **isócronos** (alt 1 = 1024 B, alt 2 = 3072 B por microframe, ≈ 24,5 MB/s). O **alt 4 (bulk)** só existe em USB 3. Os alts são lidos dos descritores (`Hd60sDescriptors`) e escolhidos pela taxa de pixels (`pickTransport`).
- Anel de URBs (`UrbRing`): isócrono com 32 pacotes por URB; se o kernel responder `ENOMEM` (kmalloc contíguo grande), reduz sozinho para 16/8/... pacotes (no A51 ficou em 8 pacotes × 64 URBs). Bulk: 16 URBs de 32 KB. Ordem de subida: portão de vídeo → espera ~105 ms → `SET_INTERFACE alt 4` → URBs.
  Descida: `SET_INTERFACE alt 0` com os URBs vivos → cancela URBs → espera ~35 ms → desliga o portão.
- Nunca intercalar transferências de controle com a troca de alt-setting (a FX3 trava).
- A placa não escala: a resolução entregue é a da fonte HDMI; mudou a fonte, a sessão derruba e
  reabre o transporte com a nova geometria.

## Limite de banda (importante)

O vídeo é YUY2 sem compressão: 1080p60 ≈ 250 MB/s, 720p60 ≈ 110 MB/s. Em USB 2.0 (≈ 35-40 MB/s úteis
em bulk) só cabem modos pequenos (ex. 480i). 1080p60 exige porta USB 3.x de verdade no celular. O
Galaxy A51 usado nos testes é USB 2.0.

## Resultados no hardware (Galaxy A51, USB 2.0, HD60 S `0FD9:005E`, 2026-10-19)

- Arranque a frio OK: `0xEC` (STALL tolerado), MCU responde, firmware do MCU 2017-03-17.
- Detecção OK: fonte 1920x1080p59.94, YCbCr 4:2:2, áudio 48 kHz.
- Transporte isócrono OK: ~24,5 MB/s contínuos, 0 erros de USB, pacotes com pixels YUY2 válidos.
- **Imagem completa não é possível em 1080p60/1080i neste aparelho**: o fluxo (~250 MB/s; 1080i ~124 MB/s)
  chega truncado (só ~24,5 MB/s) e o parser não fecha quadros. Para esse caso existe o **modo tolerante**
  (`StreamParser::setTolerant`, ligado pela sessão quando o sinal excede a banda do transporte): publica
  quadros incompletos e a tela mostra uma faixa com fragmentos reais da cena. É só diagnóstico; provou o
  caminho USB -> parser -> conversor -> Surface -> tela, sustentado por >30 s sem erro de USB.
- Falta validar imagem **completa**: fonte 480i/576i (cabe em USB 2.0) ou celular com USB 3 (alt bulk 4).
- Lição: o usbfs aloca o buffer a cada `SUBMITURB`; no A51 isso dá `ENOMEM` esporádico. URBs perdidos
  secavam o anel e travavam o fluxo. Hoje: URBs isócronos de 8 pacotes x 64 e fila de pendentes que reenvia.

## Testar com a placa

Precisa da placa ligada ao **celular** (cabo OTG), com `adb` por Wi-Fi para liberar a porta USB.
Logs úteis: `adb logcat -s Hd60s Hd60sCapture` (descritores USB, velocidade, etapa do arranque, geometria
detectada). Se o arranque falhar, o motivo aparece como `Fault` no snapshot e no Monitor.

## Testes

- Host (parser e conversor): `core-capture/src/main/cpp/tests/Hd60sHostTest.cpp`
  (`g++ -std=c++17 -I.. Hd60sHostTest.cpp ../Hd60sParser.cpp ../Hd60sConvert.cpp`).
- JVM: `Hd60sStateTest` (IDs, snapshot, mapeamento de estado), `UsbSourceMessagesTest`.
- Windows: o `PATH` não pode conter aspas soltas (ex. `...\Tailscale"`), senão a JVM de teste do
  Gradle morre com "Could not find or load main class VS". Rodar `gradlew --stop` após corrigir.
