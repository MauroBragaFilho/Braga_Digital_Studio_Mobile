# Publicação no Google Play — guia e rascunhos

Status: RASCUNHO (2026-10-04). Os textos abaixo são pontos de partida: **confira cada regra e limite no Play Console no momento do envio**, porque as políticas mudam. Itens marcados [CONFIRMAR] eu não consegui verificar nesta sessão.

## 1. Identidade do app
- Pacote (applicationId): `io.github.maurobragafilho.bdsm` (registrado na verificação de desenvolvedor do Android). Não muda depois de publicado.
- Nome exibido: `Braga Digital Studio Mobile` (27 caracteres; o limite do Play é 30).
- Versão atual: `1.0.0` (versionCode 10000, derivado do `baseVersionName`: major*10000 + minor*100 + patch). **Cada envio ao Play precisa de versionCode maior**: suba o `baseVersionName` em `app/build.gradle.kts` (ex.: 1.0.1 → 10001) ou use `-PforceReleaseVersion=1.0.1`.
- Certificado de envio (SHA-256): `9E:00:38:11:BD:A9:E5:40:C0:1C:43:14:2C:D4:A8:8E:08:48:34:D6:0A:42:ED:B0:34:38:00:4D:77:7E:A1:AF`.

## 2. Como gerar o pacote (.aab)
```
./gradlew.bat bundleRelease
```
- Saída: `app/build/outputs/bundle/release/app-release.aab`. A assinatura usa o keystore de `local.properties` (storeFile, storePassword, keyAlias, keyPassword). Sem keystore o build cai na chave de debug: **nunca envie assim**. Em CI, com tag, o build falha sem keystore (gate de assinatura).
- ABIs: arm64-v8a e armeabi-v7a (padrão). O Play gera APKs por ABI a partir do .aab.
- 16 KB: as bibliotecas nativas são compiladas com NDK 27 (páginas de 16 KB). Confirmar com a ferramenta de verificação de alinhamento do Play/`zipalign -c -P 16` antes do envio [CONFIRMAR no aab real].
- Backup do keystore: `C:\Users\mauri\keystores\bdsm-release.jks` + senha de `local.properties` em local seguro. Ative o **Play App Signing** (o Play guarda a chave definitiva; a sua vira chave de envio, substituível).

## 3. Conta e etapas no Play Console
1. Criar a conta de desenvolvedor do Play (taxa única, US$ 25 [CONFIRMAR valor atual]). Não achei isenção para estudantes. A conta de "distribuição limitada" para estudantes/hobbistas distribui por fora da loja (até 20 aparelhos) e não publica no Play.
2. Criar o app: nome, idioma padrão (pt-BR), app gratuito, declarações de política.
3. Ficha da loja (seção 5), política de privacidade em **URL pública** (seção 7).
4. Conteúdo do app: classificação indicativa, público-alvo, anúncios (não há), segurança dos dados (seção 6), declaração de serviços em primeiro plano (seção 4), acesso a permissões sensíveis.
5. Testes: começar por **teste interno** (até 100 testadores por e-mail). Contas pessoais novas costumam precisar de teste fechado com um número mínimo de testadores por um período antes da produção [CONFIRMAR número e prazo no Console].
6. Enviar o .aab, preencher notas da versão, revisar e publicar.

## 4. Permissões e justificativas (de `app/src/main/AndroidManifest.xml`)
| Permissão | Para que serve no app |
|---|---|
| CAMERA | Capturar vídeo da câmera do celular (monitor, gravação, NDI). |
| RECORD_AUDIO | Capturar áudio do microfone junto com o vídeo. |
| INTERNET, ACCESS_NETWORK_STATE | Transmissão NDI/BSP e servidor local BDSM Link na rede do usuário; detectar a rede ativa. |
| ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, CHANGE_NETWORK_STATE | Descoberta de dispositivos NDI na rede (mDNS/multicast) e uso da rede Wi-Fi da câmera Sony sem derrubar a internet. |
| ACCESS_FINE/COARSE_LOCATION (até API 32) | Android 8–12 exige localização para ler o nome (SSID) da rede Wi-Fi da câmera Sony. Não é usada para localização física nem enviada. |
| NEARBY_WIFI_DEVICES (neverForLocation) | Mesmo propósito no Android 13+, sem exigir localização. |
| FOREGROUND_SERVICE + FOREGROUND_SERVICE_CAMERA/MICROPHONE | Manter câmera e microfone vivos durante gravação/transmissão com a tela apagada ou o app em segundo plano (CaptureForegroundService). |
| FOREGROUND_SERVICE_DATA_SYNC | Servidor local BDSM Link (LinkServerService) servindo mídia e estado ao OBS/outros dispositivos na rede. |
| POST_NOTIFICATIONS | Notificação obrigatória dos serviços e aviso de pedido de pareamento. |
| WAKE_LOCK | Evitar suspensão da CPU durante gravação/transmissão. |
| BLUETOOTH_CONNECT, MODIFY_AUDIO_SETTINGS | Usar microfone Bluetooth (SCO) quando o usuário escolher. |
- Removidas do manifesto final: MANAGE/READ/WRITE_EXTERNAL_STORAGE (herdadas de bibliotecas). O app grava em pastas próprias e MediaStore.
- **Declaração de serviços em primeiro plano** (Play Console): tipos `camera`, `microphone` e `dataSync`. Prepare, para cada um, a descrição de uso e, se pedido, um vídeo curto mostrando o fluxo (gravar com a tela apagada; servidor Link ativo com notificação visível). Observação técnica: `dataSync` tem limite de tempo no Android 15; avaliar tipo mais adequado antes do envio.
- `targetSdk` atual = 35. O Play costuma exigir o nível mais recente para novos envios (hoje 36) [CONFIRMAR]. Subir para 36 muda comportamentos (ex.: bordas, back preditivo) e exige teste completo no aparelho; não foi feito.
- Tráfego HTTP em texto claro: só `192.168.122.1` (câmera Sony Camera Remote API), configurado em `network_security_config.xml`.

