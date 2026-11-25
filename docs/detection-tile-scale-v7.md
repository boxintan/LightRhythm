# 小尺寸局部横纹与矩形像素读取

2026-11-25，分支 `feature/detection-refinement`，基于 `feature/detection` 的 `23d7620`。

依据项目计划书，修改限于 A 的检测模块、接口说明及验证工具；沿用本机 Kotlin 算法。本轮不修改 B 的修复、C/D 的页面或构建配置，不合并 main，不上传照片。

## 解决的问题

`photo/09.jpg` 的屏幕仅占画面一部分，缩至 480×275 后，原有 128 像素窗口混入屏幕边框、背景和文字，未形成足够的局部周期证据。本轮先保留原有 128 窗口的判定，仅在其未形成跨位置证据时，再尝试 64 窗口。每个尺度独立要求至少四个位置、覆盖至少两行两列、周期和斜率一致；不把两个尺度累加计票。

64 窗口的子区宽度为 16，上下采样边界为 8；128 窗口仍为 32 和 16。幅度、相关、半周期反转、配置的周期上下限及 minCycles 规则保持。结果里的 `sourceMarginRows` 随所选尺度返回，坐标仍对应输入像素。

Android 的局部横纹和摩尔纹入口原先为每块的每一行读取整张图的宽度。现在直接用 `Bitmap.getPixels` 读取完整矩形块；不变更颜色筛选、去趋势、方向、尺度或摩尔纹相关判据。保留逐行适配器供旧工具和合成测试使用，增加两条路径的逐候选数值等价检查。

公开入口 `detectBanding(Bitmap, ...)`、`detectMoire(Bitmap)` 和结果字段保持兼容。像素入口横纹版本为 `row-local-periodicity-v7`，确认中性背景时为 `row-local-broad-v7`；摩尔纹为 `local-chroma-recurrence-v4`。整行轮廓分析接口的版本不变。局部证据不能当作整图行修复参数，App 继续阻断局部横纹和摩尔纹的整行修复。

## 本机照片前后对照

对 `photo` 的 12 个文件，以原图及最长边 1600、960、480 双线性缩放检查，跳过与原图尺寸重复的检查，共 47 条记录。08 与 11 的 SHA-256 相同，只有 11 个不同文件；03/07、10/12 也有相近拍摄内容。这些均是开发回测，不是独立验证集。

| 样本 / 尺寸 | 修改前 | 修改后 |
| --- | --- | --- |
| 09 / 1440×826 | 局部横纹，4 个位置 | 保留相同证据，128 窗口 |
| 09 / 960×550 | 局部横纹，5 个位置 | 保留相同证据，128 窗口 |
| 09 / 480×275 | 未找到明确周期 | 局部横纹，5 个位置，64 窗口，候选周期 5～6 行 |
| 01、02 / 四种尺寸 | 疑似颜色横带 | 保持 |
| 03、07、08、10、12 / 四种尺寸 | 疑似彩色摩尔纹 | 保持候选位置数 |
| 04、05、06 / 四种尺寸 | 默认不提示上述周期异常 | 保持；未自动开启中性背景假设 |

其余 46 条记录的状态、整行候选周期、摩尔纹候选位置数和局部位置数保持一致。09 中的重复明暗条纹只能作为图像特征，不能根据截图标题确定是 LED 频闪还是灰度摩尔纹。

## 像素读取开销

`BenchmarkTileReads.kt` 对同一张 ImageIO 解码并缩放后的图片，比较逐行适配器与矩形读取。预热各三次，交替运行六次，报告中位数；不含解码、缩放，不代表手机性能。

| 样本 / 尺寸 | 逐行读取中位数 ms | 矩形读取中位数 ms |
| --- | --- | --- |
| 07 / 1600×1200 | 61.130 | 42.462 |
| 10 / 1600×1200 | 48.680 | 32.204 |
| 06 / 1205×1600 | 27.406 | 20.196 |

前两张摩尔纹检测的读取次数由 3840 次整行变为 64 次矩形；复制源像素数由 6,144,000 降为 348,160。每次检测均检查两条路径的完整候选列表相等。这是读取机制的改进，未声称提高摩尔纹识别准确率。局部横纹在粗尺度失败时会增加一次小尺度计算，不能把这里的摩尔纹耗时比例当作整个 App 的加速比例。

## 验证

- 桌面算法/工具 84 项通过；App JVM 100 项通过，均无失败。范围有重叠，不应相加为独立测试数。
- debug APK 和 Android 测试 APK 构建成功，独立 `.detectiontest` 包。
- API 37 / Android 17 的无窗口模拟器执行 36 项设备测试，`OK (36 tests)`，无失败、无跳过。包括 3 项新增设备测试、原有真实照片检测/页面与修复阻断、Bitmap 合约、导入、保存及稳定性回归。
- 新设备测试确认 Android ImageDecoder 的 09 / 480 解码仍检出 64 窗口局部证据；03/07/08/10/12 在 1600/960/480 均有彩色候选，完整结果与逐行读取相同；06 / 1600 不触发摩尔纹；输入 Bitmap 与样本文件保持不变。
- 私人照片、CSV、诊断缩图和设备日志位于本地忽略目录，不进入提交。

## 复现

```powershell
.\tools\test-detection.ps1 `
  -JavaHome 'D:\Android Studio\jbr' `
  -DependencyCache 'C:\Users\lenovo\.gradle\caches\modules-2\files-2.1' `
  -AndroidJar 'C:\Users\lenovo\AppData\Local\Android\Sdk\platforms\android-37.0\android.jar'

# 使用脚本输出的编译目录和 Kotlin 标准库作为 classpath：
# com.example.lightrhythm.detection.InspectPhotoArtifactsKt <照片路径...>
# com.example.lightrhythm.detection.BenchmarkTileReadsKt <照片路径...>

$env:JAVA_HOME = 'D:\Android Studio\jbr'
$env:ANDROID_HOME = 'C:\Users\lenovo\AppData\Local\Android\Sdk'
.\gradlew.bat --offline -PdetectionVerification=true `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
```

设备用例 `com.example.lightrhythm.detection.DetectionTileScaleTest` 接收 instrumentation 参数 `detectionSampleDirectory`，目录中提供 03/06/07/08/09/10/12.jpg，不将图片放入 APK。安装独立 App 和测试 APK 后，可通过以下命令复现新增的 3 项设备测试（其余历史用例按既有文档选择）：

```powershell
adb -s emulator-5554 shell am instrument -w -r `
  -e class com.example.lightrhythm.detection.DetectionTileScaleTest `
  -e detectionSampleDirectory /data/user/0/com.example.lightrhythm.detectiontest/files/refinement-samples-20261125 `
  com.example.lightrhythm.detectiontest.test/androidx.test.runner.AndroidJUnitRunner
```

本地结果位于 `build/sample-review/before.csv`、`after.csv`、`tile-benchmark.csv`、`device-tests.txt`，JVM 结果位于 `app/build/test-results/testDebugUnitTest`。测试结束后清理本轮临时照片并关闭本轮启动的模拟器，复现设备命令前须重新准备私有样本。

## 剩余限制

仍需独立标注的正常图/困难负样本及实体手机性能验证。细小窗口可能把原画面的真实重复纹理判为候选，未实现光学成因识别、去摩尔纹、自动屏幕定位或透视矫正。
