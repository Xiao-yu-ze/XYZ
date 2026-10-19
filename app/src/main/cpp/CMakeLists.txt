cmake_minimum_required(VERSION 3.18)

project(pvz2capture CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(pvz2capture SHARED
        capture.cpp)

# mediandk 提供 AImageReader / AImage；
# android 提供 ANativeWindow_toSurface 与 AHardwareBuffer 常量。
target_link_libraries(pvz2capture
        android
        log
        mediandk
        jnigraphics)
