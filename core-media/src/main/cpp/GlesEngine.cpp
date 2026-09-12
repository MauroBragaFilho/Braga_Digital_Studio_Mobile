#include "GlesEngine.h"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#define LOG_TAG "GlesEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"GLSL(#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
uniform mat4 uSTMatrix;
uniform float uZoom;
uniform float uPanX;
uniform float uPanY;
uniform bool uApplyZoom;
uniform float uRotationDegrees;

void main() {
    gl_Position = aPosition;
    vec4 transformedCoord = uSTMatrix * vec4(aTexCoord, 0.0, 1.0);
    vec2 coord = transformedCoord.xy;

    // Rotação dinâmica em torno do centro (0.5, 0.5)
    vec2 c = coord - vec2(0.5);
    float rad = radians(uRotationDegrees);
    float cosA = cos(rad);
    float sinA = sin(rad);
    coord = vec2(cosA * c.x - sinA * c.y, sinA * c.x + cosA * c.y) + vec2(0.5);

    // Des-espelhamento horizontal
    coord.x = 1.0 - coord.x;

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
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform lowp samplerExternalOES uTexture;

void main() {
    fragColor = texture(uTexture, vTexCoord);
}
)GLSL";

static const char* FRAGMENT_OVERLAY = R"GLSL(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : enable
#extension GL_OES_EGL_image_external : enable
precision mediump float;
precision mediump sampler3D;
in vec2 vTexCoord;
out vec4 fragColor;
uniform lowp samplerExternalOES uTexture;

uniform bool uZebra;
uniform bool uFalseColor;
uniform float uZebraThreshold;

uniform lowp sampler3D uLutTexture;
uniform bool uLutEnabled;

uniform bool uFocusPeaking;
uniform int uFocusPeakingColor; 
uniform float uFocusPeakingSensitivity; 

float getLuma(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec4 color = texture(uTexture, vTexCoord);
    
    if (uLutEnabled) {
        vec3 c = clamp(color.rgb, 0.0, 1.0);
        color.rgb = texture(uLutTexture, c).rgb;
    }

    float luma = getLuma(color.rgb);
    
    if (uFocusPeaking) {
        float offset = 0.001; 
        
        float lumaTL = getLuma(texture(uTexture, vTexCoord + vec2(-offset, -offset)).rgb);
        float lumaT  = getLuma(texture(uTexture, vTexCoord + vec2(0.0, -offset)).rgb);
        float lumaTR = getLuma(texture(uTexture, vTexCoord + vec2(offset, -offset)).rgb);
        float lumaL  = getLuma(texture(uTexture, vTexCoord + vec2(-offset, 0.0)).rgb);
        float lumaR  = getLuma(texture(uTexture, vTexCoord + vec2(offset, 0.0)).rgb);
        float lumaBL = getLuma(texture(uTexture, vTexCoord + vec2(-offset, offset)).rgb);
        float lumaB  = getLuma(texture(uTexture, vTexCoord + vec2(0.0, offset)).rgb);
        float lumaBR = getLuma(texture(uTexture, vTexCoord + vec2(offset, offset)).rgb);
        
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
    else if (uZebra && luma >= uZebraThreshold) {
        if (mod(gl_FragCoord.x + gl_FragCoord.y, 20.0) < 10.0) {
            color.rgb = vec3(1.0, 0.0, 0.0);
        }
    } else if (uFalseColor) {
        if (luma < 0.1) color.rgb = vec3(0.0, 0.0, 1.0);
        else if (luma > 0.9) color.rgb = vec3(1.0, 0.0, 0.0);
        else if (luma > 0.4 && luma < 0.6) color.rgb = vec3(0.0, 1.0, 0.0);
    }
    
    fragColor = color;
}
)GLSL";

GlesEngine::GlesEngine() : display(EGL_NO_DISPLAY), context(EGL_NO_CONTEXT), pbufferSurface(EGL_NO_SURFACE), 
    previewSurface(EGL_NO_SURFACE), previewWindow(nullptr),
    recordSurface(EGL_NO_SURFACE), recordWindow(nullptr),
    ndiSurface(EGL_NO_SURFACE), ndiWindow(nullptr),
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

    setupGraphics();
    return true;
}

void GlesEngine::setPreviewWindow(ANativeWindow* win) {
    std::lock_guard<std::mutex> lock(renderMutex);
    if (previewWindow == win) return;
    
    if (previewSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        eglDestroySurface(display, previewSurface);
        previewSurface = EGL_NO_SURFACE;
    }
    if (previewWindow) ANativeWindow_release(previewWindow);
    
    previewWindow = win;
    if (previewWindow) {
        ANativeWindow_setBuffersGeometry(previewWindow, ANativeWindow_getWidth(win), ANativeWindow_getHeight(win), WINDOW_FORMAT_RGBA_8888);
        previewSurface = eglCreateWindowSurface(display, config, previewWindow, nullptr);
    }
}

