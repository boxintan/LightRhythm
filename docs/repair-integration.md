# 明暗修复预览对接

2026-11-22，feature/detection。

从 B 的 origin/feature/repair（6b7dd2d916fa2a51a4b6975d5d419e50cda6acdb）引入 repair/BandingRepair.kt、Fft.kt 及其 21 项 JVM 测试，保持其实现不变；没有合并 main 或更新 B 的分支。

新增 BandingPhotoProcessor，作为 C 的 PhotoProcessingPipeline 与 A/B 之间的适配器。App 的“生成修复预览”现在调用此适配器，成功后显示独立修复 Bitmap，沿用原有预览副本保存功能。检测模块不变，仍为 row-color-periodicity-v3。

## 处理条件

1. 强度必须有限且在 0～1；零强度返回未应用。
2. A 必须检出明确的明暗横纹，候选周期在输入预览的 4～64 行范围内。
3. 若存在疑似颜色横带，则明确返回暂不支持，不进行明暗校正。
4. 将编码 RGB 加权亮度归一化为 FloatArray，让 B 独立估计水平条纹；不将 A 的残差直接当作增益，也不向 B 强行传入周期。
5. B 必须实际应用校正，且周期与 A 的候选相差不超过 10%。否则返回未应用。
6. 按校正亮度与原亮度的比例调整三个 RGB 通道，限制到 0～255，并生成新的 Bitmap；若量化后没有像素变化，则不生成修复预览。

新增 ProcessingResult.NotApplied(message)，页面区分未应用和 Preview。原占位处理器保留供调用方及 C 的既有测试使用，App 明确选择真实适配器。

## 验证

- APK 与测试 APK 构建成功，63 项 App JVM 测试通过（原 42 项加 B 的 21 项）。
- API 37 模拟器 AndroidJUnitRunner：OK (23 tests)，无失败、无跳过。
- 新增 3 项适配器测试：合成 16 行周期灰度条纹得到独立修复图，灰度方差低于输入的 20%，输入像素完全不变；正常图/零强度不声称修复；颜色条带明确拒绝。
- 页面新增 1 项测试，验证“检测 → 生成修复预览 → 实际显示修复图”，源文件字节保持不变。
- 原自动检测页面测试扩展，确认颜色样本点击修复后提示暂不支持，未显示修复图。
- 原有保存、导入稳定性、颜色检测及两张私人照片的回归均通过。设备仍使用独立 .detectiontest 包，未覆盖现有光律应用。

本地日志：build/repair-integration/build.log、device-tests.txt。设备复现沿用 detection-android-validation.md 的准备步骤，运行器类列表在 app-detection-integration.md 的七个类基础上增加 com.example.lightrhythm.BandingPhotoProcessorTest。私人照片不打入 APK、不提交仓库，测试后清理。

## 当前范围

现有两张课堂照片的主要问题是长周期颜色横带，此适配器对它们明确返回暂不支持修复。不能将合成明暗条纹的改善解释成这两张真实照片已经修复。

当前支持的是预览尺寸的明暗校正，尚未实现颜色横带修复、高清原图处理、对比滑块或实体手机验证。RGB 量化与高光截断仍可能影响结果，后续需用真实明暗横纹与正常对照图验证视觉质量。
