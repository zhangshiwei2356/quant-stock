# 模拟 K 线种子说明

目录：`classpath:data/kline/`

应用唯一物理行情表：`market_1min`（价额为元；`data_source`=MOCK/TDX/MDS）。空库启动时 `MockDataImporter`：

1. 优先导入 `MIN_1.json`（标记 `MOCK`）
2. 若无则将 `MIN_5.json` 拆成 5 根同价量分摊的 1 分钟 bar

区间：`meta.json` 的 `start`/`end` 为**离线生成时**相对 `end`（默认今天）回推一年，**不是**应用每次启动滑动。重刷：

```text
mvn -q -DskipTests compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator
# 或固定截止日：-Dexec.args=end=2026-08-04
```

主灌数请用：`python scripts/fetch_min1_tdx.py --from-pool`（标记 `TDX`）。  
存量升级：`scripts/sql/20260803_market_1min_data_source.sql`（或应用启动自动 ensure，存量标 TDX）。

只保留 `MIN_1` / `MIN_5`；更大周期由查询内存聚合。  
若库中已有该股 `market_1min`，启动不会覆盖——需清该股分钟数据后再灌 classpath 种子。
