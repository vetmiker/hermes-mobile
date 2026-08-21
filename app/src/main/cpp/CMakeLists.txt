cmake_minimum_required(VERSION 3.22.1)
project(whisper_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# The app ships only the pinned CPU implementation.  Do not let CMake discover
# a host GPU backend while configuring an Android build.
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)

set(WHISPER_CPP_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp)
if(NOT EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "Pinned third_party/whisper.cpp submodule is missing; run git submodule update --init --recursive")
endif()

add_subdirectory(${WHISPER_CPP_DIR} ${CMAKE_BINARY_DIR}/whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${WHISPER_CPP_DIR}/include)
target_compile_options(whisper_jni PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
target_link_options(whisper_jni PRIVATE -Wl,--gc-sections)
if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(whisper_jni PRIVATE -O3)
endif()
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(whisper_jni PRIVATE -march=armv8.2-a+fp16)
endif()
target_link_libraries(whisper_jni PRIVATE whisper android log)
