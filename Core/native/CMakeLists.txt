cmake_minimum_required(VERSION 4.1)
project(TradingBotVesta)

set(CMAKE_CXX_STANDARD 14)

# Build a shared library (.dll on Windows) for Java/JNI consumption.
add_library(TradingBotVesta SHARED src/sortMarket.cpp)
