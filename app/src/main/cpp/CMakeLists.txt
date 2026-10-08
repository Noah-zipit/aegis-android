cmake_minimum_required(VERSION 3.22)

project(aegis-llama)

# ---- llama.cpp (CPU only) ----
include(FetchContent)

set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp
    GIT_TAG        b11496
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama)

# ---- our JNI bridge ----
add_library(aegis-llama SHARED native-lib.cpp)

target_link_libraries(aegis-llama PRIVATE llama)

find_library(log-lib log)
target_link_libraries(aegis-llama PRIVATE ${log-lib})
