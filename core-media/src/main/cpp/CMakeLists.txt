cmake_minimum_required(VERSION 3.22.1)
project("bdsm-media")

set(CMAKE_CXX_STANDARD 17)

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
