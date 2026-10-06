# Logica pura (so std): sem OBS, sem Qt. Testada por tests/ e ligada ao plugin.
add_library(
  bdsm-core
  STATIC
  json.cpp
  json.hpp
  link_state.cpp
  link_state.hpp
  mdns.cpp
  mdns.hpp
  ndi_link.cpp
  ndi_link.hpp
  pairing_proto.cpp
  pairing_proto.hpp
  secret_store.cpp
  secret_store.hpp
  tally.cpp
  tally.hpp
  util.cpp
  util.hpp
  ws_codec.cpp
  ws_codec.hpp
)
target_include_directories(bdsm-core PUBLIC "${CMAKE_CURRENT_SOURCE_DIR}")
target_compile_features(bdsm-core PUBLIC cxx_std_17)
set_property(TARGET bdsm-core PROPERTY POSITION_INDEPENDENT_CODE ON)
if(WIN32)
  target_link_libraries(bdsm-core PUBLIC crypt32) # DPAPI (CryptProtectData)
endif()
