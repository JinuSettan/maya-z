cmake_minimum_required(VERSION 3.22.1)

project("llama_jni" C CXX)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build shared libraries" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(
    llama_jni
    SHARED
    llama_jni.cpp
)

target_include_directories(
    llama_jni
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/include
    ${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/ggml/include
)

find_library(
    log-lib
    log
)

target_link_libraries(
    llama_jni
    llama
    ${log-lib}
)
