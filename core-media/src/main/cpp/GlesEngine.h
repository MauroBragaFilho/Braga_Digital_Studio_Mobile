#pragma once
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/native_window.h>
#include <atomic>
#include <cstdint>
#include <vector>
#include <mutex>

// Locations de uniforms resolvidas UMA vez após o link do programa (M56):
// glGetUniformLocation por string a cada frame/passe era custo puro. -1 =
// uniform ausente naquele programa (glUniform* com -1 é ignorado pelo GL).
struct ProgramLocations {
    GLint uTexture = -1;
    GLint uSTMatrix = -1;
    GLint uRotationDegrees = -1;
    GLint uMirrorX = -1;
    GLint uQuadScale = -1;
    GLint uApplyZoom = -1;
    GLint uZoom = -1;
    GLint uPanX = -1;
    GLint uPanY = -1;

    GLint uZebra = -1;
    GLint uFalseColor = -1;
    GLint uZebraThreshold = -1;
    GLint uLutTexture = -1;
    GLint uLutEnabled = -1;
    GLint uLutSize = -1;
    GLint uLutMix = -1;
    GLint uFocusPeaking = -1;
    GLint uFocusPeakingColor = -1;
    GLint uFocusPeakingSensitivity = -1;
};

class GlesEngine {
public:
    GlesEngine();
    int activeScopeType = 0; // 0=None, 1=Histogram, 2=Waveform, 3=Vectorscope
    ~GlesEngine();

    bool init();
    void setPreviewWindow(ANativeWindow* win);
    void setRecordWindow(ANativeWindow* win);
    void setNdiWindow(ANativeWindow* win);
    void setBspWindow(ANativeWindow* win);

    GLuint getOesTexture();
    // timestampNs: SurfaceTexture.getTimestamp() do frame atual (0 = desconhecido).
    // Usado em eglPresentationTimeANDROID para REC/BSP (PTS do instante de captura,
    // não do instante do swap).
    void render(int64_t timestampNs);
    void destroy();

    EGLSurface pbufferSurface;
    bool makeCurrent(EGLSurface surface);

    bool enableFalseColor = false;
    bool enableZebra = false;
    float zebraThreshold = 0.9f;

    bool enableFocusPeaking = false;
    int focusPeakingColor = 0;          // 0=Red, 1=Green, 2=Blue, 3=Yellow, 4=White
    float focusPeakingSensitivity = 0.5f;

    float zoomFactor = 1.0f;
    float panX = 0.0f;
    float panY = 0.0f;
    float rotationDegrees = 0.0f;
    // Rotação do conteúdo nos passes "limpos" (gravação, NDI, BSP). Definida pelo
    // lado Kotlin (OutputOrientation): acompanha o display para manter o mundo em
    // pé (ou 0 para fontes externas). NÃO é mais congelada: o TAMANHO do quadro de
    // cada saída é fixo (o encoder não reinicia), e o conteúdo girado é encaixado
    // nele com barras via a escala do quad abaixo (1,1 = preenche o quadro).
    float outputRotationDegrees = 0.0f;
    float recordScaleX = 1.0f, recordScaleY = 1.0f;
    float ndiScaleX = 1.0f, ndiScaleY = 1.0f;
    float bspScaleX = 1.0f, bspScaleY = 1.0f;
    float stMatrix[16] = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };

    // L5: des-espelhamento horizontal do vertex shader. true = comportamento
    // histórico (aplicado a todas as fontes).
    bool mirrorX = true;

    bool enableLut = false;
    float lutMix = 1.0f; // 0..1 — intensidade da LUT (M45)

    // Aceita dados RGBA em float (size^3*4 floats). Textura RGBA16F.
    void setLutDataFloat(const float* data, int size);
    // Compatibilidade: bytes RGBA8 (size^3*4), convertidos para float.
    void setLutData(const char* data, int size);

    // M18: setado por quem monitora as conexões NDI (fora da thread GL).
    // Sem receptor, o passe do NDI é pulado.
    std::atomic<bool> ndiHasReceivers{true};

    std::vector<int> histogramR;
    std::vector<int> histogramG;
    std::vector<int> histogramB;
    std::vector<int> waveformData;
    std::vector<int> vectorscopeData;

    std::mutex dataMutex;
    std::mutex renderMutex;

    // M24: pedido de snapshot vem de outra thread (Kotlin); o consumo é na thread GL.
    std::atomic<bool> needSnapshot{false};
    // Protegidos por dataMutex:
    bool snapshotReady = false;
    std::vector<int> snapshotData;
    int snapshotWidth = 0;
    int snapshotHeight = 0;

private:
    EGLDisplay display;
    EGLConfig config;
    EGLConfig pbufferConfig;
    EGLContext context;

    EGLSurface previewSurface;
    ANativeWindow* previewWindow;

    EGLSurface recordSurface;
    ANativeWindow* recordWindow;

    EGLSurface ndiSurface;
    ANativeWindow* ndiWindow;

    // Slot de saída do BSP — mesmo mecanismo do NDI: a surface aqui vem do
    // MediaCodec.createInputSurface() (BspManager, lado Kotlin), então o
    // eglSwapBuffers abaixo é o que efetivamente alimenta o encoder H.264.
    EGLSurface bspSurface;
    ANativeWindow* bspWindow;

    GLuint oesTexture;
    GLuint cleanProgram;
    GLuint overlayProgram;
    GLuint vbo;
    ProgramLocations cleanLocs;
    ProgramLocations overlayLocs;

    GLuint scopeFbo;
    GLuint scopeTexture;
    int scopeWidth;
    int scopeHeight;

    GLuint pboIds[2];
    int pboIndex;

    GLuint lutTexture = 0;
    int lutSize = 0;

    // eglPresentationTimeANDROID (nullptr se a extensão não existe).
    PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeFn = nullptr;

    void setupGraphics();
    void updateScopes(int w, int h);
    void bindCommonUniforms(const ProgramLocations& loc, float rotation, float quadScaleX, float quadScaleY);
    // Troca a janela de um slot de saída. noVsync => eglSwapInterval(0) uma
    // única vez para a surface criada (evita backpressure do consumidor).
    void replaceWindow(ANativeWindow*& currentWindow, EGLSurface& currentSurface,
                       ANativeWindow* newWindow, bool noVsync, const char* tag);
};
