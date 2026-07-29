# -*- coding: utf-8 -*-
"""从通达信拉取 1 分钟 K 线，并回填 quant_stock.market_1min。

用法：
    python scripts/fetch_min1_tdx.py --codes 600036,000001 --sleep 0.2
    python scripts/fetch_min1_tdx.py --from-pool

默认从 trade_pool 读取 status=1 的标的；未找到标的时必须传 --codes。
仅写入 market_1min（唯一物理真相源）；更大周期由应用内存聚合，不再双写 5 分钟/日线表。

TDX 的 get_security_bars(8, ...) 通常已以“股”返回 vol。少数节点会返回“手”；
当整个下载批次的非零成交量均为 100 的整数倍且中位数不大于 10000 时，将其识别为
手并乘以 100 转为股。该保守规则避免将大多数正常的低成交分钟误放大。

依赖：pip install pytdx pymysql
"""
from __future__ import annotations

import argparse
import statistics
import sys
import time
from datetime import datetime
from typing import Any, Iterable, List, Sequence, Tuple

import pymysql
from pytdx.hq import TdxHq_API

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
Bar = Tuple[str, str, float, float, float, float, int, float]

UPSERT_1MIN = """
    INSERT INTO market_1min(symbol, trade_time, open, high, low, close, volume, amount)
    VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
    ON DUPLICATE KEY UPDATE
      open=VALUES(open), high=VALUES(high), low=VALUES(low), close=VALUES(close),
      volume=VALUES(volume), amount=VALUES(amount)
"""


def tdx_market(code: str) -> int:
    """pytdx 市场号：上海=1，深圳/创业板/北交所=0。"""
    return 1 if code.startswith(("5", "6", "9")) else 0


def normalize_codes(raw_codes: str | None) -> List[str]:
    if not raw_codes:
        return []
    codes = []
    for code in raw_codes.split(","):
        code = code.strip()
        if not code:
            continue
        if not (code.isdigit() and len(code) == 6):
            raise ValueError(f"非法股票代码：{code}")
        if code not in codes:
            codes.append(code)
    return codes


def active_pool_codes() -> List[str]:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT symbol FROM trade_pool WHERE status=1 ORDER BY symbol")
            return normalize_codes(",".join(str(row[0]) for row in cur.fetchall()))
    finally:
        conn.close()


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


def bar_time(bar: dict[str, Any]) -> str:
    value = bar.get("datetime")
    if value:
        parsed = datetime.strptime(str(value), "%Y-%m-%d %H:%M")
        return parsed.strftime("%Y-%m-%d %H:%M:%S")
    return "%04d-%02d-%02d %02d:%02d:00" % (
        bar["year"], bar["month"], bar["day"], bar["hour"], bar["minute"],
    )


def volumes_are_lots(volumes: Iterable[int]) -> bool:
    values = [volume for volume in volumes if volume > 0]
    return bool(values) and all(v % 100 == 0 for v in values) and statistics.median(values) <= 10000


def fetch_tdx_bars(api: TdxHq_API, code: str) -> List[Bar]:
    """按每页 800 条从最新向更早分页，规范为升序且按分钟去重。"""
    raw: List[dict[str, Any]] = []
    start = 0
    while True:
        bars = api.get_security_bars(8, tdx_market(code), code, start, 800)
        if not bars:
            break
        raw.extend(bars)
        start += len(bars)
        if len(bars) < 800:
            break

    multiplier = 100 if volumes_are_lots(int(float(b.get("vol") or 0)) for b in raw) else 1
    if multiplier == 100:
        print(f"  {code}: TDX vol 识别为手，已换算为股", flush=True)

    unique: dict[str, Bar] = {}
    for bar in raw:
        trade_time = bar_time(bar)
        close = round(float(bar["close"]), 4)
        volume = int(round(float(bar.get("vol") or 0) * multiplier))
        unique[trade_time] = (
            code,
            trade_time,
            round(float(bar["open"]), 4),
            round(float(bar["high"]), 4),
            round(float(bar["low"]), 4),
            close,
            volume,
            round(close * volume, 4),
        )
    return [unique[key] for key in sorted(unique)]


def execute_batches(cur: Any, sql: str, rows: Sequence[Tuple[Any, ...]]) -> None:
    for index in range(0, len(rows), BATCH_SIZE):
        cur.executemany(sql, rows[index : index + BATCH_SIZE])


def upsert_symbol(code: str, bars: Sequence[Bar]) -> int:
    if not bars:
        raise RuntimeError(f"{code}: TDX 未返回 1 分钟 K 线")
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            execute_batches(cur, UPSERT_1MIN, bars)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return len(bars)


def main() -> int:
    parser = argparse.ArgumentParser(description="TDX 1 分钟行情回填（仅 market_1min）")
    parser.add_argument("--codes", help="逗号分隔的 6 位股票代码")
    parser.add_argument("--from-pool", action="store_true", help="强制从 active trade_pool 读取代码")
    parser.add_argument("--sleep", type=float, default=0.2, help="每个标的之间等待秒数（默认 0.2）")
    args = parser.parse_args()

    try:
        codes = active_pool_codes() if args.from_pool or not args.codes else normalize_codes(args.codes)
        if not codes:
            parser.error("未找到 active trade_pool 标的；请提供 --codes 600036,000001")
    except Exception as exc:
        parser.error(str(exc))

    print(f"target={len(codes)}", flush=True)
    api = connect_tdx()
    failures: List[str] = []
    try:
        for index, code in enumerate(codes, 1):
            try:
                bars = fetch_tdx_bars(api, code)
                n1 = upsert_symbol(code, bars)
                print(f"[{index}/{len(codes)}] {code}: 1min={n1}", flush=True)
            except Exception as exc:
                failures.append(code)
                print(f"[{index}/{len(codes)}] {code}: FAIL {exc}", file=sys.stderr, flush=True)
            if index < len(codes) and args.sleep > 0:
                time.sleep(args.sleep)
    finally:
        api.disconnect()
    print(f"done ok={len(codes) - len(failures)} fail={len(failures)}", flush=True)
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
