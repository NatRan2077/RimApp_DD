cmake_minimum_required(VERSION 3.22.1)
project(spodesclient_bridge)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- исходники SpodesClient (без SerialPort/*, см. SpodesClient_rasshifrovka.txt) ----
set(SPODES_SRC
    spodes/src/data_parsing.cpp
    spodes/src/data_parsing_api.cpp
    spodes/src/errors.cpp
    spodes/src/get_processing.cpp
    spodes/src/service_functions.cpp
    spodes/src/set_action_processing.cpp
    spodes/src/spodes_client.cpp
    spodes/src/spodes_client_c_api.cpp
    spodes/src/spodes_handler.cpp
    spodes/src/struct_parsing.cpp
)

add_library(spodesclient_bridge SHARED
    ${SPODES_SRC}
    spodes_jni_bridge.cpp
)

target_include_directories(spodesclient_bridge PRIVATE
    spodes/inc
    rim_transport
)

find_library(log-lib log)

target_link_libraries(spodesclient_bridge
    ${log-lib}
)

# ---- ПРИМЕЧАНИЕ ----
# Эта конфигурация не была прогнана через реальный NDK-тулчейн (в среде, где
# писался код, NDK недоступен) — первая сборка почти наверняка вскроет мелкие
# нестыковки типов/предупреждения-как-ошибки. Начать отладку стоит с:
#   1) ./gradlew :app:externalNativeBuildDebug --info   (полный лог CMake/ninja)
#   2) при ошибках компиляции plusaes.hpp — возможно потребуется добавить
#      -D_CRT_SECURE_NO_WARNINGS или отключить конкретные warnings флагом
#      target_compile_options (см. предупреждения компилятора).
