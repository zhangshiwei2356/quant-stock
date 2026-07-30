# 模拟 K 线种子说明

目录：`classpath:data/kline/`

应用唯一物理行情表：`market_1min`。空库启动时 `MockDataImporter`：

1. 优先导入 `MIN_1.json`
2. 若无则将 `MIN_5.json` 拆成 5 根同价量分摊的 1 分钟 bar

主灌数请用：`python scripts/fetch_min1_tdx.py --from-pool`

已删除未再灌库的 `DAY/WEEK/MONTH/MIN_15/30/60.json`。
