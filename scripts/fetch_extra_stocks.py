# -*- coding: utf-8 -*-
"""从东方财富拉取日线，写入 classpath JSON + MySQL，供回测增量扩样本。

用法（项目根目录）:
  python scripts/fetch_extra_stocks.py
"""
from __future__ import annotations

import json
import random
import sys
import time
from datetime import datetime, time as dtime, timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pymysql

ROOT = Path(__file__).resolve().parents[1]
KLINE_DIR = ROOT / "src" / "main" / "resources" / "data" / "kline"

STOCKS = [
    ("600519", "贵州茅台", "1.600519"),
    ("000568", "泸州老窖", "0.000568"),
    ("002415", "海康威视", "0.002415"),
    ("600276", "恒瑞医药", "1.600276"),
    ("601166", "兴业银行", "1.601166"),
]

DB = dict(
    host="127.0.0.1",
    port=3306,
    user="root",
    password="123456",
    database="quant_stock",
    charset="utf8mb4",
)

BEG = "20240701"
END = "20260728"


def fetch_daily(secid: str, retries: int = 5) -> Tuple[str, List[Dict[str, Any]]]:
    qs = urlencode(
        {
            "secid": secid,
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61",
            "klt": "101",
            "fqt": "1",
            "beg": BEG,
            "end": END,
            "lmt": "1000000",
        }
    )
    url = "https://push2his.eastmoney.com/api/qt/stock/kline/get?" + qs
    last_err: Optional[Exception] = None
    for attempt in range(retries):
        try:
            req = Request(url, headers={"User-Agent": "Mozilla/5.0 quant-stock-fetch"})
            with urlopen(req, timeout=90) as resp:
                payload = json.loads(resp.read().decode("utf-8"))
            data = payload.get("data") or {}
            name = data.get("name") or ""
            rows: List[Dict[str, Any]] = []
            for line in data.get("klines") or []:
                p = line.split(",")
                if len(p) < 7:
                    continue
                vol_hand = int(float(p[5]))
                rows.append(
                    {
                        "date": p[0],
                        "open": float(p[1]),
                        "close": float(p[2]),
                        "high": float(p[3]),
                        "low": float(p[4]),
                        "volume": vol_hand * 100,
                        "amount": float(p[6]),
                    }
                )
            return name, rows
        except Exception as e:
            last_err = e
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(str(last_err))


