# 单一交易目标池（自动覆盖）设计

**日期：** 2026-07-19  
**状态：** 已批准（废弃表改为 DROP）  
**范围：** 取消推荐确认双池；盘后扫描直接维护唯一目标池；本版不做持仓三级降级

---

## 1. 背景与目标

### 1.1 现状问题

当前为双池：

1. 盘后扫描写入 `trade_pool_recommend`（PENDING）
2. 人工 confirm → `trade_pool`（最终池）
3. 盘中 `scan-and-trade` 只读最终池

这与「每日选股机器人自动更新次日可买名单」的目标不一致，确认环节增加摩擦，且存在两份盘后任务重复调用同一重建逻辑。

### 1.2 目标（本版）

- **唯一目标池**：仅保留 `trade_pool` 存放每日扫描入选标的。
- **自动覆盖**：扫描结果写入后，不在本次 TopN 中的标的自动移出池；无需人工确认/忽略。
- **开仓边界**：盘中仅允许对池内标的**新开仓**；已持仓卖出仍只由策略信号（死叉/止损/止盈等）驱动。
- **移出 ≠ 卖出**：被踢出目标池不触发卖出（三级降级明确延后）。

### 1.3 非目标（本版不做）

- 持仓三级降级（观察 / 减半 / 连日清仓）
- 移出即清仓
- 新建 `daily_stock_pool` 表
- 企业微信 / 邮件推送
- 完整粗筛全量落地（流通市值、20 日均额等依赖真实行情字段；本版仅做已有数据可支撑的过滤）

---

## 2. 核心原则

| 原则 | 说明 |
|------|------|
| 池 = 候选买入名单 | 决定「能不能买新的」，不决定「持有的何时卖」 |
| 全量替换 | 每次盘后扫描以本次结果为准覆盖 `trade_pool` |
| 持仓独立 | 移出池不卖；卖出只听策略信号 |
| 单任务入口 | 盘后只保留一条有效调度语义（见 §6） |

---

## 3. 数据模型

### 3.1 保留并作为唯一池：`trade_pool`

继续使用现有表，语义改为「当前生效的可买目标池」。

建议字段语义（现有列够用则不强制改表）：

| 列 | 用途 |
|----|------|
| `symbol` / `name` | 标的 |
| `score` | 扫描排序分（沿用回测 `totalRate` 或后续可扩展） |
| `reason` | 入选原因摘要 |
| `status` | `1` = 在池；扫描移出时置 `0` 或物理删除（实现选一种，推荐物理删除或 `status=0` 后由 `listActiveCodes` 只读 `status=1`） |
| `source` | 固定如 `BATCH_SCAN` |
| `report_id` | 可选，关联当次分析报告 |
| `entered_at` / 更新时间 | 本次入池或刷新时间 |

**覆盖算法：**

1. 计算本次 `selectedCodes`（TopN）
2. 对 selected：upsert（更新 score/reason/report_id，status=1）
3. 对原 status=1 且不在 selected：删除或 status=0（移出）
4. 可选：写简短 batch 日志（可用报告表 `batch_id` 关联，不必新建 history 表）

### 3.2 废弃并 DROP（本版）

| 表 / 能力 | 处理 |
|-----------|------|
| `trade_pool_recommend` | **DROP TABLE**；删除对应 Mapper/DO/XML 与所有读写 |
| `trade_pool_recommend_report` | **DROP TABLE**；入池分析改为写入新表 `trade_pool_report`（或等价：分析 JSON 落文件 + `trade_pool` 仅存摘要）。推荐新增精简表 `trade_pool_report`（symbol/score/reason/summary/analysis_json/batch_id），由 `trade_pool.report_id` 关联 |
| `trade_pool_history` | **DROP TABLE**；删除历史菜单与 Mapper |
| confirm / dismiss API | 删除 |

启动时 `ensureSchema`：对上述三表执行 `DROP TABLE IF EXISTS`；`schema.sql` 同步删除建表语句并增加 `trade_pool_report`（若采用）。

### 3.3 配置

- `quant.trade-pool-max`：TopN 上限（已有，默认 30）
- 无需新增「自动确认」开关（确认流整体删除）

---

## 4. 扫描与入池流水线

```
listUniverse()
  → coarseFilter（本版最小集）
  → BatchStockBackTestService.scan(codes)
  → isRecommendable / 信号门槛
  → 按 score(totalRate) 排序取 TopN
  → replaceActivePool(selected)   // 直接写 trade_pool
  → （可选）写入分析报告行
```

### 4.1 宇宙来源

沿用 `TradePoolService.listUniverse()`：`stock_basic`（`status!=0`），否则 `quant.stock-codes`。

### 4.2 粗筛（本版最小集）

在调用 `scan` 前过滤：

- `stock_basic.status == 0` → 跳过（已有）
- `stock_basic.is_st == 1`（或等价标记）→ 跳过（若字段可用）

市值 / 20 日成交额 / 停牌：依赖行情完备后再加，本版不阻塞上线。

