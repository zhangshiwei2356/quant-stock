# 78 · 第十批审计与 P0 切片

对照 ERRATA E136–E150；门禁见 [36](36-go-live-checklist.md)。

## 1. 缺口 → 文档

| 缺口 | 处理 |
|------|------|
| 开盘竞价噪音/价源 | [72](72-opening-auction.md) |
| 尾盘/盘后成交假设 | [73](73-closing-auction-afterhours.md) |
| Volume Profile 误用 | [74](74-volume-profile.md) |
| PEAD 前视与落地 | [75](75-pead-earnings-drift.md) |
| 全 Kelly | [76](76-kelly-fractional-sizing.md) |
| 相关尖峰 | [77](77-correlation-spike.md) |

## 2. 第十批 P0（储备）

| ID | 任务 | 依据 |
|----|------|------|
| P0-28 | 开盘成交价源字段（auction vs continuous）断言 | 72 |
| P0-29 | 收盘/盘后路径与 T+1 状态机文档化（部分成交） | 73 |
| P0-30 | 回测禁 VPVR 视窗依赖；VP 仅固定区间+开关 | 74 |
| P0-31 | PEAD/事件模块：公告日 PIT + 跳空过滤（非金叉） | 75 |
| P0-32 | 定仓：分数 Kelly 上界对照 + 压力相关情景进报告 | 76,77 |

优先 P0-28/29/32（撮合诚实与组合风险）。  
竞价打板 / PEAD / VP **不静默并入**金叉主路径。  
