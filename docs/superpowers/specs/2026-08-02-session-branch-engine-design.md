# 设计：分钟级多分支会话引擎（旁路）

**日期：** 2026-08-02  
**状态：** 已批准（对话确认）  
**范围：** 按「隔日高开三分支」形状裁剪的会话回测引擎骨架 + 空策略脚手架；不改金叉；不实现隔日高开公式；纸面二期

## 已确认决策

| 项 | 选择 |
|---|---|
| 目标 | 引擎接口按隔日高开三分支形状裁剪，**不**写该策略买卖公式 |
| 缺数据 | 默认可配置：分支级 `UNAVAILABLE`；可选整策略拒跑 |
| 交付面 | 回测骨架 + `branchScaffold` 验收；纸面二期 |
| 时间轴 | 统一 **MIN_1**（`market_1min`） |
| 实现路径 | **旁路会话引擎**与经典 `BackTestEngine`/金叉并存 |

## 1. 模块边界与策略接口

### 并存

- **保留**：`BackTestEngine` + `MaCrossStrategy`（日线五步时序，行为不变）
- **新增**：`com.quant.stock.session.*`

### 核心类型

| 类型 | 职责 |
|---|---|
| `SessionStrategy` | 会话策略契约：`sessionId()` / `dataDeps()` / 分支钩子 / 持仓日态机步进；可与 `BaseStrategy` 并列或适配注册 |
| `SessionBranch` | `OPEN` / `MID` / `CLOSE` |
| `HoldDayState` | 持仓交易日态（如 `FLAT`、`HOLD_D0`、`HOLD_D1`…） |
| `DataDeps` | 依赖枚举：`MIN1`、`INDEX`、`AUCTION`、`ORDER_BOOK` 等 |
| `SessionContext` | 当前 bar、会话日、分支、持仓日态、权益快照、降级标记 |
| `SessionBackTestEngine` | MIN_1 推进 + 分支调度 +（可选）撮合子集 + 结果汇总 |
| `BranchScaffoldStrategy` | 注册 id=`branchScaffold`：不产生真实买卖，只记分支/态机事件 |

### 硬约束

- 不改 `MaCrossStrategy` 买卖骨架
- 不实现隔日高开公式
- 缺依赖不静默假撮合
- 纸面 `StrategyTask` 本期不接会话引擎

## 2. 时间轴 · 分支 · 态机 · UNAVAILABLE

### 时间轴

- 输入：`MarketDataService.getKline(code, MIN_1, start, end)`（真相源 `market_1min`）
- 不足最小长度（如 &lt; 1 个完整交易日或可配置下限）→ 回测失败并说明
- 按 A 股交易日切会话；日内按分钟推进；过滤未闭合 bar；跳过非交易分钟
- 撮合子集：复用 `TradeCostModel`、T+1 可卖、涨跌停限价保护、ADV/POV（与现回测同开关）；脚手架默认**不发单**

### 分支窗口（默认可配）

| 分支 | 默认墙钟窗口 |
|---|---|
| `OPEN` | 09:30–10:00 |
| `MID` | 10:00–14:30 |
| `CLOSE` | 14:30–15:00 |

- 每根分钟 bar 落入且仅落入一个分支
- 钩子：`onBranchBar(ctx)`；另可选 `onSessionOpen` / `onSessionClose`

### 持仓日态机（脚手架最小集）

- 演示转移：`FLAT` → `HOLD_D0` → … → 达 `maxHoldTradingDays`（脚手架自声明，隔日高开形状示意默认 **2**）后标记应平事件
- 脚手架只写**事件日志**（进入分支、态转移），不下真实单
- 后续真实策略再对接引擎挂单/撮合

### DataDeps / UNAVAILABLE

- 策略声明 `dataDeps()`；引擎探测本地能力：
  - `MIN1`：有分钟数据则满足
  - `INDEX` / `AUCTION` / `ORDER_BOOK`：本地默认**不满足**
- 配置项（建议挂 `quant.session.*` 或回测请求字段）：
  - `failOnMissingDep` 默认 **false**
- `false`：依赖缺失影响的**分支**标 `UNAVAILABLE`，跳过该分支钩子；结果含 `degradedBranches`
- `true`：整次回测失败，列出缺失依赖
- 脚手架默认 deps=`{MIN1}`，三分支均可跑通

## 3. API · UI · 结果字段

### 回测 API

- 请求增加可选：`engine=classic|session`（默认 `classic`）
- `strategyId=branchScaffold` 时走 `session`（若未显式指定 engine）
- 组合回测本期可不接 session（非必须）

### UI

- 个股回测策略下拉增加「分支脚手架（session）」
- 结果区展示：`engine`、`degradedBranches`、态机/分支事件摘要、`configFingerprint`

### 指纹

- `ConfigFingerprint` 或会话结果附加：`engine=session`、分支窗口、`failOnMissingDep`、策略 id，避免与经典金叉指纹混淆

## 4. 验收

1. 本地有 `market_1min` 时，`branchScaffold` + session 跑通，三分支事件可计数  
2. 脚手架临时声明缺 `INDEX` 且默认模式 → 相关分支 `UNAVAILABLE`，其余仍跑  
3. `failOnMissingDep=true` → 整单失败并说明  
4. 默认金叉 `engine=classic`（或未传）DAY 回测与改前一致  

## 5. 文档同步

实现同一轮更新：`README.md`、`app.html`、`memo.html`（分钟多分支引擎：骨架已落地；隔日高开仍不实现；纸面未接）

## 6. 非目标

- 隔日高开买卖公式实现
- 修改 `MaCrossStrategy`
- 纸面接会话引擎
- 用假指数/竞价/封单静默撮合
- 多策略并行分账本 / 信号冲突裁决
- 强制改造组合回测为 session（可后续）

## 7. 实现顺序（供计划拆解）

1. `session` 包：枚举/上下文/deps 探测  
2. `SessionBackTestEngine` MIN_1 推进 + 分支调度（无撮合也可先出事件）  
3. `BranchScaffoldStrategy` 注册 + 回测 API/UI 开关  
4. UNAVAILABLE / failOnMissingDep 单测  
5. 文档同步  
