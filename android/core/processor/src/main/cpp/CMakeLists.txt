cmake_minimum_required(VERSION 3.22.1)
project(formsnap_processor LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(OpenCV_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../../../opencv")
find_package(OpenCV REQUIRED)

add_library(formsnap_processor SHARED
    native-lib.cpp
)

target_include_directories(formsnap_processor PRIVATE
    ${OpenCV_INCLUDE_DIRS}
)

target_link_libraries(formsnap_processor
    ${OpenCV_LIBS}
    log
)

set_target_properties(formsnap_processor PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN YES
)
