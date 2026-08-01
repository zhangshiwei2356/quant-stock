# Strategy Param Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每个注册策略可有稀疏参数包（表 `strategy_param`），三层叠层生效；运维可按策略编辑；纸面/回测按 strategyId 使用生效快照且不污染全局单例。

**Architecture:** `strategy_param.params_json` 存稀疏白名单键；`EffectiveParamsService.resolve` 深拷贝全局 `QuantProperties` 后叠包；`ParamsScope`（ThreadLocal）在纸面扫描/回测入口安装快照；策略与引擎通过 `ParamsScope.current()` 读参；运维 UI 分「全局保存」与「策略包保存」两条 API。

**Tech Stack:** Spring Boot、MyBatis、MySQL、JUnit 5、现有 jQuery 运维页

**Spec:** `docs/superpowers/specs/2026-08-01-strategy-param-pack-design.md`

## Global Constraints

- 叠层：`yml` → `quant.prop.*` → `strategy_param` 稀疏 JSON → 生效快照
- 包内键集 = `SystemParamsService` 现有白名单（过滤/仓位/止损/金字塔/费率滑点/ADV/涨跌停/次 bar/常用风控）
- 稀疏语义；`clearKeys` 删除键后继承全局
- 画像：无包仍 `MaCrossFilterProfile`；**有任意稀疏键**则过滤改读生效快照
- 不改 `MaCrossStrategy` 买卖骨架；不做回测「本次表单临时改参」；不双写 `system_config` 的 `strategy.*`
- 提交仅在用户明确要求时；中文 commit；同步 README / app / memo（及必要 rules/nav）

---

## File map

| Path | Responsibility |
|------|----------------|
| `mapper/schema.sql` | CREATE `strategy_param` |
| `.../admin/dto/StrategyParamDO.java` | 行实体 |
| `.../mapper/StrategyParamMapper.java` + `.xml` | select/upsert |
| `.../admin/DbTableCatalog.java` | 表白名单 |
| `.../admin/WritableParamKeys.java` | 白名单类型表（从 SystemParamsService 抽出） |
| `.../admin/QuantPropertiesCopy.java` | 深拷贝 QuantProperties（白名单+指纹相关字段） |
| `.../admin/WritableParamApplier.java` | 单键 apply 到 QuantProperties |
| `.../admin/ParamsScope.java` | ThreadLocal 生效快照 |
| `.../admin/EffectiveParamsService.java` | resolve / getSparse / saveSparse / hasSparse |
| `.../admin/SystemParamsService.java` | 改用 WritableParam*；view 支持 strategyId |
| `.../controller/OpsController.java` | GET params?strategyId；POST strategy-params |
| `.../strategy/MaCrossStrategy.java` | 读 `ParamsScope.current(global)` |
| `.../strategy/AbstractMaCrossProfileStrategy.java` | hasSparse → 快照过滤 |
| `.../task/StrategyTask.java` | 扫描入口 ParamsScope.install |
| `.../backtest/BackTestEngine.java` | run 入口 install |
| `.../backtest/PortfolioBackTestEngine.java` | 同上 |
| `.../risk/OpenFilterService.java` | 读 ParamsScope（或保持 props 若引擎已在 scope 内且改 accessor） |
| `static/stock.html` + `js/stock-chart.js` + `css/style.css` | 策略下拉、覆盖标记、双保存 |
| `README.md`, `docs/app.html`, `memo.html`, `nav-schedule.html` | 文档 |
| `src/test/.../EffectiveParamsServiceTest.java` | 叠层/保存/清除 |
| `src/test/.../ProfileSparseFilterTest.java` | 画像有包走快照 |

**Scoped 读参约定：** 新增

```java
public final class ParamsScope {
  public static QuantProperties current(QuantProperties global);
  public static void run(QuantProperties snapshot, Runnable action);
  public static <T> T call(QuantProperties snapshot, java.util.concurrent.Callable<T> action) throws Exception;
}
```

纸面/回测入口必须 `ParamsScope.run(snapshot, ...)`；`MaCrossStrategy` / 引擎 / `OpenFilterService` / `RiskControlService` 在交易路径上改为 `ParamsScope.current(injectedGlobal)`，避免并发串参。

---

### Task 1: schema + Mapper + Catalog

