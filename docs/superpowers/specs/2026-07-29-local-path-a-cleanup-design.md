# 设计：A 线本地收尾（对账闸 / 种子债 / 运维策略）

**日期：** 2026-07-29  
**状态：** 已批准（对话确认范围 A）  
**范围：** A1 对账闸自洽 + A2 种子清理 + A3 策略列表与激活切换（不含参数白名单可写）

## 目标

在仅 `market_1min` 真相源下，消除假对账绿灯；缩小无用种子体积；运维可发现并安全切换纸面激活策略。不改金叉买卖公式，不接宽睿。

## A1 · 对账闸 → 1 分钟自洽

- 保留 API：`GET/POST /api/ops/data-reconcile*`、`blockNewOpen()`、配置键名尽量兼容。
- 检查对象：目标池/universe 各标的 `market_1min`（经 `MarketDataService` `MIN_1`）。
- 失败条件（任一条记 diverge）：
  - 无 1 分钟数据；
  - 最近一根相对「今天」滞后超过 `data-reconcile-sample-days` 个日历日（复用现有 sample-days，语义改为覆盖滞后阈值）；
  - 最近 `sampleDays` 个有数据交易日中，任一日 1 分钟根数 &lt; 120；
  - 抽样 bar OHLC 非法（价≤0 或 high&lt;low）。
- `max-close-diff-pct`：本轮保留配置但报告中标注「自洽模式下未用于价差」；外部源仍 `UNAVAILABLE`。
- `block-on-diverge` 默认仍 false。

## A2 · 种子与文档

- 删除未再灌库的周期文件：`DAY/WEEK/MONTH/MIN_15/MIN_30/MIN_60.json`。
- 保留：`MIN_1.json`；无 `MIN_1` 时保留 `MIN_5.json`；`meta.json` 更新描述。

## A3 · 运维策略发现与切换

- 运维「运行参数」展示已注册策略只读表（复用 `/api/config/strategies`）。
- `POST /api/ops/active-strategy`：body `{ "strategyId", "confirm": true }`；校验注册表；写入 `system_config(quant.active-strategy)`；热更 `QuantProperties` + `StrategyRegistry` 激活实例。
- 前端二次确认文案：影响纸面扫描/扫池，不影响回测下拉「仅本次」。
- **不做**：白名单参数可写、按策略参数包。

## 非目标

- MDS/OES、新策略实现、静默改 `MaCrossStrategy`。

## 文档同步

README / app.html / rules.html / memo.html（对账闸与多策略待办状态）。
