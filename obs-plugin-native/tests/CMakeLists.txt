# Testes da logica pura (sem OBS/Qt), com mini-framework proprio (minitest.hpp).
add_executable(bdsm-core-tests test_main.cpp test_tally.cpp test_ndi_link.cpp test_ws.cpp test_misc.cpp minitest.hpp)
target_link_libraries(bdsm-core-tests PRIVATE bdsm-core)
target_include_directories(bdsm-core-tests PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
add_test(NAME bdsm-core-tests COMMAND bdsm-core-tests)