def day_bars_json(code: str, rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    bars = []
    for r in rows:
        bars.append(
            [
                r["date"] + " 09:30:00",
                round(r["open"], 2),
                round(r["high"], 2),
                round(r["low"], 2),
                round(r["close"], 2),
                int(r["volume"]),
            ]
        )
    return {
        "stockCode": code,
        "period": "DAY",
        "table": "stock_bar_day",
        "fields": ["t", "o", "h", "l", "c", "v"],
        "count": len(bars),
        "source": "eastmoney_push2his_qfq",
        "bars": bars,
    }


def session_times(d) -> List[datetime]:
    out: List[datetime] = []
    t = datetime.combine(d, dtime(9, 30))
    end_am = datetime.combine(d, dtime(11, 30))
    while t < end_am:
        out.append(t)
        t += timedelta(minutes=5)
    t = datetime.combine(d, dtime(13, 0))
    end_pm = datetime.combine(d, dtime(15, 0))
    while t < end_pm:
        out.append(t)
        t += timedelta(minutes=5)
    return out


def synth_min5(code: str, rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    bars: List[List[Any]] = []
    rng = random.Random(hash(code) & 0xFFFFFFFF)
    for r in rows:
        d = datetime.strptime(r["date"], "%Y-%m-%d").date()
        times = session_times(d)
        n = len(times)
        if n == 0:
            continue
        o, h, low, c = r["open"], r["high"], r["low"], r["close"]
        path = [o]
        for i in range(1, n - 1):
            w = i / (n - 1)
            anchor = o + (c - o) * w
            noise = (h - low) * 0.08 * (rng.random() - 0.5)
            px = max(low, min(h, anchor + noise))
            path.append(px)
        path.append(c)
        path[max(1, n // 4)] = h
        path[max(1, 3 * n // 4)] = low
        path[-1] = c
        path[0] = o
        vol_each = max(100, int(r["volume"] / n))
        for i, t0 in enumerate(times):
            p0 = path[i]
            p1 = path[i + 1] if i + 1 < len(path) else path[i]
            hi = min(h, max(p0, p1) + (h - low) * 0.01)
            lo = max(low, min(p0, p1) - (h - low) * 0.01)
            bars.append(
                [
                    t0.strftime("%Y-%m-%d %H:%M:%S"),
                    round(p0, 2),
                    round(hi, 2),
                    round(lo, 2),
                    round(p1, 2),
                    vol_each,
                ]
            )
    return {
        "stockCode": code,
        "period": "MIN_5",
        "table": "stock_bar_min5",
        "fields": ["t", "o", "h", "l", "c", "v"],
        "count": len(bars),
        "source": "synth_from_eastmoney_daily",
        "bars": bars,
    }


def write_json(path: Path, obj: Dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def market_of(code: str) -> int:
    if code.startswith("3"):
        return 2
    if code.startswith("688"):
        return 3
    if code.startswith("8") or code.startswith("4"):
        return 4
    return 1


def upsert_mysql(code: str, name: str, day_rows: List[Dict[str, Any]], min5: Dict[str, Any]) -> None:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO stock_basic(symbol, name, market, industry, list_date, is_st, status)
                VALUES(%s,%s,%s,%s,%s,0,1)
                ON DUPLICATE KEY UPDATE name=VALUES(name), market=VALUES(market), status=1
                """,
                (code, name, market_of(code), "扩展样本", "2010-01-01"),
            )
            batch = []
            prev_close = None
            sql_d = """
                INSERT INTO market_daily(symbol, trade_date, open, high, low, close, volume, amount, limit_up, limit_down)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON DUPLICATE KEY UPDATE open=VALUES(open), high=VALUES(high), low=VALUES(low),
                  close=VALUES(close), volume=VALUES(volume), amount=VALUES(amount),
                  limit_up=VALUES(limit_up), limit_down=VALUES(limit_down)
            """
            for r in day_rows:
                pct = 0.20 if code.startswith("3") or code.startswith("688") else 0.10
                limit_up = round(prev_close * (1 + pct), 2) if prev_close else None
                limit_down = round(prev_close * (1 - pct), 2) if prev_close else None
                batch.append(
                    (
                        code,
                        r["date"],
                        round(r["open"], 4),
                        round(r["high"], 4),
                        round(r["low"], 4),
                        round(r["close"], 4),
                        int(r["volume"]),
                        round(float(r["amount"]), 4),
                        limit_up,
                        limit_down,
                    )
                )
                prev_close = r["close"]
                if len(batch) >= 400:
                    cur.executemany(sql_d, batch)
                    batch.clear()
            if batch:
                cur.executemany(sql_d, batch)

            sql_m = """
                INSERT INTO market_minute(symbol, trade_time, open, high, low, close, volume, amount)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
                ON DUPLICATE KEY UPDATE open=VALUES(open), high=VALUES(high), low=VALUES(low),
                  close=VALUES(close), volume=VALUES(volume), amount=VALUES(amount)
            """
            mbatch = []
            for row in min5["bars"]:
                t, o, h, low, c, v = row
                amt = round(float(c) * int(v), 4)
                mbatch.append((code, t, o, h, low, c, int(v), amt))
                if len(mbatch) >= 400:
                    cur.executemany(sql_m, mbatch)
                    mbatch.clear()
            if mbatch:
                cur.executemany(sql_m, mbatch)
        conn.commit()
    finally:
        conn.close()


def update_meta(extra: List[Tuple[str, str, float]]) -> None:
    meta_path = KLINE_DIR / "meta.json"
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    by_code = {s["code"]: s for s in meta.get("stocks") or []}
    for code, name, base in extra:
        by_code[code] = {"code": code, "name": name, "basePrice": f"{base:.2f}"}
    order = ["600036", "000001", "300059", "601318", "000858"] + [c for c, _, _ in extra]
    stocks = []
    seen = set()
    for c in order:
        if c in by_code and c not in seen:
            stocks.append(by_code[c])
            seen.add(c)
    for c, s in by_code.items():
        if c not in seen:
            stocks.append(s)
    meta["stocks"] = stocks
    meta["description"] = "演示股票 K 线：原有模拟样本 + 东方财富前复权日线扩展样本（MIN_5 由日线合成）"
    meta["note"] = "字段 [t,o,h,l,c,v]；MySQL 导入用 DAY + MIN_5；扩展股 source=eastmoney"
    meta["extendedAt"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent="\t"), encoding="utf-8")


def load_existing_day(code: str) -> Optional[Tuple[List[Dict[str, Any]], Dict[str, Any]]]:
    day_path = KLINE_DIR / code / "DAY.json"
    min_path = KLINE_DIR / code / "MIN_5.json"
    if not day_path.exists() or not min_path.exists():
        return None
    day_obj = json.loads(day_path.read_text(encoding="utf-8"))
    if day_obj.get("count", 0) < 100:
        return None
    rows = []
    for b in day_obj["bars"]:
        rows.append(
            {
                "date": b[0][:10],
                "open": float(b[1]),
                "high": float(b[2]),
                "low": float(b[3]),
                "close": float(b[4]),
                "volume": int(b[5]),
                "amount": float(b[4]) * int(b[5]),
            }
        )
    min_obj = json.loads(min_path.read_text(encoding="utf-8"))
    return rows, min_obj


def main() -> int:
    extras_meta: List[Tuple[str, str, float]] = []
    for code, fallback_name, secid in STOCKS:
        existing = load_existing_day(code)
        if existing is not None:
            rows, min_obj = existing
            print(f"skip fetch {code} (json exists days={len(rows)}), ensure mysql...")
            try:
                upsert_mysql(code, fallback_name, rows, min_obj)
                print(f"  mysql upsert ok")
            except Exception as e:
                print(f"  mysql warn {code}: {e}")
            extras_meta.append((code, fallback_name, rows[-1]["close"]))
            continue

        print(f"fetch {code} ...")
        try:
            name, rows = fetch_daily(secid)
        except Exception as e:
            print(f"FAIL {code}: {e}", file=sys.stderr)
            return 1
        if not rows:
            print(f"FAIL {code}: empty kline", file=sys.stderr)
            return 1
        name = name or fallback_name
        print(f"  {name} days={len(rows)} {rows[0]['date']}..{rows[-1]['date']}")
        day_obj = day_bars_json(code, rows)
        min_obj = synth_min5(code, rows)
        write_json(KLINE_DIR / code / "DAY.json", day_obj)
        write_json(KLINE_DIR / code / "MIN_5.json", min_obj)
        print(f"  wrote DAY={day_obj['count']} MIN_5={min_obj['count']}")
        upsert_mysql(code, name, rows, min_obj)
        print(f"  mysql upsert ok")
        extras_meta.append((code, name, rows[-1]["close"]))
        time.sleep(1.2)

    update_meta(extras_meta)
    print("meta.json updated")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
