# Strategy Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 独立一级菜单「策略管理」：按注册策略聚合回测历史与整体评价，点行查看详情；回测落库带 `strategy_id`。

**Architecture:** `bt_backtest_record.strategy_id` 持久化注册表 id；`StrategyEvalService` 聚合 overview / 摘要列表 / 详情（详情内嵌 analysis）；`StrategyController` 暴露 `/api/strategy/*`；前端新工作台复用现有历史展开交互，不搬运维改参。

**Tech Stack:** Spring Boot 2.7、Java 8、MyBatis、MySQL、JUnit 5、jQuery 静态页

**Spec:** `docs/superpowers/specs/2026-08-02-strategy-management-design.md`

## Global Constraints

- 导航：独立一级；顺序 **运维中心 → 策略管理 → 数据表**；二级仅「策略评估」
- 策略 id = `BaseStrategy.name()`；404 用 `StrategyRegistry.contains`，**禁止**用 `resolve` 做存在性（resolve 会回退 maCross）
- 旧行 `strategy_id` NULL → 不计入任一策略聚合；overview 可带 `unknownCount`
- 列表 API **不返回** `trades_json` / `stock_results_json`；详情按 `recordId` 再取
- 详情内嵌 `analysis`（`BackTestAnalysisStore.getSingleById` / `getPortfolioById`）
- 中位数：排序后奇数取正中；偶数取中间两数算术平均
- 不改金叉买卖逻辑；不搬运维改参/激活 UI；不做并排对比
- git commit 仅在用户明确要求时；中文 message；实现时同步 README / `app.html` / `memo.html` / nav 介绍

---

## File map

| Path | Responsibility |
|------|----------------|
| `src/main/resources/mapper/schema.sql` | `bt_backtest_record` 加 `strategy_id` + 索引 |
| `.../backtest/dto/BtBacktestRecordDO.java` | 字段 `strategyId` |
| `.../mapper/BacktestRecordMapper.java` + `.xml` | insert 带列；`selectSummaryByStrategyId`；`selectByRecordId` |
| `.../backtest/BackTestHistoryStore.java` | ensureColumn；append 写 strategyId；DTO 映射；按策略查询 |
| `.../backtest/dto/SingleBacktestHistoryRecord.java` | `strategyId` |
| `.../backtest/dto/PortfolioBacktestHistoryRecord.java` | `strategyId` |
| `.../controller/StockController.java` | appendSingle 传入 resolved `name()` |
| `.../controller/PortfolioController.java` | appendPortfolio 传入 query 解析后的 `name()` |
| `.../strategy/StrategyEvalService.java` | overview / history / detail + 中位数 |
| `.../controller/StrategyController.java` | `/api/strategy/**` |
| `static/docs/nav-strategy.html` | 一级介绍 |
| `static/stock.html` | 侧栏 + `viewStrategy` 骨架 |
| `static/js/stock-chart.js` | 加载 overview/history/详情展开 |
| `static/css/style.css` | 策略评估布局（轻量） |
| `README.md`, `docs/app.html`, `memo.html` | 文档 |
| `src/test/.../StrategyEvalServiceTest.java` | 聚合/中位/空 id |
| `src/test/.../BackTestHistoryStoreStrategyIdTest.java`（或扩现有） | 写入与按策略查询 |

---

### Task 1: schema + DO + Mapper 读写 `strategy_id`

**Files:**
- Modify: `src/main/resources/mapper/schema.sql`（`bt_backtest_record` 定义）
- Modify: `src/main/java/com/quant/stock/backtest/dto/BtBacktestRecordDO.java`
- Modify: `src/main/java/com/quant/stock/mapper/BacktestRecordMapper.java`
- Modify: `src/main/resources/mapper/BacktestRecordMapper.xml`
- Modify: `src/main/java/com/quant/stock/backtest/BackTestHistoryStore.java`（仅 `ensureSchema`）

**Interfaces:**
- Produces:
  - `BacktestRecordMapper.selectSummaryByStrategyId(String strategyId, String kind)` → `List<BtBacktestRecordDO>`（无 trades/stock_results JSON）
  - `BacktestRecordMapper.selectByRecordId(String recordId)` → 全列 DO 或 null
  - `BacktestRecordMapper.countUnknownStrategy()` → `long`（`strategy_id IS NULL OR strategy_id = ''`）
- Consumes: 现有 `insert` / `selectByKind`

- [x] **Step 1: schema.sql** 在 `config_fingerprint` 后增加：

```sql
  `strategy_id` VARCHAR(64) DEFAULT NULL COMMENT '注册策略 id',
```

并加索引：

```sql
  KEY `idx_strategy_saved` (`strategy_id`, `saved_at`)
```

- [x] **Step 2: DO** 增加 `private String strategyId;`

