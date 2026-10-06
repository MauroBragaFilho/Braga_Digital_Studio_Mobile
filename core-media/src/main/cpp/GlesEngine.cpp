#include "GlesEngine.h"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <algorithm>
#include <cstring>
#include <string>

#define LOG_TAG "GlesEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"GLSL(#version 300 es
precision highp float;
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
uniform mat4 uSTMatrix;
uniform float uZoom;
uniform float uPanX;
uniform float uPanY;
uniform bool uApplyZoom;
uniform float uRotationDegrees;
uniform bool uMirrorX;
// Escala do quad (letterbox/pillarbox das saídas); (1,1) preenche o viewport.
uniform vec2 uQuadScale;

void main() {
    gl_Position = vec4(aPosition.xy * uQuadScale, aPosition.zw);
    vec4 transformedCoord = uSTMatrix * vec4(aTexCoord, 0.0, 1.0);
    vec2 coord = transformedCoord.xy;

    // Rotação dinâmica em torno do centro (0.5, 0.5)
    vec2 c = coord - vec2(0.5);
    float rad = radians(uRotationDegrees);
    float cosA = cos(rad);
    float sinA = sin(rad);
    coord = vec2(cosA * c.x - sinA * c.y, sinA * c.x + cosA * c.y) + vec2(0.5);

    // Des-espelhamento horizontal (por fonte — default ligado, L5)
    if (uMirrorX) {
        coord.x = 1.0 - coord.x;
    }

    if (uApplyZoom) {
        vec2 center = vec2(0.5, 0.5);
        vec2 zoomed = (coord - center) / uZoom + center;
        zoomed += vec2(uPanX, uPanY);
        vTexCoord = zoomed;
    } else {
        vTexCoord = coord;
    }
}
)GLSL";

static const char* FRAGMENT_CLEAN = R"GLSL(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : enable
#extension GL_OES_EGL_image_external : enable
precision highp float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform highp samplerExternalOES uTexture;

void main() {
    fragColor = texture(uTexture, vTexCoord);
}
)GLSL";

static const char* FRAGMENT_OVERLAY = R"GLSL(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : enable
#extension GL_OES_EGL_image_external : enable
precision highp float;
precision highp int;
precision highp sampler3D;
in vec2 vTexCoord;
out vec4 fragColor;
uniform highp samplerExternalOES uTexture;

uniform bool uZebra;
uniform bool uFalseColor;
uniform float uZebraThreshold;

uniform highp sampler3D uLutTexture;
uniform bool uLutEnabled;
uniform float uLutSize;   // lado N do cubo 3D (para o ajuste de meio texel)
uniform float uLutMix;    // 0..1 — intensidade da LUT

uniform bool uFocusPeaking;
uniform int uFocusPeakingColor;
uniform float uFocusPeakingSensitivity;

// Luma Rec.709
float getLuma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

// Amostragem correta de LUT 3D: mapeia [0,1] para os CENTROS dos texels
// (c*((N-1)/N) + 0.5/N), senão as bordas ficam comprimidas em ~0,5/N.
vec3 applyLut(vec3 c) {
    float n = max(uLutSize, 2.0);
    vec3 uvw = clamp(c, 0.0, 1.0) * ((n - 1.0) / n) + vec3(0.5 / n);
    return texture(uLutTexture, uvw).rgb;
}

void main() {
    vec4 color = texture(uTexture, vTexCoord);

    if (uLutEnabled) {
        vec3 graded = applyLut(color.rgb);
        color.rgb = mix(color.rgb, graded, clamp(uLutMix, 0.0, 1.0));
    }

    float luma = getLuma(color.rgb);

    // Ferramentas de exposição/foco são INDEPENDENTES: a ordem de empilhamento
    // é false color -> zebra -> peaking (o peaking fica por cima).
    if (uFalseColor) {
        if (luma < 0.1) color.rgb = vec3(0.0, 0.0, 1.0);
        else if (luma > 0.9) color.rgb = vec3(1.0, 0.0, 0.0);
        else if (luma > 0.4 && luma < 0.6) color.rgb = vec3(0.0, 1.0, 0.0);
    }

    if (uZebra && luma >= uZebraThreshold) {
        if (mod(gl_FragCoord.x + gl_FragCoord.y, 20.0) < 10.0) {
            color.rgb = vec3(1.0, 0.0, 0.0);
        }
    }

    if (uFocusPeaking) {
        // Raio do kernel em ~2 texels da textura de origem (independe da resolução).
#ifdef FIXED_TEXEL
        vec2 offs = vec2(0.002);
#else
        vec2 offs = 2.0 / vec2(textureSize(uTexture, 0));
#endif
        float lumaTL = getLuma(texture(uTexture, vTexCoord + vec2(-offs.x, -offs.y)).rgb);
        float lumaT  = getLuma(texture(uTexture, vTexCoord + vec2(0.0, -offs.y)).rgb);
        float lumaTR = getLuma(texture(uTexture, vTexCoord + vec2(offs.x, -offs.y)).rgb);
        float lumaL  = getLuma(texture(uTexture, vTexCoord + vec2(-offs.x, 0.0)).rgb);
        float lumaR  = getLuma(texture(uTexture, vTexCoord + vec2(offs.x, 0.0)).rgb);
        float lumaBL = getLuma(texture(uTexture, vTexCoord + vec2(-offs.x, offs.y)).rgb);
        float lumaB  = getLuma(texture(uTexture, vTexCoord + vec2(0.0, offs.y)).rgb);
        float lumaBR = getLuma(texture(uTexture, vTexCoord + vec2(offs.x, offs.y)).rgb);

        float gx = (-lumaTL + lumaTR) + 2.0 * (-lumaL + lumaR) + (-lumaBL + lumaBR);
        float gy = (-lumaTL - 2.0 * lumaT - lumaTR) + (lumaBL + 2.0 * lumaB + lumaBR);

        float edge = sqrt(gx * gx + gy * gy);

        if (edge > uFocusPeakingSensitivity) {
            if (uFocusPeakingColor == 0) color.rgb = vec3(1.0, 0.0, 0.0); // Vermelho
            else if (uFocusPeakingColor == 1) color.rgb = vec3(0.0, 1.0, 0.0); // Verde
            else if (uFocusPeakingColor == 2) color.rgb = vec3(0.0, 0.0, 1.0); // Azul
            else if (uFocusPeakingColor == 3) color.rgb = vec3(1.0, 1.0, 0.0); // Amarelo
            else color.rgb = vec3(1.0, 1.0, 1.0); // Branco
        }
    }

    fragColor = color;
}
)GLSL";

static GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);

    GLint status = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[512];
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("Shader compile error: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

// Compila vertex + fragment e linka. Retorna 0 em falha (já limpa os recursos).
static GLuint buildProgram(const char* vertexSrc, const char* fragmentSrc) {
    GLuint v = compileShader(GL_VERTEX_SHADER, vertexSrc);
    GLuint f = compileShader(GL_FRAGMENT_SHADER, fragmentSrc);
    if (v == 0 || f == 0) {
        if (v) glDeleteShader(v);
        if (f) glDeleteShader(f);
        return 0;
    }
    GLuint program = glCreateProgram();
    glAttachShader(program, v);
    glAttachShader(program, f);
    glLinkProgram(program);
    glDeleteShader(v);
    glDeleteShader(f);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        char log[512];
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        LOGE("Program link error: %s", log);
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

static ProgramLocations queryLocations(GLuint p) {
    ProgramLocations l;
    l.uTexture = glGetUniformLocation(p, "uTexture");
    l.uSTMatrix = glGetUniformLocation(p, "uSTMatrix");
    l.uRotationDegrees = glGetUniformLocation(p, "uRotationDegrees");
    l.uMirrorX = glGetUniformLocation(p, "uMirrorX");
    l.uQuadScale = glGetUniformLocation(p, "uQuadScale");
    l.uApplyZoom = glGetUniformLocation(p, "uApplyZoom");
    l.uZoom = glGetUniformLocation(p, "uZoom");
    l.uPanX = glGetUniformLocation(p, "uPanX");
    l.uPanY = glGetUniformLocation(p, "uPanY");
    l.uZebra = glGetUniformLocation(p, "uZebra");
    l.uFalseColor = glGetUniformLocation(p, "uFalseColor");
    l.uZebraThreshold = glGetUniformLocation(p, "uZebraThreshold");
    l.uLutTexture = glGetUniformLocation(p, "uLutTexture");
    l.uLutEnabled = glGetUniformLocation(p, "uLutEnabled");
    l.uLutSize = glGetUniformLocation(p, "uLutSize");
    l.uLutMix = glGetUniformLocation(p, "uLutMix");
    l.uFocusPeaking = glGetUniformLocation(p, "uFocusPeaking");
    l.uFocusPeakingColor = glGetUniformLocation(p, "uFocusPeakingColor");
    l.uFocusPeakingSensitivity = glGetUniformLocation(p, "uFocusPeakingSensitivity");
    return l;
}

GlesEngine::GlesEngine() : pbufferSurface(EGL_NO_SURFACE), display(EGL_NO_DISPLAY),
    config(nullptr), pbufferConfig(nullptr), context(EGL_NO_CONTEXT),
    previewSurface(EGL_NO_SURFACE), previewWindow(nullptr),
    recordSurface(EGL_NO_SURFACE), recordWindow(nullptr),
    ndiSurface(EGL_NO_SURFACE), ndiWindow(nullptr),
    bspSurface(EGL_NO_SURFACE), bspWindow(nullptr),
    oesTexture(0), cleanProgram(0), overlayProgram(0), vbo(0),
    scopeFbo(0), scopeTexture(0), scopeWidth(256), scopeHeight(144),
    pboIndex(0) {
    pboIds[0] = 0;
    pboIds[1] = 0;
    histogramR.resize(256, 0);
    histogramG.resize(256, 0);
    histogramB.resize(256, 0);
    waveformData.resize(256 * 256, 0);
    vectorscopeData.resize(256 * 256, 0);
}

GlesEngine::~GlesEngine() {
    destroy();
}

bool GlesEngine::init() {
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) {
        LOGE("EGL_TRACE: eglGetDisplay falhou");
        return false;
    }

    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) {
        LOGE("EGL_TRACE: eglInitialize falhou");
        return false;
    }

    // Etapa 1: Configuração preferencial (WINDOW + PBUFFER com 8888 RGBA)
    const EGLint configAttribs1[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    bool configSuccess = (eglChooseConfig(display, configAttribs1, &config, 1, &numConfigs) && numConfigs > 0);
    pbufferConfig = config;

    if (!configSuccess) {
        LOGI("EGL_TRACE: Etapa 1 falhou. Tentando Etapa 2 (sem exigência rígida de Alpha)...");
        const EGLint configAttribs2[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_NONE
        };
        configSuccess = (eglChooseConfig(display, configAttribs2, &config, 1, &numConfigs) && numConfigs > 0);
        pbufferConfig = config;
    }

    if (!configSuccess) {
        LOGI("EGL_TRACE: Etapa 2 falhou. Tentando Etapa 3 (Configurações separadas para Window e PBuffer - Mali GPUs)...");
        const EGLint windowAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_NONE
        };
        const EGLint pbufAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_NONE
        };
        bool winOk = (eglChooseConfig(display, windowAttribs, &config, 1, &numConfigs) && numConfigs > 0);
        bool pbufOk = (eglChooseConfig(display, pbufAttribs, &pbufferConfig, 1, &numConfigs) && numConfigs > 0);
        configSuccess = winOk && pbufOk;
    }

    if (!configSuccess) {
        LOGI("EGL_TRACE: Etapa 3 falhou. Tentando fallback para OpenGL ES 2.0...");
        const EGLint es2Attribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
            EGL_NONE
        };
        configSuccess = (eglChooseConfig(display, es2Attribs, &config, 1, &numConfigs) && numConfigs > 0);
        pbufferConfig = config;
    }

    if (!configSuccess) {
        LOGE("EGL_TRACE: ERRO FATAL: Nenhuma EGLConfig compatível foi encontrada!");
        return false;
    } else {
        LOGI("EGL_TRACE: EGLConfig selecionada com sucesso!");
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) {
        LOGI("EGL_TRACE: Contexto ES3 falhou. Tentando contexto ES2...");
        const EGLint contextAttribs2[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
        };
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs2);
        if (context == EGL_NO_CONTEXT) {
            LOGE("EGL_TRACE: ERRO FATAL: Falha ao criar EGLContext!");
            return false;
        }
    }

    const EGLint pbufferAttribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    pbufferSurface = eglCreatePbufferSurface(display, pbufferConfig, pbufferAttribs);
    if (pbufferSurface == EGL_NO_SURFACE) {
        LOGE("EGL_TRACE: Falha ao criar PBuffer Surface principal");
    }

    eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);

    // PTS do instante de captura para REC/BSP (M56) — opcional.
    const char* eglExts = eglQueryString(display, EGL_EXTENSIONS);
    if (eglExts && strstr(eglExts, "EGL_ANDROID_presentation_time")) {
        eglPresentationTimeFn = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
            eglGetProcAddress("eglPresentationTimeANDROID"));
    }

    setupGraphics();
    return true;
}

