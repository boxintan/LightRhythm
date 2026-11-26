# 横纹检测接口 v10

2026-11-26：当前为工程原型，未建立独立真实场景验证，不能据开发照片回测宣称通用分类能力。v10 像素入口为 `row-region-periodicity-v10` / `row-region-broad-v10`，局部证据增加实际像素跨度检查，避免重叠窗口重复计票。摩尔纹为 `local-chroma-dual-v5`，同一周期须通过原始与归一化色差复核，并至少有三个不重叠来源矩形；`testedTiles` 记录实际尝试的空间中心数。结果字段兼容，仍不能把周期候选理解为已确认的成像干扰。方法、失败及验证边界见 [泛化审查](generalization-audit-v10.md)。

2026-11-26：像素入口为 `row-region-periodicity-v9`，中性背景模式为 `row-region-broad-v9`。128/64 局部检测失败后独立尝试 256 窗口；再失败时检查高饱和颜色区域内的周期。新增可选 `BandingResult.localizedStripes`（默认空列表），每项为输入 Bitmap 像素坐标下的矩形、`StripeAxis`、`periodPixels`、通道与最弱支持条带相关值。横向周期以行为单位、竖向以列为单位；旧行序列字段不改成区域数据。新证据沿用 `SUSPECTED_LOCAL_BANDING`，禁止整行修复。摩尔纹算法保持 v4。已改善及仍漏检的照片、参数、验证见 [v9 记录](new-samples-v9.md)。以下保留历史接口演进。

2026-11-26：像素入口为 `row-local-periodicity-v8`，中性背景模式为 `row-local-broad-v8`。局部候选必须在至少两列获得上下位置的持续支持，并使用未按倾斜过滤的周期相容位置排查矛盾；支持比例沿用 `DetectionConfig.minRegionSupport`。倾斜方向按周期相关与残差幅度的乘积选择。函数、字段及坐标含义不变，摩尔纹版本不变。参数、13 号百叶窗反例和验证见 [v8 记录](blinds-false-positive-v8.md)。以下为历史记录。

2026-11-25 更新：像素入口版本为 `row-local-periodicity-v7`（确认中性背景时 `row-local-broad-v7`）。局部回退先检查 128 像素窗口，失败后独立检查 64 窗口，不累加尺度票数。`LocalStripeTile.sourceMarginRows` 分别为 16/8，读取范围须使用返回值；`LocalBandingEvidence.testedCells` 是所选尺度的空间位置数。公开函数和原有字段含义不变。摩尔纹版本为 `local-chroma-recurrence-v4`，仅优化矩形像素读取，候选判据保持。详见 [本轮验证](detection-tile-scale-v7.md)。以下为历史演进记录。

2026-11-25：Bitmap 与桌面像素入口已增加局部/倾斜条纹回退，版本 `row-local-periodicity-v6`；确认中性原内容时为 `row-local-broad-v6`。新增 `SUSPECTED_LOCAL_BANDING` 与 `localBandingEvidence`，计入 `isSuspected`。旧行统计字段仍表示整行结果，局部结果必须读取独立证据，不能据此执行整行修复。底层 `analyzeDetectionProfiles` 的 v4 语义不变。详见 [本轮接口与照片验证](local-stripes-and-moire-v3.md)。下文保留历史演进记录。

摩尔纹检测现有独立接口 `detectMoire(Bitmap): MoireResult`，不更改本接口的周期和状态语义。App 并行展示两类结果，摩尔纹候选禁止送入横纹修复。实现范围与照片验证见 [彩色摩尔纹检测](moire-detection.md)。

宽幅模式更新：新增可选参数 `assumeNeutralBackground = false`。调用方确认白色/灰色原画面时可置 true，组合版本变为 row-color-broad-v5，附加 broadColorEvidence；无周期而有宽幅证据时为 SUSPECTED_BROAD_COLOR_BAND。默认周期 v4 行为不变，新枚举也计入 isSuspected，不能据此直接调用明暗修复。详见 [宽幅检测](broad-color-detection.md)。

2026-11-23：补充低于最小周期的整数倍候选排除规则，防止短周期纹理绕过 minPeriodRows。字段及坐标不变，组合版本为 row-color-periodicity-v4，关闭颜色分支后的明暗版本为 row-periodicity-v2；以下 v3/v2 说明保留为历史接口演进。详见 [v4 检测验证](detection-v4-minimum-period.md)。

选图对接更新：已在当前检测分支接通 PhotoImportRoute → PhotoDetectionPipeline → detectBanding → DetectionResultCard，基于最长边 1600 的预览图检测，页面展示对应分支周期和实际预览尺寸。19 项设备测试及实际系统选图检查通过。见 [App 检测对接](app-detection-integration.md)。

