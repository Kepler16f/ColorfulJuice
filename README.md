# ColorfulJuice

一款简洁的 Android 蓝牙设备电量查看器，支持读取已配对蓝牙设备的电池电量。

## 功能

- 自动发现并列出已配对的蓝牙设备
- 支持 BLE Battery Service (0x180F) 读取电量
- 支持通过系统反射读取 HFP/A2DP 设备电量（适用于大部分蓝牙耳机、手环等）
- 连接超时可配置（3-30 秒）
- 电量自动刷新（关闭 / 30秒-10分钟）
- 设备管理：显示/隐藏指定设备
- 桌面小部件：1×3 电量条 / 2×2 单设备 / 2×2 双设备
- 应用内更新：检查 GitHub Release，应用内下载 APK 并调起系统安装器
- 多语言支持：简体中文 / 繁體中文 / English
- Material 3 设计风格，底部导航栏

## 系统要求

- Android 8.0 (API 26) 及以上
- 蓝牙 BLE 支持

## 下载

前往 [Releases](https://github.com/Kepler16f/ColorfulJuice/releases) 页面下载最新 APK。

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Android BLE API
- DataStore Preferences
- Coroutines + Flow
- GitHub Actions CI/CD

## 构建

```bash
git clone https://github.com/Kepler16f/ColorfulJuice.git
cd ColorfulJuice
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

## 项目结构

```
app/src/main/java/com/colorfuljuice/bluetoothbattery/
├── MainActivity.kt              # 主界面，权限管理
├── BluetoothBatteryViewModel.kt # ViewModel，状态管理
├── utils/
│   ├── BluetoothBatteryService.kt  # 蓝牙核心服务
│   └── UpdateManager.kt            # 应用内更新：检查 / 下载 / 安装
├── ui/
│   ├── screens/
│   │   ├── DeviceListScreen.kt  # 设备列表页
│   │   └── SettingsScreen.kt    # 设置页
│   ├── components/
│   │   └── UpdateDialog.kt      # 更新进度 / 安装对话框
│   └── theme/                   # 主题配色
└── widget/                      # 桌面小部件（1×3、2×2 单/双设备）
```

## License

MIT
