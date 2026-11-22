# Android 检测验证

后续进度：已接通选图后的自动检测和结果卡片，并扩充到 19 项设备测试；见 [App 对接记录](app-detection-integration.md)。下文保留首次完成算法设备验证时的记录。

日期：2026-11-22。算法版本 row-color-periodicity-v3，分支 feature/detection。

## 本轮结果

环境：Windows、Android Studio JBR 25、Android SDK 37；Medium_Phone_API_37.0，emulator-5554，Android 17 / API 37、x86_64。使用无窗口模拟器；未在实体手机上验证。

| 项目 | 实际结果 |
| --- | --- |
| Gradle :app:testDebugUnitTest | 42 项通过，0 失败、0 错误、0 跳过；包括 41 项检测测试和 1 项工程模板测试 |
| :app:assembleDebug | 构建成功 |
| :app:assembleDebugAndroidTest | 构建成功 |
| AndroidJUnitRunner | OK (9 tests)，没有失败或跳过 |
| 真实照片回归 | 两张原图 × 四个分辨率，均检出疑似颜色横带；输入 Bitmap 像素保持不变 |

此前 47 项桌面测试包含 6 项桌面评估工具测试，不应与这里的 42 项 App JVM 测试直接累加成独立测试覆盖。

设备测试由以下三个类组成：

- BitmapRowLuminanceTest：原有 2 项，验证实际 Bitmap 的行亮度、灰度横纹与像素保护。
- BitmapDetectionContractTest：新增 6 项，验证跨分辨率颜色横带、颜色渐变反例、透明输入、已回收输入、硬件 Bitmap 拒绝及尺寸不足。
- DetectionPhotoDeviceTest：新增 1 项可选的私人照片回归，在一个测试内检查两图各四个尺寸。未提供照片目录时会明确跳过；本轮提供了目录并实际执行通过。

## Android 实际照片结果

采用 BitmapFactory 解码软件 Bitmap，再用 Bitmap.createScaledBitmap 缩放；没有使用桌面 ImageIO 作为设备像素来源。此回归不验证系统选图界面或通用 EXIF 导入逻辑。

| 图片 | Bitmap 尺寸 | 通道 | 候选周期/行 | 支持区域 | 单次分析耗时/ms |
| --- | --- | --- | --- | --- | --- |
| 01.jpg | 1920×1440 | R−G | 380 | 3/4 | 34.9600 |
| 01.jpg | 1600×1200 | R−G | 316 | 3/4 | 25.1148 |
| 01.jpg | 960×720 | R−G | 190 | 3/4 | 10.6358 |
| 01.jpg | 480×360 | R−G | 95 | 3/4 | 3.6388 |
| 02.jpg | 1920×1440 | B−G | 462 | 3/4 | 30.2118 |
| 02.jpg | 1600×1200 | B−G | 385 | 3/4 | 22.1575 |
| 02.jpg | 960×720 | B−G | 231 | 3/4 | 9.3580 |
| 02.jpg | 480×360 | B−G | 116 | 3/4 | 3.5415 |

耗时来自同一轮模拟器测试，只覆盖 detectBanding，不含解码、缩放和像素保护断言，未经独立基准设计；不能据此估计实体手机速度。两图已用于开发，八个尺寸输入是回归场景，不是八张独立样本或准确率统计。

## 独立测试应用

新增可选构建参数 `-PdetectionVerification=true`，仅让 debug 应用 ID 加上 `.detectiontest`。验证包为 `com.example.lightrhythm.detectiontest`，测试包为 `com.example.lightrhythm.detectiontest.test`。不提供此参数时，正常 debug 应用 ID 仍为 `com.example.lightrhythm`。

本轮使用独立测试包运行，不覆盖原有光律应用。私人照片只临时复制到设备，不打入 APK 或提交仓库；完成后清理测试包和临时副本，并关闭本轮启动的模拟器。

## 复现构建和设备测试

先启动 API 37 模拟器，确认 adb devices 可见设备。项目根目录中执行：

```powershell
$env:JAVA_HOME = 'D:\Android Studio\jbr'
.\gradlew.bat --offline -PdetectionVerification=true `
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest

$adb = 'C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 install -r -t .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 install -r -t .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

$verificationClasses = @(
  'com.example.lightrhythm.detection.BitmapRowLuminanceTest'
  'com.example.lightrhythm.detection.BitmapDetectionContractTest'
) -join ','
& $adb -s emulator-5554 shell am instrument -w -r -e class $verificationClasses `
  com.example.lightrhythm.detectiontest.test/androidx.test.runner.AndroidJUnitRunner
```

不带私人照片的上述命令应得到 OK (8 tests)。不要把 adb 的退出码 0 单独当作通过证据，应检查测试运行器的 OK、失败及跳过状态。工程模板仪器测试断言原应用 ID，因此本轮显式选择检测测试类，未把模板测试纳入独立测试包验证。

加入私人照片回归时，将下述源目录替换为自己的目录，保留文件名 01.jpg、02.jpg：

```powershell
$sampleSource = 'C:\Users\lenovo\Desktop\Second_Autumn\software\photo'
& $adb -s emulator-5554 shell run-as com.example.lightrhythm.detectiontest mkdir -p files/detection-samples
foreach ($name in @('01.jpg', '02.jpg')) {
  & $adb -s emulator-5554 push (Join-Path $sampleSource $name) "/data/local/tmp/lightrhythm-detection-$name"
  & $adb -s emulator-5554 shell run-as com.example.lightrhythm.detectiontest cp "/data/local/tmp/lightrhythm-detection-$name" "files/detection-samples/$name"
}
$verificationClasses += ',com.example.lightrhythm.detection.DetectionPhotoDeviceTest'
& $adb -s emulator-5554 shell am instrument -w -r -e class $verificationClasses `
  -e detectionSampleDirectory /data/user/0/com.example.lightrhythm.detectiontest/files/detection-samples `
  com.example.lightrhythm.detectiontest.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s emulator-5554 logcat -d -s LightRhythmDetection:I '*:S'
```

提供样本后应得到 OK (9 tests)。目标 APK 必须是上述 debuggable 独立包，run-as 才能准备应用私有文件。照片回归用于这两个已知颜色横带样本；一般照片的评估仍使用人工标签与批量评估流程。

本机离线运行 `:app:connectedDebugAndroidTest` 时，UTP 的 `com.android.tools.utp:gradle-work-action:32.5.1` 未缓存，任务未能启动；本轮使用已构建的 APK，直接通过 adb 和 AndroidJUnitRunner 完成相同检测类的设备执行，没有将 Gradle connected 任务记为通过。

本地证据位于 `build/android-verification/gradle-complete.log`、`device-instrumentation-final.txt`、`photo-device-results.txt`，以及 `app/build/test-results/testDebugUnitTest`。这些构建产物和本地日志不进入版本库。

清理仅针对测试包和临时照片：

```powershell
& $adb -s emulator-5554 uninstall com.example.lightrhythm.detectiontest.test
& $adb -s emulator-5554 uninstall com.example.lightrhythm.detectiontest
& $adb -s emulator-5554 shell rm -f /data/local/tmp/lightrhythm-detection-01.jpg /data/local/tmp/lightrhythm-detection-02.jpg
```

## 后续

本轮完成 Android 算法执行验证，未接入选图页面、检测状态展示或修复。后续与 C 对接后台调用、软件 Bitmap 解码和结果展示，再继续做正常照片误检、复杂场景和实体手机验证。
