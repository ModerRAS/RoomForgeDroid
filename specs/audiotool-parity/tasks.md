# 实施计划

- [x] 1. 建立 Gradle 工程
- `:core` 纯 Kotlin 与 `:app` Compose，GPL-3 头，命名空间 `com.roomforge.droid`
- _需求: 1, 8

- [x] 2. 实现并测试 DSP
- FFT、Hann、ISO 1/1 到 1/12、A/C/X、指数平滑、算术平均、SPL、Leq、记录图、峰值存储
- _需求: 1, 2, 3

- [x] 3. 实现发生器、极性、RT60 和校准
- 全部波形、粉噪/白噪、Schroeder RT60、Dayton/PMIK 导入、1/3 圆盘数据
- _需求: 4, 5, 6

- [x] 4. 实现 AudioTool 列格式
- `.at` `.leq` `.cht` `.swp` `.rt60` `.targ` `.wav`，幅度公式可还原校正 dB，追加倍频程块
- _需求: 7

- [x] 5. 实现采集、播放和麦克风路由
- AudioRecord/AudioTrack、USB 热插拔、蓝牙 SCO、效果器关闭、30 秒 WAV
- _需求: 1, 4, 7

- [x] 6. 实现全部画面
- 三区 RTA、频谱图/瀑布图、SPL/Leq/记录图/峰值、发生器、RT60、圆盘、文件、菜单和对话框
- _需求: 2, 3, 4, 5, 6, 7, 8

- [x] 7. 构建
- `./gradlew :core:test :app:assembleDebug` 通过
- _需求: 1
