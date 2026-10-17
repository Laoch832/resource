# Linux 本地构建

需要 JDK 21、Python 3、CMake、Git、Android SDK 和 NDK。原始 APK、SDK 和解码器源码在本地准备，不进入本仓库。

1. 将 README 所列两个原始 APK 放在仓库根目录，分别命名为 `sound-connect-13.3.0-original.apk`、`netease-9.6.08-original.apk`。构建器检查 SHA-256，不接受其他版本。
2. `python bridge/bootstrap_tools.py` 下载 apktool、ECJ、D8、签名工具等固定版本。
3. 使用 Android SDK Manager 安装 `platforms;android-30` 与 NDK r27d。在 `r1/android-api30.jar` 链接或复制 SDK 的 `platforms/android-30/android.jar`；在 `r1/android-ndk-r27d` 链接或解压 NDK。
4. 准备解码器并应用补丁：

```sh
git clone https://github.com/ittiam-systems/libmpegh r1/libmpegh
git -C r1/libmpegh checkout f7ff0ac78d4d83f0b853bf2dff2ef075c92724f8
git -C r1/libmpegh apply ../../third-party/libmpegh-r1.patch

cmake -S r1/libmpegh -B r1/libmpegh-android-arm64 \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/r1/android-ndk-r27d/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-23 -DLC_LEVEL_4=ON -DCMAKE_BUILD_TYPE=Release
cmake --build r1/libmpegh-android-arm64 -j2

cmake -S r1/libmpegh -B r1/libmpegh-android-armv7 \
  -DCMAKE_TOOLCHAIN_FILE="$PWD/r1/android-ndk-r27d/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-23 -DLC_LEVEL_4=ON -DCMAKE_BUILD_TYPE=Release
cmake --build r1/libmpegh-android-armv7 -j2
```

5. 自行生成 `bridge/signing/research.jks`，别名为 `bridge`，并将密码写入当前进程环境变量 `R1_KEYSTORE_PASSWORD`。同一套两个包必须使用同一密钥。
6. `python r1/build.py` 会检查原始 APK、解包资源、准备 Sony 与网易云的 DEX 补丁、编译 helper、组装并签名。输出在 `r1/build/candidates/`。
7. 可选：安装 `androguard` 后运行 `python r1/verify_packages.py` 检查原有库和资源是否保留、钩子数量及包结构。

测试用独立 APK 还需要同样方式编译 x86_64 解码器到 `r1/libmpegh-android-x86_64`，运行 `python r1/generate_test_profile.py`（需要 `cryptography`），再执行 `python r1/build.py --probe`。生产 APK 不包含测试系数。

这里不使用旧的 `bridge/build.py` 编译参数传输原型；R1 的完整构建入口是 `r1/build.py`。源码中的构建器支持已有解包缓存和全新工作目录。
