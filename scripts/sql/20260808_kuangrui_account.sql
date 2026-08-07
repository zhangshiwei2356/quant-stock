-- 宽睿账号登录：主密钥表 + 账号表（密码密文）
-- 2026-08-08

CREATE TABLE IF NOT EXISTS `kuangrui_crypto_key` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `key_material` VARCHAR(128) NOT NULL COMMENT 'Base64 AES-256 key',
  `algo` VARCHAR(64) NOT NULL DEFAULT 'AES/GCM/NoPadding',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宽睿凭据加密主密钥（勿对外暴露）';

CREATE TABLE IF NOT EXISTS `kuangrui_account` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) NOT NULL,
  `password_cipher` VARCHAR(512) NOT NULL,
  `iv` VARCHAR(64) NOT NULL,
  `active` TINYINT NOT NULL DEFAULT 0,
  `last_login_at` DATETIME NULL,
  `last_login_ok` TINYINT NOT NULL DEFAULT 0,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kuangrui_account_username` (`username`),
  KEY `idx_kuangrui_account_active` (`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宽睿联调账号（密码 AES 密文）';
