cmake_minimum_required(VERSION 3.22.1)
project("mobi_native")

add_library(mobi_native SHARED mobi_native.c)

find_library(log-lib log)
target_link_libraries(mobi_native ${log-lib})
