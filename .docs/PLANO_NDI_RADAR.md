# Plano: NDI "Ver na rede" (descoberta, radar e prévia ao vivo)

Status: IMPLEMENTADO (2026-10-03), etapas 1, 2 e 3. Ver "Resultado da implementação" no fim do arquivo. Mudança de UX do usuário aplicada: o radar fica DIRETO na tela NDI (sem botão "Ver na rede" nem tela separada); só o preview imersivo é uma rota própria.

## Objetivo
Na tela NDI, uma área "Ver na rede" que:
1. lista as fontes NDI visíveis na rede (celulares BDSM, OBS, vMix, câmeras NDI);
2. mostra uma **visão de radar/globo** das fontes, sem o aparelho do usuário no centro;
3. ao tocar numa fonte, abre uma **prévia ao vivo** em baixa qualidade (teto de 720p).

A tela principal da NDI continua simples (ligar/desligar a transmissão). "Ver na rede" é uma segunda camada, aberta por botão/folha inferior.

## O que o código já tem (verificado)
- Render por GPU: motor GLES 3.0 em `core-media/src/main/cpp/GlesEngine.cpp`.
- Codificação por hardware: `MediaCodec` com `COLOR_FormatSurface` em `RecordManager.kt` e `BspManager.kt`.
- Envio NDI: `NdiEngine.cpp` lê os pixels do GL por PBO assíncrono (`glReadPixels`) e chama `NDIlib_send_send_video_v2`. A compressão do NDI completo é feita pelo SDK na CPU.
- Não há recebimento NDI nem descoberta: o motor só envia.
- Permissão de multicast já declarada (`CHANGE_WIFI_MULTICAST_STATE`).

## Etapas
### Etapa 1 — Descoberta e lista (baixo custo)
- JNI novo em `NdiEngine.cpp`: `NDIlib_find_create_v2` (+ `NDIlib_find_wait_for_sources`/`get_current_sources`), thread própria, JNI `nativeFindStart/Stop/GetSources`.
- Kotlin: `NdiDiscovery` (core-media) expõe `StateFlow<List<NdiSource>>` com `name`, `urlAddress` (IP:porta), `type` inferido (BDSM se o nome começa com "BDSM ("; OBS/vMix por padrão do nome; senão genérico), `isSelf` (nossa fonte, via `NdiNaming`).
- Só roda enquanto a tela "Ver na rede" estiver aberta (start/stop pelo ciclo de vida) e mantém o `MulticastLock` apenas nesse período.
- Estados: Loading ("Procurando na rede..."), Vazio ("Nenhuma fonte encontrada" + dica sobre Wi-Fi com isolamento/sub-rede), Lista, Erro.

### Etapa 2 — Radar/globo (visual)
- Composable `NdiRadar` em Canvas, sem o aparelho do usuário no centro: um globo/círculo com anéis, que gira devagar. **Cada fonte é só uma bolinha ou uma seta estilo nave** (formato e cor indicam estado: no ar / ocioso / este aparelho). **Sem ícones e sem miniaturas** no radar. Lista simples como alternativa (alternar por segmento "Radar | Lista"; acessibilidade: a lista é a versão TalkBack).
- Ao tocar numa bolinha/nave: cartão flutuante (folha inferior curta) com **apenas o nome do dispositivo** (NUNCA o IP) e dois botões: **"Ver dispositivo"** (detalhes: tipo, estado, proximidade aproximada) e **"Abrir preview"** (etapa 3). O IP continua só no objeto interno para conectar, nunca exibido.
- **Distância/proximidade — limitação importante:** o NDI NÃO informa a força do sinal nem a posição. O sinal Wi-Fi que o Android mede (RSSI) é entre o nosso aparelho e o roteador, não entre nós e a outra fonte. Logo, "mais perto = sinal mais forte" não pode ser lido de forma confiável.
  - Proxy possível: latência de ida e volta (TCP connect/ping ao IP da fonte), em 3 faixas (Perto / Médio / Longe), com suavização (mediana de várias amostras) e rótulo "proximidade aproximada na rede". Isso mede distância de rede, não física: um PC por cabo ao mesmo roteador parece "perto" mesmo estando longe.
  - Alternativa exata, só para celular BDSM ↔ celular BDSM: Wi-Fi Aware (RTT a peers) ou BLE (RSSI de anúncio). Exige as duas pontas com o app novo; fica como pesquisa futura, fora do escopo inicial.
  - Decisão para a v1: anéis por latência (3 faixas) + opção "ordenar por tipo". Mostrar aviso discreto "posição aproximada". Se o resultado em teste real não for convincente, o radar vira só agrupamento por tipo/estado.

