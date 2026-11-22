# 选图与横纹检测对接

后续更新：已接通 B 的短周期明暗修复预览，63 项 JVM、23 项设备测试通过。颜色横带仍不支持修复。见 [修复对接](repair-integration.md)，以下保留首次选图检测对接记录。

日期：2026-11-22；开发分支 feature/detection。检测算法仍为 row-color-periodicity-v3。

## 已实现的用户流程

打开 App → 选择照片 → 后台解码预览 → 后台检测 → 显示原照片预览及检测结果。解码期间显示“正在读取照片…”，检测期间显示“正在检测横纹…”，并禁用选择、重复检测、修复和保存操作。

检测完成后可点击“重新检测”。换图或重试会清除旧的结论、耗时和修复预览；取消系统选图则保留上一次的照片与结果。读取失败与检测失败分别提示；透明图片检测失败时保留预览，并允许重试或重新选图。

结果卡片区分明暗横纹、颜色横带、未发现明确周期、区域不一致和尺寸不足。候选周期取对应分支的数据，不把顶层亮度 score=0 解释为颜色检测失败，不展示“概率置信度”。

检测对象是最长边 1600 像素的预览，页面明确标出实际尺寸及检测耗时；候选周期单位是预览图的像素行。保存操作仍是队友的预览副本保存，不能解释为修复结果导出。

## 代码与协作边界

按原作者提交记录，将 C 的四个已有提交 cherry-pick 到本检测分支：

| C 原提交 | 内容 |
| --- | --- |
| fdd2632 | 系统选图和预览解码 |
| 8d8213c | 后台处理入口 |
| bcfc799 | 保存预览副本及对应测试 |
| f385e7e | 大图与重复读取稳定性测试 |

未合并到 main，也未更新队友分支。当前检测入口衔接由以下文件完成：

- `PhotoImport.kt`：在 URI 变化时先解码再自动检测，管理读取/检测/失败/重试状态。
- `PhotoDetection.kt`：通过 Dispatchers.Default 调用 A 的 detectBanding，并在返回结果前检查协程取消；不修改、不回收输入 Bitmap。
- `DetectionResultCard.kt`：独立展示检测结论与候选周期，供后续界面调整。
- `PhotoProcessing.kt`：保留 C 的修复适配边界；当前仍返回 NotIntegrated，页面提示“修复算法尚未接入，未生成修复结果”。

状态保留只保存 URI 和选择版本，不序列化 Bitmap 或检测结果；页面重建后按 URI 重新加载和检测。尚未做完整进程终止恢复验证，失去读取权限时会进入已有的读取错误提示。

## 验证结果

构建使用独立的 `.detectiontest` debug 应用包，保留原光律应用。API 37 模拟器上的测试包含：

| 组别 | 数量与结果 |
| --- | --- |
| PhotoDetectionIntegrationTest | 5 项：选中 URI 后自动检测与重试、读取错误、透明图错误、忙碌状态禁用按钮、后台线程与像素保护 |
| PhotoDetectionUiPhotoTest | 2 项：两张课堂照片经真实解码后在页面展示颜色结论与预览坐标 |
| C 的 PhotoSaverTest / PhotoStabilityTest | 3 项：保存副本、大图反复读取、原占位处理和参数边界 |
| 原有检测设备测试 | 9 项：Bitmap 输入约定与两图各四尺寸回归 |

最终设备运行器得到 OK (19 tests)，无失败、无跳过。完整 APK 与测试 APK 构建成功；App JVM 测试此前本轮执行 42 项通过。Compose 测试提供初始已选择 URI，实际系统选图交互另行完成了下述检查。

| 手工设备交互 | 结果 |
| --- | --- |
| MainActivity 中点击“选择照片” | 打开系统 Photo Picker |
| 选择课堂照片并点击 Done | 返回 App，自动检测，无需再点检测按钮 |
| 结果卡片 | 显示“疑似颜色横带”、316 像素行、预览图 1600×1200 |
| 再次打开选图并返回取消 | 原预览、颜色结论和 316 行周期保持 |

两张原图尺寸为 1920×1440，UI 解码后为 1600×1200，因此 01 的页面候选周期约 316 行、02 约 385 行，不能将这些值当作原图 380/462 行坐标。

## 测试依赖兼容

Android 17 上原 Espresso 3.5.1 在输入管理器反射处失败，界面测试无法启动。本轮将测试依赖更新为 Espresso 3.7.0、AndroidX JUnit 1.3.0；这两项仅用于 androidTest。官方 [Espresso 3.7.0 发布说明](https://developer.android.com/jetpack/androidx/releases/test#espresso-3.7.0)记录了使用 getSystemService 替代 InputManager.getInstance 反射的修正。

首次解析新测试依赖需要联网，缓存就绪后可离线运行。未更改产品运行时依赖或 SDK 版本。

## 复现与证据

按 [Android 验证](detection-android-validation.md) 构建与安装独立包。准备私人样本后，将运行器 class 参数设置为下面七个类（逗号分隔）：

```text
com.example.lightrhythm.PhotoDetectionIntegrationTest
com.example.lightrhythm.PhotoDetectionUiPhotoTest
com.example.lightrhythm.PhotoSaverTest
com.example.lightrhythm.PhotoStabilityTest
com.example.lightrhythm.detection.BitmapRowLuminanceTest
com.example.lightrhythm.detection.BitmapDetectionContractTest
com.example.lightrhythm.detection.DetectionPhotoDeviceTest
```

继续传入 `detectionSampleDirectory`，私人照片沿用 01.jpg、02.jpg，不打包或提交到仓库。只运行集成测试时，没有私人样本也可运行 PhotoDetectionIntegrationTest；另一个私人照片 UI 测试类未提供样本目录时会明确跳过。

本地证据位于 `build/integration-verification/`：构建日志、最终设备测试输出、系统选图与结果页面的 UI 层级。图像副本、APK 和本地日志均不进入版本库。本轮结束后清理设备测试包及临时照片，关闭本轮启动的无窗口模拟器。

## 后续

接下来对接 B 的真实修复入口，并继续验证正常照片误检、复杂场景、更多系统版本和实体手机。当前选图与检测已经联通；检测结论不代表修复已经完成。
