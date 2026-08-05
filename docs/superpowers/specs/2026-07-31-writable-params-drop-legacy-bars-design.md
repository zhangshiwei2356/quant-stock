# 设计：运维参数白名单可写 + 删除 legacy 分表

**日期：** 2026-07-31  
**状态：** 已实现  
**范围：** 过滤/仓位/止损类参数热写；删除 `stock_bar_*` / `BarStorageService` 主路径依赖

## 参数可写

- 配置键：`system_config` 中 `quant.prop.{camelCase}`（与 yml 字段名一致）
- 白名单：过滤、仓位、止损/金字塔、常用成本与 ADV/涨跌停保护等（不含 tradeMode/db/marketMode/apiKey/调度）
- API：`POST /api/ops/params` body `{ "updates": { "rsiBuyMax": "55" }, "confirm": true }`
- 启动：`ApplicationReady` 加载 `quant.prop.*` 覆盖 `QuantProperties`
- 视图：`GET /api/ops/params` 白名单项带 `writable`/`type`；指纹随 props 变化
- UI：运维运行参数可编辑 + 确认保存

## Legacy 删除

- 删除：`BarStorageService`、`StockBarMapper(+xml)`、`StockBarDO`、`BarAggregateMetaMapper(+xml)`
- `MarketDataService` 去掉分表分支
- `BarPeriod.tableName` 保留字段但标注废弃（避免大范围改枚举）

## 非目标

- 按策略隔离参数包（`strategy.maCross.*`）本期不做
- 宽睿 / MDS
