# 需求文档

## 介绍

RoomForgeDroid 要在一台离线的 Android 手机上完成房间声学测量：实时频谱、声级与 Leq、记录图、峰值存储、信号发生、RT60、极性、校准和测量文件。行为对齐 AudioTool 手册里已经公开的测量定义和文件列，界面使用 RoomForge 自己的布局和名称。

本文档是一次性实现的需求记录。验收以本文件的条款和 `./gradlew :core:test :app:assembleDebug` 为准。

## 需求

### 需求 1 - 采集与分析

**用户故事：** 作为测量者，我希望手机把麦克风信号变成可切换计权、时间常数和分辨率的频谱，以便观察房间和扬声器。

#### 验收标准

1. When 用户授予麦克风权限，RoomForge shall 采集 Builtin Main，并在设备存在时可选 Builtin Front、Wired Headset、Bluetooth 和 USB-Audio。
2. When 一个 USB 输入设备被插入，RoomForge shall 自动改用该设备。
3. When 分析运行，RoomForge shall 使用 Hann 窗和 4096、8192 或 16384 点 FFT，并优先使用 44100 Hz 或设备公布的最接近采样率。
4. While 分析暂停，RoomForge shall 冻结频谱、Leq 和记录图，并继续刷新输入示波器。
5. When 用户切换 Flat、A、C 或 X 计权，RoomForge shall 只把计权用于分析通路；示波器、WAV 和 RT60 保持原始信号。
6. When 用户选择 Fast、Medium、Slow 或 Impulse，RoomForge shall 用 0.125 s、0.5 s、1.0 s 或 0.025 s 的指数时间常数平滑功率。
7. When 平均打开，RoomForge shall 对未平滑的每帧 dB 做算术平均，并显示累计帧数。

### 需求 2 - RTA 显示

**用户故事：** 作为测量者，我希望按倍频程、细线、频谱图和瀑布图查看同一测量，并读出光标、NC/NR 和响度估计。

#### 验收标准

1. When 用户切换模式，RoomForge shall 提供 1/1、1/3、1/6、1/12、全分辨率、频谱图或瀑布图、SPL、Leq、记录图和 1/6 峰值存储。
2. When 峰值或谷值从关到开，RoomForge shall 从当前电平重新开始保持。
3. When 模式是 1/1 且 NC 或 NR 打开，RoomForge shall 绘制曲线并给出评级。
4. When Sone 打开，RoomForge shall 用八个倍频程按 0.7×(1 kHz)+0.3×(八个频带之和) 给出估计，并说明应使用 Flat。
5. When 光标打开，RoomForge shall 显示两支光标的频率、音名和 dB 差；此时单指移动光标，双指缩放不起作用。
6. When 光标关闭，RoomForge shall 允许拖动和捏合来改变频率窗口和 dB 窗口，并允许改成线性频率轴。
7. When 用户点按频谱，RoomForge shall 隐藏或恢复按钮。屏幕保持点亮和锁定方向应可分别开关。

### 需求 3 - 声级、Leq、记录图和峰值存储

**用户故事：** 作为测量者，我希望留下随时间变化的声级和扫频峰值，以便事后比较。

#### 验收标准

1. When 分析运行，RoomForge shall 用时间域功率显示宽带 SPL，并在 SPL 画面标明当前计权和时间常数。
2. When Leq 到达设定时长，RoomForge shall 记下宽带和倍频程结果并重新开始，历史最多 5000 条，界面显示最近 8 条。
3. When 模式是记录图，RoomForge shall 记录时间、SPL、峰值频率和扫频频率，容量约 10000 点，并可按 30 秒、2 分钟、10 分钟或全部显示。
4. When 模式是峰值存储，RoomForge shall 保持 1/6 倍频程平滑电平的最大值；从暂停回到运行时清空它。
5. When 用户存储，RoomForge shall 按当前画面写入 `.leq`、`.cht`、`.swp` 或 `.at`。

