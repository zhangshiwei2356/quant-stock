-- 一次性清理：已下线金叉对照画像的回测历史 / 分析 / 策略参数包 / 死激活配置
-- 目标：maCrossTrend / maCrossVolume / maCrossStrict（及指纹类名 *Strategy）
-- 应用启动时 RetiredMaCrossProfileCleanupService 也会幂等执行同等清理。

DELETE a FROM bt_backtest_analysis a
INNER JOIN bt_backtest_record r ON r.record_id = a.record_id
WHERE LOWER(TRIM(r.strategy_id)) IN (
  'macrosstrend', 'macrossvolume', 'macrossstrict',
  'macrosstrendstrategy', 'macrossvolumestrategy', 'macrossstrictstrategy'
);

DELETE FROM bt_backtest_record
WHERE LOWER(TRIM(strategy_id)) IN (
  'macrosstrend', 'macrossvolume', 'macrossstrict',
  'macrosstrendstrategy', 'macrossvolumestrategy', 'macrossstrictstrategy'
);

DELETE FROM strategy_param
WHERE LOWER(TRIM(strategy_id)) IN (
  'macrosstrend', 'macrossvolume', 'macrossstrict',
  'macrosstrendstrategy', 'macrossvolumestrategy', 'macrossstrictstrategy'
);

-- 若纸面激活仍指向已下线策略，重置为 maCross
UPDATE system_config
SET config_value = 'maCross',
    description = '纸面激活策略（下线画像后重置为 maCross）',
    updated_at = CURRENT_TIMESTAMP
WHERE config_key = 'quant.active-strategy'
  AND LOWER(TRIM(config_value)) IN (
    'macrosstrend', 'macrossvolume', 'macrossstrict',
    'macrosstrendstrategy', 'macrossvolumestrategy', 'macrossstrictstrategy'
  );
