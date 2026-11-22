# CUKTECH 10 Ultra (AD1204U) 固件冻死离线诊断工具

[![Build & Verify APK](https://github.com/xdsoar/cuktech-10ultra-diag/actions/workflows/build.yml/badge.svg)](https://github.com/xdsoar/cuktech-10ultra-diag/actions/workflows/build.yml)

针对酷态科 10 号超级电能充 Ultra（国内米家型号 `njcuk.fitting.ad1204`，型号 `AD1204U`）`2.1.2_0079` 固件新增的诊断接口编写的**纯本地、零联网权限** Android 一键自检工具。

👉 **[前往 Releases 页面下载最新 APK（由 GitHub Actions 自动编译发布）](../../releases/latest)**

---

## 一、安全与透明性说明（为什么可以放心安装）

1. **零联网权限**：[`AndroidManifest.xml`](AndroidManifest.xml) **未声明 `android.permission.INTERNET`**，应用在系统底层不具备任何联网上传能力（Android 12+ 的 `BLUETOOTH_SCAN` 已标记 `neverForLocation`，无需申请定位权限）。
2. **极简全开源无第三方依赖**：整个工程仅包含 **4 个纯 Java 源文件**（无任何第三方闭源 SDK、无代码混淆），编译产物仅约 `41 KB`，任何人均可使用 `jadx` 反编译逐行比对：
   - [`DiagProtocol.java`](src/me/cuktech/ad1204u/diag/DiagProtocol.java)：`0xAF00` 只读协议帧构造、响应解析与蓝牙 MAC 中段脱敏（`AA:BB:**:**:EE:FF`）；
   - [`BleDiagClient.java`](src/me/cuktech/ad1204u/diag/BleDiagClient.java)：BLE 广播识别（米家 `0xFE95` Product ID `0x660E`）、`0xAF08` 通知订阅、顺序读取与自动断连释放；
   - [`ReportImageRenderer.java`](src/me/cuktech/ad1204u/diag/ReportImageRenderer.java)：本地 `Canvas` 渲染 `1080px` 宽高清报告长图（含原始十六进制回包帧）存入系统相册；
   - [`MainActivity.java`](src/me/cuktech/ad1204u/diag/MainActivity.java)：单页原生界面。
3. **GitHub Actions 透明构建**：Releases 中的 `.apk` 直接由 [`.github/workflows/build.yml`](.github/workflows/build.yml) 在 GitHub 官方托管环境中自动编译、运行协议单元测试并完成权限与字节码自检后发布。

---

## 二、协议原理（也可直接用 `nRF Connect` 手工验证）

酷态科 10 号 Ultra 除小米加密的 `0xFE95` 服务外，在固件中开放了未加密的私有 GATT 服务 **`0xAF00`**（写入特征 `0xAF07`、通知特征 `0xAF08`）。

本应用连接后开启 `0xAF08` Notify（`CCCD = 01 00`），依次向 `0xAF07` 发送以下 **4 条 `0xF1` 只读查询指令**，读取完毕后**立即主动断开蓝牙**，绝不发送任何写配置、清零或固件升级指令：

| 顺序 | 写入 `0xAF07` 的 9 字节请求帧 (Hex) | `0xAF08` 通知回包格式 | 读取内容说明 |
| :--- | :--- | :--- | :--- |
| 1 | `FE DC BA 00 F1 02 01 01 EF` | `FE DC BA 01 F1 01 <MCU:2B> <CRC:2B> <SC2021:2B> <C1:4B> <C2:4B> EF` (21B) | 读取 MCU 固件版本（如 `0x0079` → `2.1.2_0079`，旧版如 `0x0073` 会自动识别提示）、MBR CRC16 及 C1/C2 (`HUSB362`)、C3/A (`SC2021A`) 协议芯片版本 |
| 2 | `FE DC BA 00 F1 02 01 0C EF` | `FE DC BA 01 F1 0C 04 <C1:2B LE> <C2:2B LE> EF` (12B) | 读取 `0079` 新增的 **Flash 记录 `#5` 历史累计冻死拉闸次数**（16 位小端序，拔掉插座断电也不丢失） |
| 3 | `FE DC BA 00 F1 02 01 0A EF` | `FE DC BA 01 F1 0A 0C <Trig:1B> <Port:1B> <V:2B> <I:2B> <Cnt:2B> <Ticks:4B> EF` (20B) | 读取本次不断电期间首次触发冻死拉闸的**现场快照**（端口号 `1=C1 / 2=C2`、连续冻结采样数、持续 tick 数、原始电压/电流采样值） |
| 4 | `FE DC BA 00 F1 02 01 0B EF` | `FE DC BA 01 F1 0B 04 <C1Peak:2B LE> <C2Peak:2B LE> EF` (12B) | 读取本次不断电期间 C1 / C2 带载读数连续不变的**最大峰值次数**（固件阈值：电压与电流均非零且连续不变满 `301` 次及 `18001` tick 触发单口拉闸） |

---

## 三、三步使用方法

1. **安装应用**：从 [Releases](../../releases/latest) 下载 `.apk` 安装（若通过微信传输导致文件名变为 `.apk.1`，请在系统文件管理中重命名去掉 `.1` 后安装）。
2. **测前准备**：
   - 将充电头插在插座上保持通电，确认机身设置中的蓝牙处于开启状态；
   - **先从手机后台彻底划掉「米家」App**（BLE 同一时刻仅允许 1 个中心设备连接，米家占线时充电头停止对外广播）。
3. **一键检测与导出**：
   - 打开「10 Ultra 固件诊断」点击 **「开始一键检测」**，约 3 秒自动完成扫描、连接、读取与蓝牙释放；
   - 点击 **「保存证据长图」**（保存在相册 `Pictures/CUKTECH_Diag/`）或 **「复制文字报告」** 即可分享结果（蓝牙 MAC 中间两段已自动掩码脱敏，且完整附带 4 条 `0xAF08` 原始十六进制回包帧以便核验）。

---

## 四、本地自行编译复现

只需安装 JDK 17+ 与 Android SDK（含 `platforms;android-34` 与 `build-tools`），无需 Gradle 联网拉取任何 Maven 依赖：

```bash
export ANDROID_SDK_ROOT=/path/to/android/sdk
./build_apk.sh
```

脚本会先运行 [`DiagProtocolTest.java`](test/me/cuktech/ad1204u/diag/DiagProtocolTest.java) 协议单元测试，随后调用 `aapt2`、`javac`、`d8`、`zipalign` 与 `apksigner` 在 `dist/cuktech-10ultra-diag-1.0.0.apk` 生成签名安装包并打印权限清单。

## License

[MIT License](LICENSE)
