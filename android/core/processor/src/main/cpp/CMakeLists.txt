cmake_minimum_required(VERSION 3.22.1)
project(formsnap_processor LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(formsnap_processor SHARED
    native-lib.cpp
)

target_link_libraries(formsnap_processor
    log
)
