# Análise do Caminho do Sinal da Câmera e Correção de Rotação

A causa do problema de rotação persistente (a imagem permanecer "deitada" igual antes) foi totalmente desvendada analisando a arquitetura do sensor da câmera e o Pipeline do Vulkan.

## O Caminho do Sinal (Signal Path)

1. **Hardware (Sensor do S20 FE)**: O sensor de câmera de smartphones é montado fisicamente na vertical (Retrato / Orientação 90º). 
2. **Camera2 API (`Camera2Device.kt`)**: Nós solicitamos um buffer de `1920x1080` (Paisagem) para o `ImageReader`. A câmera entrega esse buffer, **mas ela não rotaciona a imagem no hardware**. Ela simplesmente despeja o que o sensor capturou deitado.
3. **Vulkan (`shader.vert`)**: Na minha última alteração, eu apliquei uma rotação de 90º CW (Horário). Como a imagem já estava deitada (90º CW fisicamente), adicionar mais 90º resultou em 180º (A imagem continuou deitada, só que de cabeça para baixo). Isso explica porque, visualmente, parecia "não ter mudado nada".

## O Paradoxo do Aspect Ratio

Se rotacionarmos a imagem corretamente (90º CCW) para ela ficar "em pé" (Upright), nós teremos uma imagem vertical (`1080x1920`) sendo exibida em uma tela horizontal (`1920x1080`).

Para que a imagem preencha a sua tela deitada (16:9) sem ficar achatada ou gorda, teremos que aplicar um **Crop (Corte)** na imagem da câmera. Ou seja, a GPU dará um "zoom" para preencher as laterais, cortando o teto e o chão do sensor. É matematicamente a única forma de transformar um buffer vertical num monitor horizontal preenchido, mantendo os pixels quadrados.

## Proposta de Correção (O que vou fazer a seguir)

1. **Rotação CCW Exata**: Alterar o `shader.vert` para `fragTexCoord = vec2(1.0 - outTexCoord.y, outTexCoord.x);`. Isso desfaz a inclinação nativa do sensor do S20 FE.
2. **Correção de Aspecto (Crop)**: Inserir a matemática de compensação de geometria (`pos.y *= 1.777`). Isso fará com que a imagem vertical ganhe zoom e preencha o 16:9 da sua tela em paisagem sem nenhuma distorção geométrica.

**Por favor, clique em "Proceed" se aprova a implementação deste Crop/Rotação.**
