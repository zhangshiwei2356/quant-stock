# -*- coding: utf-8 -*-
"""从通达信拉取日 K，并回填 quant_stock.market_daily（data_source=TDX, adj_flag=NONE）。

用法：
    python scripts/fetch_daily_tdx.py --codes 600036,000001
    python scripts/fetch_daily_tdx.py --from-basic --incremental
    python scripts/fetch_daily_tdx.py --from-basic --years 1 --no-incremental

默认 --from-basic 时会先同步 stock_basic 全市场列表（约 5000+，见 sync_stock_basic.py），
否则只会处理库内已有 status=1 的标的（可能只有演示/抽样几十上百只）。

默认 --incremental：
  · 该股无日线 → 补最近 --years 年（默认 1）
  · 已有日线 → 自 MAX(trade_date) 前几天起重拉并 upsert（补缺口到最近交易日）
价额以「元」入库；复权口径统一 NONE（与 TDX 裸价一致，勿与 QFQ 混库）。
应用 getKline(DAY) 默认 quant.day-source=auto 优先读本表，供全市场扫池。

依赖：pip install pytdx pymysql
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any, Iterable, List, Optional, Sequence, Tuple

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


def max_trade_date(code: str) -> Optional[date]:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(ENSURE_TABLE)
            cur.execute(
                "SELECT MAX(trade_date) FROM market_daily WHERE symbol=%s", (code,)
            )
            row = cur.fetchone()
            if not row or row[0] is None:
                return None
            val = row[0]
            if isinstance(val, datetime):
                return val.date()
            if isinstance(val, date):
                return val
            return datetime.strptime(str(val)[:10], "%Y-%m-%d").date()
    finally:
        conn.close()


def resolve_since(code: str, years: float, incremental: bool) -> Tuple[date, str]:
    full_since = date.today() - timedelta(days=int(365 * max(years, 0.1)))
    if not incremental:
        return full_since, "full"
    max_d = max_trade_date(code)
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
                print(f"TDX server: {name} {host}:{port}", flush=True)
                return api
        except Exception as exc:
            print(f"TDX connect failed {name}: {exc}", file=sys.stderr, flush=True)
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
    if multiplier == 100:
        print(f"  {code}: TDX vol 识别为手，已换算为股", flush=True)

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


def execute_batches(cur: Any, sql: str, rows: Sequence[Tuple[Any, ...]]) -> None:
    for index in range(0, len(rows), BATCH_SIZE):
        cur.executemany(sql, rows[index : index + BATCH_SIZE])


def upsert_symbol(code: str, bars: Sequence[Bar]) -> int:
    if not bars:
        return 0
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(ENSURE_TABLE)
            execute_batches(cur, UPSERT_DAILY, bars)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return len(bars)


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
    parser.add_argument("--sleep", type=float, default=0.05, help="每标的间隔秒（默认 0.05）")
    args = parser.parse_args()

    try:
        if args.from_basic:
            if args.sync_universe:
                print("【阶段】同步全市场股票列表…", flush=True)
                sync_result = sync_stock_basic.sync_universe(deactivate_missing=False, source="auto")
                print(
                    "【阶段】列表已就绪 · 来源={source} · 在市={active} 只 · 写入/更新={upserted}".format(
                        source=sync_result.get("source"),
                        active=sync_result.get("active"),
                        upserted=sync_result.get("upserted"),
                    ),
                    flush=True,
                )
            codes = basic_codes()
        else:
            codes = normalize_codes(args.codes)
        if not codes:
            parser.error("请提供 --codes 或 --from-basic（且 stock_basic 有 status=1）")
    except Exception as exc:
        parser.error(str(exc))

    incr_cn = "增量补缺口" if args.incremental else "按年窗口全量"
    print(
        f"【阶段】开始拉取日线 · 共 {len(codes)} 只 · {incr_cn} · 回溯年={args.years} · 复权=NONE · 来源=TDX",
        flush=True,
    )
    api = connect_tdx()
    failures: List[str] = []
    skipped = 0
    try:
        for index, code in enumerate(codes, 1):
            try:
                since, mode = resolve_since(code, args.years, args.incremental)
                mode_cn = "增量" if mode == "incr" else "全量"
                bars = fetch_tdx_day_bars(api, code, since)
                n = upsert_symbol(code, bars)
                if n == 0:
                    skipped += 1
                    print(
                        f"[{index}/{len(codes)}] {code} · 无新数据 · {mode_cn} · 自 {since}",
                        flush=True,
                    )
                else:
                    print(
                        f"[{index}/{len(codes)}] {code} · 写入 {n} 根日线 · {mode_cn} · 自 {since}",
                        flush=True,
                    )
            except Exception as exc:
                failures.append(code)
                print(
                    f"[{index}/{len(codes)}] {code} · 失败 {exc}",
                    file=sys.stderr,
                    flush=True,
                )
            if index < len(codes) and args.sleep > 0:
                time.sleep(args.sleep)
    finally:
        api.disconnect()
    print(
        f"【完成】成功 {len(codes) - len(failures)} 只 · 失败 {len(failures)} 只 · 空数据 {skipped} 只",
        flush=True,
    )
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
