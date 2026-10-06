cmake_minimum_required(VERSION 3.28...3.30)

# Modo "somente testes": logica pura (core/) + testes, sem OBS/Qt/MSVC do template.
# Uso: cmake --preset tests-only && cmake --build --preset tests-only && ctest --preset tests-only
option(BDSM_TESTS_ONLY "Compila apenas core/ e tests/ (sem OBS e sem Qt)" OFF)
if(BDSM_TESTS_ONLY)
  project(bdsm-link-tests LANGUAGES CXX)
  set(CMAKE_CXX_STANDARD 17)
  set(CMAKE_CXX_STANDARD_REQUIRED ON)
  enable_testing()
  add_subdirectory(core)
  add_subdirectory(tests)
  return()
endif()

include("${CMAKE_CURRENT_SOURCE_DIR}/cmake/common/bootstrap.cmake" NO_POLICY_SCOPE)

project(${_name} VERSION ${_version})

option(ENABLE_FRONTEND_API "Use obs-frontend-api for UI functionality" ON)
option(ENABLE_QT "Use Qt functionality" ON)
option(BDSM_BUILD_TESTS "Compila e registra os testes da logica pura (CTest)" ON)

include(compilerconfig)
include(defaults)
include(helpers)

# Logica pura (JSON, LinkState, tally, WebSocket, pareamento, mDNS, armazenamento)
add_subdirectory(core)

if(BDSM_BUILD_TESTS)
  enable_testing()
  add_subdirectory(tests)
endif()

add_library(${CMAKE_PROJECT_NAME} MODULE)

find_package(libobs REQUIRED)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::libobs)

find_package(obs-frontend-api REQUIRED)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE OBS::obs-frontend-api)

# Qt6 pre-compilado do obs-deps (Core, Gui, Widgets, Network...). NAO traz Qt WebSockets:
# o WebSocket e feito em core/ws_codec sobre QTcpSocket.
find_package(Qt6 REQUIRED COMPONENTS Core Widgets Network)
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE Qt6::Core Qt6::Widgets Qt6::Network)
set_target_properties(${CMAKE_PROJECT_NAME} PROPERTIES AUTOMOC ON AUTOUIC ON AUTORCC ON)

target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE bdsm-core)

target_sources(
  ${CMAKE_PROJECT_NAME}
  PRIVATE
    src/plugin-main.cpp
    src/bdsm_dock.cpp
    src/bdsm_dock.hpp
    src/device_manager.cpp
    src/device_manager.hpp
    src/link_connection.cpp
    src/link_connection.hpp
    src/link_types.hpp
    src/mdns_discovery.cpp
    src/mdns_discovery.hpp
    src/obs_ndi_integration.cpp
    src/obs_ndi_integration.hpp
    src/obs_scene_graph.cpp
    src/obs_scene_graph.hpp
    src/tally_controller.cpp
    src/tally_controller.hpp
)

set_target_properties_plugin(${CMAKE_PROJECT_NAME} PROPERTIES OUTPUT_NAME ${_name})
