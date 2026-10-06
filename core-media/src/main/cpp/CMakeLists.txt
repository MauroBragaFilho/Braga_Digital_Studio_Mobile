cmake_minimum_required(VERSION 3.22.1)
project("bdsm-media")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NDI Library
add_library(ndi SHARED IMPORTED)
set_target_properties(ndi PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libndi.so
)

include_directories(${CMAKE_SOURCE_DIR}/ndi/Include)

add_library(
    bdsm-media
    SHARED
    GlesEngine.cpp
    NdiEngine.cpp
    NdiNetwork.cpp
)

# M3: otimização/diagnóstico + símbolos escondidos por padrão. As funções JNI
# continuam visíveis porque JNIEXPORT expande para visibility("default").
target_compile_options(bdsm-media PRIVATE
    -O2
    -Wall
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)

# M3: alinhamento de 16 KB das páginas (Android 15+ / Google Play) e sem
# reexportar símbolos de bibliotecas estáticas. (-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES
# fica no build.gradle.)
target_link_options(bdsm-media PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections
)

find_library(log-lib log)
find_library(android-lib android)
find_library(gles3-lib GLESv3)
find_library(egl-lib EGL)

target_link_libraries(
    bdsm-media
    ndi
    ${log-lib}
    ${android-lib}
    ${gles3-lib}
    ${egl-lib}
)