### Etapa 3 — Prévia ao vivo (alto custo)
- JNI de recebimento: `NDIlib_recv_create_v3` com `bandwidth = lowest` (proxy de baixa resolução) para a miniatura e `highest` apenas ao abrir em tela cheia, com **teto de 720p** na exibição; `NDIlib_recv_capture_v2` em thread dedicada; formato de cor `UYVY_BGRA`/BGRX conforme o SDK.
- Exibição: copiar o quadro para uma textura GL (upload por PBO) e desenhar em `SurfaceView`/`TextureView`; sem converter para Bitmap por quadro (evita GC).
- **Tela do preview (decisão do usuário):** "Abrir preview" abre uma tela cheia imersiva, como o Monitor, mas só com a imagem recebida: sem HUD, sem tiles, sem dock, sem indicadores, nada sobreposto, **exceto o nome do dispositivo no canto inferior** (texto pequeno, discreto, com leve sombra/fundo translúcido para contraste, só para indicar que é um preview de NDI; nunca o IP). **Além do nome, o único elemento é o botão de voltar para a tela inicial** (pequeno, canto superior, translúcido, alvo >=48dp, com `contentDescription`). Gesto/botão voltar do sistema também sai. Mantém a tela acesa (`keepScreenOn`) e esconde as barras do sistema (imersivo); estados de carregando/erro ("Sem sinal", com tentar de novo) aparecem centralizados e discretos só quando não há imagem. Imagem em letterbox (proporção original, fundo preto), sem pan/zoom na v1.
- Ciclo de vida: abre só enquanto o usuário vê a prévia, fecha ao sair/escurecer a tela/rodar em segundo plano; 1 prévia por vez.
- Aviso de consumo (CPU/bateria) e limite térmico: se `PowerManager` thermal status ≥ MODERATE, reduzir para proxy.
- Pendente de conferir: termos de redistribuição do SDK NDI para recebimento no app; formatos compactados (NDI|HX) no SDK de Android.

## GPU: render, codificação e decodificação
- Render: já é GPU (GLES). Ganhos: manter as texturas externas (`GL_TEXTURE_EXTERNAL_OES`) e evitar cópias CPU; o PBO assíncrono já mitiga stalls.
- Codificação: gravação e BSP já usam `MediaCodec` por Surface (hardware). Pontos a medir/otimizar: perfil e nível, `KEY_LATENCY`/`KEY_PRIORITY`, `KEY_OPERATING_RATE`, B-frames desligados no BSP, bitrate por CBR/VBR.
- NDI de envio: a leitura de pixels (`glReadPixels`) e a compressão do NDI completo são CPU. Alternativa por hardware: **NDI|HX** (H.264/HEVC) usa codec do aparelho, mas depende de licença/SDK avançado e de o receptor suportar HX (OBS com DistroAV suporta). Avaliar: custo de licença, compatibilidade, qualidade. Não implementar sem decisão do usuário.
- Decodificação: hoje não há. No recebimento NDI completo (SpeedHQ) o SDK decodifica na CPU, não existe caminho de GPU no SDK padrão. Decodificação por GPU (`MediaCodec` decoder para Surface) só se aplica a fluxos H.264/HEVC (NDI|HX ou BSP/RTSP). Para a prévia, o plano é decodificar pelo SDK em baixa resolução e subir a textura na GPU.

## Vulkan em vez de OpenGL ES?
Decisão atual: NÃO migrar. Vulkan reduz sobrecarga de driver e melhora multithread, mas o BDSM tem um pipeline simples (uma passada em tela cheia + LUT 3D) em que o gargalo medido não é o driver: é a GPU do A51 presa em ~260 MHz e a leitura de pixels para o NDI. Migrar exigiria reescrever o motor (shaders para SPIR-V, amostragem de textura externa da câmera via `AHardwareBuffer` + conversão YCbCr, integração com a Surface do `MediaCodec` e com o preview), com risco alto de regressões em gravação/NDI/BSP e pouco ganho esperado. Reavaliar só se um perfil (Perfetto/simpleperf) mostrar tempo relevante em driver GL. Ganhos mais baratos: manter o caminho `OES`/`AHardwareBuffer` sem cópias CPU, reduzir passadas e leituras, ajustar prioridades do `MediaCodec`.

