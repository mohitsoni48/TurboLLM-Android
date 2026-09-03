cmake_minimum_required(VERSION 3.22.1)
project("turbollm")

add_library( libnode SHARED IMPORTED )
set_target_properties( libnode
        PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/libnode/bin/${ANDROID_ABI}/libnode.so )

add_library( native-lib SHARED native-lib.cpp )

target_include_directories( native-lib PRIVATE ${CMAKE_SOURCE_DIR}/libnode/include/node/ )

target_link_libraries( native-lib libnode ${log-lib} )
