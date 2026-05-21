cmake_minimum_required(VERSION 3.22.1)

project("dintegrity")

add_library(dintegrity SHARED native-lib.c)

# Strip symbols + dead-code elimination on release builds to make
# static analysis of the .so harder.
target_compile_options(dintegrity PRIVATE
        -fvisibility=hidden
        -ffunction-sections
        -fdata-sections
        -Wall
)
target_link_options(dintegrity PRIVATE
        -Wl,--gc-sections
        -Wl,--exclude-libs,ALL
)

# The load-time integrity tripwire (JNI_OnLoad) only terminates the process
# in non-debug builds. Debug builds still run the checks but merely log, so
# local development, CI, and emulators (which often look "rooted") stay usable.
target_compile_definitions(dintegrity PRIVATE
        $<$<NOT:$<CONFIG:Debug>>:PROOFMODE_LETHAL_INTEGRITY=1>)

find_library(log-lib log)
target_link_libraries(dintegrity ${log-lib})