### 4.3 入选条件与打分

- 入选门槛：沿用现有 `isRecommendable`（`canBuyNow` 或 MA5>MA20 且 RSI 上限等）
- 排序：`totalRate` 降序，取前 `tradePoolMax`
- **删除**「已在最终池则跳过写入推荐」的旧逻辑——唯一池下改为刷新该行 score/reason

### 4.4 与盘中策略关系

| 任务 | 行为 |
|------|------|
| 盘后重建 | 覆盖 `trade_pool` |
| `scan-and-trade` | `listActiveCodes()` 读池；仅对池内标的评估**买入**；持仓卖出逻辑不变，**不**因不在池而卖 |

---

## 5. API 与前端

### 5.1 API

| 动作 | 调整 |
|------|------|
| `POST /api/stock/trade-pool/rebuild` | 改为扫描并**覆盖唯一池**；返回 universe/scanned/selected/codes/batchId |
| `GET /api/stock/trade-pool` | 只返回当前池列表（去掉 pending/final/history 分区或 history 恒空） |
| `POST .../confirm`、`.../dismiss` | 删除 |
| `POST .../final/{code}/remove` | 可选保留为「手动移出当前池」（运维用）；若保留，不写 history 或写简单审计日志。默认：**保留手动移出**，便于剔除明显异常标的 |
| 分析展开 | 读报告：优先 `report_id` / 按 symbol 最新报告；不再依赖 recommend 表 |

### 5.2 UI（`stock.html` / `stock-chart.js`）

- 交易目标池：取消二级菜单「推荐待确认 / 最终目标池 / 推荐历史」
- 改为**单列表** +「扫描更新」+ 刷新；可选「移出」
- 介绍页 `nav-tradepool.html`、首页文案、应用说明/规则中双池描述改为单池自动覆盖
- `ScheduleJobGuide` / 定时任务说明同步

---

## 6. 定时任务

| 现状 | 调整 |
|------|------|
| `pool-rebuild`、`after-market-batch-scan` 均调 `rebuildFromUniverse` | **合并语义**：保留一个主 job（建议 `pool-rebuild`），另一个改为调用同一 handler 或文档标明废弃并默认 `enabled=0` |
| `scan-and-trade` | 不变（读唯一池） |

种子数据：不强制改 cron；文档写清「只需启用其中一个盘后重建任务」。

---

## 7. 后端改造要点（类级）

| 类 | 变更 |
|----|------|
| `TradePoolService` | `rebuildFromUniverse` → 写 `trade_pool` 全量替换；删除/废弃 `confirm`/`dismiss`；`listActiveCodes` 不变；列表 API 单池 |
| `TradePoolController` | 去掉 confirm/dismiss；rebuild/list 契约更新 |
| `ScheduleJobHandlers` / `StrategyTask.afterMarketBatchScan` | 仍调 rebuild；文案改为「覆盖目标池」 |
| `ScheduleJobGuide` | 更新任务说明 |
| 前端与 docs | 见 §5.2 |
| Mapper | `trade_pool` 增加「禁用不在集合内的 active」或 delete-not-in 方法（若尚无） |

---

## 8. 迁移与兼容

1. 部署后首次 rebuild 即用扫描结果覆盖 `trade_pool`。
2. 旧 `trade_pool_recommend` PENDING 行不再展示；可不迁移。
3. 若库中已有最终池标的：首次扫描会按新规则替换，可能整批换血——符合「每日自动池」预期。
4. 进程内模拟持仓：不在池的持仓继续由策略平仓信号处理。

---

## 9. 验收标准

1. 调用 rebuild（或盘后 job）后，`trade_pool` 中 active 集合 = 本次 TopN，且旧标的不在结果中则消失。
2. UI 仅一个目标池列表，无确认/忽略/历史三栏。
3. `scan-and-trade` 只对池内标的开新仓；持仓股被移出池后**不会**仅因移出而被卖掉。
4. confirm/dismiss API 不可用。
5. 编译通过；关键路径手测：rebuild → 列表变化 → 账户/持仓不因 rebuild 被清空。

---

## 10. 后续迭代（明确排队）

1. 粗筛增强：市值、流动性、停牌天数  
2. 持仓三级降级（观察 / 减半 / 连日清仓）与强制清仓例外  
3. 综合打分（收益 − λ·回撤）  
4. 选股质量回溯（入选后 N 日涨幅）  
5. 通知推送  

---

## 11. 决策记录

| 决策 | 选择 |
|------|------|
| 池模型 | 方案 1：复用 `trade_pool` 唯一池 |
| 确认流 | 删除，自动覆盖 |
| 「在池」定义 | 唯一 `trade_pool` active |
| 持仓降级 | 本版不做（用户选 A） |
| 新表 `daily_stock_pool` | 不做 |
| 废弃表 | **DROP** `trade_pool_recommend` / `trade_pool_recommend_report` / `trade_pool_history`；新增 `trade_pool_report` |
