# 199 · 分数差分陷阱

对照第十六万轮 AI05–AI08、AI26、AI92；衔接 [104](104-feature-store-point-in-time.md)、[163](163-research-reproducibility.md)、[29](29-paradigm-matrix.md)。

## 1. 核心错误

d 海选到「刚好平稳」；过度差分丢记忆；全特征盲目 FracsDiff；与金叉量纲混用；无配置哈希。

## 2. 改法

d **预注册**+样本外；仅必要特征；PIT 窗内估计；配置哈希；差分特征分册，不改金叉定义。

## 3. 本应用

分数差分属特征沙箱；冲突以代码/`rules.html`为准。
