cmake_minimum_required(VERSION 3.22.1)
project(bonsai_jni LANGUAGES CXX)
if(NOT DEFINED LLAMA_SOURCE_DIR OR NOT DEFINED LLAMA_LIBRARY)
    message(FATAL_ERROR "LLAMA_SOURCE_DIR and LLAMA_LIBRARY are required")
endif()
add_library(llama SHARED IMPORTED)
set_target_properties(llama PROPERTIES IMPORTED_LOCATION "\${LLAMA_LIBRARY}")
add_library(bonsai_jni SHARED bonsai_jni.cpp)
target_compile_features(bonsai_jni PRIVATE cxx_std_17)
target_include_directories(bonsai_jni PRIVATE
    "\${LLAMA_SOURCE_DIR}/include"
    "\${LLAMA_SOURCE_DIR}/ggml/include"
)
target_link_libraries(bonsai_jni PRIVATE llama log)
install(TARGETS bonsai_jni LIBRARY DESTINATION lib)
