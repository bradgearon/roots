cmake_minimum_required(VERSION 3.22.1)
project(roots_plamo LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
include(FetchContent)
# Keep native inference optimized even in a debuggable Android APK.
# Debug symbols/assertions stay enabled; avoid -O0 in CPU model kernels.
add_compile_options("$<$<AND:$<CONFIG:Debug>,$<COMPILE_LANGUAGE:C,CXX>>:-O3>")
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
# ARM's optimized kernels detect dot-product/I8MM/SME support at runtime.
# Do not impose a Pixel-specific instruction set on the whole APK.
option(ROOTS_ENABLE_KLEIDIAI "Use runtime-dispatched Arm KleidiAI kernels" ON)
option(ROOTS_ENABLE_VULKAN "Build experimental Vulkan inference backend" OFF)
if(ANDROID_ABI STREQUAL "arm64-v8a" AND ROOTS_ENABLE_KLEIDIAI)
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()
set(GGML_VULKAN ${ROOTS_ENABLE_VULKAN} CACHE BOOL "" FORCE)
if(ROOTS_ENABLE_VULKAN AND ANDROID)
    # glslc runs on the build host; libvulkan must be the Android NDK library.
    set(ROOTS_VULKAN_SDK "$ENV{VULKAN_SDK}" CACHE PATH "Host Vulkan SDK")
    if(NOT ROOTS_VULKAN_SDK)
        message(FATAL_ERROR "Vulkan build requires VULKAN_SDK or ROOTS_VULKAN_SDK (host glslc and headers)")
    endif()
    find_library(ROOTS_ANDROID_VULKAN_LIBRARY vulkan REQUIRED)
    find_program(ROOTS_HOST_GLSLC NAMES glslc glslc.exe HINTS "${ROOTS_VULKAN_SDK}/Bin" "${ROOTS_VULKAN_SDK}/bin" NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    find_path(ROOTS_VULKAN_INCLUDE vulkan/vulkan.hpp HINTS "${ROOTS_VULKAN_SDK}/Include" "${ROOTS_VULKAN_SDK}/include" NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    set(Vulkan_LIBRARY "${ROOTS_ANDROID_VULKAN_LIBRARY}" CACHE FILEPATH "Android Vulkan loader" FORCE)
    set(Vulkan_INCLUDE_DIR "${ROOTS_VULKAN_INCLUDE}" CACHE PATH "Vulkan headers" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${ROOTS_HOST_GLSLC}" CACHE FILEPATH "Host shader compiler" FORCE)
    # SPIRV-Headers is header-only. Some Windows SDK distributions contain
    # spirv.hpp but no standalone CMake package. The Android toolchain also
    # excludes host prefixes from package search unless explicitly permitted.
    set(ROOTS_SPIRV_HEADERS_DIR "" CACHE PATH "Optional directory containing SPIRV-HeadersConfig.cmake")
    if(ROOTS_SPIRV_HEADERS_DIR)
        set(SPIRV-Headers_DIR "${ROOTS_SPIRV_HEADERS_DIR}" CACHE PATH "SPIRV-Headers config" FORCE)
        find_package(SPIRV-Headers CONFIG REQUIRED NO_CMAKE_FIND_ROOT_PATH)
    else()
        # Discard cached automatic resolution when SDK/profile changes.
        unset(SPIRV-Headers_DIR CACHE)
        find_package(SPIRV-Headers CONFIG QUIET
            PATHS "${ROOTS_VULKAN_SDK}" NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
        if(NOT SPIRV-Headers_FOUND)
            # Prefer the matching SDK headers, even if no package was installed.
            unset(ROOTS_SPIRV_INCLUDE CACHE)
            find_path(ROOTS_SPIRV_INCLUDE spirv/unified1/spirv.hpp
                PATHS "${ROOTS_VULKAN_SDK}/Include" "${ROOTS_VULKAN_SDK}/include"
                NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
            if(NOT ROOTS_SPIRV_INCLUDE)
                message(STATUS "SDK lacks SPIRV-Headers; fetching pinned Khronos headers")
                FetchContent_Declare(roots_spirv_headers
                    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Headers.git
                    GIT_TAG vulkan-sdk-1.4.350.0 GIT_SHALLOW TRUE)
                FetchContent_GetProperties(roots_spirv_headers)
                if(NOT roots_spirv_headers_POPULATED)
                    FetchContent_Populate(roots_spirv_headers)
                endif()
                set(ROOTS_SPIRV_INCLUDE "${roots_spirv_headers_SOURCE_DIR}/include")
            endif()
            if(NOT EXISTS "${ROOTS_SPIRV_INCLUDE}/spirv/unified1/spirv.hpp")
                message(FATAL_ERROR "SPIRV-Headers is incomplete: ${ROOTS_SPIRV_INCLUDE}")
            endif()
            # Create a build-local config for the standard header-only target.
            # No host binary or Android-compiled library is needed.
            set(_roots_spirv_config "${CMAKE_CURRENT_BINARY_DIR}/roots-spirv-package")
            file(MAKE_DIRECTORY "${_roots_spirv_config}")
            file(WRITE "${_roots_spirv_config}/SPIRV-HeadersConfig.cmake"
                "if(NOT TARGET SPIRV-Headers::SPIRV-Headers)\n"
                "  add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED GLOBAL)\n"
                "  set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES INTERFACE_INCLUDE_DIRECTORIES \"${ROOTS_SPIRV_INCLUDE}\")\n"
                "endif()\n")
            set(SPIRV-Headers_DIR "${_roots_spirv_config}" CACHE PATH "SPIRV-Headers config" FORCE)
            find_package(SPIRV-Headers CONFIG REQUIRED NO_CMAKE_FIND_ROOT_PATH)
            message(STATUS "Roots SPIRV-Headers: ${ROOTS_SPIRV_INCLUDE} (generated package)")
        else()
            message(STATUS "Roots SPIRV-Headers package: ${SPIRV-Headers_DIR}")
        endif()
    endif()
endif()
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
# b10724 lacks the plamo3 tokenizer; PFN's tested PLaMo 3 runtime is b11960.
set(ROOTS_PLAMO_LLAMA_REVISION b11960)
FetchContent_Declare(llama GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git GIT_TAG ${ROOTS_PLAMO_LLAMA_REVISION} GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)
if(ROOTS_ENABLE_VULKAN AND ANDROID AND TARGET ggml-vulkan)
    # The upstream package check does not itself propagate header include paths.
    target_link_libraries(ggml-vulkan PRIVATE SPIRV-Headers::SPIRV-Headers)
endif()
add_library(roots_plamo SHARED roots_plamo.cpp)
target_link_libraries(roots_plamo PRIVATE llama android log)
target_compile_definitions(roots_plamo PRIVATE ROOTS_PLAMO_LLAMA_REVISION="${ROOTS_PLAMO_LLAMA_REVISION}"
    ROOTS_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}> ROOTS_VULKAN=$<BOOL:${GGML_VULKAN}>)
