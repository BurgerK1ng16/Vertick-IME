cmake_minimum_required(VERSION 3.22.1)
project(weike_jieba)

add_library(weike_jieba SHARED JiebaBridge.cpp)

target_compile_features(weike_jieba PRIVATE cxx_std_17)
target_compile_options(weike_jieba PRIVATE -O3 -fexceptions -frtti)
target_include_directories(weike_jieba PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

find_library(log-lib log)
target_link_libraries(weike_jieba ${log-lib})