// Troca a janela de um slot de saída (preview/REC/NDI/BSP).
//
// M24: ANativeWindow_fromSurface (JNI) devolve uma referência adquirida. Se a
// janela é a MESMA já registrada, essa referência extra seria perdida — então é
// liberada aqui. Se a surface EGL ainda não existe (criação anterior falhou),
// tenta de novo reaproveitando a janela.
void GlesEngine::replaceWindow(ANativeWindow*& currentWindow, EGLSurface& currentSurface,
                               ANativeWindow* newWindow, bool noVsync, const char* tag) {
    if (currentWindow == newWindow && (currentSurface != EGL_NO_SURFACE || newWindow == nullptr)) {
        if (newWindow) ANativeWindow_release(newWindow);
        return;
    }

    if (currentSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        eglDestroySurface(display, currentSurface);
        currentSurface = EGL_NO_SURFACE;
    }
    if (currentWindow) ANativeWindow_release(currentWindow);

    currentWindow = newWindow;
    if (!newWindow) return;

    ANativeWindow_setBuffersGeometry(newWindow, ANativeWindow_getWidth(newWindow),
                                     ANativeWindow_getHeight(newWindow), WINDOW_FORMAT_RGBA_8888);
    currentSurface = eglCreateWindowSurface(display, config, newWindow, nullptr);
    if (currentSurface == EGL_NO_SURFACE) {
        LOGE("%s: eglCreateWindowSurface FALHOU! Erro EGL: 0x%x", tag, eglGetError());
        return;
    }
    LOGI("%s: EGLSurface criada com SUCESSO!", tag);

    if (noVsync) {
        // eglSwapInterval vale para a surface CORRENTE: define uma única vez aqui,
        // não por frame. Sem isso o swap de uma saída lenta (NDI) segura o thread GL.
        if (eglMakeCurrent(display, currentSurface, currentSurface, context)) {
            eglSwapInterval(display, 0);
        }
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
    }
}

void GlesEngine::setPreviewWindow(ANativeWindow* win) {
    std::lock_guard<std::mutex> lock(renderMutex);
    replaceWindow(previewWindow, previewSurface, win, false, "BDSM_PREVIEW");
}

void GlesEngine::setRecordWindow(ANativeWindow* win) {
    std::lock_guard<std::mutex> lock(renderMutex);
    replaceWindow(recordWindow, recordSurface, win, true, "BDSM_REC");
}

void GlesEngine::setNdiWindow(ANativeWindow* win) {
    std::lock_guard<std::mutex> lock(renderMutex);
    replaceWindow(ndiWindow, ndiSurface, win, true, "BDSM_NDI");
}

void GlesEngine::setBspWindow(ANativeWindow* win) {
    std::lock_guard<std::mutex> lock(renderMutex);
    replaceWindow(bspWindow, bspSurface, win, true, "BDSM_BSP");
}

