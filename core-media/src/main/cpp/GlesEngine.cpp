#include "GlesEngine.h"
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#define LOG_TAG "GlesEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char* VERTEX_SHADER = R"(
#version 300 es
layout(location = 0) in vec4 aPosition;
layout(location = 1) in vec2 aTexCoord;
out vec2 vTexCoord;
uniform float uZoom;
uniform float uPanX;
uniform float uPanY;
uniform bool uApplyZoom;
void main() {
    gl_Position = aPosition;
    if (uApplyZoom) {
        vec2 center = vec2(0.5, 0.5);
        vec2 zoomed = (aTexCoord - center) / uZoom + center;
        zoomed += vec2(uPanX, uPanY);
        vTexCoord = zoomed;
    } else {
        vTexCoord = aTexCoord;
    }
}
)";

// Clean shader for Recording, NDI and Scopes
static const char* FRAGMENT_CLEAN = R"(
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform samplerExternalOES uTexture;

void main() {
    fragColor = texture(uTexture, vTexCoord);
}
)";

static const char* FRAGMENT_OVERLAY = R"(
#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vTexCoord;
out vec4 fragColor;
uniform samplerExternalOES uTexture;

uniform bool uZebra;
uniform bool uFalseColor;
uniform float uZebraThreshold;

uniform sampler3D uLutTexture;
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
        // ✅ OFFSET MÍNIMO para capturar APENAS detalhes ultra-finos de foco
        float offset = 0.001; 
        
        float lumaTL = getLuma(texture(uTexture, vTexCoord + vec2(-offset, -offset)).rgb);
        float lumaT  = getLuma(texture(uTexture, vTexCoord + vec2(0.0, -offset)).rgb);
        float lumaTR = getLuma(texture(uTexture, vTexCoord + vec2(offset, -offset)).rgb);
        float lumaL  = getLuma(texture(uTexture, vTexCoord + vec2(-offset, 0.0)).rgb);
        float lumaR  = getLuma(texture(uTexture, vTexCoord + vec2(offset, 0.0)).rgb);
        float lumaBL = getLuma(texture(uTexture, vTexCoord + vec2(-offset, offset)).rgb);
        float lumaB  = getLuma(texture(uTexture, vTexCoord + vec2(0.0, offset)).rgb);
        float lumaBR = getLuma(texture(uTexture, vTexCoord + vec2(offset, offset)).rgb);
        
        // Operador Sobel
        float gx = (-lumaTL + lumaTR) + 2.0 * (-lumaL + lumaR) + (-lumaBL + lumaBR);
        float gy = (-lumaTL - 2.0 * lumaT - lumaTR) + (lumaBL + 2.0 * lumaB + lumaBR);
        
        float edge = sqrt(gx * gx + gy * gy);
        
        // ✅ Com thresholds de 0.35 a 0.70, APENAS bordas extremamente nítidas serão pintadas
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
)";

GlesEngine::GlesEngine() : display(EGL_NO_DISPLAY), context(EGL_NO_CONTEXT), pbufferSurface(EGL_NO_SURFACE), 
    previewSurface(EGL_NO_SURFACE), previewWindow(nullptr),
    recordSurface(EGL_NO_SURFACE), recordWindow(nullptr),
    ndiSurface(EGL_NO_SURFACE), ndiWindow(nullptr),
    oesTexture(0), cleanProgram(0), overlayProgram(0), vbo(0),
    scopeFbo(0), scopeTexture(0), scopeWidth(256), scopeHeight(144) {
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
    if (display == EGL_NO_DISPLAY) return false;

    EGLint major, minor;
    if (!eglInitialize(display, &major, &minor)) return false;

    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_BLUE_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_RED_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE
    };

    EGLint numConfigs;
    if (!eglChooseConfig(display, configAttribs, &config, 1, &numConfigs) || numConfigs <= 0) {
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };

    context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttribs);
    if (context == EGL_NO_CONTEXT) return false;

    const EGLint pbufferAttribs[] = {
        EGL_WIDTH, 1,
        EGL_HEIGHT, 1,
        EGL_NONE
    };
    pbufferSurface = eglCreatePbufferSurface(display, config, pbufferAttribs);
    
    eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
    
    setupGraphics();
    return true;
}

void GlesEngine::setPreviewWindow(ANativeWindow* win) {
    if (previewWindow == win) return;
    
    if (previewSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        eglDestroySurface(display, previewSurface);
        previewSurface = EGL_NO_SURFACE;
    }
    if (previewWindow) ANativeWindow_release(previewWindow);
    
    previewWindow = win;
    if (previewWindow) {
        previewSurface = eglCreateWindowSurface(display, config, previewWindow, nullptr);
    }
}

void GlesEngine::setRecordWindow(ANativeWindow* win) {
    if (recordWindow == win) return;
    
    if (recordSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        eglDestroySurface(display, recordSurface);
        recordSurface = EGL_NO_SURFACE;
    }
    if (recordWindow) ANativeWindow_release(recordWindow);
    
    recordWindow = win;
    if (recordWindow) {
        recordSurface = eglCreateWindowSurface(display, config, recordWindow, nullptr);
    }
}

