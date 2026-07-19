# Single Trade Pool (Auto Replace) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace dual-pool (recommend → confirm → final) with one auto-updated `trade_pool` written by after-market scan; drop obsolete tables; keep holdings exits strategy-driven only.

**Architecture:** `TradePoolService.rebuildFromUniverse()` scans universe, picks TopN by existing recommendability + `totalRate`, then `deactivateAll` + upsert selected into `trade_pool` (source=`BATCH_SCAN`). Analysis rows go to new `trade_pool_report`. Confirm/dismiss/history paths and mappers are removed. UI becomes a single pool list. Live `scan-and-trade` keeps using `listActiveCodes()`.

**Tech Stack:** Spring Boot 2.7, MyBatis, MySQL, jQuery frontend (`stock.html` / `stock-chart.js`)

## Global Constraints

- Unique active pool table: `trade_pool` only
- DROP: `trade_pool_recommend`, `trade_pool_recommend_report`, `trade_pool_history`
- ADD: `trade_pool_report` (analysis JSON linked by `trade_pool.report_id`)
- No human confirm/dismiss; scan auto-replaces pool
- Removing from pool does NOT sell holdings (no tier-3 degradation this iteration)
- Coarse filter this iteration: `status!=0` + skip `is_st` when field present
- Keep manual remove from pool for ops
- Merge job semantics: both after-market jobs may call rebuild; docs say enable only one
- Do not commit unless the user explicitly asks

---

## File map

| Path | Responsibility |
|------|----------------|
| `mapper/schema.sql` | Drop old CREATE blocks; add `trade_pool_report`; update `trade_pool` comments/defaults |
| `pool/dto/TradePoolReportDO.java` | New report DO |
| `mapper/TradePoolReportMapper.java` + `.xml` | Insert/select report |
| `mapper/TradePoolMapper.java` + `.xml` | Keep deactivateAll/upsert/selectActive; no history helpers |
| Delete recommend/history Mapper+DO+XML | Full removal |
| `pool/TradePoolService.java` | Replace rebuild; remove confirm/dismiss/history; coarse filter |
| `controller/TradePoolController.java` | Slim API surface |
| `admin/DbTableCatalog.java` | Whitelist: drop 3, add report, retitle trade_pool |
| `task/ScheduleJobGuide.java` | Copy updates |
| `static/stock.html`, `js/stock-chart.js`, `docs/nav-tradepool.html`, related docs | Single-pool UI/docs |

---

### Task 1: Schema + report persistence layer

**Files:**
- Modify: `src/main/resources/mapper/schema.sql`
- Create: `src/main/java/com/quant/stock/pool/dto/TradePoolReportDO.java`
- Create: `src/main/java/com/quant/stock/mapper/TradePoolReportMapper.java`
- Create: `src/main/resources/mapper/TradePoolReportMapper.xml`
- Delete: `TradePoolRecommendMapper.java/.xml`, `TradePoolRecommendReportMapper.java/.xml`, `TradePoolHistoryMapper.java/.xml`
- Delete: `TradePoolRecommendDO.java`, `TradePoolRecommendReportDO.java`, `TradePoolHistoryDO.java`

**Interfaces:**
- Produces: `TradePoolReportMapper.insert(TradePoolReportDO)` returning generated `id`; `selectById(Long id)`; `selectLatestBySymbol(String symbol)`
- Produces schema table `trade_pool_report` columns: `id, symbol, name, score, reason, summary, analysis_json, batch_id, created_at`

- [ ] **Step 1: Update `schema.sql`**

Replace the recommend/final/history block with:

