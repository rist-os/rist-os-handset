cmake_minimum_required(VERSION 3.22.1)
project(ristnav C)
add_library(ristnav SHARED ristnav/ristnav.c rn_jni.c)
target_include_directories(ristnav PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/ristnav)