**Files:**
- Modify: `src/main/resources/mapper/schema.sql`
- Create: `src/main/java/com/quant/stock/admin/dto/StrategyParamDO.java`
- Create: `src/main/java/com/quant/stock/mapper/StrategyParamMapper.java`
- Create: `src/main/resources/mapper/StrategyParamMapper.xml`
- Modify: `src/main/java/com/quant/stock/admin/DbTableCatalog.java`

**Interfaces:**
- Produces: `StrategyParamMapper.selectByStrategyId(String)`, `upsert(StrategyParamDO)`（INSERT … ON DUPLICATE KEY UPDATE，version+1）

- [ ] **Step 1: Append table**（放在 `system_config` 附近）

```sql
CREATE TABLE IF NOT EXISTS `strategy_param` (
  `strategy_id` VARCHAR(64) NOT NULL COMMENT '注册策略 id',
  `params_json` TEXT COMMENT '稀疏白名单 JSON',
  `version` INT NOT NULL DEFAULT 0,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `updated_by` VARCHAR(64) DEFAULT 'ops',
  PRIMARY KEY (`strategy_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='策略稀疏参数包';
```

- [ ] **Step 2: DO + Mapper + XML**（字段一一对应；upsert 时 `version = version + 1`）
- [ ] **Step 3: DbTableCatalog** 增加 `strategy_param`（模块「配置与风控」）
- [ ] **Step 4: Commit**（仅当用户要求）`feat: 新增 strategy_param 表与 Mapper`

---

### Task 2: 白名单抽出 + 拷贝/Apply + ParamsScope

**Files:**
- Create: `WritableParamKeys.java`, `WritableParamApplier.java`, `QuantPropertiesCopy.java`, `ParamsScope.java`
- Modify: `SystemParamsService.java`（改用抽出的白名单/apply，行为不变）

**Interfaces:**
- `WritableParamKeys.types(): Map<String,String>`
- `WritableParamApplier.apply(QuantProperties target, String key, String raw)`
- `QuantPropertiesCopy.copy(QuantProperties src): QuantProperties`（至少拷贝白名单键 + ConfigFingerprint 用到的字段 + activeStrategy）
- `ParamsScope.current / run / call`

- [ ] **Step 1: 写失败单测** `QuantPropertiesCopyTest`：改拷贝不影响源
- [ ] **Step 2: 实现 Copy / Applier / Keys / ParamsScope**
- [ ] **Step 3: SystemParamsService 委托 Applier**，跑 `SystemParamsServiceTest`
- [ ] **Step 4: Commit**（用户要求时）`refactor: 抽出可写参数白名单与 ParamsScope`

---

### Task 3: EffectiveParamsService + 单测

**Files:**
- Create: `src/main/java/com/quant/stock/admin/EffectiveParamsService.java`
- Create: `src/test/java/com/quant/stock/admin/EffectiveParamsServiceTest.java`

**Interfaces:**
- `boolean hasSparse(String strategyId)`
- `Map<String,String> getSparse(String strategyId)`
- `QuantProperties resolve(String strategyId)`
- `Map<String,Object> saveSparse(String strategyId, Map<String,Object> updates, List<String> clearKeys, boolean confirm, Integer expectedVersion)`

规则：
- db 关闭或无 Mapper：resolve=copy(global)，save 返回 ok=false 提示需 db
- strategyId 必须 `StrategyRegistry.contains`
- save：合并 updates、移除 clearKeys、校验白名单、写 `updated_by=ops`
- version 冲突：expectedVersion 非 null 且不等于行 version → ok=false

- [ ] **Step 1: 单测**（Mockito Mapper + 真 QuantProperties）
  - 无行 resolve 等于全局拷贝
  - 稀疏 rsiBuyMax 覆盖
  - 全局再改 rsi 不影响已覆盖生效值
  - clearKeys 后继承
  - confirm=false / 未知键 / 未知策略 拒绝
- [ ] **Step 2: 实现服务**
- [ ] **Step 3: 跑测通过**
- [ ] **Step 4: Commit**（用户要求时）`feat: EffectiveParamsService 三层叠层与稀疏保存`

---

### Task 4: 纸面 / 回测 / 策略读 ParamsScope

**Files:**
- Modify: `MaCrossStrategy.java` — `ParamsScope.current(quantProperties)`
- Modify: `AbstractMaCrossProfileStrategy.java` — 注入 `EffectiveParamsService`；`hasSparse(name())` 时用快照过滤（逻辑对齐 MaCrossStrategy.reject）
- Modify: `StrategyTask.java` — 扫描主入口 resolve(active)+`ParamsScope.run`
- Modify: `BackTestEngine.java` / `PortfolioBackTestEngine.java` — run 开头 resolve(strategy.fingerprint 对应的 **name()** / 传入的 strategy)+install；指纹用生效快照 `ConfigFingerprint.of(effective, strategy.fingerprintId(), fee)`
- Modify: `OpenFilterService.java`、`RiskControlService.java` — 交易相关读 `ParamsScope.current(props)`

**注意：** `BaseStrategy#name()` 是注册 id；指纹仍用 `fingerprintId()`。resolve 必须用 **name()**（如 `maCrossTrend`），不是类名。