```sql
-- ---------- 模块：交易目标池（盘后扫描自动覆盖） ----------
CREATE TABLE IF NOT EXISTS `trade_pool_report` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL COMMENT '股票代码',
  `name` VARCHAR(32) DEFAULT NULL,
  `score` DECIMAL(12,6) DEFAULT NULL,
  `reason` VARCHAR(256) DEFAULT NULL COMMENT '入选依据摘要',
  `summary` VARCHAR(1024) DEFAULT NULL,
  `analysis_json` LONGTEXT COMMENT '完整分析 JSON',
  `batch_id` VARCHAR(32) DEFAULT NULL COMMENT '同一次扫描批次',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_symbol_time` (`symbol`, `created_at`),
  KEY `idx_batch` (`batch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标池入选分析报告';

CREATE TABLE IF NOT EXISTS `trade_pool` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL COMMENT '股票代码',
  `name` VARCHAR(32) DEFAULT NULL COMMENT '简称',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '1在池 0停用',
  `score` DECIMAL(12,6) DEFAULT NULL COMMENT '扫描分数',
  `reason` VARCHAR(256) DEFAULT NULL COMMENT '入选原因',
  `source` VARCHAR(16) NOT NULL DEFAULT 'BATCH_SCAN' COMMENT 'BATCH_SCAN/MANUAL',
  `report_id` BIGINT DEFAULT NULL COMMENT '关联 trade_pool_report.id',
  `entered_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_symbol` (`symbol`),
  KEY `idx_status` (`status`),
  KEY `idx_report` (`report_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='量化交易目标池（盘后扫描自动覆盖；盘中开仓名单）';
```

Remove CREATE for `trade_pool_recommend`, `trade_pool_recommend_report`, `trade_pool_history`.

- [ ] **Step 2: Add `TradePoolReportDO` + Mapper interface/XML**

Mirror former recommend-report mapper (insert + selectById + selectLatestBySymbol). Use `@Options(useGeneratedKeys=true, keyProperty="id")` or XML `useGeneratedKeys`.

- [ ] **Step 3: Delete obsolete Mapper/DO/XML files listed above**

- [ ] **Step 4: Compile check**

Run: `mvn -q -DskipTests compile`  
Expected: FAIL with missing recommend/history symbols in `TradePoolService` / catalog (until Task 2–3). If only schema/mapper compile issues, fix XML namespace/`typeAliases`.

---

### Task 2: `TradePoolService` rebuild → replace unique pool

**Files:**
- Modify: `src/main/java/com/quant/stock/pool/TradePoolService.java` (majority rewrite of pool methods)
- Modify: `src/main/java/com/quant/stock/mapper/TradePoolMapper.java` + `TradePoolMapper.xml` if needed (already has `deactivateAll` / `upsert`)

**Interfaces:**
- Consumes: `TradePoolReportMapper`, `TradePoolMapper`, `BatchStockBackTestService.scan`, `StockBasicMapper`
- Produces:
  - `Map<String,Object> rebuildFromUniverse()` — keys: `universe`, `scanned`, `selected`, `codes`, `batchId`
  - `Map<String,Object> overview()` — keys: `items`, `count` (single list; no pending/final/history)
  - `List<String> listActiveCodes()` — unchanged contract
  - `Map<String,Object> analysis(String code)` — load via `trade_pool.report_id` or latest report
  - `Map<String,Object> removeFinal(String code)` rename conceptually to `removeFromPool` — deactivate only, no history insert
  - Delete methods: `confirm`, `dismiss`, `listPendingRecommends`, `listHistoryViews`, `applyRecommendFromScan` (replace body)

- [ ] **Step 1: Rewrite `ensureSchema` in `TradePoolService`**

At startup (existing ensure path):

```java
jdbc.update("DROP TABLE IF EXISTS `trade_pool_history`");
jdbc.update("DROP TABLE IF EXISTS `trade_pool_recommend`");
jdbc.update("DROP TABLE IF EXISTS `trade_pool_recommend_report`");
// then CREATE IF NOT EXISTS trade_pool_report + trade_pool (same DDL as schema.sql)
```

Drop order: history/recommend first (no FK), then old report table, then ensure new report + trade_pool.

- [ ] **Step 2: Add coarse filter helper**

```java
private List<String> coarseFilter(List<Map<String, String>> universe) {
    List<String> out = new ArrayList<String>();
    for (Map<String, String> u : universe) {
        String code = u.get("code");
        if (code == null) continue;
        // listUniverse already skips status==0; double-check ST if name/flags available
        String st = u.get("isSt"); // populate in listUniverse from stock_basic.is_st
        if ("1".equals(st) || "true".equalsIgnoreCase(st)) continue;
        out.add(code);
    }
    return out;
}
```

Extend `listUniverse()` to include `isSt` from `StockBasicDO` when present.

- [ ] **Step 3: Implement `replaceActivePool`**

```java
@Transactional
private List<String> replaceActivePool(List<BatchScanResultDTO> picked, Map<String,String> nameByCode, String batchId) {
    tradePoolMapper.deactivateAll();
    List<String> selected = new ArrayList<String>();
    for (BatchScanResultDTO r : picked) {
        String code = r.getStockCode();
        String name = nameByCode.getOrDefault(code, code);
        Map<String, Object> analysis = buildAnalysisMap(code, name, r, true);
        String summary = buildRecommendSummary(name, code, r, true);
        analysis.put("summary", summary);
        analysis.put("batchId", batchId);
        TradePoolReportDO report = TradePoolReportDO.builder()
                .symbol(code).name(name)
                .score(r.getTotalRate() == null ? BigDecimal.ZERO : r.getTotalRate())
                .reason(trimReason(recommendReason(r) + " | " + r.getSignalDesc()))
                .summary(summary.length() > 1000 ? summary.substring(0, 1000) : summary)
                .analysisJson(JSON.toJSONString(analysis))
                .batchId(batchId)
                .build();
        reportMapper.insert(report);
        tradePoolMapper.upsert(TradePoolDO.builder()
                .symbol(code).name(name).status(1)
                .score(report.getScore()).reason(report.getReason())
                .source("BATCH_SCAN").reportId(report.getId())
                .build());
        selected.add(code);
    }
    return selected;
}
```

Wire `rebuildFromUniverse`:

```java
public Map<String, Object> rebuildFromUniverse() {
    List<Map<String, String>> uni = listUniverse();
    Map<String, String> nameByCode = new HashMap<String, String>();
    for (Map<String, String> u : uni) {
        nameByCode.put(u.get("code"), u.get("name"));
    }
    List<String> codes = coarseFilter(uni);
    List<BatchScanResultDTO> scanned = codes.isEmpty()
            ? Collections.<BatchScanResultDTO>emptyList()
            : batchStockBackTestService.scan(codes);
    int max = Math.max(1, quantProperties.getTradePoolMax());
    List<BatchScanResultDTO> picked = new ArrayList<BatchScanResultDTO>();
    for (BatchScanResultDTO r : scanned) {
        if (r != null && isRecommendable(r)) {
            picked.add(r);
        }
        if (picked.size() >= max) break;
    }
    String batchId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
            + "-" + UUID.randomUUID().toString().substring(0, 8);
    List<String> selected = replaceActivePool(picked, nameByCode, batchId);
    Map<String, Object> out = new LinkedHashMap<String, Object>();
    out.put("universe", uni.size());
    out.put("scanned", scanned.size());
    out.put("selected", selected.size());
    out.put("codes", selected);
    out.put("batchId", batchId);
    return out;
}
```

Note: `scan()` already sorts by `totalRate` DESC — keep that assumption; if not sorted, sort before pick.

- [ ] **Step 4: Slim `overview` / analysis / remove**

- `overview()`: only active `trade_pool` rows as `items` + `count`
- `recommendAnalysis` → `poolAnalysis(code)` reading report by active row `report_id`
- `removeFinal`: `deactivateBySymbol` only; **no** history insert
- Remove confirm/dismiss/history/pending list methods entirely
- Update `analyzeAndRecommend()` to call same rebuild + optional markdown under `historyDir/reports/pool-*.md`

- [ ] **Step 5: Compile**

Run: `mvn -q -DskipTests compile`  
Expected: PASS (controller may still reference deleted methods until Task 3)

---

### Task 3: Controller + DbTableCatalog + schedule copy

**Files:**
- Modify: `src/main/java/com/quant/stock/controller/TradePoolController.java`
- Modify: `src/main/java/com/quant/stock/admin/DbTableCatalog.java`
- Modify: `src/main/java/com/quant/stock/task/ScheduleJobGuide.java`
- Modify: `src/main/java/com/quant/stock/task/StrategyTask.java` (log/javadoc for `afterMarketBatchScan` only if wording wrong)
- Modify: `src/main/java/com/quant/stock/task/ScheduleJobHandlers.java` (javadoc)

**Interfaces:**
- Produces HTTP:
  - `GET /api/stock/trade-pool` → `{ count, items }`
  - `GET /api/stock/trade-pool/final` → same as pool (compat alias) OR remove; prefer keep alias returning active items
  - `GET /api/stock/trade-pool/{code}/analysis` (replace recommend path)
  - `GET /api/stock/trade-pool/report/{id}`
  - `POST /api/stock/trade-pool/rebuild`
  - `POST /api/stock/trade-pool/analyze`
  - `POST /api/stock/trade-pool/{code}/remove` (keep)
  - DELETE endpoints: confirm, dismiss, recommend list, history

- [ ] **Step 1: Rewrite controller**

Remove mappings for `/recommend`, `/history`, `/confirm`, `/dismiss`.  
Keep rebuild/analyze/remove/report.  
Change class-level comments to「唯一目标池」.

- [ ] **Step 2: Update `DbTableCatalog`**

Remove entries for three dropped tables; add `trade_pool_report`; retitle `trade_pool` purpose/usage to auto-cover pool.

- [ ] **Step 3: Update `ScheduleJobGuide` text** for `pool-rebuild` / `after-market-batch-scan`: 「扫描后覆盖唯一目标池；启用其一即可」；remove「写入推荐待确认」 wording.

- [ ] **Step 4: Compile + unit tests**

Run: `mvn -q test`  
Expected: existing tests PASS; no new Spring context test required.

---

### Task 4: Frontend single pool UI

**Files:**
- Modify: `src/main/resources/static/stock.html`
- Modify: `src/main/resources/static/js/stock-chart.js`
- Modify: `src/main/resources/static/docs/nav-tradepool.html`
- Modify: `src/main/resources/static/docs/home.html` (if tradepool blurb mentions confirm)
- Bump cache query on `stock-chart.js` / `style.css` in `stock.html`

**Interfaces:**
- Consumes: `GET /api/stock/trade-pool`, `POST .../rebuild`, `POST .../{code}/remove`, analysis GET
- Produces UI mode `tradepool` with one view (no pending/final/history panels)

- [ ] **Step 1: HTML**

- Replace `#tradepoolMenu` three items with single entry or remove submenu and use「进入目标池」button (like schedule)
- Remove `#viewTpPending`, `#viewTpHistory` (or hide permanently); keep one `#viewTpFinal` renamed to `#viewTradePool` title「交易目标池」
- Toolbar: 扫描更新 / 刷新 / 移出（行内）； remove 确认选中 / 忽略选中

- [ ] **Step 2: JS**

- Remove `showTradePoolPanel` pending/final/history branching; `showMode('tradepool')` shows single view
- `loadTradePoolManage` loads one table from `data.items` (or `data.final` fallback removed — use `items` only)
- Wire rebuild button to `POST /rebuild`; remove confirm/dismiss handlers
- Row expand analysis: `GET /api/stock/trade-pool/{code}/analysis` or report id
- Update `hideAllWorkspaceViews` ids
- Side counts: one badge for pool size

- [ ] **Step 3: `nav-tradepool.html`**

Rewrite intro: 盘后扫描自动覆盖唯一池；人工可移出；持仓不因移出而卖；CTA `data-enter-mode="tradepool"`.

- [ ] **Step 4: Manual smoke (app running)**

1. Open 交易目标池 → single list  
2. Click 扫描更新 → list refreshes; old codes not in TopN disappear  
3. Confirm no confirm/dismiss UI  
4. Hard refresh cache-busted JS  

---

### Task 5: Docs cleanup + final verify

**Files:**
- Modify: `src/main/resources/static/docs/rules.html` (dual-pool sections)
- Modify: any `memo.html` / README snippets mentioning recommend confirm
- Modify: `docs/superpowers/specs/2026-07-19-single-trade-pool-design.md` if DROP wording needs sync (already updated)

- [ ] **Step 1: Search and fix stale copy**

Run ripgrep for: `待确认`, `推荐历史`, `trade_pool_recommend`, `confirm`, `最终目标池` in `static/docs` and README; update to 唯一目标池 / 自动覆盖.

- [ ] **Step 2: Full compile + tests**

Run: `mvn -q test`  
Expected: BUILD SUCCESS

- [ ] **Step 3: Spec coverage checklist**

- [x] Unique pool auto replace  
- [x] DROP three tables + add report  
- [x] No confirm  
- [x] Manual remove kept  
- [x] No tier-3 degradation  
- [x] Schedule guide wording  
- [x] UI single list  

---

## Spec coverage (self-review)

| Spec item | Task |
|-----------|------|
| Reuse `trade_pool` unique | 2 |
| Auto replace TopN | 2 |
| DROP recommend/history/old report | 1 + ensureSchema in 2 |
| New `trade_pool_report` | 1–2 |
| Remove confirm/dismiss API/UI | 3–4 |
| listActiveCodes for live | unchanged in 2 |
| Manual remove | 2–4 |
| Coarse ST filter | 2 |
| No holdings sell-on-remove | 2 (no sell calls) |
| Docs/schedule | 3, 5 |

## Placeholder scan

None intentional. Commit steps omitted from task checklists per user git rule (commit only when asked).
