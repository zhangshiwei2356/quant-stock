-- ============================================================
-- Quant Stock 核心表结构（日线 + 5分钟 + 因子 + 交易 + 回测）
-- 库名：quant_stock  |  MySQL 5.7+ / 8.0+
-- 股票代码统一使用 6 位裸码（000001），与现有 Java 一致
-- ============================================================

CREATE DATABASE IF NOT EXISTS quant_stock DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE quant_stock;

-- ---------- 模块一：基础信息 ----------
CREATE TABLE IF NOT EXISTS `stock_basic` (
  `id` INT AUTO_INCREMENT PRIMARY KEY COMMENT '自增主键',
  `symbol` VARCHAR(10) NOT NULL COMMENT '股票代码(6位)',
  `name` VARCHAR(20) NOT NULL COMMENT '股票简称',
  `market` TINYINT NOT NULL COMMENT '1主板 2创业板 3科创板 4北交所',
  `industry` VARCHAR(50) DEFAULT NULL COMMENT '所属行业',
  `list_date` DATE NOT NULL COMMENT '上市日期',
  `delist_date` DATE DEFAULT NULL COMMENT '退市日期',
  `is_st` TINYINT DEFAULT 0 COMMENT '是否ST',
  `status` TINYINT DEFAULT 1 COMMENT '1正常 0停牌/退市',
  UNIQUE KEY `idx_symbol` (`symbol`),
  KEY `idx_status` (`status`),
  KEY `idx_market` (`market`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='股票基本信息表';

-- ---------- 模块二：行情（仅日线 + 5分钟） ----------
CREATE TABLE IF NOT EXISTS `market_daily` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL COMMENT '股票代码',
  `trade_date` DATE NOT NULL COMMENT '交易日期',
  `open` DECIMAL(10,4) NOT NULL,
  `high` DECIMAL(10,4) NOT NULL,
  `low` DECIMAL(10,4) NOT NULL,
  `close` DECIMAL(10,4) NOT NULL,
  `volume` BIGINT NOT NULL COMMENT '成交量(股)',
  `amount` DECIMAL(16,4) DEFAULT NULL COMMENT '成交额(元)',
  `turnover_rate` DECIMAL(8,4) DEFAULT NULL,
  `limit_up` DECIMAL(10,4) DEFAULT NULL,
  `limit_down` DECIMAL(10,4) DEFAULT NULL,
  UNIQUE KEY `idx_symbol_date` (`symbol`, `trade_date`),
  KEY `idx_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日线行情表(前复权)';

CREATE TABLE IF NOT EXISTS `market_minute` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL,
  `trade_time` DATETIME NOT NULL COMMENT '5分钟K起始时间',
  `open` DECIMAL(10,4) NOT NULL,
  `high` DECIMAL(10,4) NOT NULL,
  `low` DECIMAL(10,4) NOT NULL,
  `close` DECIMAL(10,4) NOT NULL,
  `volume` BIGINT NOT NULL,
  `amount` DECIMAL(16,4) DEFAULT NULL,
  UNIQUE KEY `idx_symbol_time` (`symbol`, `trade_time`),
  KEY `idx_time` (`trade_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='5分钟线行情表';

-- ---------- 模块三：因子缓存 ----------
CREATE TABLE IF NOT EXISTS `factor_daily` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL,
  `trade_date` DATE NOT NULL,
  `ma5` DECIMAL(10,4) DEFAULT NULL,
  `ma20` DECIMAL(10,4) DEFAULT NULL,
  `ma60` DECIMAL(10,4) DEFAULT NULL,
  `rsi14` DECIMAL(10,4) DEFAULT NULL,
  `atr14` DECIMAL(10,4) DEFAULT NULL,
  `adx` DECIMAL(10,4) DEFAULT NULL,
  `volume_ma20` DECIMAL(16,4) DEFAULT NULL,
  `ma60_up` TINYINT DEFAULT NULL,
  `is_volume_break` TINYINT DEFAULT NULL,
  UNIQUE KEY `idx_symbol_date` (`symbol`, `trade_date`),
  KEY `idx_date` (`trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='日频技术因子缓存表';

CREATE TABLE IF NOT EXISTS `factor_minute` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL,
  `trade_time` DATETIME NOT NULL,
  `ma5` DECIMAL(10,4) DEFAULT NULL,
  `ma20` DECIMAL(10,4) DEFAULT NULL,
  `atr14` DECIMAL(10,4) DEFAULT NULL,
  UNIQUE KEY `idx_symbol_time` (`symbol`, `trade_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='分钟频因子(可选)';

-- ---------- 模块四：交易执行 ----------
CREATE TABLE IF NOT EXISTS `trade_orders` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `order_id` VARCHAR(32) NOT NULL,
  `account_id` VARCHAR(32) NOT NULL DEFAULT 'LIVE' COMMENT '账户/回测run隔离',
  `symbol` VARCHAR(10) NOT NULL,
  `signal_date` DATE NOT NULL,
  `execution_date` DATE DEFAULT NULL,
  `order_type` TINYINT NOT NULL COMMENT '1首开 2加仓30 3加仓20 4死叉 5止损 6止盈 7熔断',
  `stage` TINYINT DEFAULT 0,
  `price` DECIMAL(10,4) NOT NULL,
  `volume` INT NOT NULL,
  `filled_volume` INT DEFAULT 0,
  `filled_price` DECIMAL(10,4) DEFAULT NULL,
  `fee` DECIMAL(12,4) DEFAULT NULL,
  `status` TINYINT NOT NULL DEFAULT 1,
  `expire_date` DATE DEFAULT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `idx_order_id` (`order_id`),
  KEY `idx_account_symbol` (`account_id`, `symbol`, `status`),
  KEY `idx_execution_date` (`execution_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='交易委托记录表';

CREATE TABLE IF NOT EXISTS `trade_positions` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `account_id` VARCHAR(32) NOT NULL DEFAULT 'LIVE',
  `symbol` VARCHAR(10) NOT NULL,
  `entry_date` DATE NOT NULL,
  `current_volume` INT NOT NULL,
  `avg_cost` DECIMAL(10,4) NOT NULL,
  `highest_price_since_entry` DECIMAL(10,4) DEFAULT NULL,
  `stop_price` DECIMAL(10,4) DEFAULT NULL,
  `trail_price` DECIMAL(10,4) DEFAULT NULL,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `idx_account_symbol` (`account_id`, `symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当前持仓快照表';

CREATE TABLE IF NOT EXISTS `trade_position_lots` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `account_id` VARCHAR(32) NOT NULL DEFAULT 'LIVE',
  `symbol` VARCHAR(10) NOT NULL,
  `open_date` DATE NOT NULL COMMENT '开仓日(T+1可卖判定)',
  `volume` INT NOT NULL,
  `cost` DECIMAL(10,4) NOT NULL COMMENT '含买佣成本',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_account_symbol` (`account_id`, `symbol`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='持仓批次(T+1)';

CREATE TABLE IF NOT EXISTS `trade_cashflows` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `account_id` VARCHAR(32) NOT NULL DEFAULT 'LIVE',
  `trade_date` DATE NOT NULL,
  `cash` DECIMAL(16,4) NOT NULL,
  `market_value` DECIMAL(16,4) NOT NULL,
  `total_equity` DECIMAL(16,4) NOT NULL,
  `peak_equity` DECIMAL(16,4) DEFAULT NULL,
  `daily_pnl` DECIMAL(16,4) DEFAULT NULL,
  `daily_pnl_rate` DECIMAL(8,4) DEFAULT NULL,
  `drawdown_rate` DECIMAL(8,4) DEFAULT NULL,
  `consecutive_loss_count` INT DEFAULT 0,
  UNIQUE KEY `idx_account_date` (`account_id`, `trade_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账户资金与权益日表';

-- ---------- 模块五：配置与风控 ----------
CREATE TABLE IF NOT EXISTS `system_config` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `config_key` VARCHAR(50) NOT NULL,
  `config_value` VARCHAR(100) NOT NULL,
  `type` TINYINT DEFAULT 1,
  `description` VARCHAR(200) DEFAULT NULL,
  `updated_at` DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `idx_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统动态配置表';

CREATE TABLE IF NOT EXISTS `risk_control_log` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `account_id` VARCHAR(32) NOT NULL DEFAULT 'LIVE',
  `log_date` DATE NOT NULL,
  `symbol` VARCHAR(10) DEFAULT NULL,
  `rule_type` VARCHAR(30) NOT NULL,
  `trigger_value` DECIMAL(16,4) DEFAULT NULL,
  `action_taken` VARCHAR(100) NOT NULL,
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY `idx_log_date` (`log_date`),
  KEY `idx_rule_type` (`rule_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='风控规则触发日志表';

-- ---------- 模块六：回测历史与分析 ----------
CREATE TABLE IF NOT EXISTS `bt_backtest_record` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `record_id` VARCHAR(32) NOT NULL COMMENT '业务UUID',
  `kind` VARCHAR(16) NOT NULL COMMENT 'SINGLE / PORTFOLIO',
  `saved_at` DATETIME NOT NULL,
  `stock_code` VARCHAR(64) DEFAULT NULL COMMENT '单股代码；组合可为空',
  `stock_codes_json` TEXT DEFAULT NULL COMMENT '组合股票列表JSON',
  `period` VARCHAR(16) DEFAULT NULL,
  `back_start` VARCHAR(32) DEFAULT NULL,
  `back_end` VARCHAR(32) DEFAULT NULL,
  `init_capital` DECIMAL(18,4) DEFAULT NULL,
  `final_asset` DECIMAL(18,4) DEFAULT NULL,
  `total_rate` DECIMAL(12,6) DEFAULT NULL,
  `max_drawdown` DECIMAL(12,6) DEFAULT NULL,
  `total_trade_num` INT DEFAULT NULL,
  `win_rate` DECIMAL(12,6) DEFAULT NULL,
  `trade_stats_json` MEDIUMTEXT DEFAULT NULL COMMENT 'BackTestTradeStats JSON',
  `trades_json` LONGTEXT DEFAULT NULL COMMENT '成交明细JSON',
  `stock_results_json` LONGTEXT DEFAULT NULL COMMENT '组合分股结果JSON',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_record_id` (`record_id`),
  KEY `idx_kind_code` (`kind`, `stock_code`),
  KEY `idx_saved_at` (`saved_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测历史记录表';

CREATE TABLE IF NOT EXISTS `bt_backtest_analysis` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `record_id` VARCHAR(32) NOT NULL COMMENT '对应 bt_backtest_record.record_id',
  `kind` VARCHAR(16) NOT NULL COMMENT 'SINGLE / PORTFOLIO',
  `saved_at` DATETIME NOT NULL,
  `stock_code` VARCHAR(64) DEFAULT NULL,
  `stock_codes_json` TEXT DEFAULT NULL,
  `period` VARCHAR(16) DEFAULT NULL,
  `back_start` VARCHAR(32) DEFAULT NULL,
  `back_end` VARCHAR(32) DEFAULT NULL,
  `init_capital` DECIMAL(18,4) DEFAULT NULL,
  `final_asset` DECIMAL(18,4) DEFAULT NULL,
  `total_trade_num` INT DEFAULT NULL,
  `event_count` INT DEFAULT NULL,
  `summary` TEXT DEFAULT NULL,
  `events_json` LONGTEXT DEFAULT NULL COMMENT 'AnalysisEvent列表JSON',
  `created_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_record_id` (`record_id`),
  KEY `idx_kind_code` (`kind`, `stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回测分析数据表';

-- 兼容旧版分表（可选保留，新逻辑以 market_* 为准）
CREATE TABLE IF NOT EXISTS `bar_aggregate_meta` (
  `stock_code`      varchar(10)  NOT NULL,
  `period`          varchar(16)  NOT NULL,
  `last_agg_time`   datetime     DEFAULT NULL,
  `source_max_time` datetime     DEFAULT NULL,
  `updated_at`      datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`stock_code`, `period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合元数据(兼容)';

-- 初始化配置（忽略重复）
INSERT IGNORE INTO `system_config` (`config_key`, `config_value`, `type`, `description`) VALUES
('base_atr', '0.05', 1, 'ATR调节基准系数'),
('single_stock_limit', '0.3', 1, '单只股票资金上限'),
('atr_min_clamp', '0.2', 1, 'ATR调节系数下限'),
('atr_max_clamp', '1.5', 1, 'ATR调节系数上限'),
('buy_slippage', '0.001', 1, '买入滑点'),
('sell_slippage', '0.001', 1, '卖出滑点'),
('max_total_position_ratio', '0.8', 1, '总仓权益上限');
