-- market_1min：增加行情来源字段；存量标为 TDX（通达信）
-- 用法：mysql -u... -p quant_stock < scripts/sql/20260803_market_1min_data_source.sql
-- 幂等：列已存在则跳过（需人工判断；应用启动 CoreMarketBarService 也会 ensure）

USE quant_stock;

-- 来源字段：默认 TDX，存量行自动获得 TDX
SET @col_exists := (
  SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'market_1min' AND COLUMN_NAME = 'data_source'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `market_1min` ADD COLUMN `data_source` VARCHAR(16) NOT NULL DEFAULT ''TDX'' COMMENT ''行情来源: MOCK/TDX/MDS'' AFTER `amount`',
  'SELECT ''data_source already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
  SELECT COUNT(1) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'market_1min' AND COLUMN_NAME = 'ingested_at'
);
SET @sql := IF(@col_exists = 0,
  'ALTER TABLE `market_1min` ADD COLUMN `ingested_at` DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT ''入库时间'' AFTER `data_source`',
  'SELECT ''ingested_at already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
  SELECT COUNT(1) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'market_1min' AND INDEX_NAME = 'idx_data_source'
);
SET @sql := IF(@idx_exists = 0,
  'ALTER TABLE `market_1min` ADD KEY `idx_data_source` (`data_source`)',
  'SELECT ''idx_data_source already exists''');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 明确把存量（含空串）标为通达信
UPDATE `market_1min`
SET `data_source` = 'TDX'
WHERE `data_source` IS NULL OR TRIM(`data_source`) = '';
