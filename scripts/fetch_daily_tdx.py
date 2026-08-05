# -*- coding: utf-8 -*-
"""从通达信拉取日 K，并回填 quant_stock.market_daily（data_source=TDX, adj_flag=NONE）。

用法：
    python scripts/fetch_daily_tdx.py --codes 600036,000001
    python scripts/fetch_daily_tdx.py --from-basic --incremental
    python scripts/fetch_daily_tdx.py --from-basic --years 1 --workers 4

默认 --from-basic 时会先同步 stock_basic 全市场列表（约 5000+，见 sync_stock_basic.py），
否则只会处理库内已有 status=1 的标的（可能只有演示/抽样几十上百只）。

默认 --incremental：
  · 该股无日线 → 补最近 --years 年（默认 1）
  · 已有日线且 MAX(trade_date) 距今 ≤ --skip-fresh-days → **直接跳过**（不调 TDX）
  · 已有日线但未齐 → 自 MAX(trade_date) 前几天起重拉并 upsert

默认 --workers 4：多连接并行拉 TDX（每线程独立连接；过大易被行情源限流）。

依赖：pip install pytdx pymysql
"""
from __future__ import annotations

import argparse
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# Windows 下管道到 Java 时强制 UTF-8，避免进度中文乱码
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

import pymysql
from pytdx.hq import TdxHq_API

# 允许直接 import 同目录 sync_stock_basic
_SCRIPTS_DIR = Path(__file__).resolve().parent
if str(_SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS_DIR))
import sync_stock_basic  # noqa: E402

DB = dict(
    host="127.0.0.1",
    port=3306,
    user="root",
    password="123456",
    database="quant_stock",
    charset="utf8mb4",
)

TDX_HOSTS = [
    ("招商", "218.75.126.9", 7709),
    ("腾讯", "119.147.212.81", 7709),
    ("华泰", "60.12.136.159", 7709),
]
BATCH_SIZE = 500
TDX_KTYPE_DAY = 4
INCR_OVERLAP_DAYS = 5
Bar = Tuple[str, str, float, float, float, float, int, float]

UPSERT_DAILY = """
    INSERT INTO market_daily(
      symbol, trade_date, open, high, low, close, volume, amount, adj_flag, data_source, ingested_at)
    VALUES(%s,%s,%s,%s,%s,%s,%s,%s,'NONE','TDX',NOW())
    ON DUPLICATE KEY UPDATE
      open=VALUES(open), high=VALUES(high), low=VALUES(low), close=VALUES(close),
      volume=VALUES(volume), amount=VALUES(amount),
      adj_flag=VALUES(adj_flag), data_source=VALUES(data_source), ingested_at=NOW()
"""

ENSURE_TABLE = """
CREATE TABLE IF NOT EXISTS `market_daily` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `symbol` VARCHAR(10) NOT NULL,
  `trade_date` DATE NOT NULL,
  `open` DECIMAL(10,4) NOT NULL,
  `high` DECIMAL(10,4) NOT NULL,
  `low` DECIMAL(10,4) NOT NULL,
  `close` DECIMAL(10,4) NOT NULL,
  `volume` BIGINT NOT NULL,
  `amount` DECIMAL(16,4) DEFAULT NULL,
  `adj_flag` VARCHAR(8) NOT NULL DEFAULT 'NONE',
  `data_source` VARCHAR(16) NOT NULL DEFAULT 'TDX',
  `ingested_at` DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY `idx_symbol_date` (`symbol`, `trade_date`),
  KEY `idx_date` (`trade_date`),
  KEY `idx_data_source` (`data_source`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
"""

_print_lock = threading.Lock()
_db_lock = threading.Lock()


def log(msg: str, *, err: bool = False) -> None:
    with _print_lock:
        print(msg, file=sys.stderr if err else sys.stdout, flush=True)


def tdx_market(code: str) -> int:
    return 1 if code.startswith(("5", "6", "9")) else 0


def normalize_codes(raw_codes: str | None) -> List[str]:
    if not raw_codes:
        return []
    codes: List[str] = []
    for code in raw_codes.split(","):
        code = code.strip()
        if not code:
            continue
        if not (code.isdigit() and len(code) == 6):
            raise ValueError(f"非法股票代码：{code}")
        if code not in codes:
            codes.append(code)
    return codes


