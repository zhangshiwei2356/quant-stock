# 模拟 / 扩展 K 线数据说明（种子文件）

目录：`classpath:data/kline/`

应用默认连接 MySQL `quant_stock`，**唯一物理行情表**：

- `market_1min`（1 分钟真相源；5/15/30/60/日/周/月由应用内存聚合）
- `market_daily` / `market_minute`：表结构兼容保留，**应用主路径不再读写**

**启动时若某代码尚无 `market_1min`**，`MockDataImporter` 会优先导入 `MIN_1.json`；若无则将 `MIN_5.json` 拆成 5 根同价量分摊的 1 分钟 bar。

## 股票清单

### 原有模拟样本（近一年 2025-07-17 ~ 2026-07-17）

| 代码 | 名称 |
|------|------|
| 600036 | 招商银行 |
| 000001 | 平安银行 |
| 300059 | 东方财富 |
| 601318 | 中国平安 |
| 000858 | 五粮液 |

## 通达信 1 分钟回填（主灌数路径）

- 脚本：`python scripts/fetch_min1_tdx.py --codes 600036 --sleep 0.2`
- 默认优先读取 `trade_pool` 中 `status=1` 的标的；无活动标的时需显式传入 `--codes`，也可用 `--from-pool` 强制读取目标池。
- **只写入** `market_1min`；更大周期由应用聚合。
- TDX 公开节点的完整 OHLC 分钟历史通常约 90 个交易日，实际深度随节点和标的而变化。

## 历史脚本说明

- `scripts/fetch_stocks_batch.py` 等仍可能写旧 `market_daily`，但**回测/图表已不再读取该表**；需要行情请改用 TDX 1 分钟回填。
