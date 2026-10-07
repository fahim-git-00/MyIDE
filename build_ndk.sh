#!/bin/bash
CPP_DIR="/storage/emulated/0/MyIDE/app/src/main/cpp"
OUT_DIR="/storage/emulated/0/MyIDE/app/src/main/jniLibs/arm64-v8a"

if [ -d "$CPP_DIR" ]; then
    mkdir -p "$OUT_DIR"
    aarch64-linux-android-clang++ -shared -fPIC \
      -o "$OUT_DIR/libnative-lib.so" \
      "$CPP_DIR"/*.cpp -llog
    echo "NDK Build Success: $OUT_DIR/libnative-lib.so"
fi