void GlesEngine::setRecordWindow(ANativeWindow* win) {
    std::lock_guard<std::mutex> lock(renderMutex);
    if (recordWindow == win) return;
    
    if (recordSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        eglDestroySurface(display, recordSurface);
        recordSurface = EGL_NO_SURFACE;
    }
    if (recordWindow) ANativeWindow_release(recordWindow);
    
    recordWindow = win;
    if (recordWindow) {
        ANativeWindow_setBuffersGeometry(recordWindow, ANativeWindow_getWidth(win), ANativeWindow_getHeight(win), WINDOW_FORMAT_RGBA_8888);
        recordSurface = eglCreateWindowSurface(display, config, recordWindow, nullptr);
    }
}

void GlesEngine::setNdiWindow(ANativeWindow* win) {
    std::lock_guard<std::mutex> lock(renderMutex);
    if (ndiWindow == win) return;
    
    if (ndiSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        eglDestroySurface(display, ndiSurface);
        ndiSurface = EGL_NO_SURFACE;
    }
    if (ndiWindow) ANativeWindow_release(ndiWindow);
    
    ndiWindow = win;
    if (ndiWindow) {
        ANativeWindow_setBuffersGeometry(ndiWindow, ANativeWindow_getWidth(win), ANativeWindow_getHeight(win), WINDOW_FORMAT_RGBA_8888);
        ndiSurface = eglCreateWindowSurface(display, config, ndiWindow, nullptr);
        if (ndiSurface == EGL_NO_SURFACE) {
            EGLint err = eglGetError();
            LOGE("BSM_NDI: eglCreateWindowSurface para NDI FALHOU! Erro EGL: 0x%x", err);
        } else {
            LOGI("BSM_NDI: EGLSurface do NDI criada com SUCESSO!");
        }
    }
}

bool GlesEngine::makeCurrent(EGLSurface targetSurface) {
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return false;
    if (targetSurface == EGL_NO_SURFACE) return false;
    return eglMakeCurrent(display, targetSurface, targetSurface, context);
}

GLuint GlesEngine::getOesTexture() {
    return oesTexture;
}

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    
    GLint status;
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
        GLuint vShader = compileShader(GL_VERTEX_SHADER, VERTEX_SHADER);
        GLuint fClean = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_CLEAN);
        GLuint fOverlay = compileShader(GL_FRAGMENT_SHADER, FRAGMENT_OVERLAY);
        
        cleanProgram = glCreateProgram();
        glAttachShader(cleanProgram, vShader);
        glAttachShader(cleanProgram, fClean);
        glLinkProgram(cleanProgram);
        
        overlayProgram = glCreateProgram();
        glAttachShader(overlayProgram, vShader);
        glAttachShader(overlayProgram, fOverlay);
        glLinkProgram(overlayProgram);
        
        glDeleteShader(vShader);
        glDeleteShader(fClean);
        glDeleteShader(fOverlay);
        
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

