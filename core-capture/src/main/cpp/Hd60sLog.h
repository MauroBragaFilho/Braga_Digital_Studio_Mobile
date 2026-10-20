#pragma once

#ifdef __ANDROID__
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "Hd60s", __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "Hd60s", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "Hd60s", __VA_ARGS__)
#else
#include <cstdio>
#define LOGI(...) (std::fprintf(stderr, "[I] " __VA_ARGS__), std::fputc('\n', stderr))
#define LOGW(...) (std::fprintf(stderr, "[W] " __VA_ARGS__), std::fputc('\n', stderr))
#define LOGE(...) (std::fprintf(stderr, "[E] " __VA_ARGS__), std::fputc('\n', stderr))
#endif