## 720p para a prévia?
Sim, como TETO. Para a miniatura/radar, a menor banda do NDI (proxy) basta. Ao abrir uma fonte, 720p é um bom limite no A51: nítido o suficiente para conferir enquadramento e menos pesado que 1080p. Fazer teste de CPU/temperatura no A51 (SpeedHQ 720p30) antes de fixar o padrão; 1080p fica fora.

## Testes e verificação
- Unitários: classificação de tipo por nome, ordenação, faixas de latência, estados.
- No aparelho: A51 + S20 FE como fontes uma da outra (ambos BDSM), OBS no PC com DistroAV; medir CPU (`top`/`dumpsys cpuinfo`), temperatura e jank com a prévia aberta; teste com Wi-Fi 5 GHz e 2,4 GHz.
- Riscos: rede com isolamento, multicast bloqueado, muitas fontes (limitar a 24 na tela), vazamento de threads nativas (start/stop equilibrados).

## Resultado da implementação (2026-10-03)

### O que foi feito
- **Etapa 1 (descoberta)**: `NdiNetwork.cpp` (NDIlib_find_create_v2 / wait_for_sources / get_current_sources; JNI `NdiNative`), `NdiDiscovery` (StateFlow `NdiDiscoveryState`: IDLE/SEARCHING/READY/ERROR, sondagem de proximidade por TCP connect com mediana de 3 amostras e suavização de 5 medições). Liga com a tela NDI visível (`LifecycleStartEffect`) e só então mantém o `MulticastLock`.
- **Etapa 2 (radar)**: `NdiRadar` (Canvas, globo com meridianos girando 1 volta em 3 min, 3 anéis; bolinha cheia = disponível, bolinha vazada = sem resposta, seta estilo nave = este aparelho; sem ícones, nomes ou miniaturas). Radar | Lista | ordenar (proximidade/tipo) só com ícones na tela NDI; a lista é a versão TalkBack; limite de 24 fontes; toque abre cartão curto (só o nome) com "Ver dispositivo" (tipo, estado, proximidade aproximada) e "Abrir preview". A animação lê o estado só na fase de desenho e pausa fora de STARTED.
- **Etapa 3 (preview)**: `NdiReceiver` (NDIlib_recv_create_v3, formato RGBX_RGBA, thread nativa dedicada, cópia direta para `ANativeWindow` da `SurfaceView`, sem GL/PBO/Bitmap; teto de 1280x720 com redução por vizinho mais próximo; banda highest, ou lowest com térmico >= MODERATE) e `NdiPreviewScreen` (imersivo, `keepScreenOn`, letterbox, botão "Voltar à tela inicial" translúcido de 48 dp, nome no canto inferior em área segura, estados Carregando/Sem sinal/Erro centrais, `BackHandler`, receptor fechado ao sair/escurecer/segundo plano, 1 por vez). Rota `ndi_preview/{name}` (nome NDI codificado, nunca IP).

### Limites e decisões
- NDI não informa se a fonte está "no ar" ou "ociosa": a fonte existir já significa transmissor criado. "Sem resposta" vem da sonda de rede (não é estado do NDI). Proximidade é distância de rede por latência (Perto <= 8 ms, Médio <= 25 ms, Longe acima), nunca física.
- O SDK mostra as fontes da própria máquina como `LOCALHOST (nome)`; a detecção de "este aparelho" aceita `BDSM (nome)` e `LOCALHOST (nome)`. A prévia da própria transmissão não é oferecida.
- Licença: os arquivos `libndi_licenses.txt` e `libndi_bonjour_license.txt` (em `core-media/src/main/jniLibs/<abi>/`) só citam as licenças online "NDI SDK" (ndi.link/ndisdk_license) e "NDI Advanced SDK" e as licenças de terceiros (RapidJSON, SpeexDSP, RapidXML, CxxUrl, mDNSResponder); os termos de redistribuição/recebimento NÃO estão no repositório e não foram verificados. Conferir a licença online antes de publicar.
- A biblioteca Android inclusa exporta `NDIlib_find_*`, `NDIlib_recv_*` (create_v3, capture_v2/v3, free_video_v2, bandwidth lowest/highest) e os formatos RGBX_RGBA/BGRX_BGRA/UYVY; o recebimento NDI|HX não foi avaliado.
- Não verificado: teste de rede com Wi-Fi 2,4 GHz/5 GHz, OBS/DistroAV, medição de CPU/temperatura/jank com imagem real (a única fonte remota, o S20 FE, enviava quadros pretos e saiu da rede durante os testes).