bool GlesEngine::makeCurrent(EGLSurface targetSurface) {
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return false;
    if (targetSurface == EGL_NO_SURFACE) return false;
    return eglMakeCurrent(display, targetSurface, targetSurface, context);
}

GLuint GlesEngine::getOesTexture() {
    return oesTexture;
}

void GlesEngine::setupGraphics() {
    if (oesTexture == 0) {
        glGenTextures(1, &oesTexture);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }

    if (cleanProgram == 0) {
        cleanProgram = buildProgram(VERTEX_SHADER, FRAGMENT_CLEAN);
        if (cleanProgram == 0) {
            LOGE("Falha ao criar o programa 'limpo' — nada será renderizado");
        }

        overlayProgram = buildProgram(VERTEX_SHADER, FRAGMENT_OVERLAY);
        if (overlayProgram == 0) {
            // Alguns drivers não aceitam textureSize() em samplerExternalOES:
            // tenta de novo com o offset fixo do peaking.
            LOGI("Overlay shader falhou; tentando variante com FIXED_TEXEL");
            std::string src(FRAGMENT_OVERLAY);
            size_t nl = src.find('\n');
            if (nl != std::string::npos) src.insert(nl + 1, "#define FIXED_TEXEL 1\n");
            overlayProgram = buildProgram(VERTEX_SHADER, src.c_str());
        }
        if (overlayProgram == 0) {
            LOGE("Falha ao criar o programa de overlays — preview sem zebra/peaking/LUT");
        }

        if (cleanProgram != 0) cleanLocs = queryLocations(cleanProgram);
        if (overlayProgram != 0) overlayLocs = queryLocations(overlayProgram);

        GLfloat vertices[] = {
            -1.0f, -1.0f,  0.0f, 1.0f,
             1.0f, -1.0f,  1.0f, 1.0f,
            -1.0f,  1.0f,  0.0f, 0.0f,
             1.0f,  1.0f,  1.0f, 0.0f
        };
        glGenBuffers(1, &vbo);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    }

    // Setup FBO para Scopes
    if (scopeFbo == 0) {
        glGenFramebuffers(1, &scopeFbo);
        glGenTextures(1, &scopeTexture);

        glBindTexture(GL_TEXTURE_2D, scopeTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, scopeWidth, scopeHeight, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glBindFramebuffer(GL_FRAMEBUFFER, scopeFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, scopeTexture, 0);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            LOGE("Scope FBO is incomplete");
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    // Setup PBOs para leitura assíncrona de pixels (evita stalls em GPUs Mali)
    if (pboIds[0] == 0) {
        glGenBuffers(2, pboIds);
        int bufferSize = scopeWidth * scopeHeight * 4;
        for (int i = 0; i < 2; i++) {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, pboIds[i]);
            glBufferData(GL_PIXEL_PACK_BUFFER, bufferSize, nullptr, GL_STREAM_READ);
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }
}

// Uniforms comuns a todos os passes (preview, saídas e scopes).
void GlesEngine::bindCommonUniforms(const ProgramLocations& loc, float rotation, float quadScaleX, float quadScaleY) {
    glUniform1i(loc.uTexture, 0);
    glUniformMatrix4fv(loc.uSTMatrix, 1, GL_FALSE, stMatrix);
    glUniform1f(loc.uRotationDegrees, rotation);
    glUniform1i(loc.uMirrorX, mirrorX ? 1 : 0);
    glUniform2f(loc.uQuadScale, quadScaleX, quadScaleY);
}

void GlesEngine::render(int64_t timestampNs) {
    std::lock_guard<std::mutex> lock(renderMutex);
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return;
    if (cleanProgram == 0) return;

    // Drenar erros GL pendentes antes de qualquer operação (necessário para Mali GPUs)
    while (glGetError() != GL_NO_ERROR) {}

    // isPreview: desenha com overlays/zoom/LUT (programa de overlay) e rotação
    // do display. Caso contrário usa o programa limpo com a rotação das saídas
    // (outputRotationDegrees) e o encaixe com barras (quadScale). stampPts: carimba o PTS do frame da câmera na surface (REC/BSP).
    auto drawPass = [&](EGLSurface destSurface, bool isPreview, bool stampPts, float quadScaleX, float quadScaleY) {
        if (!makeCurrent(destSurface)) return;

        const bool overlays = isPreview && overlayProgram != 0;
        const GLuint programId = overlays ? overlayProgram : cleanProgram;
        const ProgramLocations& loc = overlays ? overlayLocs : cleanLocs;

        EGLint w = 0, h = 0;
        eglQuerySurface(display, destSurface, EGL_WIDTH, &w);
        eglQuerySurface(display, destSurface, EGL_HEIGHT, &h);

        glViewport(0, 0, w, h);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(programId);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture);
        bindCommonUniforms(loc, isPreview ? rotationDegrees : outputRotationDegrees, quadScaleX, quadScaleY);

        if (overlays) {
            glUniform1i(loc.uZebra, enableZebra ? 1 : 0);
            glUniform1i(loc.uFalseColor, enableFalseColor ? 1 : 0);
            glUniform1f(loc.uZebraThreshold, zebraThreshold);

            glUniform1i(loc.uFocusPeaking, enableFocusPeaking ? 1 : 0);
            glUniform1i(loc.uFocusPeakingColor, focusPeakingColor);
            glUniform1f(loc.uFocusPeakingSensitivity, focusPeakingSensitivity);

            glUniform1i(loc.uApplyZoom, 1);
            glUniform1f(loc.uZoom, zoomFactor);
            glUniform1f(loc.uPanX, panX);
            glUniform1f(loc.uPanY, panY);

            const bool lutOn = enableLut && lutTexture != 0;
            glUniform1i(loc.uLutEnabled, lutOn ? 1 : 0);
            glUniform1i(loc.uLutTexture, 1);
            glUniform1f(loc.uLutSize, static_cast<float>(lutSize));
            glUniform1f(loc.uLutMix, lutMix);

            if (lutOn) {
                glActiveTexture(GL_TEXTURE1);
                glBindTexture(GL_TEXTURE_3D, lutTexture);
                glActiveTexture(GL_TEXTURE0);
            }
        } else {
            glUniform1i(loc.uApplyZoom, 0);
        }

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)(2 * sizeof(GLfloat)));
        glEnableVertexAttribArray(1);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Snapshot: lê o framebuffer do preview ANTES do swap. glReadPixels devolve
        // bytes R,G,B,A; lidos como int (little-endian) viram 0xAABBGGRR — aqui
        // são convertidos para ARGB (0xAARRGGBB), o formato de Bitmap.setPixels.
        if (isPreview && w > 0 && h > 0 && needSnapshot.load()) {
            std::lock_guard<std::mutex> dataLock(dataMutex);
            const size_t count = static_cast<size_t>(w) * static_cast<size_t>(h);
            snapshotWidth = w;
            snapshotHeight = h;
            snapshotData.resize(count);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, snapshotData.data());
            uint32_t* px = reinterpret_cast<uint32_t*>(snapshotData.data());
            for (size_t i = 0; i < count; i++) {
                const uint32_t v = px[i];
                px[i] = 0xFF000000u | ((v & 0xFFu) << 16) | (v & 0xFF00u) | ((v >> 16) & 0xFFu);
            }
            snapshotReady = true;
            needSnapshot.store(false);
        }

        // PTS do instante de captura (timestamp da SurfaceTexture), não do swap.
        if (stampPts && timestampNs > 0 && eglPresentationTimeFn) {
            eglPresentationTimeFn(display, destSurface, static_cast<EGLnsecsANDROID>(timestampNs));
        }

        eglSwapBuffers(display, destSurface);
    };

    // Preview primeiro (a latência percebida pelo operador não depende das saídas).
    if (previewSurface != EGL_NO_SURFACE) {
        drawPass(previewSurface, true, false, 1.0f, 1.0f);
    }

    // Gravação (limpo)
    if (recordSurface != EGL_NO_SURFACE) {
        drawPass(recordSurface, false, true, recordScaleX, recordScaleY);
    }

    // BSP (limpo) — mesmo frame "limpo" do NDI/gravação, sem overlays de monitor
    // (zebra/false color/scopes ficam só no preview).
    if (bspSurface != EGL_NO_SURFACE) {
        drawPass(bspSurface, false, true, bspScaleX, bspScaleY);
    }

    // NDI (limpo) por último: é a saída potencialmente lenta. Sem receptor
    // conectado (flag atômica alimentada fora da thread GL) o passe é pulado.
    if (ndiSurface != EGL_NO_SURFACE && ndiHasReceivers.load(std::memory_order_relaxed)) {
        drawPass(ndiSurface, false, false, ndiScaleX, ndiScaleY);
    }

    // Restaurar contexto ao pbuffer e limpar erros GL para que o próximo updateTexImage() não encontre estado sujo
    eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
    glFlush();
    while (glGetError() != GL_NO_ERROR) {}

    // Scopes FBO Pass
    if (activeScopeType > 0 && makeCurrent(pbufferSurface)) {
        glBindFramebuffer(GL_FRAMEBUFFER, scopeFbo);
        glViewport(0, 0, scopeWidth, scopeHeight);

        glUseProgram(cleanProgram);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture);

        // uSTMatrix é a matriz de transformação da SurfaceTexture (câmera OES) —
        // sem ela, o vertex shader multiplica as coordenadas por uma matriz zerada
        // (valor padrão de um uniform mat4 não setado) e todo o frame colapsa pra
        // um único ponto (UV 0,0), fazendo os scopes "travarem" numa cor só em vez
        // de refletir a imagem inteira.
        bindCommonUniforms(cleanLocs, rotationDegrees, 1.0f, 1.0f);
        glUniform1i(cleanLocs.uApplyZoom, 0);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)(2 * sizeof(GLfloat)));
        glEnableVertexAttribArray(1);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        updateScopes(scopeWidth, scopeHeight);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    // Restore Pbuffer current so we don't hold onto a Window that might be destroyed
    makeCurrent(pbufferSurface);
}

