cmake_minimum_required(VERSION 3.22.1)
project("beirus_engine" LANGUAGES CXX)

# ── C++ standard ────────────────────────────────────────────────────────────
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Oboe via Prefab ─────────────────────────────────────────────────────────
find_package(oboe REQUIRED CONFIG)

# ── Engine shared library ───────────────────────────────────────────────────
add_library(beirus_engine SHARED
    native-lib.cpp
)

# ── Compiler flags ──────────────────────────────────────────────────────────
target_compile_options(beirus_engine PRIVATE
    -O3
    -ffast-math
    -fno-exceptions        # reduces binary size; we use return codes
    -Wall
    -Wextra
)

# ── Link ─────────────────────────────────────────────────────────────────────
target_link_libraries(beirus_engine
    oboe::oboe
    log
    android
)
