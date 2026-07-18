# 模拟K线数据说明

目录：`classpath:data/kline/`

## 股票（近一年 2025-07-17 ~ 2026-07-17）

| 代码 | 名称 |
|------|------|
| 600036 | 招商银行 |
| 000001 | 平安银行 |
| 300059 | 东方财富 |

## 文件结构（对应分表）

```
kline/
  meta.json
  600036/
    MIN_1.json   # stock_bar_1min
    MIN_5.json
    MIN_15.json
    MIN_30.json
    MIN_60.json
    DAY.json
    WEEK.json
    MONTH.json
  000001/ ...
  300059/ ...
```

## JSON 格式

```json
{
  "stockCode": "600036",
  "period": "DAY",
  "table": "stock_bar_day",
  "fields": ["t","o","h","l","c","v"],
  "count": 262,
  "bars": [["2025-07-17 09:30:00", 35.01, 35.2, 34.9, 35.1, 12345], ...]
}
```

## 重新生成

```bash
mvn -q compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator
```
