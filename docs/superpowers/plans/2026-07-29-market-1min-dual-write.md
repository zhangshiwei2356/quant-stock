# Market 1min Dual-Write Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `market_1min` as the raw K-line layer with dual-write transition (`auto` read prefers 1min aggregation when enough bars exist; otherwise keep `market_daily` / `market_minute`).

**Architecture:** New table + Mapper/DO; `CoreMarketBarService` gains 1min save/load and `quant.kline.source=auto|prefer_1min|legacy`; `BarDTO` carries period minutes for close/TA4J; pytdx script backfills trade-pool symbols (~90 trading days). Old daily/5min tables remain for fallback and optional cache refresh.

**Tech Stack:** Spring Boot, MyBatis, MySQL, JUnit 5, Python 3 + pytdx/pymysql

**Spec:** `docs/superpowers/specs/2026-07-29-market-1min-dual-write-design.md`

## Global Constraints

- Dual-write variant **A**: enough 1min → aggregate; else legacy tables
- Do **not** delete or rename `market_daily` / `market_minute`
- Do **not** change `MaCrossStrategy` buy/sell formulas
- Do **not** implement ClickHouse / monthly sharding / Tushare / `bankTrend`
- Default `quant.kline.source=auto`, `quant.kline.min-1min-bars=240`
- `trade_time` / `barBegin` = bar **start** (left edge)
- Commit only when the user explicitly asks (`git-commit-zh` if committing)
- Sync README / `app.html` / `memo.html` in the docs task (same change round)

---

## File map

| Path | Responsibility |
|------|----------------|
| `src/main/resources/mapper/schema.sql` | CREATE `market_1min` |
| `src/main/java/.../dto/Market1MinDO.java` | 1min row ↔ BarDTO |
| `src/main/java/.../mapper/Market1MinMapper.java` + `.xml` | count / selectRange / batchUpsert |
| `src/main/java/.../dto/BarDTO.java` | `periodMinutes`; period-aware `getBarEnd` / TA4J |
| `src/main/java/.../config/QuantProperties.java` + `application.yml` | `klineSource`, `min1minBars` |
| `src/main/java/.../market/CoreMarketBarService.java` | saveMinutes1; auto load path; settle from 1min |
| `src/main/java/.../market/MarketDataService.java` | javadoc priority note |
| `src/main/java/.../market/MockDataImporter.java` | optional `MIN_1.json` import |
| `src/main/java/.../admin/DbTableCatalog.java` | whitelist `market_1min` |
| `src/main/java/.../task/StrategyTask.java` | settle prefer 1min day |
| `scripts/fetch_min1_tdx.py` | pytdx → MySQL (+ optional cache) |
| `src/test/java/.../market/BarDtoPeriodTest.java` | BarDTO duration |
| `src/test/java/.../market/OneMinAggregatePathTest.java` | aggregate from synthetic 1min |
| `README.md`, `static/docs/app.html`, `static/docs/memo.html` | user-facing sync |

---

### Task 1: Schema + Market1Min persistence

**Files:**
- Modify: `src/main/resources/mapper/schema.sql` (after `market_minute` block)
- Create: `src/main/java/com/quant/stock/market/dto/Market1MinDO.java`
- Create: `src/main/java/com/quant/stock/mapper/Market1MinMapper.java`
- Create: `src/main/resources/mapper/Market1MinMapper.xml`
- Modify: `src/main/java/com/quant/stock/admin/DbTableCatalog.java`

**Interfaces:**
- Produces: `Market1MinMapper.countBySymbol(String)`, `selectRange(symbol, start, end)`, `batchUpsert(List<Market1MinDO>)`
- Produces: `Market1MinDO.fromBarDTO(BarDTO)` / `toBarDTO()` (sets `periodMinutes=1` when mapping to BarDTO — after Task 2 field exists; until then omit and set in Task 3)

- [ ] **Step 1: Append table to `schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS `market_1min` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL COMMENT '股票代码',
  `trade_time` DATETIME NOT NULL COMMENT '1分钟K起始时间',
  `open` DECIMAL(10,4) NOT NULL,
  `high` DECIMAL(10,4) NOT NULL,
  `low` DECIMAL(10,4) NOT NULL,
  `close` DECIMAL(10,4) NOT NULL,
  `volume` BIGINT NOT NULL COMMENT '成交量(股)',
  `amount` DECIMAL(16,4) DEFAULT NULL COMMENT '成交额(元)',
  UNIQUE KEY `idx_symbol_time` (`symbol`, `trade_time`),
  KEY `idx_time` (`trade_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='1分钟线原始行情表(唯一明细层)';
