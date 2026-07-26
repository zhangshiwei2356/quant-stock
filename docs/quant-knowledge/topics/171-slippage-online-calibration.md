# 171 · 滑点在线校准

对照第十二万轮 AE13–AE17、AE38、AE44、AE45、AE71；衔接 [20](20-execution-cost-impact.md)、[165](165-partial-fill-cancel-replace.md)、[157](157-capacity-aum-scaling.md)。

## 1. 核心错误

固定 bps 无视 ADV；事后拟合美化；纸面-实盘残差不回写；部成忽略仍用满额滑点；扩容不重校准。

## 2. 改法

ADV/参与率分段冲击；**在线残差**（影子/实盘 vs 模型）日报；与部成状态机同开；扩容触发重校准；灰度切换。

## 3. 本应用

滑点属成本/执行层；不改金叉开仓方向；冲突以 `rules.html`/代码为准。
