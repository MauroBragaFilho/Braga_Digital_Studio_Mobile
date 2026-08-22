# Braga Studio Mobile

## Arquitetura

- `:app` hospeda a estrutura principal da aplicação, incluindo o ciclo de inicialização (Splash) e a navegação.
- `:common` contém componentes de interface compartilhados, rotas de navegação e modelos de domínio reutilizáveis.
- `:core` reúne a camada de persistência, configurações via DataStore e os contratos do banco de dados Room.
- `:core-capture` define a abstração de dispositivos de captura (`CaptureDevice`) e sua implementação utilizando a Camera2 API.
- `:core-media` implementa o **MediaGraph**, responsável pelo roteamento do fluxo de mídia dentro da aplicação.
- `:feature-home`, `:feature-preview` e `:feature-settings` implementam as primeiras funcionalidades disponíveis ao usuário.

O projeto segue os seguintes princípios arquiteturais:

- Clean Architecture
- MVVM
- Repository Pattern
- Use Cases para regras de negócio
- StateFlow e Coroutines para gerenciamento de estado
- Hilt para Injeção de Dependência

---

## Estrutura do Projeto

- `presentation/` — ViewModels, estados da interface e camada de apresentação.
- `domain/` — Modelos de domínio, contratos e regras de negócio.
- `data/` — Implementações concretas dos repositórios e fontes de dados.
- `di/` — Módulos de Injeção de Dependência (Hilt).
- `navigation/` — Rotas e auxiliares de navegação.
- `repository/` — Interfaces dos repositórios.
- `usecase/` — Casos de uso responsáveis pelas regras de negócio.
- `model/` — Modelos específicos das telas.
- `ui/` — Telas e componentes desenvolvidos com Jetpack Compose.

---

## Como Compilar

1. Abra o projeto no Android Studio.
2. Certifique-se de que o **JDK 17** está configurado para o Gradle.
3. Sincronize o projeto com o Gradle.
4. Compile ou execute o módulo `:app` em um dispositivo Android ou emulador que possua câmera.

---

## Dependências

- Kotlin
- Jetpack Compose
- Material Design 3
- Hilt
- Room
- DataStore
- Camera2
- Kotlin Coroutines
- Flow e StateFlow

---