```

Also update the module comment above `market_daily` from「仅日线 + 5分钟」to「日线 + 5分钟缓存 + 1分钟原始层」.

- [ ] **Step 2: Create `Market1MinDO`** (mirror `MarketMinuteDO`, class/table comments say 1min)

- [ ] **Step 3: Create Mapper interface + XML** (copy `MarketMinuteMapper` / XML, rename table to `market_1min`, types to `Market1MinDO`)

- [ ] **Step 4: Register in `DbTableCatalog`**

Add near other market tables:

```java
add(m, "market_1min", "1分钟行情(原始层)", "行情", "id DESC",
    "symbol,trade_time,open,high,low,close,volume,amount");
```

(Adjust `add(...)` arity to match existing catalog helpers.)

- [ ] **Step 5: Apply DDL on local MySQL**

Run:

```bash
mysql -uroot -p123456 quant_stock -e "SHOW CREATE TABLE market_1min\G"
```

If table missing, execute the CREATE from schema.sql. Expected: table exists with unique `(symbol, trade_time)`.

- [ ] **Step 6: Commit only if user asks**

---

### Task 2: Period-aware `BarDTO` (TDD)

**Files:**
- Create: `src/test/java/com/quant/stock/market/BarDtoPeriodTest.java`
- Modify: `src/main/java/com/quant/stock/market/dto/BarDTO.java`

**Interfaces:**
- Produces: `BarDTO.periodMinutes` (`Integer`, null → treat as 5 for backward compat with existing 5min callers)
- Produces: `getBarEnd()` uses `periodMinutes == null ? 5 : periodMinutes`
- Produces: `toTa4jBar()` uses `Duration.ofMinutes(same)`

- [ ] **Step 1: Write failing test**

```java
package com.quant.stock.market;

import com.quant.stock.market.dto.BarDTO;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarDtoPeriodTest {
    @Test
    void barEnd_usesPeriodMinutes_defaultFive() {
        BarDTO b = BarDTO.builder()
                .code("600036")
                .barBegin(LocalDateTime.of(2026, 7, 28, 9, 30))
                .open(BigDecimal.ONE).high(BigDecimal.ONE).low(BigDecimal.ONE).close(BigDecimal.ONE)
                .volume(BigDecimal.TEN)
                .build();
        assertEquals(LocalDateTime.of(2026, 7, 28, 9, 35), b.getBarEnd());
    }

    @Test
    void barEnd_oneMinute() {
        BarDTO b = BarDTO.builder()
                .code("600036")
                .barBegin(LocalDateTime.of(2026, 7, 28, 9, 30))
                .periodMinutes(1)
                .open(BigDecimal.ONE).high(BigDecimal.ONE).low(BigDecimal.ONE).close(BigDecimal.ONE)
                .volume(BigDecimal.TEN)
                .build();
        assertEquals(LocalDateTime.of(2026, 7, 28, 9, 31), b.getBarEnd());
    }
}
```

- [ ] **Step 2: Run test — expect FAIL** (no `periodMinutes` / still +5)

```bash
mvn -q -Dtest=BarDtoPeriodTest test
```

- [ ] **Step 3: Implement `BarDTO` changes**

- Add field `private Integer periodMinutes;`
- Change javadoc: physical source may be 1min or 5min; end = begin + periodMinutes (default 5)
- `getBarEnd()`:

```java
public LocalDateTime getBarEnd() {
    if (barBegin == null) {
        return null;
    }
    int mins = periodMinutes == null ? 5 : periodMinutes.intValue();
    return barBegin.plusMinutes(mins);
}
```

- `toTa4jBar()`: `Duration.ofMinutes(periodMinutes == null ? 5 : periodMinutes)`

- [ ] **Step 4: Run test — expect PASS**

```bash
mvn -q -Dtest=BarDtoPeriodTest test
```

- [ ] **Step 5: Commit only if user asks**

---

### Task 3: Config + CoreMarketBarService auto path (TDD aggregate)

**Files:**
- Modify: `src/main/java/com/quant/stock/config/QuantProperties.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/java/com/quant/stock/market/CoreMarketBarService.java`
- Modify: `src/main/java/com/quant/stock/market/dto/Market1MinDO.java` (`toBarDTO` set `periodMinutes(1)`)
- Modify: `src/main/java/com/quant/stock/market/dto/MarketMinuteDO.java` (`toBarDTO` set `periodMinutes(5)`)
- Create: `src/test/java/com/quant/stock/market/OneMinAggregatePathTest.java`
- Modify: `src/main/java/com/quant/stock/market/MarketDataService.java` (class javadoc only)

**Interfaces:**
- Consumes: `Market1MinMapper`, `QuantProperties.getKlineSource()`, `getMin1minBars()`
- Produces: `CoreMarketBarService.saveMinutes1(List<BarDTO>)`, `hasOneMin(String)`, updated `load(...)`, updated `upsertDailyFromMinutes` to prefer 1min for that day when present

- [ ] **Step 1: Add properties**

In `QuantProperties`:

```java
/** auto | prefer_1min | legacy */
private String klineSource = "auto";
/** auto 下认定有足够 1min 的最少根数 */
private int min1minBars = 240;
```

In `application.yml` under `quant:`:

```yaml
  kline-source: auto
  min-1min-bars: 240