2026-11-22 算法验证记录：debug APK、测试 APK 和 App JVM 测试已通过；API 37 模拟器的 9 项算法测试通过，其中两张真实照片各四个尺寸均检出颜色候选。实体手机尚未验证，选图页面对接进度见上。详见 [Android 验证](detection-android-validation.md)。

2026-11-22：颜色分支的去趋势半径现在按输入高度缩放。`ColorDetectionConfig.referenceHeightRows` 默认 1440；实际半径为 round(trendRadiusRows × 输入高度 / referenceHeightRows)，限制在 1～1024 行。设为 null 时使用固定半径，可复现 v2 的计算。原明暗分支、幅度/相关阈值及颜色最大搜索周期 1024 行均未改变。

`colorEvidence.effectiveTrendRadiusRows` 和 `effectiveMaxLagRows` 记录本次实际窗口与最大搜索滞后。候选周期、区域坐标和行序列继续使用传入 Bitmap 的坐标，不自动换算原图坐标。版本为 `row-color-periodicity-v3`；关闭颜色分支仍为 `row-periodicity-v1`。详见 [分辨率一致性验证](detection-v3-resolution.md)。

以下为 v2 引入的颜色接口；其中固定 256 行窗口现由以上缩放规则替代。

2026-11-22：新增独立颜色横带检测。`detectBanding(bitmap, config)` 仍可调用，默认启用颜色检测；第三个可选参数为 `ColorDetectionConfig`。复现旧版时传 `colorConfig = ColorDetectionConfig(enabled = false)`。

新增状态 `SUSPECTED_COLOR_BANDING`，`isSuspected` 同时包含明暗和颜色候选。C 若用枚举分支展示结果，需要增加此状态。B 的明暗修复入口应检查 `status == SUSPECTED`，不能单凭 `isSuspected` 决定采用明暗校正。

**字段区分：**原有 score、candidatePeriodRows、rmsAmplitude、autocorrelation、regionSupport、行序列、regions 继续只描述亮度分析。颜色候选单独放在 `colorEvidence.channels`，每项包括 channel、status、candidatePeriodRows、score、rmsAmplitude、autocorrelation、regionSupport、regions。颜色候选可能存在而顶层亮度 score 仍为 0、candidatePeriodRows 仍为 null；界面展示颜色结果时必须读取颜色字段。

`colorEvidence.luminanceStatus` 保留原明暗检测结论，`colorEvidence.config` 记录颜色搜索参数。颜色检测未开启或尺寸太小时，colorEvidence 可以为空。

颜色分支使用 `(R-G)/2+127.5` 和 `(B-G)/2+127.5` 两个 0～255 编码序列，同样要求周期峰、半周期反转及至少三个区域一致。颜色默认搜索至 min(1024, 高度/2)，局部去趋势窗口半径为 256 行；最小周期、幅度和相关阈值沿用 DetectionConfig。至少两个周期的条件仅用于颜色分支，旧明暗分支仍要求至少四个周期、默认最大周期 256 行。窗口与周期均使用输入图的像素行坐标，不自动随缩放换算。

版本为 `row-color-periodicity-v2`；禁用颜色时复现 `row-periodicity-v1`。以下原有明暗字段定义继续有效；两图诊断见 [v2 算法分析](detection-v2-analysis.md)。

适用成员：A 检测、B 修复、C Android 核心。依据本地 `../Project Plan.docx`，首版在本机运行，Kotlin 实现。此文档是当前代码的接口草案，待 B/C 确认。

## 调用

```kotlin
import com.example.lightrhythm.detection.detectBanding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val result = withContext(Dispatchers.Default) {
    detectBanding(softwareBitmap)
}
```

输入应为未释放、不透明、已处理 EXIF 方向的**软件 Bitmap**；调用不修改输入像素、不缩放图片、不读取 URI、不上传图片。透明图须先确定背景合成规则，硬件 Bitmap 须由调用方解码或转换为可读像素的软件 Bitmap。不支持的输入抛出 `IllegalArgumentException`，由 C 转换成用户提示。

## 处理逻辑

1. 分成四个覆盖全宽的纵向区域，每行读取一次所有像素，区域宽度差最多一像素。
2. 计算 `0.299R + 0.587G + 0.114B` 的行均值；这是编码 RGB 的亮度近似，不是线性物理光照强度。
3. 按像素数量加权得到整图序列。滑动窗口做局部最小二乘直线拟合，减去慢变趋势；边缘同样拟合直线。
4. 对重叠区间算 Pearson 归一化自相关。在候选周期处要求相关峰、半周期处要求负相关，避免仅凭平滑信号的高相关判断周期。
5. 至少三个区域在该周期有足够变化，并与整图残差同相，才输出 SUSPECTED。

