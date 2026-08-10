#pragma once
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <vector>
#include <mutex>

class GlesEngine {
public:
    GlesEngine();
    int activeScopeType = 0; // 0=None, 1=Histogram, 2=Waveform, 3=Vectorscope
    ~GlesEngine();

    bool init();
    void setPreviewWindow(ANativeWindow* win);
    void setRecordWindow(ANativeWindow* win);
    void setNdiWindow(ANativeWindow* win);
    
    GLuint getOesTexture();
    void render();
    void destroy();

    EGLSurface pbufferSurface;
    bool makeCurrent(EGLSurface surface);

    bool enableFalseColor = false;
    bool enableZebra = false;
    float zebraThreshold = 0.9f;

    bool enableFocusPeaking = false;
    int focusPeakingColor = 0;          // 0=Red, 1=Green, 2=Blue, 3=White
    float focusPeakingSensitivity = 0.5f;

    float zoomFactor = 1.0f;
    float panX = 0.0f;
    float panY = 0.0f;
    float rotationDegrees = 0.0f;
    float stMatrix[16] = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };
    void setSTMatrix(const float* matrix);

    bool enableLut = false;

    
    void setLutData(const char* data, int size);

    std::vector<int> histogramR;
    std::vector<int> histogramG;
    std::vector<int> histogramB;
    std::vector<int> waveformData;
    std::vector<int> vectorscopeData;
    
    std::mutex dataMutex;
    std::mutex renderMutex;

    bool needSnapshot = false;
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
    
    GLuint oesTexture;
    GLuint cleanProgram;
    GLuint overlayProgram;
    GLuint vbo;
    
    GLuint scopeFbo;
    GLuint scopeTexture;
    int scopeWidth;
    int scopeHeight;

    GLuint pboIds[2];
    int pboIndex;

    GLuint lutTexture = 0;
    int lutSize = 0;

    void setupGraphics();
    void updateScopes(int w, int h);
};


