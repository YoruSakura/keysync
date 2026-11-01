cmake_minimum_required(VERSION 3.22.1)

project(keysync_root LANGUAGES CXX)

add_library(
        keysync_root
        SHARED
        RootEvdevCapture.cpp
        RootVirtualGamepad.cpp)

target_compile_features(keysync_root PRIVATE cxx_std_17)

find_library(log_library log REQUIRED)
target_link_libraries(keysync_root PRIVATE ${log_library})
