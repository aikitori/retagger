cmake_minimum_required(VERSION 3.22.1)
project(retagger C)

# LAME 3.100 (LGPL), unchanged sources, built as its own shared library
file(GLOB LAME_SOURCES lame/*.c)
add_library(mp3lame SHARED ${LAME_SOURCES})
target_include_directories(mp3lame PUBLIC lame)
# normally set by configure in config.h
target_compile_definitions(mp3lame PRIVATE STDC_HEADERS HAVE_STDINT_H HAVE_LIMITS_H ieee754_float32_t=float)
target_compile_options(mp3lame PRIVATE -O2 -w)

add_library(lamejni SHARED lame_jni.c)
target_link_libraries(lamejni mp3lame)
