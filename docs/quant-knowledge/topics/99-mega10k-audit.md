# 99 · 万轮审计与 P0 切片（R1211–R11210）

对照万轮 ERRATA 段与 [catalog-10k.md](catalog-10k.md)；门禁见 [36](36-go-live-checklist.md)。

## 1. 交付物

| 物 | 位置 |
|----|------|
| 10000 轮正文 | `rounds/mega/B001`…`B100` |
| 簇索引 | [R1211–R11210-MANIFEST](../rounds/R1211-R11210-MANIFEST.md) |
| 簇目录 | [catalog-10k.md](catalog-10k.md) |
| 新专文 | [93](93-longhubang-seat-pit.md)…[98](98-factor-neutralization-pit.md) |

> 说明：万轮以 **100 块 × 100 轮** 存储（非 10000 个单文件），便于检索且每轮仍含「不足→错误→解析→优化」。

## 2. 万轮 P0（储备）

| ID | 任务 |
|----|------|
| P0-43 | 龙虎榜/席位 as-of（若引入） |
| P0-44 | 事件三时间戳契约（公告/可知/生效） |
| P0-45 | Kill Switch / 日终对账验收 |
| P0-46 | 因子中性行业/股本 as-of |
| P0-47 | 打板/转债/期货期权 **禁**并股票金叉（边界测试） |

## 3. 边界

知识库 ≠ 已实现；公开数字不可外推；冲突以 `rules.html` / 代码为准。  