### 需求 4 - 信号发生器与极性

**用户故事：** 作为测量者，我希望手机发出测试信号，以便测量扬声器和极性。

#### 验收标准

1. When 发生器打开，RoomForge shall 能输出正弦、方波、三角、锯齿、脉冲、静音、白噪、粉噪、线性扫频、对数扫频、啭音和极性脉冲。
2. When 波形是扫频或啭音，RoomForge shall 在设定的起止频率和时长内循环；啭音在一半时长内上升，然后下降。
3. When 用户切换左右声道或右声道反相，RoomForge shall 只改变对应声道的输出。
4. When 极性信号打开，RoomForge shall 显示 Pol +++、Pol --- 或 Pol ?。
5. When 粉噪播放且 1/3 修正和通路正确，1/3 RTA shall 接近平直；白噪在倍频程上大约每倍频程上升 3 dB。

### 需求 5 - 校准

**用户故事：** 作为测量者，我希望用声级计和麦克风校准文件修正读数。

#### 验收标准

1. When 校准方式是单一偏移，RoomForge shall 把一个全局 dB 加到宽带 SPL 和所有频点上。
2. When 校准方式是 1/3，RoomForge shall 用 31 个圆盘修正对应频带，并把另一个全局偏移加在圆盘之上；宽带 SPL 只用全局偏移。
3. When 用户双击圆盘区左右边缘或上下拖动，RoomForge shall 切换频带或改变该频带的 dB。
4. When 用户导入 Dayton iMM-6 或 miniDSP PMIK 文本，RoomForge shall 把频点功率平均到 1/3，相对 1 kHz 归一，并把响应取反作为修正。
5. When 文件含灵敏度因子，RoomForge shall 保存它，并且只在用户确认后加到全局偏移。
6. When 用户开始噪声校准，RoomForge shall 打开粉噪或白噪、Flat 计权和 1/3 RTA，并说明哪一种应该看起来平直。

### 需求 6 - RT60

**用户故事：** 作为测量者，我希望用拍手或测试信号测量混响时间。

#### 验收标准

1. While 发生器关闭，When 用户让 RT60 等待，RoomForge shall 在检测到拍手后记录衰减。
2. While 发生器打开，When 用户触发，RoomForge shall 先停止发生器，再测量衰减。
3. When 衰减记录完成，RoomForge shall 用 Schroeder 反向积分给出宽带和 ISO 倍频程的 RT Early（峰值到 -10 dB 的时间 × 6）和 15–20（-5 dB 到 -20 dB 的时间 × 4）。
4. When 用户存储或载入 `.rt60`，RoomForge shall 保存结果并在表格中对照已载入的文件。

### 需求 7 - 文件

**用户故事：** 作为测量者，我希望测量能被保存、再次叠放、分享，并被现有的 REW 转换工具读到。

#### 验收标准

1. When 用户存储频谱，RoomForge shall 写入频率、幅度、校正 dB、峰值、谷值，并使校正 dB 等于 `20·log10(幅度)+20+4.2+dBcalibration`。
2. When 当前模式有倍频程，RoomForge shall 在文件末尾追加该倍频程块。
3. When 用户连续载入频谱，RoomForge shall 最多保留两条，第三条替换较旧的一条。
4. When 用户管理文件，RoomForge shall 支持按扩展名打开、删除、系统分享和导出。目录为应用外部文件下的 `AudioTool`。
5. When 用户录音，RoomForge shall 以分析器采样率写入最多约 30 秒的 WAV，并在结束后询问文件名。

### 需求 8 - 诚实的限制

**用户故事：** 作为测量者，我希望知道哪些读数不能当成计量结果。

#### 验收标准

1. When 用户打开关于页面，RoomForge shall 说明本应用不是 IEC Class 1，并列出系统 API 做不到的项目。
2. While 没有声级计偏移，RoomForge shall 把 SPL 显示为 dBFS 加偏移，而不是声称已校准的声压级。