```

- [ ] **Step 2: Write aggregate unit test** (no Spring — pure `BarAggregateUtil`)

```java
@Test
void aggregate_fiveOneMin_toOneFiveMin() {
    List<BarDTO> ones = new ArrayList<>();
    LocalDateTime t = LocalDateTime.of(2026, 7, 28, 9, 30);
    for (int i = 0; i < 5; i++) {
        ones.add(BarDTO.builder().code("600036").barBegin(t.plusMinutes(i)).periodMinutes(1)
                .open(new BigDecimal("10")).high(new BigDecimal("11"))
                .low(new BigDecimal("9")).close(new BigDecimal("10.5"))
                .volume(BigDecimal.valueOf(100)).build());
    }
    List<BarDTO> m5 = BarAggregateUtil.aggregate(ones, BarAggregateUtil.Period.M5);
    assertEquals(1, m5.size());
    assertEquals(t, m5.get(0).getBarBegin());
}
```

Run: `mvn -q -Dtest=OneMinAggregatePathTest test` — should PASS with existing util (documents contract). If trading-minute filter drops bars, adjust times to valid session slots already used in `MockFiveMinuteBarsTest`.

- [ ] **Step 3: Implement `saveMinutes1` + inject `Market1MinMapper` + `QuantProperties`**

Mirror `saveMinutes` but `Market1MinDO` / `market1MinMapper`.

Helper:

```java
public boolean hasEnoughOneMin(String symbol) {
    return market1MinMapper.countBySymbol(symbol) >= quantProperties.getMin1minBars();
}

private boolean useOneMin(String symbol) {
    String src = quantProperties.getKlineSource();
    if (src == null) src = "auto";
    src = src.trim().toLowerCase();
    if ("legacy".equals(src)) return false;
    boolean enough = hasEnoughOneMin(symbol);
    if ("prefer_1min".equals(src)) return enough; // caller returns empty if !enough
    return enough; // auto
}
```

- [ ] **Step 4: Rewrite `load` switch**

Pseudocode:

```java
public List<BarDTO> load(String code, BarPeriod period, LocalDateTime start, LocalDateTime end) {
    if (period == null) period = BarPeriod.DAY;
    String src = normalizeSource();
    boolean enough = hasEnoughOneMin(code);
    if ("prefer_1min".equals(src) && !enough) {
        return new ArrayList<>();
    }
    if (!"legacy".equals(src) && enough) {
        List<BarDTO> ones = loadOneMin(code, start, end);
        switch (period) {
            case MIN_1: return ones;
            case MIN_5: return BarAggregateUtil.aggregate(ones, Period.M5);
            case MIN_15: return BarAggregateUtil.aggregate(ones, Period.M15);
            case MIN_30: return BarAggregateUtil.aggregate(ones, Period.M30);
            case MIN_60: return BarAggregateUtil.aggregate(ones, Period.M60);
            case DAY: return BarAggregateUtil.aggregate(ones, Period.DAY);
            case WEEK: return BarAggregateUtil.aggregate(ones, Period.WEEK);
            case MONTH: return BarAggregateUtil.aggregate(ones, Period.MONTH);
            default: return new ArrayList<>();
        }
    }
    // existing daily / minute-5 path (MIN_1 still degrades to 5min as today)
    ...
}
```

`loadOneMin`: map rows via `toBarDTO()` with `periodMinutes=1`.

- [ ] **Step 5: `upsertDailyFromMinutes` prefer 1min**

For `tradeDay`, load 1min in `[09:30, 15:00]`; if non-empty, aggregate DAY and `saveDailies`; else existing 5min path.

- [ ] **Step 6: Update `MarketDataService` class javadoc**

Priority line → MySQL(`market_1min` auto-aggregate / daily / minute5) → Redis → …

- [ ] **Step 7: Compile + tests**

```bash
mvn -q -Dtest=BarDtoPeriodTest,OneMinAggregatePathTest,MockFiveMinuteBarsTest test
```

Expected: PASS

- [ ] **Step 8: Commit only if user asks**

---

### Task 4: Settle + Mock importer

**Files:**
- Modify: `src/main/java/com/quant/stock/task/StrategyTask.java` (only if settle calls need explicit 1min save — prefer keeping logic inside `upsertDailyFromMinutes`)
- Modify: `src/main/java/com/quant/stock/market/MockDataImporter.java`
- Modify: `src/main/java/com/quant/stock/task/ScheduleJobGuide.java` (settle / market-collect remark one line)

**Interfaces:**
- Consumes: `CoreMarketBarService.saveMinutes1`, `upsertDailyFromMinutes` (Task 3)

- [ ] **Step 1: MockDataImporter**

When scanning classpath kline dirs, if `MIN_1.json` exists and `market_1min` empty for symbol, parse and `saveMinutes1`. Do not require MIN_1 for coverage (daily-only stocks still “covered” as today).

- [ ] **Step 2: ScheduleJobGuide copy**

`settle-after-close` remark: 有 1min 则优先聚日线；否则 5min→日线。  
`market-collect` remark: 未来先落 market_1min。

- [ ] **Step 3: Smoke compile**

```bash
mvn -q -DskipTests compile
```

- [ ] **Step 4: Commit only if user asks**

---

### Task 5: `scripts/fetch_min1_tdx.py`

**Files:**
- Create: `scripts/fetch_min1_tdx.py`
- Modify: `src/main/resources/data/kline/README.md` (one section on 1min backfill)

**Interfaces:**
- CLI: `python scripts/fetch_min1_tdx.py [--codes 600036,000001] [--from-pool] [--skip-cache] [--sleep 0.2]`
- Default: try load active trade_pool symbols via pymysql `SELECT symbol FROM trade_pool WHERE status=1`, else require `--codes`
- Writes `market_1min`; unless `--skip-cache`, also aggregate in Python to upsert `market_minute` (5min) and `market_daily`

- [ ] **Step 1: Implement script**

Reuse patterns from `scripts/fetch_stocks_batch.py` (DB dict) + `scripts/probe_min1_sources.py` (TDX hosts, category 8, page by 800 until empty).

Volume: pytdx `vol` often shares; convert to 股 (`*100`) if values look like 手 (document in script header). Keep consistent with existing minute importer if one exists.

Pagination:

```python
start = 0
while True:
    bars = api.get_security_bars(8, market, code, start, 800)
    if not bars:
        break
    # convert + buffer
    start += len(bars)
    if len(bars) < 800:
        break
