-- 一次性清理：已下线金叉对照画像的回测历史 / 分析 / 策略参数包
-- 目标：maCrossTrend / maCrossVolume / maCrossStrict
-- 应用启动时 RetiredMaCrossProfileCleanupService 也会幂等执行同等清理。

DELETE a FROM bt_backtest_analysis a
INNER JOIN bt_backtest_record r ON r.record_id = a.record_id
WHERE LOWER(TRIM(r.strategy_id)) IN ('macrosstrend', 'macrossvolume', 'macrossstrict');

DELETE FROM bt_backtest_record
WHERE LOWER(TRIM(strategy_id)) IN ('macrosstrend', 'macrossvolume', 'macrossstrict');

DELETE FROM strategy_param
WHERE LOWER(TRIM(strategy_id)) IN ('macrosstrend', 'macrossvolume', 'macrossstrict');
