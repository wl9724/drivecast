cmake_minimum_required(VERSION 3.5.0)
project(drivecast)

add_library(cast SHARED cast.cpp)
target_compile_options(cast PRIVATE -Wall -Werror)
target_link_libraries(cast PUBLIC libace_napi.z.so libnative_avscreen_capture.so libnative_media_codecbase.so
    libnative_media_core.so libnative_media_venc.so libnative_window.so)