```

Upsert SQL batch 500, same as Java ON DUPLICATE KEY UPDATE.

- [ ] **Step 2: Dry-run one symbol**

```bash
python scripts/fetch_min1_tdx.py --codes 600036 --sleep 0.2
```

Expected: rows in `market_1min` for 600036; count roughly thousands; `SELECT MIN(trade_time), MAX(trade_time), COUNT(1) FROM market_1min WHERE symbol='600036'`.

- [ ] **Step 3: Commit only if user asks**

---

### Task 6: Docs sync

**Files:**
- Modify: `README.md` (行情物理表、`kline-source`、灌数脚本)
- Modify: `src/main/resources/static/docs/app.html`
- Modify: `src/main/resources/static/docs/memo.html`
- Modify: `docs/superpowers/specs/2026-07-29-market-1min-dual-write-design.md` status already 已批准

- [ ] **Step 1: README**

State:

- 物理表：`market_1min`（原始）、`market_minute`（5min 缓存/回退）、`market_daily`（日线缓存/回退）
- `quant.kline-source` 默认 `auto`；`min-1min-bars` 默认 240
- 灌数：`python scripts/fetch_min1_tdx.py --from-pool`

- [ ] **Step 2: app.html / memo.html**

One bullet each: 1min 原始层双写过渡；无 1min 时回退日线/5min。

- [ ] **Step 3: rules.html**

Only if needed: under 单只回测 add「有 `market_1min` 且 auto 时，非 DAY 周期可由 1min 聚合；默认工作台仍可 DAY」。If default UX unchanged, a single clarifying line is enough.

- [ ] **Step 4: Commit only if user asks**

---

## Spec coverage checklist

| Spec item | Task |
|-----------|------|
| Create `market_1min` | 1 |
| Dual-write / keep old tables | 3, 5 |
| `kline.source` auto/prefer/legacy + min bars | 3 |
| BarDTO period end / TA4J | 2 |
| Core load aggregate from 1min | 3 |
| settle prefer 1min | 3–4 |
| Mock MIN_1.json optional | 4 |
| pytdx trade-pool backfill | 5 |
| Docs README/app/memo | 6 |
| No maCross change / no ClickHouse / no Tushare | Global Constraints |

## Placeholder / consistency self-review

- No TBD steps; method names: `saveMinutes1`, `hasEnoughOneMin`, `loadOneMin`, `klineSource`, `min1minBars`
- YAML keys: `kline-source`, `min-1min-bars` (Spring relaxed binding to camelCase)
- Table name fixed: `market_1min` (not `stock_kline_1min`) per approved dual-write naming in plan aligned with existing `market_*` prefix

---

## Execution handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-29-market-1min-dual-write.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks  
2. **Inline Execution** — run tasks in this session with executing-plans checkpoints  

Which approach?
