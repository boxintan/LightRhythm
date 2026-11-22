# 真实照片批量评估

本工具在电脑上运行现有 Kotlin 检测代码，输入照片清单，输出逐图结果和汇总表。桌面运行使用 ImageIO 解码，不代表 Android 设备上的解码或性能结果。

## 1. 准备样本

复制 `datasets/manifest.template.tsv` 为 `datasets/local/samples.tsv`，用 Excel 或文本编辑器填写，保存为 UTF-8、制表符分隔文件。每行一张图，五列都必填。

| 列 | 填写方式 |
| --- | --- |
| id | 唯一样本编号，例如 classroom-001 |
| path | 图片绝对路径，或相对清单所在文件夹的路径；支持中文、空格 |
| label | `banding`：人工确认有周期明暗横纹；`clean`：没有目标明暗横纹；`unknown`：暂不能判断 |
| split | `dev`：用于分析和调参；`validation`：用于固定参数后的独立验证 |
| group | 拍摄场景或来源组，例如 classroom-screen-session1；同一连拍、同图裁剪和同源场景放在同一组 |

标签由人工查看照片决定，不依据算法输出决定。彩色色序伪影与本版目标明暗条带需要区分；不明确的照片先标 `unknown`。不能通过调整标签来提高指标。同一组不能横跨两个集合，工具会检查组名；不同文件的重复内容仍需人工排查。

建议照片覆盖：明显明暗横纹、轻微横纹、正常室内外照片、文字/建筑横线/百叶窗等困难负样本。为横纹正样本保留拍摄场景、设备和人工标注依据；仅凭检测结果不能判断 LED 成因。

ImageIO 不应用 EXIF 方向。请使用像素方向已经正确、横纹呈水平方向的不透明 JPG/PNG。需要旋转时保留原文件，另存方向已应用的评估副本；全分辨率样本和缩略图不要混为同一测试条件。尺寸不足、透明输入和无法解码的图片会被单独记录。

## 2. 运行

在项目根目录的 PowerShell 中执行（下列依赖路径适用于当前电脑）：

```powershell
.\tools\test-detection.ps1 `
  -JavaHome 'D:\Android Studio\jbr' `
  -DependencyCache 'C:\Users\lenovo\.gradle\caches\modules-2\files-2.1' `
  -AndroidJar 'C:\Users\lenovo\AppData\Local\Android\Sdk\platforms\android-37.0\android.jar' `
  -DatasetManifest '.\datasets\local\samples.tsv' `
  -ReportDirectory '.\evaluation-reports\run-001'
```

先编译源码并执行计算与评估工具测试，再进行整批检测。每次使用一个新的报告目录，已有报告不会被覆盖。单张图片失败会写入错误列并继续下一张；清单格式或分组错误会中止。样本图片和报告目录默认被 Git 忽略；不要将私人照片加入版本库。

## 3. 查看结果

`results.csv` 记录每张照片的人工标签、检测状态、TP/FP/TN/FN、周期、亮度变化幅度、区域支持率和分析耗时。用 Excel 打开后，优先筛选 FP 和 FN 查看误检、漏检，再检查 ERROR 和 SKIPPED。

| outcome | 含义 |
| --- | --- |
| TP | 人工标横纹，算法输出 SUSPECTED |
| FP | 人工标无目标横纹，算法输出 SUSPECTED，即误检 |
| TN | 人工标无目标横纹，算法未输出 SUSPECTED |
| FN | 人工标横纹，算法未输出 SUSPECTED，即漏检 |
| ERROR | 读取、解码或像素处理失败；不计入混淆矩阵 |
| SKIPPED | 尺寸不足；不计入混淆矩阵 |
| UNLABELED | 人工标签为 unknown，仅记录诊断结果 |

`summary.csv` 分开汇总 dev 与 validation：

| 列 | 计算 |
| --- | --- |
| precision | TP / (TP + FP)；检出的样本中有多少符合人工横纹标签 |
| recall | TP / (TP + FN)；可评估横纹正样本中检出多少 |
| falsePositiveRate | FP / (FP + TN)；可评估负样本中误检多少 |
| labeledCoverage | (TP + FP + TN + FN) / 全部已标注样本；显示失败和尺寸不足造成的评估缺口 |

分母为零时指标留空，不能解释为零错误或百分之百正确。ERROR 和 SKIPPED 不应被遗漏：必须同时看覆盖率和各类缺口。没有人工标注的真实样本时，无法计算检测准确性。

耗时只覆盖桌面逐行统计和周期分析，不含图片读取/解码，没有预热，不能当作手机耗时。当前工具使用默认 DetectionConfig 和 ColorDetectionConfig，版本为 row-color-periodicity-v2；旧报告可能为 row-periodicity-v1。规则变化后，应在新目录重新评估并保留对应代码版本。

v2 增加颜色横带分支。results.csv 的原有 score、periodRows、rms、autocorrelation、regionSupport 仍为亮度字段；新增 luminanceStatus 以及 colorChannel、colorPeriodRows、colorScore、colorAutocorrelation、colorRegionSupport。颜色列选取通过疑似判据且 score 最高的通道，完整双通道证据可用 InspectDetectionImage 查看。status=SUSPECTED_COLOR_BANDING 时，原亮度 score 可以为 0，这是两个独立分支的结果。评估统计中两种疑似状态均视为预测阳性；使用 banding 标签前须明确目标是否包含颜色横带，目前两张课堂图片仍为 unknown，未用于计算准确性指标。

## 4. 下一轮改进

先用 dev 集逐图分析 FP/FN 的原因，记录失败类型；需要改算法时只据 dev 集调整。固定规则后再查看 validation 结果。新增真实样本、标签修订或重复场景都会改变比较条件，应在实验记录中注明。