void GlesEngine::setNdiWindow(ANativeWindow* win) {
    if (ndiWindow == win) return;
    
    if (ndiSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        eglDestroySurface(display, ndiSurface);
        ndiSurface = EGL_NO_SURFACE;
    }
    if (ndiWindow) ANativeWindow_release(ndiWindow);
    
    ndiWindow = win;
    if (ndiWindow) {
        ndiSurface = eglCreateWindowSurface(display, config, ndiWindow, nullptr);
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
    
    // Setup FBO for scopes
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
}

void GlesEngine::render() {
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) return;
    
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
        
        if (drawOverlays) {
              glUniform1i(glGetUniformLocation(programId, "uZebra"), enableZebra ? 1 : 0);
              glUniform1i(glGetUniformLocation(programId, "uFalseColor"), enableFalseColor ? 1 : 0);
              glUniform1f(glGetUniformLocation(programId, "uZebraThreshold"), zebraThreshold);
              
              // ✅ ADICIONE ESTAS 3 LINHAS:
              glUniform1i(glGetUniformLocation(programId, "uFocusPeaking"), enableFocusPeaking ? 1 : 0);
              glUniform1i(glGetUniformLocation(programId, "uFocusPeakingColor"), focusPeakingColor);
              glUniform1f(glGetUniformLocation(programId, "uFocusPeakingSensitivity"), focusPeakingSensitivity);
              
              glUniform1i(glGetUniformLocation(programId, "uApplyZoom"), 1);
              glUniform1f(glGetUniformLocation(programId, "uZoom"), zoomFactor);
              glUniform1f(glGetUniformLocation(programId, "uPanX"), panX);
              glUniform1f(glGetUniformLocation(programId, "uPanY"), panY);
              
              glUniform1i(glGetUniformLocation(programId, "uLutEnabled"), enableLut ? 1 : 0);
              glUniform1i(glGetUniformLocation(programId, "uLutTexture"), 1); // ALWAYS bind sampler3D to unit 1 to prevent conflict with unit 0 (samplerExternalOES)
              
              if (enableLut && lutTexture != 0) {
                  glActiveTexture(GL_TEXTURE1);
                  glBindTexture(GL_TEXTURE_3D, lutTexture);
              }
          } else {
              glUniform1i(glGetUniformLocation(programId, "uApplyZoom"), 0);
              // if programId == cleanProgram, we might need to define uApplyZoom there too.
              // Actually cleanProgram shares VERTEX_SHADER!
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
    
    // Scopes FBO Pass
    if (makeCurrent(pbufferSurface)) {
        glBindFramebuffer(GL_FRAMEBUFFER, scopeFbo);
        glViewport(0, 0, scopeWidth, scopeHeight);
        
        glUseProgram(cleanProgram);
        
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, oesTexture);
        glUniform1i(glGetUniformLocation(cleanProgram, "uTexture"), 0);
        
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
    
    std::vector<uint8_t> pixels(w * h * 4);
    // Reading 256x144 pixels is very fast (147KB) and doesn't drop FPS
    glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
    
    std::lock_guard<std::mutex> lock(dataMutex);
    
    if (activeScopeType == 1) { // Histogram
        std::fill(histogramR.begin(), histogramR.end(), 0);
        std::fill(histogramG.begin(), histogramG.end(), 0);
        std::fill(histogramB.begin(), histogramB.end(), 0);
        
        for (size_t i = 0; i < pixels.size(); i += 4) {
            uint8_t r = pixels[i];
            uint8_t g = pixels[i+1];
            uint8_t b = pixels[i+2];
            uint8_t luma = static_cast<uint8_t>(0.299f * r + 0.587f * g + 0.114f * b);
            histogramR[luma]++;
        }
    } else if (activeScopeType == 2) { // Waveform
        std::fill(waveformData.begin(), waveformData.end(), 0);
        
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                size_t i = (y * w + x) * 4;
                uint8_t r = pixels[i];
                uint8_t g = pixels[i+1];
                uint8_t b = pixels[i+2];
                
                int luma = (299 * r + 587 * g + 114 * b) / 1000;
                if (luma < 0) luma = 0;
                if (luma > 255) luma = 255;
                
                int mappedX = (x * 256) / w;
                if (mappedX > 255) mappedX = 255;
                
                // Pack R, G, B, L counts into one 32-bit int. Cap each at 255.
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
        
        for (size_t i = 0; i < pixels.size(); i += 4) {
            uint8_t r = pixels[i];
            uint8_t g = pixels[i+1];
            uint8_t b = pixels[i+2];
            
            int u = 128 + (-147 * r - 289 * g + 436 * b) / 1000;
            int v = 128 + (615 * r - 515 * g - 100 * b) / 1000;
            
            if (u < 0) u = 0; if (u > 255) u = 255;
            if (v < 0) v = 0; if (v > 255) v = 255;
            
            vectorscopeData[v * 256 + u]++;
        }
    }
}

void GlesEngine::destroy() {
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
        
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
    }  eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
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
        eng->destroy();
        delete eng;
        if (eng == engine) engine = nullptr;
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
