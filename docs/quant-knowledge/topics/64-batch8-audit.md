# 64 · 第八批审计与 P0 切片

对照 ERRATA E106–E120；门禁见 [36](36-go-live-checklist.md)。

## 1. 缺口 → 文档

| 缺口 | 处理 |
|------|------|
| 指数调仓套利 | [58](58-index-reconstitution.md) |
| 两融/卖空约束 | [59](59-margin-short-constraints.md) |
| 止损形态对比 | [60](60-stop-placement.md) |
| LLM 情绪前视 | [61](61-news-llm-sentiment.md) |
| CS vs TS 语义 | [62](62-cs-vs-ts-momentum.md) |
| 费率分段 | [63](63-fee-regime-turnover.md) |

## 2. 第八批 P0（储备）

| ID | 任务 | 依据 |
|----|------|------|
| P0-18 | 回测费率按生效日分段 + 高压档 | 63 |
| P0-19 | 止损/trail 参数邻域与 regime 切片报告 | 60 |
| P0-20 | 可选：调出预期/大额调仓窗降权 | 58 |
| P0-21 | 若启用 NLP：模型截止日门禁 | 61 |
| P0-22 | 池排名 vs 开仓规则语义断言（单测） | 62 |

与批五 Heat/跳空、批六验证、批七 Bootstrap 并行时，优先 P0-18/19（成本与出场诚实）。  