def ensure_table() -> None:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(ENSURE_TABLE)
        conn.commit()
    finally:
        conn.close()


def basic_codes() -> List[str]:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT symbol FROM stock_basic WHERE status=1 ORDER BY symbol"
            )
            return normalize_codes(",".join(str(row[0]) for row in cur.fetchall()))
    finally:
        conn.close()


def load_max_trade_dates(codes: Sequence[str]) -> Dict[str, date]:
    """一次查出标的最新日线日期，避免逐只开连接。"""
    out: Dict[str, date] = {}
    if not codes:
        return out
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(ENSURE_TABLE)
            # 分批 IN，避免 SQL 过长
            chunk = 800
            for i in range(0, len(codes), chunk):
                part = list(codes[i : i + chunk])
                placeholders = ",".join(["%s"] * len(part))
                cur.execute(
                    f"SELECT symbol, MAX(trade_date) FROM market_daily "
                    f"WHERE symbol IN ({placeholders}) GROUP BY symbol",
                    part,
                )
                for symbol, max_d in cur.fetchall():
                    if max_d is None:
                        continue
                    if isinstance(max_d, datetime):
                        out[str(symbol)] = max_d.date()
                    elif isinstance(max_d, date):
                        out[str(symbol)] = max_d
                    else:
                        out[str(symbol)] = datetime.strptime(str(max_d)[:10], "%Y-%m-%d").date()
        return out
    finally:
        conn.close()


def is_fresh(max_d: Optional[date], skip_fresh_days: int) -> bool:
    """日线已齐：最新交易日距今不超过 skip_fresh_days 个日历日则跳过拉网。"""
    if max_d is None or skip_fresh_days < 0:
        return False
    return max_d >= date.today() - timedelta(days=skip_fresh_days)


def resolve_since(
    code: str,
    years: float,
    incremental: bool,
    max_d: Optional[date],
) -> Tuple[date, str]:
    full_since = date.today() - timedelta(days=int(365 * max(years, 0.1)))
    if not incremental:
        return full_since, "full"
    if max_d is None:
        return full_since, "full"
    incr_since = max_d - timedelta(days=INCR_OVERLAP_DAYS)
    if incr_since < full_since:
        incr_since = full_since
    return incr_since, "incr"


def connect_tdx() -> TdxHq_API:
    api = TdxHq_API(raise_exception=False)
    for name, host, port in TDX_HOSTS:
        try:
            if api.connect(host, port, time_out=6):
                return api
        except Exception as exc:
            log(f"TDX connect failed {name}: {exc}", err=True)
    raise RuntimeError("无法连接任何 TDX 行情服务器")


def bar_date(bar: dict[str, Any]) -> str:
    value = bar.get("datetime")
    if value:
        text = str(value)[:10]
        datetime.strptime(text, "%Y-%m-%d")
        return text
    return "%04d-%02d-%02d" % (bar["year"], bar["month"], bar["day"])


def volumes_are_lots(volumes: Iterable[int]) -> bool:
    values = [volume for volume in volumes if volume > 0]
    return bool(values) and all(v % 100 == 0 for v in values) and statistics.median(values) <= 10000


def fetch_tdx_day_bars(api: TdxHq_API, code: str, since: date) -> List[Bar]:
    raw: List[dict[str, Any]] = []
    start = 0
    while True:
        bars = api.get_security_bars(TDX_KTYPE_DAY, tdx_market(code), code, start, 800)
        if not bars:
            break
        raw.extend(bars)
        start += len(bars)
        if len(bars) < 800:
            break
        oldest = bar_date(bars[-1])
        if datetime.strptime(oldest, "%Y-%m-%d").date() < since:
            break

    multiplier = 100 if volumes_are_lots(int(float(b.get("vol") or 0)) for b in raw) else 1

    unique: dict[str, Bar] = {}
    for bar in raw:
        trade_date = bar_date(bar)
        if datetime.strptime(trade_date, "%Y-%m-%d").date() < since:
            continue
        close = round(float(bar["close"]), 4)
        volume = int(round(float(bar.get("vol") or 0) * multiplier))
        amount = bar.get("amount")
        if amount is None:
            amount = close * volume
        else:
            amount = float(amount)
        unique[trade_date] = (
            code,
            trade_date,
            round(float(bar["open"]), 4),
            round(float(bar["high"]), 4),
            round(float(bar["low"]), 4),
            close,
            volume,
            round(float(amount), 4),
        )
    return [unique[key] for key in sorted(unique)]


