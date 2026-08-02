# 设计：策略管理（评估与回测历史）

**日期：** 2026-08-02  
**状态：** 已批准（对话确认）  
**范围：** 独立一级菜单「策略管理」；按策略聚合回测历史与整体评价；点行看详情。不含运维改参/激活切换。

## 已确认决策

| 项 | 选择 |
|---|---|
| 导航位置 | **独立一级菜单**；顺序：运维中心 → **策略管理** → 数据表 |
| 实现路径 | 落库 `strategy_id` + 新 API + 新工作台页（不用指纹反推） |
| 策略 id 语义 | 注册表稳定 id（`BaseStrategy.name()`，如 `maCross` / `branchScaffold`） |
| 旧历史 | `strategy_id` 为空 → UI 显示「未知」；**不**猜测默认策略、不强制回填 |
| 改参 / 纸面激活 | **不**搬入本页；仍归运维中心 |
| 并排对比图 / 参数 diff | 首版不做 |
| 金叉主路径 | 不改交易逻辑；仅多写一列与只读展示 |

## 1. 存储

### `bt_backtest_record` 增列

| 列 | 类型 | 说明 |
|---|---|---|
| `strategy_id` | `VARCHAR(64) NULL` | 回测时解析到的策略注册 id；旧行可为 NULL |

- 启动时 `BackTestHistoryStore.ensureSchema` 补齐列（与 `config_fingerprint` 同套路）
- `schema.sql`（若有建表脚本）同步加列，便于新库
- `DbTableCatalog` 字段中文兜底可加 `strategy_id` →「策略ID」（可选）
- **不**新增 `engine` 列（首版）；引擎信息仍在结果 JSON / 指纹侧，评估页不依赖

### 写入

- `appendSingle` / `appendPortfolio` 增加 `strategyId` 参数（或从结果/query 读取）
- 控制器在跑完回测后传入：`strategyRegistry.resolve(strategyId).name()`（空入参则用当前激活/默认解析结果的 `name()`）
- 历史 DTO（`SingleBacktestHistoryRecord` / `PortfolioBacktestHistoryRecord`）增加 `strategyId` 字段，列表映射带出
- `config_fingerprint` 继续写入，语义不变

### 查询

- Mapper 增加按 `strategy_id` 列表查询（可选 `kind`：`SINGLE` / `PORTFOLIO` / 全部）
- 列表接口**默认不返回**完整 `trades_json` / `stock_results_json`（摘要行即可）；详情接口再取全量，避免策略页一次拉爆

## 2. API

新建 `StrategyController`（或等价命名），前缀 `/api/strategy`：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/strategy/overview` | 注册表策略列表 + 是否纸面激活 + 聚合指标 |
| GET | `/api/strategy/{id}/history` | 该策略回测摘要；`kind=ALL\|SINGLE\|PORTFOLIO`（默认 ALL） |
| GET | `/api/strategy/history/{recordId}` | 单条详情（含成交统计/流水摘要；分析事件可同响应或复用现有 analysis） |

### overview 每项字段（最低集）

- `strategyId`、`displayName`（可用 `name()` / 已有标题）、`active`（是否当前纸面激活）
- `runCount`、`avgTotalRate`、`medianTotalRate`、`avgMaxDrawdown`
- `lastSavedAt`、`lastTotalRate`（最近一次；无历史则 null）
- 聚合**仅统计** `strategy_id = 该 id` 的行；空 id 旧行不计入任一策略（可另给 overview 顶层 `unknownCount` 可选）

### history 摘要行

- `id`（recordId）、`kind`、`savedAt`、`strategyId`
- 单股：`stockCode`；组合：`stockCodes`（数组或短字符串）
- `period`、`backStart`、`backEnd`
- `initCapital`、`finalAsset`、`totalRate`、`maxDrawdown`、`totalTradeNum`、`winRate`
- `configFingerprint`（可前端截短展示）

### 详情

- 在摘要基础上附带 `tradeStats`、`trades`（及组合的 `stockResults`）
- 分析：详情响应内嵌 `analysis`（由 `BackTestAnalysisStore` 按 `recordId` 读取）；前端不再二次请求 analysis

### 错误

- 未知 `strategyId`（不在注册表）：HTTP **404** + 中文 message
- 未知 `recordId`：HTTP **404**
- `quant.db-enabled=false`：overview 仍可返回注册表元数据，历史为空、`enabled=false` hint

## 3. 前端

### 导航

- `stock.html` 新增一级：`data-nav="strategy"`，label「策略管理」
- 介绍页：`docs/nav-strategy.html`（短文：评估 vs 运维职责）
- 二级固定一项「策略评估」；页内左侧再列策略（侧栏不枚举全部策略 id）

### 工作台 `viewStrategy`

1. **左栏（页内）**：策略列表——id、名称、激活角标、回测次数  
2. **上部**：整体评价——次数 · 均收益 · 中位收益 · 均回撤 · 最近一次  
3. **下部**：历史表——时间、kind、标的/成分、区间、收益、回撤、胜率、指纹短码  
4. **点行**：展开详情（成交统计 + 分析摘要；样式对齐现有回测历史展开行）

### 交互约定

- 保留现有 id / 事件绑定风格；Toast + loading；空状态占位
- 不引入 React/Vue
- 现有个股/组合「回测历史」面板保留；本页为跨 kind 的策略视角

## 4. 文档同步（实现时同一轮）

- `README.md`：功能清单 + API 表
- `docs/app.html`：系统概述加「策略管理」
- `docs/memo.html`：能力台账加一项
- `rules.html`：无交易规则变化则不改

## 5. 测试

- 单测：写入带 `strategyId`；按策略查询；overview 聚合
- 中位数：收益列表排序后，奇数取正中；偶数取中间两数算术平均（`BigDecimal` 四舍五入到与库内收益率同精度即可）
- 未知/空 `strategy_id` 不计入策略聚合
- 前端无强制 E2E；手工：跑两次不同 strategyId 回测 → 策略页分别可见

## 6. 非目标（明确不做）

- 运维改参、稀疏包编辑、纸面激活切换 UI
- 多策略并排权益曲线 / 参数 diff
- 用指纹反推历史策略
- 改 `MaCrossStrategy` 买卖逻辑

## 7. 风险与兼容

- InnoDB 旧行 NULL：展示「未知」即可
- 大表 `trades_json`：列表必须摘要化，详情按需加载
- 与 `strategy_param` / 运维激活数据只读关联，无写冲突
