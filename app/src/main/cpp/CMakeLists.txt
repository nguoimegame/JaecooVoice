# libjaecoo-espeak.so: espeak-ng text -> IPA phonemes for Piper TTS models. sherpa-onnx 2.x no
# longer ships a phonemizer. espeak-ng is GPL-3.0, so this library (and the APK) are GPL-3.0.
#
# espeak-ng is the fork sherpa-onnx v1.13.5 used (cmake/espeak-ng-for-piper.cmake):
# csukuangfj/espeak-ng @ ed530aa113046142eb5115cf2fc9157854d0ffe1 (git submodule), which has the
# piper-phonemize API espeak_TextToPhonemesWithTerminator. Same build flags as sherpa-onnx.
cmake_minimum_required(VERSION 3.22.1)
project(jaecoo-espeak C CXX)

set(BUILD_ESPEAK_NG_TESTS OFF CACHE BOOL "" FORCE)
set(BUILD_ESPEAK_NG_EXE OFF CACHE BOOL "" FORCE)
set(USE_ASYNC OFF CACHE BOOL "" FORCE)
set(USE_MBROLA OFF CACHE BOOL "" FORCE)
set(USE_LIBSONIC OFF CACHE BOOL "" FORCE)
set(USE_LIBPCAUDIO OFF CACHE BOOL "" FORCE)
set(USE_KLATT OFF CACHE BOOL "" FORCE)
set(USE_SPEECHPLAYER OFF CACHE BOOL "" FORCE)
set(EXTRA_cmn ON CACHE BOOL "" FORCE)
set(EXTRA_ru ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

set(ESPEAK_NG_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/espeak-ng)
# EXCLUDE_FROM_ALL: only what jaecoo-espeak links gets built. BUILD_ESPEAK_NG_EXE=OFF already skips
# compiling espeak-ng-data (that needs a host binary); the data comes inside the model archives.
add_subdirectory(${ESPEAK_NG_DIR} espeak-ng EXCLUDE_FROM_ALL)

set_target_properties(espeak-ng ucd PROPERTIES
  POSITION_INDEPENDENT_CODE ON
  C_VISIBILITY_PRESET hidden
  CXX_VISIBILITY_PRESET hidden
)
target_compile_options(espeak-ng PRIVATE
  -Wno-unused-result
  -Wno-format
  -Wno-uninitialized
  -Wno-unused-parameter
  -Wno-missing-prototypes
)
target_include_directories(espeak-ng INTERFACE
  ${ESPEAK_NG_DIR}/src/include
  ${ESPEAK_NG_DIR}/src/ucd-tools/src/include
)

add_library(jaecoo-espeak SHARED espeak_jni.cpp)
target_link_libraries(jaecoo-espeak PRIVATE espeak-ng log)
# 16 KB pages (Android 15+ devices); harmless on the head unit.
target_link_options(jaecoo-espeak PRIVATE "-Wl,-z,max-page-size=16384")
