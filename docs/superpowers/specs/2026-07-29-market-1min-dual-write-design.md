# 1 分钟 K 线底层 + 双写过渡设计

**日期：** 2026-07-29  
**状态：** 已批准（2026-07-29）  
**范围：** 新增 `market_1min` 作为原始层；过渡期双写并保留 `market_daily` / `market_minute` 回退；查询 `auto` 优先从 1min 聚合  
**选定方案：** 双写过渡变体 **A**（有 1min 走聚合，否则回退旧表）

---

## 1. 背景与目标

### 1.1 现状问题

当前物理真相源为：

- `market_daily`：日线
- `market_minute`：约定存 **5 分钟** K

更大周期由内存聚合。枚举与旧代码仍保留 `MIN_1` / `aggregateFrom1Min`，但生产路径并未持久化 1 分钟。

缺陷：

- 无法精细模拟盘中止损、盘中金叉、短线策略
- 多周期若分表存储，指标易割裂
- 后续任意新周期都需重新采数或加表

### 1.2 目标（本版）

1. **底层持久化 1 分钟 K**：表 `market_1min`，唯一原始明细层  
2. **过渡期双写**：继续可写可读 `market_daily` / `market_minute`，避免打断仅有日线的批量样本回测  
3. **读路径 `auto`**：标的 1min 足够 → 按需聚合；否则回退旧表  
4. **目标池优先灌数**：通达信 pytdx 约 90 交易日完整 OHLC → `market_1min`（可选刷 5min/日线缓存）  
5. **修正 `BarDTO` 周期时长**：结束时刻 / TA4J duration 按 `BarPeriod`，禁止写死 +5 分钟

### 1.3 非目标（本版不做）

- 强制全库 `prefer_1min`、停写 5 分钟表
- MySQL 按月分表 / 分区、冷热分离、ClickHouse
- Tushare 多年分钟权限灌数
- 修改 `maCross` 买卖公式或撮合规则
- 新增 `bankTrend` 策略
- 删除 `market_daily` / `market_minute`

---

## 2. 核心原则

| 原则 | 说明 |
|------|------|
| 1min = 唯一计算基准（终态） | 策略/回测在有 1min 时强制从 1min 聚合，不信任缓存脏数据作为基准 |
| 双写过渡 | 旧表仍服务无 1min 的标的与页面兼容 |
| 缓存可选 | 5min/日线可继续 upsert，仅加速与回退，非终态真相 |
| 渐进灌数 | 先目标池短窗，再考虑全市场/更久历史 |
| 可回滚 | `quant.kline.source=legacy` 恢复旧读路径 |

---

## 3. 数据模型

### 3.1 新建：`market_1min`（层1 · 原始）

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | BIGINT PK AI | |
| `symbol` | VARCHAR(10) NOT NULL | 6 位代码 |
| `trade_time` | DATETIME NOT NULL | **1 分钟 K 起始时刻**（与 `BarDTO.barBegin` 一致） |
| `open/high/low/close` | DECIMAL(10,4) NOT NULL | |
| `volume` | BIGINT NOT NULL | 成交量（股；导入时统一换算） |
| `amount` | DECIMAL(16,4) NULL | 成交额（元） |

- 唯一键：`(symbol, trade_time)`  
- 写入：`INSERT ... ON DUPLICATE KEY UPDATE`（upsert）  
- 语义：物理粒度固定为 1 分钟；本版不做删除/归档

### 3.2 过渡保留

| 表 | 过渡角色 |
|----|----------|
| `market_daily` | 兼容回退 + 可选缓存；有 1min 时日线信号优先由 1min 聚合 |
| `market_minute` | 仍为 **5 分钟** 物理表；兼容回退 + 可选缓存 |

### 3.3 时间语义

- `trade_time` / `barBegin`：**K 线左端（起始）**  
- `barEnd` = `barBegin + periodMinutes`（MIN_1→+1，MIN_5→+5，…）  
- `isClosedBar` / `toTa4jBar` 必须使用对应 period 的 duration，不得写死 5 分钟

---

## 4. 配置

建议加入 `QuantProperties` / `application.yml`：

| 键 | 默认 | 含义 |
|----|------|------|
| `quant.kline.source` | `auto` | `auto` \| `prefer_1min` \| `legacy` |
| `quant.kline.min-1min-bars` | `240` | `auto` 下认定「有足够 1min」的最少根数（约 1 交易日） |

