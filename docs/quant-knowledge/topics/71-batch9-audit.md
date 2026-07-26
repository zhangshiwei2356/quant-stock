# 71 · 第九批审计与 P0 切片

对照 ERRATA E121–E135；门禁见 [36](36-go-live-checklist.md)。

## 1. 缺口 → 文档

| 缺口 | 处理 |
|------|------|
| 次新虚高 | [65](65-ipo-xinxin-filter.md) |
| ST 时点 | [66](66-st-pit-filter.md) |
| VWAP/TWAP 验收 | [67](67-vwap-twap-execution.md) |
| 突破/回踩 | [68](68-breakout-vs-pullback.md) |
| 因子择时 | [69](69-factor-timing-regime.md) |
| 回撤持续期 | [70](70-drawdown-duration.md) |

## 2. 第九批 P0（储备）

| ID | 任务 | 依据 |
|----|------|------|
| P0-23 | 宇宙上市日 N 过滤断言（与 OpenFilter 对齐文档） | 65 |
| P0-24 | ST as-of 状态进开仓过滤说明/验收 | 66 |
| P0-25 | 回测报告增加回撤持续期/恢复期 | 70 |
| P0-26 | 大单路径：IS 主验收，VWAP 仅诊断 | 67 |
| P0-27 | 金叉外突破腿须显式开关+计 N | 68 |

优先 P0-23/24/25（宇宙与风控报告诚实）。  
