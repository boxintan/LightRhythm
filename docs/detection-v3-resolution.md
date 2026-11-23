# 检测 v3：缩略图分辨率一致性

日期：2026-11-22；分支 feature/detection。

## 问题与改动

在两张课堂照片上做桌面双线性缩放，长边分别为 1920、1600、960、480 像素。旧 v2 对 01.jpg 仅在 1920 长边时输出疑似颜色横带，三个缩略尺寸均漏检；02.jpg 四个尺寸均检出。

固定 256 行的去趋势半径在图片缩小时占据越来越大的画面比例，改变了分析得到的颜色残差。v3 增加参考高度配置：默认以 1440 行为参考，实际半径 = round(256 × 输入高度 / 1440)，限制在 1～1024 行。四个测试尺寸的半径分别为 256、213、128、64 行。

保持原明暗检测及颜色检测的幅度、相关、区域一致性阈值不变；颜色最大周期上限仍为 1024 行，且不超过输入高度/2。原图不被修改，检测函数也不执行缩放。所有输出周期与行序列仍对应实际输入 Bitmap。

颜色配置新增 `referenceHeightRows: Int? = 1440`。设为 null 时复现固定窗口的 v2 计算；禁用颜色分支仍可复现原明暗基线。颜色证据新增实际窗口和搜索上限，版本为 row-color-periodicity-v3。

## 两图验证结果

| 图片 | 长边 | v2 | v3 | v3 颜色候选周期（输入像素行） |
| --- | --- | --- | --- | --- |
| 01 | 1920 | 疑似颜色横带 | 疑似颜色横带 | R−G，380 |
| 01 | 1600 | 未找到明确周期 | 疑似颜色横带 | R−G，316 |
| 01 | 960 | 未找到明确周期 | 疑似颜色横带 | R−G，190 |
| 01 | 480 | 未找到明确周期 | 疑似颜色横带 | R−G，95 |
| 02 | 1920 | 疑似颜色横带 | 疑似颜色横带 | B−G，462 |
| 02 | 1600 | 疑似颜色横带 | 疑似颜色横带 | B−G，385 |
| 02 | 960 | 疑似颜色横带 | 疑似颜色横带 | B−G，231 |
| 02 | 480 | 疑似颜色横带 | 疑似颜色横带 | B−G，116 |

v3 的八次检测均为四区中三区支持。周期随缩放比例变化，例如 380 行在缩小一半后为 190 行。本轮八种输入来自两张照片，是开发集上的尺度回归检查，不能算八张独立照片或真实检测准确率。

编译和 47 项 JVM 测试通过：原 42 项保持通过，新增 5 项覆盖窗口缩放及关闭缩放、跨分辨率候选周期与输出坐标、平滑色彩渐变反例、窗口上下限、非法参考高度。

## 复现

先生成仅用于桌面测试的样本，输出目录每次使用新名字，并放在已忽略的 build 下。原图只读，不应用 EXIF；输入应已具有正确像素方向。缩放不模拟 Android 的具体解码器。

```powershell
& 'D:\Android Studio\jbr\bin\java.exe' .\tools\PrepareResolutionSamples.java `
  '.\build\resolution-check-001' `
  'C:\Users\lenovo\Desktop\Second_Autumn\software\photo\01.jpg' `
  'C:\Users\lenovo\Desktop\Second_Autumn\software\photo\02.jpg'

.\tools\test-detection.ps1 `
  -JavaHome 'D:\Android Studio\jbr' `
  -DependencyCache 'C:\Users\lenovo\.gradle\caches\modules-2\files-2.1' `
  -AndroidJar 'C:\Users\lenovo\AppData\Local\Android\Sdk\platforms\android-37.0\android.jar' `
  -DatasetManifest '.\build\resolution-check-001\manifest.tsv' `
  -ReportDirectory '.\evaluation-reports\resolution-check-001'
```

首次对照报告保存在本地 `evaluation-reports/resolution-v2-001` 与 `resolution-v3-001`，对应 `build/resolution-probe/manifest.tsv`。逐图报告增加 colorTrendRadiusRows 与 colorMaxLagRows。

## 仍待完成

本轮仅处理颜色去趋势窗口的尺度一致性，不意味着任意分辨率下结果完全相同。最小周期仍为 4 行、最大周期仍有上限，缩放也会改变像素细节；原明暗分支仍使用原配置。参考高度与窗口比例属于工程参数，需要后续正常图、困难负样本和不同场景验证。Android 解码、Bitmap 仪器测试、设备耗时及 App 接入仍待进行。