定位纸面入口：`StrategyTask` 中对外 `scanAndTrade` / 池扫描调用链最外层包一层 scope（finally 清理）。

- [ ] **Step 1: 画像单测** 无包仍 Profile；有包 trendFilter 随快照
- [ ] **Step 2: 改策略与引擎入口**
- [ ] **Step 3: 编译 + 相关单测**
- [ ] **Step 4: Commit**（用户要求时）`feat: 纸面与回测按策略参数包生效`

---

### Task 5: Ops API

**Files:**
- Modify: `SystemParamsService.view` → `view(String strategyId)`  
  每项增加 `globalValue` / `overrideValue` / `effectiveValue` / `overridden`；顶层 `strategyId`、`sparseVersion`、`configFingerprint`（生效）、`strategies` 列表
- Modify: `OpsController`：`GET /params` 增加可选 `strategyId`；新增 `POST /strategy-params`

```json
POST /api/ops/strategy-params
{
  "strategyId": "maCross",
  "updates": { "rsiBuyMax": "55" },
  "clearKeys": ["trendFilterEnabled"],
  "confirm": true,
  "version": 0
}
```

- [ ] **Step 1: 实现 API**
- [ ] **Step 2: 手工或 MockMvc 冒烟（若项目已有 Web 测试则补一条）**
- [ ] **Step 3: Commit**（用户要求时）`feat: 运维策略参数包 API`

---

### Task 6: 运维 UI

**Files:**
- Modify: `static/stock.html` — `#paramsStrategySelect`、保存策略包按钮、清除覆盖控件
- Modify: `static/js/stock-chart.js` — 加载带 strategyId；区分全局保存 / 策略包保存；覆盖标记
- Modify: `static/css/style.css` — 覆盖标签样式

规则：
- 保留现有 id：`btnParamsRefresh`、`btnParamsSave`（全局）、新增 `btnStrategyParamsSave`
- 下拉切换重新 `GET /api/ops/params?strategyId=`
- confirm + loading + toast

- [ ] **Step 1: HTML 控件**
- [ ] **Step 2: JS 双保存路径**
- [ ] **Step 3: 本地打开运维页点选验证（或静态检查绑定）**
- [ ] **Step 4: Commit**（用户要求时）`feat: 运维页按策略编辑参数包`

---

### Task 7: 文档同步

**Files:**
- `README.md` — 运行参数 / API 表
- `static/docs/app.html`、`memo.html`（「按策略隔离的参数包」→ 已落地）、`nav-schedule.html`
- 若影响交易读参说明：`rules.html` 一句

- [ ] **Step 1: 按 sync-readme / sync-memo 更新**
- [ ] **Step 2: Commit**（用户要求时）`docs: 同步策略参数包能力说明`

---

## Spec coverage checklist

| Spec 项 | Task |
|---------|------|
| strategy_param 表 | 1 |
| 稀疏 JSON / version / updated_by | 1+3 |
| resolve 三层叠层 | 3 |
| 纸面 / 回测 scoped | 4 |
| 画像有包走快照 | 4 |
| GET params 扩展 + POST strategy-params | 5 |
| 全局 POST /params 不变 | 5 |
| 运维选择器 UI | 6 |
| 指纹对生效快照 | 4 |
| 文档 | 7 |
| 非目标（临时改参/双写/改金叉） | 不实现 |

## Execution

Plan complete. 两选一：

1. **Subagent-Driven（推荐）** — 每任务新开子代理，任务间复查  
2. **Inline Execution** — 本会话按任务连续实现并设检查点  

选哪个？若直接说「按方案 2 做」或「开始」，即按 Inline 开工。