def upsert_symbol(code: str, bars: Sequence[Bar]) -> int:
    if not bars:
        return 0
    # 多线程写库串行化，避免连接风暴；批量仍按股提交
    with _db_lock:
        conn = pymysql.connect(**DB)
        try:
            with conn.cursor() as cur:
                for index in range(0, len(bars), BATCH_SIZE):
                    cur.executemany(UPSERT_DAILY, bars[index : index + BATCH_SIZE])
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
    return len(bars)


def process_one(
    api: TdxHq_API,
    code: str,
    years: float,
    incremental: bool,
    max_d: Optional[date],
    skip_fresh_days: int,
) -> Tuple[str, str, int, Optional[str]]:
    """
    返回 (action, mode, written, error)
    action: skip_fresh | empty | wrote | fail
    """
    if incremental and is_fresh(max_d, skip_fresh_days):
        return "skip_fresh", "skip", 0, None
    try:
        since, mode = resolve_since(code, years, incremental, max_d)
        bars = fetch_tdx_day_bars(api, code, since)
        n = upsert_symbol(code, bars)
        if n == 0:
            return "empty", mode, 0, None
        return "wrote", mode, n, None
    except Exception as exc:
        return "fail", "err", 0, str(exc)


def worker_run(
    worker_id: int,
    codes: Sequence[str],
    max_dates: Dict[str, date],
    years: float,
    incremental: bool,
    skip_fresh_days: int,
    sleep_sec: float,
    total: int,
    counter: List[int],
    counter_lock: threading.Lock,
    failures: List[str],
    stats: Dict[str, int],
) -> None:
    api = connect_tdx()
    try:
        for code in codes:
            max_d = max_dates.get(code)
            action, mode, n, err = process_one(
                api, code, years, incremental, max_d, skip_fresh_days
            )
            with counter_lock:
                counter[0] += 1
                index = counter[0]
                if action == "skip_fresh":
                    stats["skip_fresh"] += 1
                elif action == "empty":
                    stats["empty"] += 1
                elif action == "wrote":
                    stats["wrote"] += 1
                elif action == "fail":
                    stats["fail"] += 1
                    failures.append(code)

            mode_cn = {
                "skip": "已齐跳过",
                "incr": "增量",
                "full": "全量",
                "err": "失败",
            }.get(mode, mode)

            if action == "skip_fresh":
                # 已齐跳过：每 50 只打一行，避免刷屏；尾部必打
                if index % 50 == 0 or index == total:
                    log(
                        f"[{index}/{total}] {code} · 已齐跳过 · 最新 {max_d} "
                        f"(累计跳过 {stats['skip_fresh']})",
                    )
            elif action == "empty":
                log(f"[{index}/{total}] {code} · 无新数据 · {mode_cn}")
            elif action == "wrote":
                log(f"[{index}/{total}] {code} · 写入 {n} 根日线 · {mode_cn}")
            else:
                log(f"[{index}/{total}] {code} · 失败 {err}", err=True)

            if sleep_sec > 0 and action not in ("skip_fresh",):
                time.sleep(sleep_sec)
    finally:
        try:
            api.disconnect()
        except Exception:
            pass


def split_chunks(codes: Sequence[str], workers: int) -> List[List[str]]:
    workers = max(1, workers)
    if workers == 1 or len(codes) <= 1:
        return [list(codes)]
    # 轮询分片，让「已齐」与「待补」较均匀
    buckets: List[List[str]] = [[] for _ in range(workers)]
    for i, code in enumerate(codes):
        buckets[i % workers].append(code)
    return [b for b in buckets if b]


