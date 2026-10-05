# NAV-020 Gate 2 engine: host (x64-linux) build of the shipped valhalla-mobile code (ADR-0017 A1 items 1-2).
# Used only by backend/gate2/Dockerfile. Our code is driver.cpp; everything else is built UNMODIFIED from the pinned
# wrapper commit (valhalla-mobile src/, its CMake options and vcpkg manifest) and its Valhalla submodule.
#
#   cmake -G Ninja -S /build/gate2 -B /build/out \
#     -DCMAKE_TOOLCHAIN_FILE=/opt/vcpkg/scripts/buildsystems/vcpkg.cmake -DVCPKG_MANIFEST_DIR=/src/vm/src \
#     -DVCPKG_TARGET_TRIPLET=x64-linux -DCMAKE_BUILD_TYPE=Release -DWRAPPER_SRC=/src/vm/src -DNAV_GATE2_VARIANT=wrapper
cmake_minimum_required(VERSION 3.14)
project(nav-gate2 CXX C)

set(CMAKE_CXX_STANDARD 20 CACHE STRING "C++ language version (valhalla-mobile src/CMakeLists.txt)")
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(WRAPPER_SRC "" CACHE PATH "valhalla-mobile src/ directory at the pinned commit")
set(NAV_GATE2_VARIANT "wrapper" CACHE STRING "wrapper (A1 item 1) or upstream (A1 item 2)")
if(NOT EXISTS "${WRAPPER_SRC}/CMakeLists.txt")
  message(FATAL_ERROR "WRAPPER_SRC must point at valhalla-mobile/src")
endif()

if(NAV_GATE2_VARIANT STREQUAL "wrapper")
  # The wrapper's own CMakeLists: sets ENABLE_TOOLS/DATA_TOOLS/PYTHON_BINDINGS/HTTP/SERVICES/TESTS OFF, C++20,
  # -Wno-deprecated-builtins, finds the vcpkg packages, adds Valhalla and builds valhalla-wrapper (STATIC when
  # ANDROID_ABI is not set). Its JNI and Obj-C++ parts compile only under __ANDROID__ / __APPLE__.
  add_subdirectory(${WRAPPER_SRC} wrapper)
  add_executable(gate2-driver driver.cpp)
  target_link_libraries(gate2-driver PRIVATE valhalla-wrapper valhalla)
  target_include_directories(gate2-driver PRIVATE ${WRAPPER_SRC}/wrapper/include ${WRAPPER_SRC}/valhalla)
elseif(NAV_GATE2_VARIANT STREQUAL "upstream")
  # A1 item 2: upstream Valhalla at the submodule commit, with the wrapper's options, without the wrapper layer.
  set(ENABLE_TOOLS OFF)
  set(ENABLE_DATA_TOOLS OFF)
  set(ENABLE_PYTHON_BINDINGS OFF)
  set(ENABLE_HTTP OFF)
  set(ENABLE_SERVICES OFF)
  set(ENABLE_TESTS OFF)
  set(ENABLE_SINGLE_FILES_WERROR OFF)
  set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -Wno-deprecated-builtins")
  set(protobuf_BUILD_STATIC_LIBS ON CACHE INTERNAL "")
  find_package(absl CONFIG REQUIRED)
  find_package(Protobuf CONFIG REQUIRED)
  find_package(Boost REQUIRED)
  find_package(RapidJSON CONFIG REQUIRED)
  add_subdirectory(${WRAPPER_SRC}/valhalla/third_party/date date)
  add_subdirectory(${WRAPPER_SRC}/valhalla valhalla)
  add_executable(gate2-driver driver.cpp)
  target_compile_definitions(gate2-driver PRIVATE NAV_GATE2_UPSTREAM=1)
  target_link_libraries(gate2-driver PRIVATE valhalla pthread)
  target_include_directories(gate2-driver PRIVATE ${WRAPPER_SRC}/valhalla)
else()
  message(FATAL_ERROR "NAV_GATE2_VARIANT must be wrapper or upstream")
endif()
install(TARGETS gate2-driver RUNTIME DESTINATION bin)
