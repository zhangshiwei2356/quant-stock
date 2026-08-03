# 模拟 K 线种子说明

目录：`classpath:data/kline/`

应用唯一物理行情表：`market_1min`（价额为元；`data_source`=MOCK/TDX/MDS）。空库启动时 `MockDataImporter`：

1. 优先导入 `MIN_1.json`（标记 `MOCK`）
2. 若无则将 `MIN_5.json` 拆成 5 根同价量分摊的 1 分钟 bar

主灌数请用：`python scripts/fetch_min1_tdx.py --from-pool`（标记 `TDX`）
存量升级：`scripts/sql/20260803_market_1min_data_source.sql`（或应用启动自动 ensure，存量标 TDX）

已删除未再灌库的 `DAY/WEEK/MONTH/MIN_15/30/60.json`。