## 5. Ficha da loja (pt-BR) — rascunho
- **Nome (≤30):** Braga Digital Studio Mobile
- **Descrição curta (≤80):** Monitor de câmera profissional com gravação e transmissão NDI no seu celular.
- **Descrição completa (≤4000):**
  Transforme o celular em um monitor e câmera de produção.

  O Braga Digital Studio Mobile (BDSM) usa a câmera do celular, uma câmera HDMI-USB ou uma câmera Sony por Wi-Fi como monitor de produção, com ferramentas de exposição e foco, gravação local e transmissão pela sua rede.

  Principais recursos
  • Monitor com ferramentas profissionais: zebra, foco com destaque (peaking), cores falsas, scopes e grade.
  • Aplicação de LUTs em tempo real, com intensidade ajustável.
  • Gravação local em H.264 ou H.265 com áudio, que continua mesmo ao girar o aparelho.
  • Transmissão NDI para OBS, vMix e outros programas compatíveis, com nome simples: "BDSM (nome do aparelho)".
  • Radar de rede para descobrir outras fontes NDI e abrir uma prévia ao vivo.
  • BDSM Link: pareie com o OBS (confirmação nos dois lados), receba telemetria como bateria e estado, e use o tally (programa e prévia) direto no monitor.
  • Galeria de gravações com compartilhamento, renomear e exclusão com desfazer.
  • Tema claro e escuro; interface simples, pensada para uso com uma mão.

  Privacidade
  O BDSM não envia seus vídeos, áudios ou dados para servidores do desenvolvedor. A transmissão e o pareamento acontecem apenas na sua rede local, e só com a sua ação.

  Observação: NDI é marca da Vizrt Group. Este app não é afiliado nem endossado pela Vizrt, OBS Project, Sony ou Samsung.
- **Categoria sugerida:** Fotografia ou Ferramentas de vídeo (Play: "Video Players & Editors"/"Photography") [CONFIRMAR lista atual].
- **Ativos gráficos:** ícone 512x512 em `branding/play-store-icon-512.png` (pronto); imagem de destaque 1024x500 (a fazer); capturas de tela de telefone (a fazer, no A51/S20: Home, Monitor, NDI com radar, Mídia, Ajustes; sem dados pessoais).
- **Notas da versão 1.0.0:** Primeira versão: monitor profissional, gravação H.264/H.265, transmissão NDI, radar de rede com prévia, pareamento com OBS e temas claro e escuro.

## 6. Segurança dos dados (rascunho de respostas)
Base técnica verificada na revisão desta sessão: o app não inclui SDKs de análise, anúncios nem relatório de falhas, e não há servidor do desenvolvedor. Dados ficam no aparelho (Room/DataStore, arquivos de vídeo) ou trafegam na rede local por ação do usuário (NDI, BDSM Link com token por pareamento, câmera Sony).
- Coleta de dados: o Play define "coletar" como enviar dados para fora do aparelho (para você ou terceiros). Transmissão NDI/Link para dispositivos do próprio usuário na rede local normalmente não conta, mas **decida e responda conforme o texto do formulário** [CONFIRMAR].
- Compartilhamento com terceiros: nenhum.
- Criptografia em trânsito: o BDSM Link usa HTTP/WebSocket na rede local, protegido por token de pareamento (sem TLS) [dizer isso com honestidade; avaliar TLS no futuro]. NDI não é criptografado por padrão.
- Exclusão de dados: o usuário apaga gravações e LUTs no app; desinstalar remove os dados do app.
- Dashboard web do Link: a página servida pelo celular carrega fontes do Google Fonts no navegador do cliente (não pelo app). Considere embutir a fonte para evitar essa requisição externa.
- Permissões de localização (até API 32): usadas só para ler o SSID da câmera Sony; não coletadas.

