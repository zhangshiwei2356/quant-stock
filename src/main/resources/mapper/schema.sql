-- ============================================================
-- Quant Stock K线分层存储：原始层(1min) + 聚合层(多周期)
-- 库名：quant_stock
-- ============================================================

CREATE DATABASE IF NOT EXISTS quant_stock DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE quant_stock;

-- ---------- 统一建表模板说明 ----------
-- 字段规范：stock_code + bar_time 联合主键；open/high/low/close/volume[/amount]
-- 仅 stock_bar_1min 为直接写入入口，其余表由聚合任务生成

CREATE TABLE IF NOT EXISTS `stock_bar_1min` (
  `stock_code` varchar(10)  NOT NULL COMMENT '股票代码',
  `bar_time`   datetime     NOT NULL COMMENT 'K线起始时间',
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL COMMENT '成交额(可选)',
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='原始层-1分钟K(唯一真相源)';

CREATE TABLE IF NOT EXISTS `stock_bar_5min` (
  `stock_code` varchar(10)  NOT NULL,
  `bar_time`   datetime     NOT NULL,
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合层-5分钟K';

CREATE TABLE IF NOT EXISTS `stock_bar_15min` (
  `stock_code` varchar(10)  NOT NULL,
  `bar_time`   datetime     NOT NULL,
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合层-15分钟K';

CREATE TABLE IF NOT EXISTS `stock_bar_30min` (
  `stock_code` varchar(10)  NOT NULL,
  `bar_time`   datetime     NOT NULL,
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合层-30分钟K';

CREATE TABLE IF NOT EXISTS `stock_bar_60min` (
  `stock_code` varchar(10)  NOT NULL,
  `bar_time`   datetime     NOT NULL,
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合层-60分钟K';

CREATE TABLE IF NOT EXISTS `stock_bar_day` (
  `stock_code` varchar(10)  NOT NULL,
  `bar_time`   datetime     NOT NULL COMMENT '交易日 00:00:00',
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合层-日K';

CREATE TABLE IF NOT EXISTS `stock_bar_week` (
  `stock_code` varchar(10)  NOT NULL,
  `bar_time`   datetime     NOT NULL COMMENT '当周周一 00:00:00',
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合层-周K';

CREATE TABLE IF NOT EXISTS `stock_bar_month` (
  `stock_code` varchar(10)  NOT NULL,
  `bar_time`   datetime     NOT NULL COMMENT '当月1日 00:00:00',
  `open`       decimal(10,4) DEFAULT NULL,
  `high`       decimal(10,4) DEFAULT NULL,
  `low`        decimal(10,4) DEFAULT NULL,
  `close`      decimal(10,4) DEFAULT NULL,
  `volume`     bigint        DEFAULT NULL,
  `amount`     decimal(18,2) DEFAULT NULL,
  PRIMARY KEY (`stock_code`, `bar_time`),
  KEY `idx_time` (`bar_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合层-月K';

-- 聚合版本校验：记录各股票各周期最近聚合时间，用于判断是否需重聚合
CREATE TABLE IF NOT EXISTS `bar_aggregate_meta` (
  `stock_code`      varchar(10)  NOT NULL,
  `period`          varchar(16)  NOT NULL COMMENT 'MIN_5/MIN_15/.../DAY/WEEK/MONTH',
  `last_agg_time`   datetime     DEFAULT NULL COMMENT '聚合覆盖到的1min最后bar_time',
  `source_max_time` datetime     DEFAULT NULL COMMENT '聚合时1min表最大bar_time',
  `updated_at`      datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`stock_code`, `period`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='聚合元数据/版本校验';

-- ---------- 业务表（订单/持仓/回测） ----------
CREATE TABLE IF NOT EXISTS `bt_order` (
  `id`          bigint PRIMARY KEY AUTO_INCREMENT,
  `order_id`    varchar(64)  NOT NULL,
  `stock_code`  varchar(16)  NOT NULL,
  `side`        varchar(8)   NOT NULL,
  `price`       decimal(16,4) NOT NULL,
  `volume`      int          NOT NULL,
  `status`      varchar(16)  DEFAULT 'FILLED',
  `created_at`  datetime     DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_order_id` (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `bt_position` (
  `id`          bigint PRIMARY KEY AUTO_INCREMENT,
  `stock_code`  varchar(16)  NOT NULL,
  `volume`      int          NOT NULL DEFAULT 0,
  `avg_cost`    decimal(16,4) DEFAULT 0,
  `updated_at`  datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_code` (`stock_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `bt_backtest_record` (
  `id`            bigint PRIMARY KEY AUTO_INCREMENT,
  `stock_code`    varchar(64),
  `init_capital`  decimal(18,2),
  `final_asset`   decimal(18,2),
  `total_rate`    decimal(12,6),
  `max_drawdown`  decimal(12,6),
  `trade_num`     int,
  `win_rate`      decimal(12,6),
  `created_at`    datetime DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
