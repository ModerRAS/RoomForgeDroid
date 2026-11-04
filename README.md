# RoomForgeDroid

RoomForge 是一个房间声学分析仪。它在 Android 上提供实时频谱（RTA）、声级、Leq、记录图、峰值存储、信号发生器、RT60、极性检测，以及和 AudioTool 公开列格式兼容的测量文件。

测量本身完全离线。应用会在启动时检查一次 GitHub releases 有没有新版（可在菜单里关掉），下载和安装只在你点了之后才发生。

界面和名称是 RoomForge 自己的。本应用不是 AudioTool，也不使用它的商标或图标。文件目录仍叫 `AudioTool`，因为现有的频谱转换工具按这个目录名找 `.at` 文件。

手机麦克风不是 IEC 61672 Class 1 声级计。在用声级计设定全局偏移之前，SPL 读数是 dBFS 加上你设置的偏移。

## 功能

- RTA：1/1、1/3、1/6、1/12、全分辨率 FFT、频谱图（Fast/Slow）、瀑布图、SPL、Leq、记录图、1/6 峰值存储
- 计权：Flat、A、C、X 曲线（分析通路）。示波器、WAV 和 RT60 使用原始麦克风信号
- 时间常数：Fast 0.125 s、Medium 0.5 s、Slow 1.0 s、Impulse 0.025 s，作用在功率上
- 平均：未平滑的每帧 dB 做算术平均，按钮旁显示累计帧数
- 峰值 / 谷值：关掉再打开会重新抓住。暂停后再运行会清空峰值存储并重开 Leq
- 双光标、音名、捏合缩放、拖动平移、对数 / 线性频率、频率和 dB 刻度
- NC、NR（只在 1/1 上绘制和评级）、Sone 估计（ISO 226:2003 + HVI 组合，建议 Flat）
- 目标曲线 `.targ`，默认约 60 dB 平直
- 发生器：正弦、方波、三角、锯齿、脉冲、静音、白噪、粉噪、线性扫频、对数扫频、啭音、极性；左右声道、右声道反相、音量
- 校准：单一全局偏移，或 31 段 1/3 圆盘再加全局偏移。可导入 Dayton iMM-6 与 miniDSP PMIK 文本（功率平均进 1/3，相对 1 kHz，响应取反成为修正）。灵敏度因子单独保存，需手动加到偏移上
- 粉噪在 1/3 RTA 上应接近平直。白噪在倍频程上大约每倍频程 +3 dB，在线性 FFT 上才接近平直
- RT60：拍手，或由发生器触发（先停发生器再测衰减）。宽带加 ISO 倍频程。RT Early 与 15–20，Schroeder 反向积分，可存 `.rt60` 并对照
- 文件在 `Android/data/com.roomforge.droid/files/AudioTool`：`.at` `.leq` `.cht` `.swp` `.rt60` `.targ` `.cal` `.wav`（最长约 30 秒）。可打开、分享、导出、删除。最多叠放两条频谱
- 麦克风：Builtin Main、Builtin Front、Wired Headset、Bluetooth、USB-Audio {名称}。插入 USB 输入时自动切换

`.at` 行是频率、幅度、校正 dB、峰值、谷值。校正 dB 是权威值，幅度按手册公式回写：

`校正 dB = 20·log10(幅度) + 20 + 4.2 + dBcalibration`

倍频程模式会在文件末尾追加倍频程块。Rex Beckett 的 REW 转换器读取追加的 1/3 倍频程块；那一块是校准后的频带电平、谷值和峰值。

## 构建

需要 JDK 17 或 21、Android SDK 35、Build-Tools 35.0.0。

```bash
export ANDROID_HOME="$HOME/android-sdk"   # 或写入 local.properties 的 sdk.dir
./gradlew :core:test :app:assembleDebug
```

`local.properties` 不入库。核心算法在 `:core`，不依赖 Android，可以用 JVM 测试。`:app` 是 Jetpack Compose。Debug APK 在 `app/build/outputs/apk/debug/`。

本仓库没有模拟器。验收是 `:core:test` 与 `:app:assembleDebug`。真机上的点击路径需要在设备上再看一遍。

## 发版

1. 先在仓库 Settings > Secrets and variables > Actions 里加四个签名 secrets：`ANDROID_KEYSTORE_BASE64`（keystore 文件 base64）、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`、`ANDROID_STORE_PASSWORD`。
2. 升 `app/build.gradle.kts` 里的 `versionName` / `versionCode`。
3. 打 tag 并推送：`git tag vX.Y.Z && git push origin vX.Y.Z`。CI 会跑测试、编 release 包、签名并创建 GitHub Release（带 APK）。
4. 应用内更新：启动时自动查一次 GitHub releases，有新版弹框提示；菜单里也能手动“检查更新”，可关闭自动检查。注意覆盖安装要求签名一致：之前装的是 debug 包的话，第一次需卸载重装。

## 系统做不到的部分

这些不是留到下一阶段的空函数，而是没有可用的 Android API，或系统会改写结果：

- 没有公开 API 做“最近任务”按钮的长按菜单
- 前置麦使用 `CAMCORDER`，系统可能忽略 `setPreferredDevice`
- 蓝牙走 SCO，带宽是通话窄带。Android 12 及以上用 `setCommunicationDevice`，更早版本用 `startBluetoothSco`
- USB 只能使用设备公布的采样率；列表为空时依次尝试 44100、48000、32000、22050、16000
- 能创建的 AEC / NS / AGC 会被关掉，厂商仍可能在驱动里处理。`UNPROCESSED` 失败时退到 `VOICE_RECOGNITION`，再退到 `MIC`
- 禁止旋转使用 `Activity.setRequestedOrientation`，自由窗口或桌面模式可能不生效
- 分享是 `ACTION_SEND`，导出是系统文档选择器，没有网盘 SDK
- X 曲线是按 3 dB/oct 设计的 2048 点线性相位 FIR，不是认证影院曲线
- Sone 是估计，不是认证响度计
- WAV 最长约 30 秒，Leq 历史最多 5000 条，记录图最多约 10000 点

应用内“关于与限制”有同样的说明。

## 许可

GNU GPL-3.0-or-later。见 `LICENSE`。

## English

RoomForge is an offline room-acoustics analyzer: RTA, SPL, Leq, chart recorder, peak store, signal generator, RT60 and polarity. Measurement text files follow the published AudioTool column layout so existing converters can read them. The UI is original. This app is not AudioTool and does not claim IEC 61672 Class 1.

Pink noise should read approximately flat on the 1/3-octave RTA when the 1/3 correction and the signal path are right. White noise rises about 3 dB per octave on octave bands and is approximately flat on the linear FFT. A sound-level meter is still required to set the broadband offset. The microphone sensitivity factor stored in a Dayton or miniDSP file is kept separate and is not applied until you add it yourself.

Build with JDK 17+ and Android SDK 35: `./gradlew :core:test :app:assembleDebug`. Limits that come from Android itself are listed above and in the in-app About screen.