void GlesEngine::render() {
    std::lock_guard<std::mutex> lock(renderMutex);
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return;

    // Drenar erros GL pendentes antes de qualquer operação (necessário para Mali GPUs)
    while (glGetError() != GL_NO_ERROR) {}
    
    auto drawPass = [&](EGLSurface destSurface, GLuint programId, bool drawOverlays) {
        if (!makeCurrent(destSurface)) return;
        
        EGLint w, h;
        eglQuerySurface(display, destSurface, EGL_WIDTH, &w);
        eglQuerySurface(display, destSurface, EGL_HEIGHT, &h);
        
        glViewport(0, 0, w, h);
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        
        glUseProgram(programId);
        
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture);
        glUniform1i(glGetUniformLocation(programId, "uTexture"), 0);
        glUniformMatrix4fv(glGetUniformLocation(programId, "uSTMatrix"), 1, GL_FALSE, stMatrix);
        glUniform1f(glGetUniformLocation(programId, "uRotationDegrees"), rotationDegrees);
        
        if (drawOverlays) {
              glUniform1i(glGetUniformLocation(programId, "uZebra"), enableZebra ? 1 : 0);
              glUniform1i(glGetUniformLocation(programId, "uFalseColor"), enableFalseColor ? 1 : 0);
              glUniform1f(glGetUniformLocation(programId, "uZebraThreshold"), zebraThreshold);
              
              glUniform1i(glGetUniformLocation(programId, "uFocusPeaking"), enableFocusPeaking ? 1 : 0);
              glUniform1i(glGetUniformLocation(programId, "uFocusPeakingColor"), focusPeakingColor);
              glUniform1f(glGetUniformLocation(programId, "uFocusPeakingSensitivity"), focusPeakingSensitivity);
              
              glUniform1i(glGetUniformLocation(programId, "uApplyZoom"), 1);
              glUniform1f(glGetUniformLocation(programId, "uZoom"), zoomFactor);
              glUniform1f(glGetUniformLocation(programId, "uPanX"), panX);
              glUniform1f(glGetUniformLocation(programId, "uPanY"), panY);
              
              glUniform1i(glGetUniformLocation(programId, "uLutEnabled"), enableLut ? 1 : 0);
              glUniform1i(glGetUniformLocation(programId, "uLutTexture"), 1);
              
              if (enableLut && lutTexture != 0) {
                  glActiveTexture(GL_TEXTURE1);
                  glBindTexture(GL_TEXTURE_3D, lutTexture);
              }
          } else {
              glUniform1i(glGetUniformLocation(programId, "uApplyZoom"), 0);
          }
        
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)0);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), (void*)(2 * sizeof(GLfloat)));
        glEnableVertexAttribArray(1);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        
        if (drawOverlays && needSnapshot) {
            std::lock_guard<std::mutex> lock(dataMutex);
            snapshotWidth = w;
            snapshotHeight = h;
            snapshotData.resize(w * h);
            glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, snapshotData.data());
            needSnapshot = false;
        }
        
        eglSwapBuffers(display, destSurface);
    };


    // Render for NDI (Clean)
    if (ndiSurface != EGL_NO_SURFACE) {
        drawPass(ndiSurface, cleanProgram, false);
    }
    
    // Render for Recording (Clean)
    if (recordSurface != EGL_NO_SURFACE) {
        drawPass(recordSurface, cleanProgram, false);
    }
    
    // Render for Preview (Overlays)
    if (previewSurface != EGL_NO_SURFACE) {
        drawPass(previewSurface, overlayProgram, true);
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
        glUniform1i(glGetUniformLocation(cleanProgram, "uTexture"), 0);

        // uSTMatrix é a matriz de transformação da SurfaceTexture (câmera OES) —
        // sem ela, o vertex shader multiplica as coordenadas por uma matriz zerada
        // (valor padrão de um uniform mat4 não setado) e todo o frame colapsa pra
        // um único ponto (UV 0,0), fazendo os scopes "travarem" numa cor só em vez
        // de refletir a imagem inteira. O drawPass() usado pelo preview/NDI/
        // gravação já seta isso; este bloco de scopes é uma passada separada que
        // ficou sem essa linha.
        glUniformMatrix4fv(glGetUniformLocation(cleanProgram, "uSTMatrix"), 1, GL_FALSE, stMatrix);
        glUniform1f(glGetUniformLocation(cleanProgram, "uRotationDegrees"), rotationDegrees);
        
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
            size_t totalBytes = static_cast<size_t>(w * h * 4);
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
                    size_t i = (y * w + x) * 4;
                    uint8_t r = ptr[i];
                    uint8_t g = ptr[i+1];
                    uint8_t b = ptr[i+2];

                    int luma = (299 * r + 587 * g + 114 * b) / 1000;
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
            size_t totalBytes = static_cast<size_t>(w * h * 4);

            for (size_t i = 0; i < totalBytes; i += 4) {
                uint8_t r = ptr[i];
                uint8_t g = ptr[i+1];
                uint8_t b = ptr[i+2];

                int u = 128 + (-147 * r - 289 * g + 436 * b) / 1000;
                int v = 128 + (615 * r - 515 * g - 100 * b) / 1000;

                if (u < 0) u = 0; if (u > 255) u = 255;
                if (v < 0) v = 0; if (v > 255) v = 255;

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
        
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        if (previewSurface != EGL_NO_SURFACE) eglDestroySurface(display, previewSurface);
        if (recordSurface != EGL_NO_SURFACE) eglDestroySurface(display, recordSurface);
        if (ndiSurface != EGL_NO_SURFACE) eglDestroySurface(display, ndiSurface);
        if (pbufferSurface != EGL_NO_SURFACE) eglDestroySurface(display, pbufferSurface);
        eglTerminate(display);
    }
    display = EGL_NO_DISPLAY;
    context = EGL_NO_CONTEXT;
    previewSurface = EGL_NO_SURFACE;
    recordSurface = EGL_NO_SURFACE;
    ndiSurface = EGL_NO_SURFACE;
    pbufferSurface = EGL_NO_SURFACE;
    scopeFbo = 0;
    scopeTexture = 0;
    
    if (previewWindow) { ANativeWindow_release(previewWindow); previewWindow = nullptr; }
    if (recordWindow) { ANativeWindow_release(recordWindow); recordWindow = nullptr; }
    if (ndiWindow) { ANativeWindow_release(ndiWindow); ndiWindow = nullptr; }
}

// JNI Wrapper
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

