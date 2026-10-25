#pragma once

#if defined(__ANDROID__)
#include <android/log.h>
#define NANQUIM_LOG_INFO(...) __android_log_print(ANDROID_LOG_INFO, "NanquimEngine", __VA_ARGS__)
#define NANQUIM_LOG_ERROR(...) __android_log_print(ANDROID_LOG_ERROR, "NanquimEngine", __VA_ARGS__)
#else
#include <cstdio>
#define NANQUIM_LOG_INFO(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#define NANQUIM_LOG_ERROR(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif
