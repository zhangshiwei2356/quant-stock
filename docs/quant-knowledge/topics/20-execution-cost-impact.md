# 20 · 交易成本与冲击（四层）

对照 ERRATA E23、E28；扩展成本认知。

## 1. 四层

| 层 | 含义 |
|----|------|
| 显性 | 佣金、印花税、过户费 |
| 滑点 | 意向价与成交价差（观测） |
| 冲击 | 己方下单推动价格（归因） |
| 机会 | 未成交/延迟的错失 |

## 2. 冲击直觉

常见工程近似：冲击与 \(\sqrt{Q/ADV}\) 及波动相关；永久+临时分量。  
大资金固定 30–50bp 往往不够；组合优化应把冲击放进目标（可用分段线性逼近以求可解）。

## 3. 与本应用

`rules.html`：分级滑点 + `min(0.1×量/均量, 2%)` 冲击。  
研发大资金策略时：提高冲击系数做压力、限制单票成交额占比。  
参与率与风险预算取更严者：[56-participation-sizing.md](56-participation-sizing.md)。  
印花税等费率制度分段：[63-fee-regime-turnover.md](63-fee-regime-turnover.md)。

回测–纸面–实盘落差与 Implementation Shortfall 诊断见 [31-execution-gap-is.md](31-execution-gap-is.md)。  
