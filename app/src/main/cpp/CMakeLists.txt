cmake_minimum_required(VERSION 3.22)
project(imci_jni C CXX)

# app/src/main/cpp -> mobile/whisper.cpp and mobile/llama.cpp (the clones used for the PC/phone tests)
set(WHISPER_ROOT ${CMAKE_SOURCE_DIR}/../../../../whisper.cpp)
set(LLAMA_ROOT   ${CMAKE_SOURCE_DIR}/../../../../llama.cpp)

set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD       OFF CACHE BOOL "" FORCE)
# One APK for every phone: build all Android CPU variants
# (armv8.0 for Redmi Note 7 ... armv8.6/9.x with dotprod+i8mm for Poco F5);
# ggml_backend_load_all_from_path() picks the best one at runtime.
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

# llama.cpp first: its ggml (a slightly newer superset of whisper.cpp's copy) is the one both libraries use,
# so speech (whisper) and form filling (llama) share one set of ggml libraries and CPU-variant backends.
add_subdirectory(${LLAMA_ROOT} llama_build)
add_subdirectory(${WHISPER_ROOT} whisper_build)   # sees TARGET ggml and skips its own copy

add_library(imci_jni SHARED imci_jni.c imci_llm.cpp)
target_link_libraries(imci_jni whisper llama ggml ggml-base log android)
