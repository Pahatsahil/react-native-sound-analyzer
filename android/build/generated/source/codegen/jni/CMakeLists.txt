# Copyright (c) Meta Platforms, Inc. and affiliates.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.

cmake_minimum_required(VERSION 3.13)
set(CMAKE_VERBOSE_MAKEFILE on)

file(GLOB react_codegen_SRCS CONFIGURE_DEPENDS *.cpp react/renderer/components/ReactNativeSoundAnalyzerSpec/*.cpp)

add_library(
  react_codegen_ReactNativeSoundAnalyzerSpec
  OBJECT
  ${react_codegen_SRCS}
)

target_include_directories(react_codegen_ReactNativeSoundAnalyzerSpec PUBLIC . react/renderer/components/ReactNativeSoundAnalyzerSpec)

target_link_libraries(
  react_codegen_ReactNativeSoundAnalyzerSpec
  fbjni
  jsi
  # We need to link different libraries based on whether we are building rncore or not, that's necessary
  # because we want to break a circular dependency between react_codegen_rncore and reactnative
  reactnative
)

target_compile_options(
  react_codegen_ReactNativeSoundAnalyzerSpec
  PRIVATE
  -DLOG_TAG=\"ReactNative\"
  -fexceptions
  -frtti
  -std=c++20
  -Wall
)
