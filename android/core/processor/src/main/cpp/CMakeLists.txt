cmake_minimum_required(VERSION 3.22.1)
project(formsnap_processor LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

find_package(OpenCV REQUIRED COMPONENTS OpenCV::opencv_java4)

add_library(formsnap_processor SHARED
    native-lib.cpp
)

target_link_libraries(formsnap_processor
    OpenCV::opencv_java4
    log
)
