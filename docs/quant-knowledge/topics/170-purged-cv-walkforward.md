# 170 · 净化交叉验证与 Walk-Forward

对照第十二万轮 AE01–AE12、AE37、AE47、AE70、AE72；衔接 [46](46-dsr-pbo-validation.md)、[163](163-research-reproducibility.md)。

## 1. 核心错误

标准 K 折标签重叠泄漏；只 purge 决策日不按标签区间；零 embargo 滚动特征桥接；全局标准化前视；WFA 超参偷看全样本；CPCV **路径数**误当 DSR trials。

## 2. 改法

标签区间 **purge + embargo（≥特征窗）**；折内标准化；锚定/滚动 WFA 预注册且 **成本同开**；DSR 按**配置/试验数**（相关试验折算）；配置哈希可复现。

## 3. 本应用

验证属门禁层；不得用 CV/WFA 最优参静默改写 MA5/MA20 金叉定义。
