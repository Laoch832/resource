# Sound Connect × 网易云音乐：R1 测试构建

安卓版本机音频互操作实现。两个安装包从本仓库 Releases 下载。

已实现：

- Sound Connect 向网易云本机传递耳形参数，读取权限限定同一签名。
- 独立读取 RIFF / BRIR / h13 / HKI2 系数容器。
- 网易云三个原有音频入口在写入 AudioTrack 前运行耳形卷积；支持双声道 PCM16、浮点 PCM、数组和 ByteBuffer、部分写入与清空。使用实际前方左右方向滤波器，保留 256 帧处理延迟和 6 dB 余量。
- 网易云新增本机 MPEG-H 播放入口：选择未加密的 48 kHz MHAS / MP4，经开源解码器、5.1.4 布局和导入的耳形 BRIR 输出双耳音频。

普通双声道歌曲的处理是个性化虚拟音箱渲染。网易云在线曲库是否提供原生 360RA 由服务端和音源决定；客户端改动无法生成缺少的音频对象。原生 MPEG-H 文件使用新增的专门播放入口。此实现没有使用 Sony 的专有虚拟化 SDK，也不伪造 Sound Connect 的官方“优化成功”状态。

## 安装和测试

两个 APK 必须配套安装，Android 11 及以上，ARM64 / ARMv7。

1. 官方原版的签名不同，不能直接覆盖。先备份所需本机数据；需要卸载官方版再安装这两个包。此前同签名参数桥测试包可尝试直接更新。
2. 在修改版 Sound Connect 中连接耳机并重新完成耳形分析。卸载原版会删除其私有耳形数据。
3. 打开“Sony 耳形音频处理 · R1 验证”，导入参数，开启耳形渲染，然后更改参数或开关后，请先在系统设置中强行停止网易云，再重新打开并播放，使新输出使用新设置。建议先关闭其他音效。
4. 播放网易云普通歌曲测试虚拟音箱效果；使用“播放本机 MPEG-H / 360RA 文件”测试原生音源。

耳形数据保存在本机应用私有目录。仓库不包含个人耳形文件、Sony SDK 原生库、参考应用的默认系数文件或 APK 签名私钥。

## 验证范围

`evidence/` 保存测试结果。主机测试使用公开默认系数作本地格式参考，验证了 25 项卷积与容器检查、1,727 次写入边界检查，以及 8 个开源 MPEG-H 测试码流。Android CI 使用自行生成的 HKI2 测试系数，调用安装包中的三个网易云音频入口并测试 AudioTrack 和 MPEG-H。

实机蓝牙、个人耳形参数的具体版本、账号登录、网易云在线播放和 Sony 耳形服务仍需用户测试。测试构建不承诺官方应用全部功能均不受重新签名影响。

## 实现和构建资料

`r1/src/` 为独立系数读取、卷积、音频入口和本机播放器实现；`bridge/src/` 为本机参数传输；`r1/tests/` 为测试。`ci/r1-runtime-probe.apk` 只含自制参数夹具和开源测试音源，供 CI 使用。

MPEG-H 解码器使用 [Ittiam libmpegh](https://github.com/ittiam-systems/libmpegh)，固定提交 `f7ff0ac78d4d83f0b853bf2dff2ef075c92724f8`；对应许可保存在 `third-party/`。本实现修复了 BRIR 映射错误：必须按目标渲染布局初始化全部声道，而非按编码原始声道数初始化。另增加 6 dB 混合余量，将过短的直接声段补齐至最小滤波块，并让致命初始化错误立即失败、正确返回退出码。补丁见 `third-party/libmpegh-r1.patch`。

原始安装包来源：

- [Sony 官方 Sound Connect 13.3.0](https://www.sonystyle.com.cn/minisite/cross/app/download/pa/sound_connect_v13.3.0.apk)
- [网易云官方 Android 下载](https://music.163.com/api/android/download/latest2)，本构建基于 `9.6.08.261015102648_3264`。

本地完整构建需要 JDK 21、ECJ、apktool、D8、Android API 30、NDK r27d、上述原始 APK 和开源解码器源码。入口为 `r1/build.py`，步骤见 [构建说明](r1/BUILDING.md)。重新构建须自行生成签名密钥并同时签署两个包。仓库未附带官方应用反编译目录和开发环境工具链。