- [x] **Step 3: Mapper 接口**

```java
List<BtBacktestRecordDO> selectSummaryByStrategyId(@Param("strategyId") String strategyId,
                                                   @Param("kind") String kind);

BtBacktestRecordDO selectByRecordId(@Param("recordId") String recordId);

long countUnknownStrategy();
```

- [x] **Step 4: XML**
  - `RecMap` 增加 `strategy_id` → `strategyId`
  - `insert` 列与值增加 `strategy_id` / `#{strategyId}`
  - `selectSummaryByStrategyId`：SELECT 摘要列（含 `trade_stats_json`、`config_fingerprint`、`strategy_id`，**不含** `trades_json`、`stock_results_json`）；`WHERE strategy_id = #{strategyId}`；若 `kind` 非空且非 `ALL` 则 `AND kind = #{kind}`；`ORDER BY saved_at DESC, id DESC`
  - `selectByRecordId`：`SELECT * FROM bt_backtest_record WHERE record_id = #{recordId}`
  - `countUnknownStrategy`：`SELECT COUNT(1) FROM bt_backtest_record WHERE strategy_id IS NULL OR strategy_id = ''`

- [x] **Step 5: ensureSchema** 在 `BackTestHistoryStore.ensureSchema` 补齐列：

```java
ensureColumn(jdbc, "bt_backtest_record", "strategy_id",
    "ALTER TABLE `bt_backtest_record` ADD COLUMN `strategy_id` VARCHAR(64) DEFAULT NULL "
        + "COMMENT '注册策略 id' AFTER `config_fingerprint`");
```

可选：若无索引则 `CREATE INDEX idx_strategy_saved ON bt_backtest_record (strategy_id, saved_at)`（失败忽略已存在）。

- [x] **Step 6: 编译** `mvn -q -DskipTests compile` Expected: SUCCESS

- [x] **Step 7: Commit**（仅用户要求时）`feat: bt_backtest_record 增加 strategy_id`

---

### Task 2: HistoryStore 写入/映射 strategyId + 控制器传参

**Files:**
- Modify: `SingleBacktestHistoryRecord.java`, `PortfolioBacktestHistoryRecord.java`
- Modify: `BackTestHistoryStore.java`（`appendSingle` / `appendPortfolio` / `toSingle` / `toPortfolio` + 新查询方法）
- Modify: `StockController.java`, `PortfolioController.java`
- Test: `src/test/java/com/quant/stock/backtest/BackTestHistoryStoreStrategyIdTest.java`（可用 `@SpringBootTest` + 条件库，或对 Mapper mock；优先仿仓库现有 History 测试风格）

**Interfaces:**
- Produces:
  - `appendSingle(..., BackTestResult result, String strategyId)`
  - `appendPortfolio(BackTestQueryDTO query, PortfolioResultDTO result, String strategyId)`
  - `listSummaryByStrategy(String strategyId, String kind)` → 摘要 DTO 列表（见下）
  - `getByRecordId(String recordId)` → 全量单股或组合视图（可用统一包装，见 Task 3）
- 历史 DTO 增加 `String strategyId`
- `fromResult` / builder 带上 strategyId

- [x] **Step 1: 写失败单测**（无 DB 时测纯映射亦可）

```java
@Test
void toSingle_mapsStrategyId() {
    BtBacktestRecordDO row = BtBacktestRecordDO.builder()
            .recordId("abc").kind("SINGLE").strategyId("maCross")
            .totalRate(new BigDecimal("0.1")).build();
    // 通过 package 可见性或 public list 映射断言 strategyId
}
```

若 Store 方法为 private，改为测 `listSummaryByStrategy` 在 mapper mock 下的行为，或抽 `package` 级 mapper→DTO 转换。

- [x] **Step 2: 改 append 签名**，row builder 增加 `.strategyId(emptyToNull(strategyId))`；内存返回的 HistoryRecord 同步 set

- [x] **Step 3: StockController** 在 classic/session 两路径：

```java
String resolvedId = strategyRegistry.resolve(strategyId).name();
// ...
backTestHistoryStore.appendSingle(period, startStr, endStr, result, resolvedId);
```

- [x] **Step 4: PortfolioController**

```java
String resolvedId = strategyRegistry.resolve(query == null ? null : query.getStrategyId()).name();
backTestHistoryStore.appendPortfolio(query, result, resolvedId);
```

注入 `StrategyRegistry`（若尚未注入）。

- [x] **Step 5: 跑相关测试**  
  Run: `mvn -q -Dtest=BackTestHistoryStoreStrategyIdTest,SessionBackTestEngineTest test`  
  Expected: PASS（或仅新测 PASS，不破坏现有）

