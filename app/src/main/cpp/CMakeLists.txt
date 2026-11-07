cmake_minimum_required(VERSION 3.22.1)

project(WinlatorNative C CXX)

# ---------------------------------------------------------------------------
# Global optimization flags.
#
# add_compile_options() is emitted after CMAKE_<LANG>_FLAGS[_<CONFIG>] on the
# compiler command line, so these override both the per-library "-O2" and the
# "-O0" that the NDK injects for the Debug configuration.
# ---------------------------------------------------------------------------
set(WINLATOR_TARGET_SOC "sm8550" CACHE STRING "Target SoC for CPU tuning (sm8550|generic)")

add_compile_options(-O3 -DNDEBUG -fno-plt -fomit-frame-pointer -ffunction-sections -fdata-sections)
add_link_options(-Wl,--gc-sections -Wl,-O2 -Wl,--icf=safe)

if(WINLATOR_TARGET_SOC STREQUAL "sm8550")
    # Snapdragon 8 Gen 2 (Galaxy S23): 1x Cortex-X3, 2x A715, 2x A710, 3x A510.
    # All cores are ARMv9.0-A, but Qualcomm does not expose SVE/SVE2 to
    # userspace, so target the ARMv8.x feature set they all share (NEON,
    # FP16, dot product, crypto, LSE atomics, RCPC) and tune for the big cores.
    add_compile_options(-march=armv8.2-a+fp16+dotprod+crypto+rcpc -mtune=cortex-x3)
    message(STATUS "Winlator: tuning native code for Snapdragon 8 Gen 2 (sm8550)")
else()
    message(STATUS "Winlator: generic ARMv8-A native code")
endif()

add_subdirectory(winlator)
add_subdirectory(vortekrenderer)
add_subdirectory(virglrenderer)
add_subdirectory(midihandler)
add_subdirectory(libadrenotools)
add_subdirectory(gladiorenderer)
