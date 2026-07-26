# 85 · 第十一批审计与 P0 切片

对照 ERRATA E151–E165；门禁见 [36](36-go-live-checklist.md)。

## 1. 缺口 → 文档

| 缺口 | 处理 |
|------|------|
| 分析师预期 PIT | [79](79-analyst-revision-pit.md) |
| tick / 整手 | [80](80-tick-lot-constraints.md) |
| 波动率目标 | [81](81-volatility-targeting.md) |
| 风险平价误区 | [82](82-risk-parity-pitfalls.md) |
| VPIN / OFI | [83](83-orderflow-vpin.md) |
| 停牌非法撮合 | [84](84-suspension-halt.md) |

## 2. 第十一批 P0（储备）

| ID | 任务 | 依据 |
|----|------|------|
| P0-33 | 停牌状态拒成断言（禁止昨收成交） | 84 |
| P0-34 | 撮合 tick/lot 与现金拖累报告字段 | 80 |
| P0-35 | vol 缩放若启用：杠杆硬顶 + 不关熔断 | 81 |
| P0-36 | 分析师/一致预期 as-of 契约（若引入） | 79 |
| P0-37 | 禁止日频主路径读取 VPIN/OFI 开仓 | 83 |

优先 P0-33/34（流动性与微观约束诚实）。  
分析师修正 / RP / VPIN **不静默并入**金叉。  