void GlesEngine::updateScopes(int w, int h) {
    if (w <= 0 || h <= 0 || activeScopeType == 0) return;

    int nextPboIndex = (pboIndex + 1) % 2;

    // Dispara glReadPixels assíncrono para o PBO atual
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pboIds[pboIndex]);
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, 0);

    // Mapeia o PBO anterior (nextPboIndex) para ler os dados sem travar a GPU
    glBindBuffer(GL_PIXEL_PACK_BUFFER, pboIds[nextPboIndex]);
    GLubyte* ptr = static_cast<GLubyte*>(glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, w * h * 4, GL_MAP_READ_BIT));

    if (ptr) {
        std::lock_guard<std::mutex> lock(dataMutex);

        if (activeScopeType == 1) { // Histogram
            std::fill(histogramR.begin(), histogramR.end(), 0);
            std::fill(histogramG.begin(), histogramG.end(), 0);
            std::fill(histogramB.begin(), histogramB.end(), 0);

            // Histograma RGB real (um bin por canal), não apenas luma jogado no
            // array de R — assim o operador vê clipping/tint por canal de cor,
            // como num histograma de câmera de referência de verdade.
            size_t totalBytes = static_cast<size_t>(w) * h * 4;
            for (size_t i = 0; i < totalBytes; i += 4) {
                uint8_t r = ptr[i];
                uint8_t g = ptr[i+1];
                uint8_t b = ptr[i+2];
                histogramR[r]++;
                histogramG[g]++;
                histogramB[b]++;
            }
        } else if (activeScopeType == 2) { // Waveform
            std::fill(waveformData.begin(), waveformData.end(), 0);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    size_t i = (static_cast<size_t>(y) * w + x) * 4;
                    uint8_t r = ptr[i];
                    uint8_t g = ptr[i+1];
                    uint8_t b = ptr[i+2];

                    // Luma Rec.709 (0.2126/0.7152/0.0722) em aritmética inteira
                    int luma = (2126 * r + 7152 * g + 722 * b) / 10000;
                    if (luma < 0) luma = 0;
                    if (luma > 255) luma = 255;

                    int mappedX = (x * 256) / w;
                    if (mappedX > 255) mappedX = 255;

                    int idxR = r * 256 + mappedX;
                    int countR = (waveformData[idxR] >> 24) & 0xFF;
                    if (countR < 255) waveformData[idxR] = (waveformData[idxR] & 0x00FFFFFF) | ((countR + 1) << 24);

                    int idxG = g * 256 + mappedX;
                    int countG = (waveformData[idxG] >> 16) & 0xFF;
                    if (countG < 255) waveformData[idxG] = (waveformData[idxG] & 0xFF00FFFF) | ((countG + 1) << 16);

                    int idxB = b * 256 + mappedX;
                    int countB = (waveformData[idxB] >> 8) & 0xFF;
                    if (countB < 255) waveformData[idxB] = (waveformData[idxB] & 0xFFFF00FF) | ((countB + 1) << 8);

                    int idxL = luma * 256 + mappedX;
                    int countL = waveformData[idxL] & 0xFF;
                    if (countL < 255) waveformData[idxL] = (waveformData[idxL] & 0xFFFFFF00) | (countL + 1);
                }
            }
        } else if (activeScopeType == 3) { // Vectorscope
            std::fill(vectorscopeData.begin(), vectorscopeData.end(), 0);
            size_t totalBytes = static_cast<size_t>(w) * h * 4;

            for (size_t i = 0; i < totalBytes; i += 4) {
                const float r = ptr[i];
                const float g = ptr[i+1];
                const float b = ptr[i+2];

                // Cb/Cr Rec.709 normalizados para -127.5..+127.5 (Cb = (B-Y)/1.8556,
                // Cr = (R-Y)/1.5748), centrados em 128 — sem clip do vermelho/azul
                // saturados como na matriz YUV analógica antiga.
                const float y = 0.2126f * r + 0.7152f * g + 0.0722f * b;
                int u = 128 + static_cast<int>((b - y) / 1.8556f);
                int v = 128 + static_cast<int>((r - y) / 1.5748f);

                if (u < 0) u = 0;
                if (u > 255) u = 255;
                if (v < 0) v = 0;
                if (v > 255) v = 255;

                vectorscopeData[v * 256 + u]++;
            }
        }

        glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
    }

    glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    pboIndex = nextPboIndex;
}

