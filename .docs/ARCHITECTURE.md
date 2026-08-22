# ARCHITECTURE.md

# Braga Studio Mobile (BSM)

## Visão Geral

O Braga Studio Mobile (BSM) é um aplicativo Android desenvolvido para transformar um smartphone em uma câmera profissional para gravações e transmissões.

O aplicativo oferece controle manual da câmera, captura de vídeo, áudio, monitoramento, perfis de gravação e outras funcionalidades voltadas à produção audiovisual.

**Importante**

Este aplicativo **não utiliza Inteligência Artificial em nenhuma funcionalidade do produto final**.

Ferramentas de IA podem ser utilizadas durante o desenvolvimento para auxiliar na geração de código, documentação e revisão, porém **nenhuma dependência de IA deve ser adicionada ao aplicativo**.

---

# Objetivos

* Código limpo
* Arquitetura modular
* Fácil manutenção
* Alta performance
* Baixo consumo de bateria
* Compatibilidade com Android modernos
* Código testável

---

# Arquitetura

O projeto utiliza:

* Clean Architecture
* MVVM
* Modularização por Feature
* Repository Pattern
* Dependency Injection (Hilt)
* Coroutines
* Flow / StateFlow

---

# Tecnologias

## Linguagem

* Kotlin

## Interface

* Jetpack Compose

## Navegação

* Navigation Compose

## Injeção de Dependência

* Hilt

## Banco

* Room

## Serialização

* Kotlinx Serialization

## Concorrência

* Kotlin Coroutines

## Câmera

Prioridade:

1. Camera2 API

CameraX poderá ser utilizada apenas quando simplificar funcionalidades sem reduzir o controle manual da câmera.

---

# Estrutura do Projeto

```
app/

core/
    common/
    ui/
    util/

data/

domain/

feature/
    camera/
    preview/
    settings/
    recording/
    gallery/

di/
```

Cada módulo deve possuir responsabilidades bem definidas.

---

# Regras Gerais

## Não criar código duplicado.

Sempre reutilizar componentes existentes.

---

## Antes de criar um novo arquivo

Verifique se já existe implementação semelhante.

Caso exista, reutilize.

---

## Antes de alterar código

Analise o impacto da alteração.

Evite quebrar APIs públicas do projeto.

---

## Código

Preferências:

* classes pequenas
* funções pequenas
* nomes descritivos
* evitar comentários desnecessários
* priorizar código autoexplicativo

---

# Interface

Utilizar exclusivamente:

* Jetpack Compose

Evitar XML sempre que possível.

---

# Estado

Utilizar:

* StateFlow
* MutableStateFlow
* remember
* rememberSaveable

Evitar estados globais.

---

# Camadas

## UI

Responsável apenas pela interface.

Nunca acessar banco ou APIs diretamente.

---

## ViewModel

Responsável pela lógica de apresentação.

Não conter código específico da câmera.

---

## Domain

Responsável pelas regras de negócio.

Independente do Android Framework.

---

## Data

Responsável por:

* Room
* DataStore
* Arquivos
* Repositórios

---

# Dependências

Adicionar novas dependências somente quando realmente necessárias.

Preferir bibliotecas oficiais do Android.

---

# Performance

Priorizar:

* baixo uso de memória
* baixo uso de CPU
* inicialização rápida
* evitar recomposições desnecessárias

---

# Organização

Cada Feature deve ser independente.

Sempre que possível:

Feature

→ Domain

→ Data

→ Core

Nunca acessar outra Feature diretamente.

---

# Convenções

Arquivos:

* PascalCase

Funções:

* camelCase

Constantes:

* UPPER_SNAKE_CASE

Pacotes:

* letras minúsculas

---

# Testes

Sempre que possível:

* Unit Tests
* ViewModel Tests

# Inteligência Artificial

O produto final **não possui funcionalidades baseadas em IA**.

Não adicionar:

* OpenAI SDK
* Gemini SDK
* ML Kit
* TensorFlow Lite
* ONNX Runtime
* LangChain
* Ollama
* LM Studio
* qualquer SDK de modelos de linguagem ou visão computacional

Caso alguma funcionalidade pareça depender de IA, deve ser implementada por algoritmos convencionais ou bibliotecas tradicionais.

---

# Papel do Agente

O agente deve atuar exclusivamente como auxiliar de desenvolvimento.

Sempre:

1. Analisar o código existente antes de sugerir mudanças.
2. Respeitar a arquitetura do projeto.
3. Minimizar alterações desnecessárias.
4. Explicar modificações relevantes.
5. Gerar código compilável.
6. Não alterar arquivos fora do workspace.
7. Não criar funcionalidades não solicitadas.
8. Não introduzir dependências de IA no aplicativo.

---

# Objetivo Final

Construir um aplicativo Android profissional, modular, escalável e de fácil manutenção, focado em captura e gerenciamento de vídeo, mantendo independência de serviços externos de Inteligência Artificial.
