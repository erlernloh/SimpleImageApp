# CMakeLists.txt for Ultra Detail+ Native Pipeline
# Provides HDR+ style burst alignment, merging, and edge detection

cmake_minimum_required(VERSION 3.22.1)
project("ultradetail" VERSION 1.0.0 LANGUAGES CXX)

# C++17 standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Enable NEON optimizations for ARM
if(ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "armeabi-v7a")
    add_definitions(-DUSE_NEON)
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
    endif()
endif()

# Optimization flags
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3 -ffast-math -funroll-loops")

# Source files
set(ULTRADETAIL_SOURCES
    ultradetail_jni.cpp
    burst_processor.cpp
    pyramid.cpp
    alignment.cpp
    optical_flow.cpp
    phase_correlation.cpp
    tiled_pipeline.cpp
    merge.cpp
    edge_detection.cpp
    yuv_converter.cpp
    mfsr.cpp
)

# Header files
set(ULTRADETAIL_HEADERS
    burst_processor.h
    pyramid.h
    alignment.h
    optical_flow.h
    phase_correlation.h
    tiled_pipeline.h
    merge.h
    edge_detection.h
    yuv_converter.h
    neon_utils.h
    common.h
    mfsr.h
)

# Create shared library
add_library(ultradetail SHARED ${ULTRADETAIL_SOURCES})

# Find required libraries
find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)

# Link libraries
target_link_libraries(ultradetail
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
)

# Include directories
target_include_directories(ultradetail PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
