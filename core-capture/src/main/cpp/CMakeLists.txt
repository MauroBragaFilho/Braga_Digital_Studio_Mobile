cmake_minimum_required(VERSION 3.22.1)
project("bdsm-hd60s")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Captura da Elgato HD60 S (não-UVC) por usbfs: parser do fluxo, conversão YUY2->RGBA e sessão.
add_library(
    bdsm-hd60s
    SHARED
    Hd60sConvert.cpp
    Hd60sDescriptors.cpp
    Hd60sFrontEnd.cpp
    Hd60sJni.cpp
    Hd60sMstarFrontEnd.cpp
    Hd60sParser.cpp
    Hd60sPortModes.cpp
    Hd60sPortMstar.cpp
    Hd60sSession.cpp
    Hd60sUsb.cpp
)

target_compile_options(bdsm-hd60s PRIVATE
    -O2
    -Wall
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)

# Alinhamento de 16 KB das páginas (Android 15+ / Google Play), como em :core-media.
target_link_options(bdsm-hd60s PRIVATE
    -Wl,-z,max-page-size=16384
    -Wl,--exclude-libs,ALL
    -Wl,--gc-sections
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(bdsm-hd60s ${log-lib} ${android-lib})