行为：

- **`auto`**：`count(1min) >= min-1min-bars` → 从 1min 读/聚合；否则旧表  
- **`prefer_1min`**：无足够 1min 则返回空（严格，本版可实现开关，默认不启用）  
- **`legacy`**：永不读 `market_1min`（回滚）

---

## 5. 读写与代码落点

### 5.1 写入

- 新：`CoreMarketBarService.saveMinutes1` → `market_1min`  
- 保留：`saveMinutes`（5min）、`saveDailies`  
- 灌数脚本主写 1min；默认可选再聚合 upsert 5min/日线缓存  
- `MockDataImporter`：仅当存在 `MIN_1.json` 时导入 1min；无则行为与现网一致

### 5.2 读取

改造 `CoreMarketBarService.load` / `MarketDataService` 优先级（db 启用时）：

1. 若 `source!=legacy` 且 1min 足够：  
   - `MIN_1` 直读  
   - 其它周期 `BarAggregateUtil.aggregate`  
2. 否则走现有 `market_daily` / `market_minute`  
3. 其后仍可 Redis → 旧分表 → JSON → mock（保持现有兜底链）

### 5.3 定时任务（轻改）

- `settle-after-close`：若当日存在 1min，优先 1min→日线写 `market_daily`；否则保持 5min→日线  
- `market-collect`：本版可不实现；约定未来「先落 1min，再可选刷缓存」

### 5.4 明确不改

- `MaCrossStrategy` 及画像策略买卖条件  
- 撮合 / T+1 / 账户风控规则本身（仅数据分辨率变细时回测更真实）

---

## 6. 灌数

### 6.1 脚本

- 新增：`scripts/fetch_min1_tdx.py`  
- 源：通达信协议（pytdx），`get_security_bars` category 1min，分页约 **90 交易日** OHLC  
- 目标：MySQL `market_1min`；默认顺带刷新 `market_minute` / `market_daily` 缓存  
- 标的：默认读目标池 API/表或 `--codes`；失败多 host 重试

### 6.2 深度预期

| 源 | 约深度 | 备注 |
|----|--------|------|
| pytdx 1min OHLC | ~90 交易日 | 本版主路径 |
| 公网新浪/腾讯 | 数日 | 仅备援，非主灌 |
| Tushare stk_mins | 多年 | 需权限，本版不做 |

---

## 7. 分阶段交付

| 阶段 | 内容 | 验收 |
|------|------|------|
| **0** | 建表 + Mapper + `source=auto` + BarDTO 按周期 + 单测聚合 | 无 1min 时行为与改前一致；合成 1min 可聚出 5min/DAY |
| **1** | pytdx 灌目标池 → `market_1min`（可选刷缓存） | 池内股 `GET /api/kline?period=MIN_1` 有数；DAY 可聚 |
| **2** | settle 优先 1min 聚日线；同步 README / app.html / memo.html | 文档与开关说明一致 |
| **3+** | （非本版）prefer_1min、停写 5min、分区/冷热、长历史 | — |

---

## 8. 风险与回滚

| 风险 | 对策 |
|------|------|
| 1min 体积 | 先目标池 ~90 日 |
| pytdx 不稳 | 多 host；失败保留旧表回退 |
| 聚合与旧 5min 缓存微差 | 有 1min 时以聚合为准 |
| BarDTO +5 写死 | 阶段 0 必改 |
| 双写失败 | 1min 成功即可；缓存失败只打日志 |

**回滚：** `quant.kline.source=legacy`；表可保留。

---

## 9. 文档同步（实现时同一轮）

- `README.md`：物理表增加 `market_1min`；说明双写与 `kline.source`  
- `static/docs/app.html`：系统概述一句  
- `static/docs/memo.html`：能力「1min 原始层双写过渡」  
- 交易规则：仅当回测默认周期/撮合分辨率对外行为变化时改 `rules.html`（本版若默认仍 DAY 回测，可只注明「有 1min 时可切 MIN_*」）

---

## 10. 对话确认记录

- 表策略：用户选 **3（双写过渡）**，落地变体 **A**  
- §1 表与语义、§2 读写任务、§3 迁移风险：均已确认
