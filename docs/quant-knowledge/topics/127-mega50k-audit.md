# 127 · 第五万轮审计与 P0（R41211–R51210）

对照 ERRATA E221–E230；目录 [catalog-50k.md](catalog-50k.md)。

## 1. 交付

| 物 | 位置 |
|----|------|
| 10000 轮 | `rounds/mega5/B001`…`B100` |
| 清单 | [R41211–R51210-MANIFEST](../rounds/R41211-R51210-MANIFEST.md) |
| 专文 | [121](121-call-auction-signals.md)…[126](126-rebalance-scaling-calendar.md) |

## 2. P0（储备）

| ID | 任务 |
|----|------|
| P0-63 | 竞价时段状态机 + 废单捕获；静默期禁倾泻 |
| P0-64 | 同 K 禁成交；OHLC 双触悲观路径；信号次开/限价 |
| P0-65 | PEAD/事件：公告 PIT + ADV/成本同测 |
| P0-66 | 选股 ADV/参与率硬过滤；与 Heat 取严 |
| P0-67 | 再平衡优先级：止损/熔断 > 再平衡；对齐金字塔 |

竞价/PEAD/增强/配对 **不静默并入**金叉；公开数字不可外推。  