void GlesEngine::destroy() {
    if (display != EGL_NO_DISPLAY) {
        if (context != EGL_NO_CONTEXT && pbufferSurface != EGL_NO_SURFACE) {
            eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);

            if (pboIds[0] != 0) {
                glDeleteBuffers(2, pboIds);
                pboIds[0] = 0;
                pboIds[1] = 0;
            }
            if (scopeFbo != 0) {
                glDeleteFramebuffers(1, &scopeFbo);
                scopeFbo = 0;
            }
            if (scopeTexture != 0) {
                glDeleteTextures(1, &scopeTexture);
                scopeTexture = 0;
            }
            if (lutTexture != 0) {
                glDeleteTextures(1, &lutTexture);
                lutTexture = 0;
            }
            if (oesTexture != 0) {
                glDeleteTextures(1, &oesTexture);
                oesTexture = 0;
            }
            if (cleanProgram != 0) {
                glDeleteProgram(cleanProgram);
                cleanProgram = 0;
            }
            if (overlayProgram != 0) {
                glDeleteProgram(overlayProgram);
                overlayProgram = 0;
            }
            if (vbo != 0) {
                glDeleteBuffers(1, &vbo);
                vbo = 0;
            }
        }

        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        // Surfaces antes do contexto.
        if (previewSurface != EGL_NO_SURFACE) eglDestroySurface(display, previewSurface);
        if (recordSurface != EGL_NO_SURFACE) eglDestroySurface(display, recordSurface);
        if (ndiSurface != EGL_NO_SURFACE) eglDestroySurface(display, ndiSurface);
        if (bspSurface != EGL_NO_SURFACE) eglDestroySurface(display, bspSurface);
        if (pbufferSurface != EGL_NO_SURFACE) eglDestroySurface(display, pbufferSurface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglTerminate(display);
    }
    display = EGL_NO_DISPLAY;
    context = EGL_NO_CONTEXT;
    previewSurface = EGL_NO_SURFACE;
    recordSurface = EGL_NO_SURFACE;
    ndiSurface = EGL_NO_SURFACE;
    bspSurface = EGL_NO_SURFACE;
    pbufferSurface = EGL_NO_SURFACE;
    scopeFbo = 0;
    scopeTexture = 0;

    if (previewWindow) { ANativeWindow_release(previewWindow); previewWindow = nullptr; }
    if (recordWindow) { ANativeWindow_release(recordWindow); recordWindow = nullptr; }
    if (ndiWindow) { ANativeWindow_release(ndiWindow); ndiWindow = nullptr; }
    if (bspWindow) { ANativeWindow_release(bspWindow); bspWindow = nullptr; }
}

