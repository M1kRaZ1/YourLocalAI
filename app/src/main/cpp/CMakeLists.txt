cmake_minimum_required(VERSION 3.22.1)
project("lightestai")

# Configuration des Threads pour l'outil CMake sur Android
set(CMAKE_THREAD_LIBS_INIT "-lpthread")
set(CMAKE_HAVE_THREADS_LIBRARY 1)
set(CMAKE_USE_WIN32_THREADS_INIT 0)
set(CMAKE_USE_PTHREADS_INIT 1)
set(THREADS_PREFER_PTHREAD_FLAG ON)

# Flags de compilation spécifiques par architecture
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -march=armv7-a -mfpu=neon -mfloat-abi=softfp")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv7-a -mfpu=neon -mfloat-abi=softfp -std=c++17")
else()
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -std=c++17")
endif()

# Options de configuration de llama.cpp pour faible mémoire (1 Go RAM / ARMv7-A)
set(GGML_OPENMP OFF CACHE BOOL "Disable OpenMP" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "Disable llamafile for ARMv7 compatibility" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "Disable native host flags" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "Disable server" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "Disable examples" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "Disable tests" FORCE)

# Inclusion du sous-dossier llama.cpp
add_subdirectory(llama.cpp)

# Déclaration de la bibliothèque native-lib (JNI)
add_library(native-lib SHARED native-lib.cpp)

target_include_directories(native-lib PRIVATE
        llama.cpp/include
        llama.cpp/ggml/include
        llama.cpp/src)

target_link_libraries(native-lib llama log)