def main() -> int:
    parser = argparse.ArgumentParser(description="TDX 日线回填（market_daily）")
    parser.add_argument("--codes", help="逗号分隔的 6 位股票代码")
    parser.add_argument(
        "--from-basic",
        action="store_true",
        help="从 stock_basic status=1 读取全市场代码",
    )
    parser.add_argument(
        "--years",
        type=float,
        default=1.0,
        help="无日线时回溯年数（默认 1）",
    )
    parser.add_argument(
        "--incremental",
        dest="incremental",
        action="store_true",
        default=True,
        help="增量：无数据补 years 年，有数据自 MAX(trade_date) 起补（默认开）",
    )
    parser.add_argument(
        "--no-incremental",
        dest="incremental",
        action="store_false",
        help="强制全量按 --years 窗口重拉",
    )
    parser.add_argument(
        "--skip-fresh-days",
        type=int,
        default=3,
        help="最新日线距今≤N 个日历日则视为已齐，跳过拉网（默认 3；-1 关闭）",
    )
    parser.add_argument(
        "--workers",
        type=int,
        default=4,
        help="并行线程数，每线程独立 TDX 连接（默认 4；1=串行）",
    )
    parser.add_argument(
        "--sync-universe",
        dest="sync_universe",
        action="store_true",
        default=True,
        help="--from-basic 时先同步 stock_basic 全市场列表（默认开）",
    )
    parser.add_argument(
        "--no-sync-universe",
        dest="sync_universe",
        action="store_false",
        help="不刷新 stock_basic，仅用库内现有 status=1",
    )
    parser.add_argument(
        "--sleep",
        type=float,
        default=0.02,
        help="每标的拉网后间隔秒（已齐跳过不睡；默认 0.02）",
    )
    args = parser.parse_args()

    try:
        if args.from_basic:
            if args.sync_universe:
                log("【阶段】同步全市场股票列表…")
                sync_result = sync_stock_basic.sync_universe(
                    deactivate_missing=False, source="auto"
                )
                log(
                    "【阶段】列表已就绪 · 来源={source} · 在市={active} 只 · 写入/更新={upserted}".format(
                        source=sync_result.get("source"),
                        active=sync_result.get("active"),
                        upserted=sync_result.get("upserted"),
                    )
                )
            codes = basic_codes()
        else:
            codes = normalize_codes(args.codes)
        if not codes:
            parser.error("请提供 --codes 或 --from-basic（且 stock_basic 有 status=1）")
    except Exception as exc:
        parser.error(str(exc))

    ensure_table()
    log("【阶段】预加载各股最新日线日期…")
    max_dates = load_max_trade_dates(codes)
    already = sum(1 for c in codes if is_fresh(max_dates.get(c), args.skip_fresh_days))
    need = len(codes) - already

    workers = max(1, int(args.workers))
    incr_cn = "增量补缺口" if args.incremental else "按年窗口全量"
    fresh_cn = (
        f"已齐跳过≤{args.skip_fresh_days}日"
        if args.skip_fresh_days >= 0
        else "不跳过已齐"
    )
    log(
        f"【阶段】开始拉取日线 · 共 {len(codes)} 只 · 预估已齐 {already} / 需拉网 {need} "
        f"· {incr_cn} · {fresh_cn} · workers={workers} · 回溯年={args.years} · 复权=NONE · 来源=TDX"
    )

    # 试连一次，失败早退；并打印节点
    probe = connect_tdx()
    try:
        # 从内部 hosts 顺序可知第一个成功的；此处简单提示已连通
        log("TDX server: connected")
    finally:
        probe.disconnect()

    failures: List[str] = []
    stats = {"skip_fresh": 0, "empty": 0, "wrote": 0, "fail": 0}
    counter = [0]
    counter_lock = threading.Lock()
    chunks = split_chunks(codes, workers)

    if workers == 1:
        worker_run(
            0,
            codes,
            max_dates,
            args.years,
            args.incremental,
            args.skip_fresh_days,
            args.sleep,
            len(codes),
            counter,
            counter_lock,
            failures,
            stats,
        )
    else:
        with ThreadPoolExecutor(max_workers=len(chunks)) as pool:
            futs = [
                pool.submit(
                    worker_run,
                    wid,
                    chunk,
                    max_dates,
                    args.years,
                    args.incremental,
                    args.skip_fresh_days,
                    args.sleep,
                    len(codes),
                    counter,
                    counter_lock,
                    failures,
                    stats,
                )
                for wid, chunk in enumerate(chunks)
            ]
            for fut in as_completed(futs):
                fut.result()

    log(
        f"【完成】成功 {len(codes) - len(failures)} 只 · 失败 {len(failures)} 只 "
        f"· 已齐跳过 {stats['skip_fresh']} · 写入 {stats['wrote']} · 空数据 {stats['empty']}"
    )
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