默认要求：高度至少 64；四区每区宽至少 8；候选周期为 4～min(256, 高度/4) 行；残差 RMS 至少 2；周期相关至少 0.65；半周期相关不高于 -0.15；区域与整图相关至少 0.7；区域支持率至少 0.75。可配置项保存在结果的 `config` 中，其余为此版本固定规则。

这些是尚未经过真实数据标定的工程初始值。默认范围外的周期、局部条带、非周期变化、彩色变化和被场景内容遮蔽的条带可能漏检。

时间复杂度约为 O(宽×高 + 高×最大搜索周期)，额外空间 O(宽 + 高)；不代表已经达到任何手机延迟目标。

## 输出状态

| status | 含义 |
| --- | --- |
| SUSPECTED | 找到了跨区域一致的周期性明暗变化；仍不能确认 LED 成因 |
| SUSPECTED_COLOR_BANDING | 颜色分支存在跨区域周期候选；查看 colorEvidence，不输出颜色修复增益 |
| LOW_AMPLITUDE | 残差变化低于当前幅度阈值 |
| NO_CLEAR_PERIODICITY | 在当前搜索范围和规则下没有找到候选；不代表照片正常 |
| INCONSISTENT_REGIONS | 整图有候选，但区域支持不足，保留候选及区域诊断 |
| INSUFFICIENT_DATA | 尺寸不足以支持当前方法；不输出“正常”结论 |

`isSuspected` 等价于 `status == SUSPECTED || status == SUSPECTED_COLOR_BANDING`。

## BandingResult 字段

| 字段 | 约定 |
| --- | --- |
| imageWidth / imageHeight | 输入 Bitmap 的尺寸 |
| score | 0～1 启发式指标：候选相关 × min(RMS/8, 1) × 区域支持率；不是概率、不是已标定置信度 |
| candidatePeriodRows | 输入图片行坐标下的整数周期；无候选时为 null |
| rmsAmplitude | 去趋势残差的均方根，单位为 0～255 亮度近似值 |
| autocorrelation | 所选周期的相关值，无候选时为 0；不表示所有滞后相关都为 0 |
| regionSupport | 四区中支持当前候选的比例；未评估时为 0 |
| rowLuminance / rowTrend / rowResidual | 每行一个值，长度均为 imageHeight；raw = trend + residual |
| candidateRowOffsets | 仅 SUSPECTED 时提供：按周期折叠平均并去除周期模板均值，再展开至每行；其他状态为空 |
| regions | 各区域的半开 x 范围、幅度、周期相关、同相相关和支持标记；尚未评估时为空 |
| config / algorithmVersion | 参数快照和算法版本，便于复现实验 |

`rowResidual` 仍包含场景变化；`candidateRowOffsets` 也只是候选加性亮度分量，不是每行乘性增益。B 不能未经验证直接用它校正 RGB；正常场景损伤、色彩、范围限制与 strength 参数由修复模块独立验证。暂不自动给出修复许可。

所有行下标均对应**传入的 Bitmap**。将 1600 像素预览的结果用于高清原图前，A/B/C 必须另行确认缩放与行坐标映射。

## 方法边界

真实的全宽重复横向纹理与某些频闪条带可能具有相同统计特征，单凭这套统计无法判定成因。本版采用“疑似”而非“确认”，并保留全宽纹理会触发疑似的测试案例。不能将合成周期测试替代真实 LED 样本验收。

基础数学参考：[最小二乘去趋势](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.detrend.html)、[相关运算定义](https://docs.scipy.org/doc/scipy/reference/generated/scipy.signal.correlate.html)。本版是独立 Kotlin 实现，局部窗口、归一化、判定规则和阈值由当前工程定义，不依赖 SciPy，也不是该文档验证过的 LED 分类方法。

## 复现计算测试

正常环境优先执行 `./gradlew.bat :app:testDebugUnitTest`；完整设备测试使用 `:app:connectedDebugAndroidTest`。

当前环境 Gradle 缓存锁不可写时，可在项目根目录使用下面的离线替代命令。它直接使用已经缓存的编译器，不修改依赖版本，也不替代 APK 构建或设备测试：

```powershell
.\tools\test-detection.ps1 `
  -JavaHome 'D:\Android Studio\jbr' `
  -DependencyCache 'C:\Users\lenovo\.gradle\caches\modules-2\files-2.1' `
  -AndroidJar 'C:\Users\lenovo\AppData\Local\Android\Sdk\platforms\android-37.0\android.jar'
```

可添加 `-SampleImage '本地图片的绝对路径'` 读取原文件并打印桌面诊断。此工具使用 ImageIO，不处理 EXIF，不能直接等同于 Android 的解码和设备行为。