- [x] **Step 6: Commit**（用户要求时）`feat: 回测历史写入 strategy_id`

---

### Task 3: StrategyEvalService + StrategyController

**Files:**
- Create: `src/main/java/com/quant/stock/strategy/StrategyEvalService.java`
- Create: `src/main/java/com/quant/stock/controller/StrategyController.java`
- Create: `src/test/java/com/quant/stock/strategy/StrategyEvalServiceTest.java`

**Interfaces:**
- Produces:

```java
// overview
Map<String, Object> overview();
// 结构：
// enabled: boolean
// unknownCount: long
// strategies: List<{
//   strategyId, displayName, active,
//   runCount, avgTotalRate, medianTotalRate, avgMaxDrawdown,
//   lastSavedAt, lastTotalRate
// }>

List<Map<String, Object>> history(String strategyId, String kind);
// 摘要行 keys: id, kind, savedAt, strategyId, stockCode, stockCodes,
// period, backStart, backEnd, initCapital, finalAsset, totalRate,
// maxDrawdown, totalTradeNum, winRate, configFingerprint
// （可含 tradeStats 对象；不含 trades）

Map<String, Object> detail(String recordId);
// 摘要字段 + tradeStats + trades + stockResults? + analysis
```

- 未知策略：`StrategyEvalService` 抛 `NoSuchElementException` 或自定义；Controller → `404`
- 未知 recordId → `404`
- `db` 未启用：overview 仍列出 `strategyRegistry.ids()`，聚合数字为 0，`enabled=false`

- [x] **Step 1: 写失败单测 — 中位数与聚合**

```java
@Test
void median_oddAndEven() {
    assertEquals(0, StrategyEvalService.median(Arrays.asList(
            bd("1"), bd("2"), bd("3"))).compareTo(bd("2")));
    // even: (2+3)/2 = 2.5
    assertEquals(0, StrategyEvalService.median(Arrays.asList(
            bd("1"), bd("2"), bd("3"), bd("4"))).compareTo(bd("2.5")));
}

@Test
void overview_ignoresNullStrategyIdRows() {
    // mock mapper/store：一行 strategyId=null 的 100% 收益不进入 maCross
}
```

- [x] **Step 2: 跑测确认失败**  
  Run: `mvn -q -Dtest=StrategyEvalServiceTest test`  
  Expected: FAIL（类不存在）

- [x] **Step 3: 实现 StrategyEvalService**
  - 注入：`StrategyRegistry`、`ActiveStrategyService` 或直接 `QuantProperties`+registry、`ObjectProvider<BacktestRecordMapper>` / `BackTestHistoryStore`、`BackTestAnalysisStore`
  - `median(List<BigDecimal>)`：`static`，空列表返回 null；排序后计算；除法 `RoundingMode.HALF_UP` scale 6
  - `avg`：同样 scale 6
  - `active`：`strategyRegistry.active().name().equalsIgnoreCase(id)`
  - `displayName`：暂用 `strategyId`（无独立中文名表）
  - history：`contains` 校验；`kind` 规范化：`null/blank/ALL` → 不按 kind 滤；否则 `SINGLE`/`PORTFOLIO`
  - detail：`selectByRecordId`；按 `kind` 填 trades；`analysis` = kind 对应 getById（可为 null）

- [x] **Step 4: StrategyController**

```java
@RestController
@RequestMapping("/api/strategy")
public class StrategyController {
  @GetMapping("/overview")
  public Map<String, Object> overview() { ... }

  @GetMapping("/{id}/history")
  public List<?> history(@PathVariable String id,
      @RequestParam(value = "kind", defaultValue = "ALL") String kind) { ... }

  @GetMapping("/history/{recordId}")
  public Map<String, Object> detail(@PathVariable String recordId) { ... }
}
```

404：`ResponseStatusException(HttpStatus.NOT_FOUND, "未知策略: " + id)`

- [x] **Step 5: 跑测** `mvn -q -Dtest=StrategyEvalServiceTest test` Expected: PASS

- [x] **Step 6: Commit**（用户要求时）`feat: 策略评估 overview/history/详情 API`

---

### Task 4: 前端策略管理页

**Files:**
- Create: `src/main/resources/static/docs/nav-strategy.html`
- Modify: `src/main/resources/static/stock.html`（侧栏序号重排、view）
- Modify: `src/main/resources/static/js/stock-chart.js`
- Modify: `src/main/resources/static/css/style.css`
- Cache-bust: `stock.html` 中 `style.css` / `stock-chart.js` 的 `?v=`

**Interfaces:**
- Consumes: `GET /api/strategy/overview`、`GET /api/strategy/{id}/history`、`GET /api/strategy/history/{recordId}`
- 保留现有 DOM id 约定；新 id 建议：
  - `#strategyMenu`、`#viewStrategy`、`#strategyList`、`#strategyEvalCards`、`#strategyHistoryBody`
  - `data-strategy-panel="eval"`

