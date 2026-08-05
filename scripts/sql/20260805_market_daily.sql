-- 重建 market_daily：全市场日线选股层（与 market_1min 池内分钟层分工）
-- 用法：mysql -u... -p quant_stock < scripts/sql/20260805_market_daily.sql
-- 幂等：CREATE IF NOT EXISTS；应用启动 CoreMarketBarService 也会 ensure 建表
-- 注意：勿再执行旧版 schema 中的 DROP TABLE market_daily

USE quant_stock;

CREATE TABLE IF NOT EXISTS `market_daily` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL COMMENT '股票代码(6位)',
  `trade_date` DATE NOT NULL COMMENT '交易日',
  `open` DECIMAL(10,4) NOT NULL COMMENT '开盘价(元)',
  `high` DECIMAL(10,4) NOT NULL COMMENT '最高价(元)',
  `low` DECIMAL(10,4) NOT NULL COMMENT '最低价(元)',
  `close` DECIMAL(10,4) NOT NULL COMMENT '收盘价(元)',
  `volume` BIGINT NOT NULL COMMENT '成交量(股)',
  `amount` DECIMAL(16,4) DEFAULT NULL COMMENT '成交额(元)',
  `adj_flag` VARCHAR(8) NOT NULL DEFAULT 'NONE' COMMENT 'NONE=不复权 / QFQ=前复权(库内勿混用)',
  `data_source` VARCHAR(16) NOT NULL DEFAULT 'TDX' COMMENT 'TDX/EM/BAO/MOCK/…',
  `ingested_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '入库时间',
  UNIQUE KEY `idx_symbol_date` (`symbol`, `trade_date`),
  KEY `idx_date` (`trade_date`),
  KEY `idx_data_source` (`data_source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日线行情(全市场选股真相源;价额一律为元)';