extern "C" JNIEXPORT jint JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetOesTexture(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) return eng->getOesTexture();
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetTransformMatrix(JNIEnv* env, jobject thiz, jlong ptr, jfloatArray matrixArray) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng && matrixArray) {
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
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeRender(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        eng->render();
    }
}

  extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetSettings(
      JNIEnv* env, jobject thiz, jlong ptr,
      jboolean falseColor, jboolean zebra, jint gridType, jfloat aspectRatioMarker,
      jboolean focusPeaking, jfloat zoomFactor, jfloat panX, jfloat panY,
      jfloat rotationDegrees, jboolean lutEnabled, jint scopeType,
      jfloat zebraThreshold, jint focusPeakingColor, jfloat focusPeakingSensitivity) {
      
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
          
          // ✅ ADICIONE ESTAS 3 LINHAS PARA SALVAR OS DADOS DO FOCUS PEAKING
          eng->enableFocusPeaking = focusPeaking;
          eng->focusPeakingColor = focusPeakingColor;
          eng->focusPeakingSensitivity = focusPeakingSensitivity;
      }
  }
  
  extern "C" JNIEXPORT void JNICALL
    Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeSetLutData(
        JNIEnv* env, jobject thiz, jlong ptr, jbyteArray data, jint size) {
        
        if (ptr == 0 || data == nullptr) {
            LOGE("LUT_TRACE C++: Falha! ptr ou data são nulos.");
            return;
        }
        
        LOGI("LUT_TRACE C++: Dados recebidos! Size da dimensão: %d", size);
        
        jbyte* elements = env->GetByteArrayElements(data, nullptr);
        if (elements) {
            GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
            // Agora o cast para (const char*) é válido porque a assinatura no .h foi atualizada
            eng->setLutData((const char*)elements, size);
            env->ReleaseByteArrayElements(data, elements, JNI_ABORT);
            LOGI("LUT_TRACE C++: Textura 3D (GL_RGBA8) carregada na GPU com sucesso.");
        }
    }

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetHistogramR(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->histogramR.empty()) return nullptr;
    jintArray result = env->NewIntArray(256);
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
    env->SetIntArrayRegion(result, 0, 256, reinterpret_cast<const jint*>(eng->histogramB.data()));
    return result;
}


extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetWaveform(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->waveformData.empty()) return nullptr;
    jintArray result = env->NewIntArray(eng->waveformData.size());
    env->SetIntArrayRegion(result, 0, eng->waveformData.size(), reinterpret_cast<const jint*>(eng->waveformData.data()));
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetVectorscope(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->vectorscopeData.empty()) return nullptr;
    jintArray result = env->NewIntArray(eng->vectorscopeData.size());
    env->SetIntArrayRegion(result, 0, eng->vectorscopeData.size(), reinterpret_cast<const jint*>(eng->vectorscopeData.data()));
    return result;
}

void GlesEngine::setLutData(const char* data, int size) {
    makeCurrent(pbufferSurface);
    
    if (lutTexture != 0) {
        glDeleteTextures(1, &lutTexture);
        lutTexture = 0;
    }
    
    glGenTextures(1, &lutTexture);
    
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, lutTexture);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);

    // ✅ CORREÇÃO CRÍTICA PARA SNAPDRAGON: GL_RGBA e GL_UNSIGNED_BYTE
    glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA, size, size, size, 0, GL_RGBA, GL_UNSIGNED_BYTE, data);
    
    // Verificar erros do OpenGL
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        LOGE("LUT_TRACE C++: ERRO AO CRIAR TEXTURA 3D! Código: %d", err);
    } else {
        LOGI("LUT_TRACE C++: glTexImage3D executado sem erros.");
    }
    
    glBindTexture(GL_TEXTURE_3D, 0);
    lutSize = size;
}

extern "C" JNIEXPORT void JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeRequestSnapshot(JNIEnv* env, jobject thiz, jlong ptr) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (eng) {
        eng->needSnapshot = true;
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_bragastudio_mobile_coremedia_graphics_NativeRenderer_nativeGetSnapshot(JNIEnv* env, jobject thiz, jlong ptr, jintArray outDimensions) {
    GlesEngine* eng = reinterpret_cast<GlesEngine*>(ptr);
    if (!eng) return nullptr;
    std::lock_guard<std::mutex> lock(eng->dataMutex);
    if (eng->snapshotData.empty()) return nullptr;
    
    jint dims[2] = { eng->snapshotWidth, eng->snapshotHeight };
    env->SetIntArrayRegion(outDimensions, 0, 2, dims);
    
    jintArray result = env->NewIntArray(eng->snapshotData.size());
    env->SetIntArrayRegion(result, 0, eng->snapshotData.size(), eng->snapshotData.data());
    
    eng->snapshotData.clear();
    return result;
}
