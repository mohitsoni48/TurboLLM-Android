# Native LiteRT-LM server, cross-compiled with the NDK by scripts/stage-litert-lm-engine.mjs.
#
# The output is an EXECUTABLE named like a library — liblitertlm_server.so — because Android only
# installs (and lets an app execute) files under lib/<abi>/ that match lib*.so. It is staged into
# jniLibs next to liblitert-lm.so, which it dlopens at runtime (src/litert_lm_api.h explains why
# it is loaded rather than linked).
cmake_minimum_required(VERSION 3.22)
project(litertlm_server CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

set(LITERT_LM_VERSION "unknown" CACHE STRING "LiteRT-LM version of the liblitert-lm.so shipped beside this server")

add_executable(litertlm_server src/main.cc src/litert_lm_api.cc)
target_include_directories(litertlm_server PRIVATE third_party/litert_lm third_party)
target_compile_definitions(litertlm_server PRIVATE LITERT_LM_VERSION="${LITERT_LM_VERSION}")
target_compile_options(litertlm_server PRIVATE -Wall -Wextra)
target_link_libraries(litertlm_server PRIVATE dl)

if(ANDROID)
  set_target_properties(litertlm_server PROPERTIES OUTPUT_NAME "liblitertlm_server" SUFFIX ".so")
  # The C++ runtime is linked statically (ANDROID_STL=c++_static, set by the staging script) and
  # nothing from it is exported, so it can never meet the one liblitert-lm.so carries inside it.
  # max-page-size: 16 KB-page devices refuse to load a 4 KB-aligned ELF, and Play requires it.
  target_link_options(litertlm_server PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384 -s)
else()
  # Host builds exist only to test the server against the desktop liblitert-lm.so.
  target_link_options(litertlm_server PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL -pthread)
endif()
