cmake_minimum_required(VERSION 3.22.1)
project("homvpn_native" LANGUAGES C CXX)

# ────────────────────────────────────────────────────────────────────────────────
# Dobby — inline hooking framework (built as a static library)
# ────────────────────────────────────────────────────────────────────────────────
set(DOBBY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/Dobby)

# Dobby build options — we only need the core hooking engine
macro(SET_OPTION option value)
    set(${option} ${value} CACHE INTERNAL "" FORCE)
endmacro()

SET_OPTION(DOBBY_DEBUG           OFF)
SET_OPTION(DOBBY_GENERATE_SHARED OFF)   # static lib, linked into our .so
SET_OPTION(Plugin.SymbolResolver ON)    # needed for DobbySymbolResolver()

add_subdirectory(${DOBBY_DIR} dobby)

# ────────────────────────────────────────────────────────────────────────────────
# Our native payload — libhomvpn_native.so
# ────────────────────────────────────────────────────────────────────────────────
add_library(homvpn_native SHARED
    homvpn_native.cpp
    sanitizer.cpp
)

# Include paths: Dobby public header + our own headers
get_property(DOBBY_INCLUDE_DIRS TARGET dobby PROPERTY INCLUDE_DIRECTORIES)
target_include_directories(homvpn_native PRIVATE
    ${DOBBY_INCLUDE_DIRS}
    ${DOBBY_DIR}/include
    ${CMAKE_CURRENT_SOURCE_DIR}
)

# Link: dobby (static), Android log, dynamic linker (for dladdr)
target_link_libraries(homvpn_native
    dobby
    log
    dl
)

# Strip debug symbols in release for smaller .so
set_target_properties(homvpn_native PROPERTIES
    LINK_FLAGS_RELEASE "-s"
)