- [x] **Step 1: nav-strategy.html**（对齐 nav-dbtables 结构）
  - 说明：本页看策略回测效果；改参/激活仍在运维中心

- [x] **Step 2: stock.html 侧栏**（插在 schedule 与 dbtables 之间）

```html
<div class="side-nav" data-nav="strategy">
  <button type="button" class="side-nav-toggle" data-body="strategyBody" data-mode="strategy"
          data-intro="/docs/nav-strategy.html" data-intro-title="策略管理" aria-expanded="false">
    <span class="nav-idx">07</span>
    <span class="nav-label">策略管理</span>
    <span class="chevron">▸</span>
  </button>
  <div id="strategyBody" class="side-nav-body">
    <p class="hint">二级：策略评估（回测历史与整体评价）</p>
    <ul id="strategyMenu" class="side-nav-menu">
      <li data-strategy-panel="eval" role="button" tabindex="0">策略评估</li>
    </ul>
  </div>
</div>
```

其后：数据表 `08`、量化知识 `09`、应用说明 `10`（改 nav-idx）。

工作台面板布局：

```html
<div id="viewStrategy" class="workspace-view" hidden>
  <section class="panel">
    <h3>策略评估</h3>
    <p class="hint">按策略查看回测历史与整体评价；改参/激活请到运维中心</p>
    <div class="strategy-eval-layout">
      <aside id="strategyList" class="strategy-list"></aside>
      <div class="strategy-eval-main">
        <div id="strategyEvalCards" class="strategy-eval-cards"></div>
        <div class="table-wrap">
          <table class="tp-table">
            <thead>...</thead>
            <tbody id="strategyHistoryBody">...</tbody>
          </table>
        </div>
      </div>
    </div>
  </section>
</div>
```

- [x] **Step 3: CSS** `.strategy-eval-layout` 两栏（左约 200px）；卡片横排；激活角标小标签

- [x] **Step 4: JS**
  - `hideAllWorkspaceViews` 纳入 `#viewStrategy`
  - `showStrategyEval()`：开侧栏、拉 overview、渲染左列表、默认选中 active 或第一个
  - 点策略：刷卡片 + `GET /api/strategy/{id}/history`
  - 点历史行：`GET /api/strategy/history/{recordId}`，展开行展示 tradeStats + analysis.summary/events（可复用 `history-analysis-row` 样式）
  - `kind` Tab 可选：全部 / 单股 / 组合（简单按钮，默认 ALL）
  - Toast 失败提示；空表 empty-state

- [x] **Step 5: 手工验收清单**
  1. 侧栏出现「策略管理」且序号正确
  2. overview 列出注册策略与激活角标
  3. 用 `maCross` 与 `branchScaffold` 各跑一次回测后，次数与收益出现在对应策略下
  4. 点行能看到详情/分析摘要
  5. 旧无 strategy_id 的记录不出现在策略列表聚合中（unknownCount 可增）

- [x] **Step 6: Commit**（用户要求时）`feat: 策略管理评估页`

---

### Task 5: 文档同步

**Files:**
- Modify: `README.md`（功能清单顺序、API 表）
- Modify: `src/main/resources/static/docs/app.html`
- Modify: `src/main/resources/static/docs/memo.html`
- Modify: 工作台顺序文案中含「策略管理」

- [x] **Step 1: README**
  - 工作台顺序加入策略管理
  - API：`GET /api/strategy/overview`、`/{id}/history`、`/history/{recordId}`
  - 数据表节可一句带过历史含 `strategy_id`

- [x] **Step 2: app.html** 功能列表加策略管理要点

- [x] **Step 3: memo.html** 「本地已落地」加策略管理评估项

- [x] **Step 4: Commit**（用户要求时）`docs: 同步策略管理说明`

---

## Spec coverage check

| Spec 项 | Task |
|---------|------|
| `strategy_id` 列 + ensureColumn | T1 |
| 写入注册表 id | T2 |
| 旧行未知、不回填 | T3 overview |
| overview / history / detail API | T3 |
| 列表无大 JSON | T1 selectSummary + T3 |
| 详情内嵌 analysis | T3 |
| 独立一级导航 + 策略评估 | T4 |
| 评价卡片 + 历史表 + 点行详情 | T4 |
| 不做改参/激活/并排图 | 全局约束 |
| README / app / memo | T5 |
| 中位数规则 | T3 单测 |

## Placeholder / consistency self-review

- 无 TBD；404 统一 `contains` + `NOT_FOUND`
- append 三参数签名在 T2 固定，控制器同步
- 前端 id 与 API 路径与 Spec 一致