void GlesEngine::setLutDataFloat(const float* data, int size) {
    if (!data || size < 2 || size > 129) {
        LOGE("LUT_TRACE C++: tamanho de LUT inválido: %d", size);
        return;
    }
    std::lock_guard<std::mutex> lock(renderMutex);
    if (!makeCurrent(pbufferSurface)) return;

    if (lutTexture != 0) {
        glDeleteTextures(1, &lutTexture);
        lutTexture = 0;
        lutSize = 0;
    }

    while (glGetError() != GL_NO_ERROR) {}

    GLuint tex = 0;
    glGenTextures(1, &tex);

    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, tex);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

    // RGBA16F: precisão de 10+ bits (LUT em 8 bits gera banding) e filtro linear
    // suportado no ES 3.0 core. Upload a partir de float (o driver converte).
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA16F, size, size, size, 0, GL_RGBA, GL_FLOAT, data);

    GLenum err = glGetError();
    glBindTexture(GL_TEXTURE_3D, 0);
    glActiveTexture(GL_TEXTURE0);

    if (err != GL_NO_ERROR) {
        LOGE("LUT_TRACE C++: ERRO AO CRIAR TEXTURA 3D! Código: 0x%x", err);
        glDeleteTextures(1, &tex);
        return;
    }
    lutTexture = tex;
    lutSize = size;
    LOGI("LUT_TRACE C++: textura 3D RGBA16F %d^3 carregada.", size);
}

void GlesEngine::setLutData(const char* data, int size) {
    if (!data || size < 2 || size > 129) {
        LOGE("LUT_TRACE C++: tamanho de LUT inválido: %d", size);
        return;
    }
    const size_t count = static_cast<size_t>(size) * size * size * 4;
    std::vector<float> converted(count);
    const uint8_t* src = reinterpret_cast<const uint8_t*>(data);
    for (size_t i = 0; i < count; i++) {
        converted[i] = static_cast<float>(src[i]) / 255.0f;
    }
    setLutDataFloat(converted.data(), size);
}

// ===================== JNI Wrapper =====================
static GlesEngine* engine = nullptr;

