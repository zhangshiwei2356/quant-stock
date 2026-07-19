# 模拟K线数据说明（种子文件）

目录：`classpath:data/kline/`

应用默认连接 MySQL `quant_stock`，行情真相源为表：

- `market_daily`（日线）
- `market_minute`（5 分钟）

**启动时若库中无数据**，`MockDataImporter` 会自动从本目录的 `DAY.json` / `MIN_5.json` 导入，并写入 `stock_basic`、`factor_daily`。

运行时查询**不再优先读 JSON**（`quant.market-mode=db`）。

## 股票（近一年 2025-07-17 ~ 2026-07-17）

| 代码 | 名称 |
|------|------|
| 600036 | 招商银行 |
| 000001 | 平安银行 |
| 300059 | 东方财富 |
| 601318 | 中国平安 |
| 000858 | 五粮液 |

## 重新导入

- 启动时：若某代码在库中尚无日线/分钟，会**增量导入**该代码（不必清空全库）。
- 清空后重启可全量重导：

```sql
TRUNCATE market_daily;
TRUNCATE market_minute;
TRUNCATE factor_daily;
TRUNCATE stock_basic;
```

重新生成 JSON 种子：

```bash
mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator
# 仅新增两只
mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator -Dexec.args="only=601318,000858"
```
