# 模拟 / 扩展 K 线数据说明（种子文件）

目录：`classpath:data/kline/`

应用默认连接 MySQL `quant_stock`，行情物理层为表：

- `market_1min`（1 分钟原始层；`quant.kline-source=auto` 且根数 ≥ 240 时查询优先从此聚合）
- `market_daily`（日线缓存/回退）
- `market_minute`（5 分钟缓存/回退）

**启动时若某代码尚无日线/分钟**，`MockDataImporter` 会从本目录的 `DAY.json` / `MIN_5.json` **增量导入**。  
仅有日线、且无 `MIN_5.json` 的「公开接口批量样本」视为已覆盖，不会每次启动重导。

## 股票清单

### 原有模拟样本（近一年 2025-07-17 ~ 2026-07-17）

| 代码 | 名称 |
|------|------|
| 600036 | 招商银行 |
| 000001 | 平安银行 |
| 300059 | 东方财富 |
| 601318 | 中国平安 |
| 000858 | 五粮液 |

### 公开接口批量扩展（约 100 只 · 近一年日线）

- 脚本：`python scripts/fetch_stocks_batch.py --limit 100 --datalen 280 --skip-minute`
- 清单结果：`scripts/batch100_universe.json`
- 数据：写入 `market_daily` + `stock_basic`；**日线金叉回测可用**
- 可选合成分钟：去掉 `--skip-minute`（更慢、库更大）
- 小批量：`python scripts/fetch_extra_stocks.py`

## 通达信 1 分钟回填

- 脚本：`python scripts/fetch_min1_tdx.py --codes 600036 --sleep 0.2`
- 默认优先读取 `trade_pool` 中 `status=1` 的标的；无活动标的时需显式传入 `--codes`，也可用 `--from-pool` 强制读取目标池。
- 写入 `market_1min` 原始分钟层，并默认聚合 upsert `market_minute`（5 分钟）和 `market_daily`；传入 `--skip-cache` 时仅写原始层。
- TDX 公开节点的完整 OHLC 分钟历史通常约 90 个交易日，实际深度随节点和标的而变化。

## 重新导入

```bash
python scripts/fetch_stocks_batch.py --limit 100 --datalen 280 --skip-minute --sleep 0.6
```