extern "C" JNIEXPORT jlong JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeCreate(JNIEnv* env, jobject thiz) {
    if (!engine) engine = new GlesEngine();
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeInit(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) return eng->init() ? JNI_TRUE : JNI_FALSE;
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeDestroy(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        // Zera o singleton ANTES do delete: comparar ponteiro já liberado (dangling)
        // depois do delete é indefinido.
        if (eng == engine) engine = nullptr;
        eng->destroy();
        delete eng;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetPreviewSurface(JNIEnv* env, jobject thiz, jlong ptr, jobject surface) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
        eng->setPreviewWindow(window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetRecordSurface(JNIEnv* env, jobject thiz, jlong ptr, jobject surface) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
        eng->setRecordWindow(window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetNdiSurface(JNIEnv* env, jobject thiz, jlong ptr, jobject surface) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
        eng->setNdiWindow(window);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetBspSurface(JNIEnv* env, jobject thiz, jlong ptr, jobject surface) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;
        eng->setBspWindow(window);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetOesTexture(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) return eng->getOesTexture();
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetTransformMatrix(JNIEnv* env, jobject thiz, jlong ptr, jfloatArray matrixArray) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng && matrixArray && env->GetArrayLength(matrixArray) >= 16) {
        jfloat* elems = env->GetFloatArrayElements(matrixArray, nullptr);
        if (elems) {
            for (int i = 0; i < 16; i++) {
                eng->stMatrix[i] = elems[i];
            }
            env->ReleaseFloatArrayElements(matrixArray, elems, JNI_ABORT);
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeUpdateTexImage(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        eng->makeCurrent(eng->pbufferSurface);
        while (glGetError() != GL_NO_ERROR) {}
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeRender(JNIEnv* env, jobject thiz, jlong ptr, jlong timestampNs) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        eng->render(static_cast<int64_t>(timestampNs));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetSettings(
      JNIEnv* env, jobject thiz, jlong ptr,
      jboolean falseColor, jboolean zebra, jboolean focusPeaking,
      jfloat zoomFactor, jfloat panX, jfloat panY,
      jfloat rotationDegrees, jboolean lutEnabled, jint scopeType,
      jfloat zebraThreshold, jint focusPeakingColor, jfloat focusPeakingSensitivity,
      jfloat lutMix, jboolean mirrorX) {

    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        eng->enableZebra = zebra;
        eng->enableFalseColor = falseColor;
        eng->zebraThreshold = zebraThreshold;
        eng->zoomFactor = zoomFactor;
        eng->panX = panX;
        eng->panY = panY;
        eng->rotationDegrees = rotationDegrees;
        eng->enableLut = lutEnabled;
        eng->activeScopeType = scopeType;
        eng->enableFocusPeaking = focusPeaking;
        eng->focusPeakingColor = focusPeakingColor;
        eng->focusPeakingSensitivity = focusPeakingSensitivity;
        eng->lutMix = lutMix < 0.0f ? 0.0f : (lutMix > 1.0f ? 1.0f : lutMix);
        eng->mirrorX = mirrorX;
    }
}

// Orientação das saídas (calculada em Kotlin por OutputOrientation): ângulo do
// conteúdo e escala do quad (barras) de cada saída. Chamado na thread GL, a mesma
// que desenha — sem corrida com render().
extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetOutputLayout(
        JNIEnv* env, jobject thiz, jlong ptr, jfloat rotationDegrees,
        jfloat recScaleX, jfloat recScaleY, jfloat ndiScaleX, jfloat ndiScaleY,
        jfloat bspScaleX, jfloat bspScaleY) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        auto clampScale = [](float v) { return v < 0.01f ? 0.01f : (v > 1.0f ? 1.0f : v); };
        eng->outputRotationDegrees = rotationDegrees;
        eng->recordScaleX = clampScale(recScaleX);
        eng->recordScaleY = clampScale(recScaleY);
        eng->ndiScaleX = clampScale(ndiScaleX);
        eng->ndiScaleY = clampScale(ndiScaleY);
        eng->bspScaleX = clampScale(bspScaleX);
        eng->bspScaleY = clampScale(bspScaleY);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetNdiHasReceivers(JNIEnv* env, jobject thiz, jlong ptr, jboolean hasReceivers) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        eng->ndiHasReceivers.store(hasReceivers == JNI_TRUE);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetLutData(
        JNIEnv* env, jobject thiz, jlong ptr, jbyteArray data, jint size) {

    if (ptr == 0 || data == nullptr || size < 2 || size > 129) {
        LOGE("LUT_TRACE C++: Falha! ptr/data nulos ou size inválido (%d).", (int)size);
        return;
    }
    const jlong expected = static_cast<jlong>(size) * size * size * 4;
    if (env->GetArrayLength(data) < expected) {
        LOGE("LUT_TRACE C++: array de LUT menor que o esperado.");
        return;
    }

    jbyte* elements = env->GetByteArrayElements(data, nullptr);
    if (elements) {
        GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
        eng->setLutData(reinterpret_cast<const char*>(elements), size);
        env->ReleaseByteArrayElements(data, elements, JNI_ABORT);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetLutDataFloat(
        JNIEnv* env, jobject thiz, jlong ptr, jfloatArray data, jint size) {

    if (ptr == 0 || data == nullptr || size < 2 || size > 129) {
        LOGE("LUT_TRACE C++: Falha! ptr/data nulos ou size inválido (%d).", (int)size);
        return;
    }
    const jlong expected = static_cast<jlong>(size) * size * size * 4;
    if (env->GetArrayLength(data) < expected) {
        LOGE("LUT_TRACE C++: array float de LUT menor que o esperado.");
        return;
    }

    jfloat* elements = env->GetFloatArrayElements(data, nullptr);
    if (elements) {
        GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
        eng->setLutDataFloat(elements, size);
        env->ReleaseFloatArrayElements(data, elements, JNI_ABORT);
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetHistogramR(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->histogramR.empty()) return nullptr;
    jintArray result = env->NewIntArray(256);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, 256, reinterpret_cast<const jint*>(eng->histogramR.data()));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetHistogramG(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->histogramG.empty()) return nullptr;
    jintArray result = env->NewIntArray(256);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, 256, reinterpret_cast<const jint*>(eng->histogramG.data()));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetHistogramB(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->histogramB.empty()) return nullptr;
    jintArray result = env->NewIntArray(256);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, 256, reinterpret_cast<const jint*>(eng->histogramB.data()));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetWaveform(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->waveformData.empty()) return nullptr;
    const jsize n = static_cast<jsize>(eng->waveformData.size());
    jintArray result = env->NewIntArray(n);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint*>(eng->waveformData.data()));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetVectorscope(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->vectorscopeData.empty()) return nullptr;
    const jsize n = static_cast<jsize>(eng->vectorscopeData.size());
    jintArray result = env->NewIntArray(n);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, n, reinterpret_cast<const jint*>(eng->vectorscopeData.data()));
    return result;
}

// L13: cada pedido descarta o resultado anterior — nativeGetSnapshot só devolve
// um frame capturado DEPOIS deste pedido.
extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeRequestSnapshot(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        {
            std::lock_guard<std::mutex> lock(eng->dataMutex);
            eng->snapshotData.clear();
            eng->snapshotReady = false;
        }
        eng->needSnapshot.store(true);
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetSnapshot(JNIEnv* env, jobject thiz, jlong ptr, jintArray outDimensions) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (!eng->snapshotReady || eng->snapshotData.empty()) return nullptr;

    if (outDimensions && env->GetArrayLength(outDimensions) >= 2) {
        jint dims[2] = { eng->snapshotWidth, eng->snapshotHeight };
        env->SetIntArrayRegion(outDimensions, 0, 2, dims);
    }

    const jsize n = static_cast<jsize>(eng->snapshotData.size());
    jintArray result = env->NewIntArray(n);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, n, eng->snapshotData.data());

    eng->snapshotData.clear();
    eng->snapshotReady = false;
    return result;
}