## 7. Política de privacidade — rascunho (publicar em URL pública)
Sugestão de hospedagem gratuita: GitHub Pages (`https://maurobragafilho.github.io/…`) ou o próprio repositório. Texto base:

> **Política de Privacidade — Braga Digital Studio Mobile**
> Última atualização: [data].
> **Quem somos.** O Braga Digital Studio Mobile ("BDSM") é um aplicativo desenvolvido por [nome do desenvolvedor]. Contato: [e-mail de contato].
> **Dados que o aplicativo usa.** O BDSM acessa câmera e microfone para capturar vídeo e áudio, e a rede local para transmitir e parear dispositivos. Gravações e LUTs ficam no seu aparelho.
> **O que não fazemos.** Não enviamos vídeo, áudio, imagens ou dados pessoais para servidores do desenvolvedor, não usamos anúncios e não vendemos ou compartilhamos dados com terceiros.
> **Rede local.** Ao ligar o NDI ou o BDSM Link, o aparelho pode ser visto por dispositivos da mesma rede. O pareamento exige confirmação nos dois aparelhos e usa um token guardado de forma protegida; você pode revogar dispositivos a qualquer momento nas configurações.
> **Permissões.** Câmera, microfone, notificações, rede e (em Androids antigos) localização apenas para ler o nome da rede Wi-Fi da câmera Sony. Você pode revogar cada permissão nas configurações do sistema.
> **Crianças.** O app não é direcionado a menores de 13 anos.
> **Mudanças e contato.** Podemos atualizar esta política; a versão atual fica sempre nesta página. Dúvidas: [e-mail de contato].

## 8. Pendências antes de publicar
1. Ler a licença online do SDK NDI (recebimento e redistribuição no app; incluir `libndi_licenses.txt` nas licenças). Sem isso, a publicação tem risco jurídico.
2. Criar a política de privacidade pública e preencher [nome] e [e-mail].
3. Revisar `targetSdk` (36?) e o tipo de serviço `dataSync` (limite no Android 15).
4. Gerar imagem de destaque e capturas; escolher categoria.
5. Backup do keystore e senha.
6. Testar o .aab real (instalar via bundletool no A51 e S20) e verificar alinhamento de 16 KB.
7. Rodar testes de ponta a ponta no release (REC com rotação, NDI, Link/OBS, preview) e as etapas de One UI pendentes de validação no aparelho.

## 9. Licença do SDK NDI (lida em 2026-10-04) — RISCO PARA PUBLICAR
Fontes lidas: [NDI Technology License Agreement (nov/2024)](https://downloads.ndi.tv/SDK/NDI_SDK/NDI%20License%20Agreement.pdf) e [NDI Embedded SDK License Agreement](https://downloads.ndi.tv/SDK/NDI_SDK/NDI%20Advanced%20License%20Agreement.pdf) (são os destinos de `ndi.link/ndisdk_license` e `ndi.link/ndisdk_embedded_license`, citados nos `libndi_licenses.txt` do app). Isto é uma leitura técnica, **não é parecer jurídico**.
- **Licença padrão (royalty-free):** permite distribuir o código objeto do SDK junto do produto, mas define "Products" como software em sistemas de uso geral (servidores, desktops, notebooks). A definição **exclui expressamente** produtos em dispositivos/sistemas "embarcados", e cita **Android, Linux, iOS** na lista. Pela letra, um app Android não é um "Product" coberto pela licença padrão.
- **Licença Embedded/Advanced:** o SDK em si é gratuito, mas diz que é preciso **um acordo de licença separado com a NDI** para explorar comercialmente ou distribuir produtos que usem/embutam o software NDI ("NDI Embedded Products").
- **Exigências comuns às duas:** manter avisos de copyright da NDI; distribuir sob termos que proíbam engenharia reversa e modificação do SDK e isentem a NDI de garantias/responsabilidade; usar marcas NDI só para indicar compatibilidade, com a nota de que são marcas da NDI, sem sugerir patrocínio; usar versão do SDK com menos de 30 dias para um lançamento de produto, se houver; manter compatibilidade NDI completa; a NDI pode testar o produto e encerrar a licença.
- **Receber vídeo (NDIlib_recv):** é um "Specific SDK" listado (NDI Receive); não vi vedação específica, mas vale a mesma pergunta de licença.
- **Conclusão prática:** antes de publicar no Play (ou distribuir o APK amplamente), **confirmar com a NDI** (portal de desenvolvedores/contato do SDK em ndi.video) se um app Android que usa `libndi.so` precisa de um acordo separado e em que condições. O app já traz `libndi_licenses.txt`; garantir que o texto de licenças dentro do app (tela Licenças) inclui a atribuição "NDI® é marca registrada da Vizrt Group" e o aviso de copyright. Sem essa confirmação, o risco jurídico/de recusa na publicação permanece.
- Alternativa de contingência: publicar uma variante sem o módulo NDI (apenas monitor/gravação/BSP/Link) até a confirmação.
