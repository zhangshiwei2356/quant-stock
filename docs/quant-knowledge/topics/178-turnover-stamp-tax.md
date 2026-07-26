# 178 · 换手约束与印花税

对照第十三万轮 AF05–AF08、AF31、AF46、AF60；衔接 [20](20-execution-cost-impact.md)、[171](171-slippage-online-calibration.md)、[109](109-program-trading-reg-detail.md)。

## 1. 核心错误

事后才扣成本；无换手惩罚导致纸面高 IR；印花税漏计或按买卖双边；税率变更无 as-of；异常换手无合规约束。

## 2. 改法

换手 **L1/硬顶进优化或再平衡**；A股卖出印花税显式且税率 as-of；换手日报；与滑点/部成同开。

## 3. 本应用

成本假设写进回测报告；不改金叉定义；冲突以 `rules.html`/代码为准。
