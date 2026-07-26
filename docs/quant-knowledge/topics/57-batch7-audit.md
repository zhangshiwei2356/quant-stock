# 57 · 第七批审计与 P0 切片

对照 ERRATA E91–E105；门禁见 [36](36-go-live-checklist.md)。

## 1. 缺口 → 文档

| 缺口 | 处理 |
|------|------|
| 解禁/减持误用 | [51](51-unlock-reduce-events.md) |
| 盈余质量/红旗 | [52](52-earnings-quality-fraud.md) |
| Bootstrap MDD 乐观 | [53](53-bootstrap-drawdown.md) |
| 题材并金叉 | [54](54-theme-concept-pitfalls.md) |
| 假集成 | [55](55-signal-ensemble.md) |
| 参与率定仓 | [56](56-participation-sizing.md) |

## 2. 第七批 P0（储备，不改码）

| ID | 任务 | 依据 |
|----|------|------|
| P0-13 | 大额解禁窗降仓/禁新开可选开关 | 51 |
| P0-14 | 回测报告块 Bootstrap MDD/Sharpe 分位 | 53 |
| P0-15 | 仓位公式增加 ADV 参与率夹逼验收 | 56 |
| P0-16 | 信号正交性检查（滤网相关矩阵） | 55 |
| P0-17 | 题材标签仅服务 Heat，禁打板成交假设 | 54,37 |

优先级：P0-14/15 服务验证与成交诚实；P0-13/16/17 服务过滤卫生。仍低于批五 Heat/跳空主线时可并行验证类。  
