# BDSM Link para OBS Studio (script Python)

Script que roda **dentro do OBS** e liga o OBS aos celulares com o app BDSM:

- **Pareia** com cada celular (consentimento duplo: você aprova no celular conferindo um código de 4 dígitos).
- **Mostra a telemetria** de cada celular: bateria (alerta abaixo de 20%), lente, fonte, FPS, REC, nome do NDI.
- **Envia o tally** (`PROGRAM` / `PREVIEW` / `OFF`) para o celular conforme as cenas do OBS (a borda do monitor do BDSM acende em vermelho/verde).

Usa **apenas a biblioteca padrão do Python** (nada de `pip`). O celular fala com o OBS pela porta 8080 (HTTP/WebSocket, protocolo da seção 5 de `.docs/BDSM_PLUGIN_OBS.md`).

> Status: testado contra um **celular simulado** (`tests/fake_phone.py`) e com um `obspython` falso. **Ainda não foi testado no OBS real nem em aparelho real.**

## Requisitos

- OBS Studio 28 ou superior (desenvolvido para a 32.x) no Windows 11 (também deve funcionar em macOS/Linux; só o Windows foi exercitado).
- **Python configurado no OBS**: *Ferramentas > Scripts > aba "Configurações do Python"* apontando para uma instalação de Python 3 de 64 bits. O OBS 32 documenta suporte até Python 3.12/3.13; **use o Python 3.12 ou 3.13**. O Python 3.14 que está no PC de desenvolvimento serve para rodar os testes, mas não é garantido para o OBS (verifique a mensagem "Python carregado" na tela de Scripts). Na máquina de desenvolvimento o OBS ainda não tem Python configurado (`global.ini` sem seção `[Python]`).
- Celular e PC na mesma rede (Wi-Fi/Ethernet), app BDSM com o **BDSM Link ligado** (padrão).
- Para o tally por nome automático: o plugin **DistroAV/NDI** no OBS (fontes do tipo `ndi_source`).

## Instalação

1. Instale um Python 3.12/3.13 (python.org) se o OBS ainda não tiver um.
2. No OBS: *Ferramentas > Scripts > Configurações do Python* e escolha a pasta de instalação do Python. Deve aparecer "Python carregado".
3. Em *Ferramentas > Scripts*, clique em **+** e escolha `obs-plugin\bdsm_link_obs.py`. Mantenha a pasta `bdsm_link\` **ao lado** do arquivo (o script a encontra sozinho).
4. Os dados do script (clientId e tokens protegidos) ficam em `%APPDATA%\obs-studio\plugin_config\bdsm_link\bdsm_link.json`. No Windows o token é protegido com DPAPI (só o seu usuário no seu PC lê). O token nunca é escrito no log.

## Uso

1. Selecione o script. Em **Celulares (IP[:porta])** clique em **+** e digite o IP do celular (ex.: `192.168.0.20`; porta padrão 8080) — ou clique em **Procurar na rede (mDNS)** e depois em *Atualizar status*.
2. No grupo do celular, clique em **Parear**. O estado muda para *Aguardando aprovação no celular - código 1234*. O mesmo código aparece no **log do script** (*Ajuda > Arquivos de log > Ver log atual*, linhas `[BDSM Link]`).
3. No celular aparece o pedido com o **mesmo código**. Confira e **aprove**. O estado passa para *Conectado* e a telemetria aparece (clique em *Atualizar status* para redesenhar a tela; o OBS não atualiza propriedades sozinho).
4. **Mapear a fonte NDI**: por padrão o script casa sozinho a fonte `BDSM (<nome do NDI do celular>)`. Se a sua fonte NDI tiver outro nome, escolha a fonte no dropdown **Fonte NDI do OBS** do celular (o mapeamento fica salvo).
5. **Esquecer** apaga o token local. Para revogar também no celular, use a lista de clientes pareados do app.

Opção *Reparear automaticamente (401)*: se o celular revogar o acesso, o script apaga o token e envia um novo pedido (você ainda precisa aprovar no celular). Desmarque para exigir clique em *Parear*.

## Como funciona o tally

A cada troca de cena, prévia, Modo Estúdio ou coleção (eventos do OBS) — e por segurança a cada ~2 s — o script percorre as cenas:

- **PROGRAM**: o celular está em alguma fonte **visível** da cena de programa (inclui cenas aninhadas e grupos).
- **PREVIEW**: mesma regra na cena de prévia — só existe com o **Modo Estúdio ligado**.
- **OFF**: nenhum dos dois. Precedência: PROGRAM > PREVIEW > OFF.

O casamento é feito entre a fonte NDI do OBS (`ndi_source`, config `ndi_source_name`, ex.: `BDSM (Galaxy A51)`) e `BDSM (<ndiStreamName>)` do celular, sem diferenciar maiúsculas e ignorando espaços repetidos; também aceita outra "MÁQUINA" antes de `(nome)`. Com mapeamento manual, vale o nome da fonte do OBS escolhida. O `TALLY_UPDATE` só é enviado **quando muda** e **ao (re)conectar**. Itens ocultos não contam; filtros e cenas dentro de fontes de navegador não são analisados.

## Solução de problemas

| Sintoma | O que fazer |
| --- | --- |
| "Sem conexão com IP:8080" | Celular e PC na mesma rede? App aberto/serviço do Link ligado? Firewall/roteador com "isolamento de clientes"? |
| `429` ao parear | Já há um pedido pendente ou você recusou há pouco; o script tenta de novo sozinho (6, 12, 24 s). Aguarde. |
| "Pedido recusado" / "expirou (90 s)" | Clique em *Parear* de novo e aprove dentro de 90 s. |
| Fica "Não pareado" depois de conectar | O celular revogou o acesso (401). Pareie de novo. |
| mDNS não acha o celular | Redes com mDNS bloqueado; digite o IP manualmente (o app mostra o IP nas configurações do Link). |
| Tally não acende | O celular está com o NDI ligado? O nome da fonte NDI do OBS bate com `BDSM (<nome>)`? Senão use o dropdown de mapeamento. Tally de PREVIEW exige Modo Estúdio. |
| Scripts mostra erro de Python | Veja *Ajuda > Arquivos de log*; confirme a versão do Python (3.12/3.13) e que `bdsm_link\` está ao lado do script. |

## Estrutura

```
obs-plugin/
  bdsm_link_obs.py        # camada do OBS (obspython): UI, eventos, timer, SceneGraph real
  bdsm_link/              # Python puro, sem obspython
    websocket.py          # cliente WebSocket RFC 6455 (mascarado, ping/pong, fragmentação)
    pairing.py            # pareamento HTTP (request/status, 400/413/429)
    storage.py            # clientId + tokens (DPAPI no Windows)
    mdns.py               # descoberta _bdsm._tcp por UDP multicast
    state.py              # LinkState
    tally.py              # SceneGraph, motor de tally, alerta de bateria
    manager.py            # uma thread de rede por celular + fila de eventos
  tests/                  # unittest + fake_phone.py (celular simulado)
```

Threads de rede **não chamam a API do OBS**: enviam eventos por uma fila; o timer de 500 ms do OBS os processa.

## Rodando os testes

Na raiz do repositório (Python 3.9+; não usa pip nem o OBS):

```
py -3 -m unittest discover -s obs-plugin/tests -v
```

Os testes cobrem pareamento (aprovado, recusado, expirado, 429, 400/413), reconexão, 401→reparear, persistência/DPAPI, WebSocket, mDNS, tally (cena simples, aninhada, grupo, Modo Estúdio, dois celulares, mapeamento), bateria e um *smoke* do script com `obspython` falso.
