cmake_minimum_required(VERSION 3.22.1)

project(formsnap_processor LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(FORMSNAP_NATIVE_ROOT "${CMAKE_CURRENT_LIST_DIR}")

# Preserve the existing native layout:
#
# cpp/
# ├── include/
# │   └── OpenCV headers
# └── jniLibs/
#     ├── arm64-v8a/libopencv_java5.so
#     └── armeabi-v7a/libopencv_java5.so
#
set(FORMSNAP_OPENCV_INCLUDE_DIR
    "${FORMSNAP_NATIVE_ROOT}/include"
)

set(FORMSNAP_OPENCV_LIBRARY
    "${FORMSNAP_NATIVE_ROOT}/jniLibs/${ANDROID_ABI}/libopencv_java5.so"
)

if(NOT EXISTS "${FORMSNAP_OPENCV_INCLUDE_DIR}/opencv.hpp")
    message(FATAL_ERROR
        "Missing OpenCV headers: ${FORMSNAP_OPENCV_INCLUDE_DIR}/opencv.hpp"
    )
endif()

if(NOT EXISTS "${FORMSNAP_OPENCV_LIBRARY}")
    message(FATAL_ERROR
        "Missing OpenCV native library for ABI ${ANDROID_ABI}: ${FORMSNAP_OPENCV_LIBRARY}"
    )
endif()

add_library(formsnap_opencv_java5 SHARED IMPORTED GLOBAL)

set_target_properties(formsnap_opencv_java5 PROPERTIES
    IMPORTED_LOCATION "${FORMSNAP_OPENCV_LIBRARY}"
    INTERFACE_INCLUDE_DIRECTORIES "${FORMSNAP_OPENCV_INCLUDE_DIR}"
)

add_library(formsnap_processor SHARED native-lib.cpp)

target_include_directories(formsnap_processor
    PRIVATE
        "${FORMSNAP_NATIVE_ROOT}"
        "${FORMSNAP_OPENCV_INCLUDE_DIR}"
)

target_link_libraries(formsnap_processor
    PRIVATE
        formsnap_opencv_java5
        android
        log
)

set_target_properties(formsnap_processor PROPERTIES
    POSITION_INDEPENDENT_CODE ON
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

target_compile_options(formsnap_processor
    PRIVATE
        -Wall
        -Wextra
        -Wpedantic
        -ffunction-sections
        -fdata-sections
)

target_link_options(formsnap_processor
    PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
)
