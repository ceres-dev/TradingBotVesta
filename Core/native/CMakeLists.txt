cmake_minimum_required(VERSION 3.20)
project(TradingBotVestaNative)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(DEFINED ENV{JAVA_HOME})
    set(JAVA_HOME_PATH "$ENV{JAVA_HOME}")
elseif(DEFINED JAVA_HOME)
    set(JAVA_HOME_PATH "${JAVA_HOME}")
else()
    message(FATAL_ERROR "JAVA_HOME is required to compile the JNI bridge")
endif()

add_library(TradingBotVesta SHARED
        src/TriangularArbitrage.cpp
)

target_include_directories(TradingBotVesta PRIVATE
        "${JAVA_HOME_PATH}/include"
        "${JAVA_HOME_PATH}/include/win32"
)
target_link_options(TradingBotVesta PRIVATE -static -static-libgcc -static-libstdc++)