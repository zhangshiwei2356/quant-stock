# -*- coding: utf-8 -*-
"""批量从公开接口拉取约 100 只 A 股近一年日线，写入 MySQL（及可选 DAY.json）。

- 日线：新浪 / 腾讯（东财易断连时自动切换）
- 5 分钟：由日线合成后只写 MySQL，不写巨型 MIN_5.json（避免仓库膨胀）
- 用法: python scripts/fetch_stocks_batch.py [--limit 100] [--skip-json] [--days 260]

依赖: pip install pymysql
"""
from __future__ import annotations

import argparse
import json
import random
import sys
import time
from datetime import datetime, timedelta, time as dtime
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pymysql

ROOT = Path(__file__).resolve().parents[1]
KLINE_DIR = ROOT / "src" / "main" / "resources" / "data" / "kline"
BATCH_META = ROOT / "scripts" / "batch100_universe.json"

DB = dict(
    host="127.0.0.1",
    port=3306,
    user="root",
    password="123456",
    database="quant_stock",
    charset="utf8mb4",
)

# 流动性较好的主板/创业板样本（去 ST/科创；可与库内已有重复，upsert 覆盖）
UNIVERSE_100: List[Tuple[str, str]] = [
    ("600519", "贵州茅台"), ("600036", "招商银行"), ("601318", "中国平安"), ("600276", "恒瑞医药"),
    ("601166", "兴业银行"), ("600030", "中信证券"), ("600887", "伊利股份"), ("601398", "工商银行"),
    ("601288", "农业银行"), ("601988", "中国银行"), ("601328", "交通银行"), ("600000", "浦发银行"),
    ("600016", "民生银行"), ("601601", "中国太保"), ("601628", "中国人寿"), ("600048", "保利发展"),
    ("600104", "上汽集团"), ("600309", "万华化学"), ("600585", "海螺水泥"), ("600690", "海尔智家"),
    ("600809", "山西汾酒"), ("600900", "长江电力"), ("601012", "隆基绿能"), ("601088", "中国神华"),
    ("601225", "陕西煤业"), ("601857", "中国石油"), ("600028", "中国石化"), ("601668", "中国建筑"),
    ("601186", "中国铁建"), ("601390", "中国中铁"), ("601766", "中国中车"), ("600031", "三一重工"),
    ("600406", "国电南瑞"), ("600438", "通威股份"), ("600893", "航发动力"), ("601888", "中国中免"),
    ("603259", "药明康德"), ("603288", "海天味业"), ("603501", "韦尔股份"), ("603986", "兆易创新"),
    ("000001", "平安银行"), ("000002", "万科A"), ("000063", "中兴通讯"), ("000100", "TCL科技"),
    ("000157", "中联重科"), ("000166", "申万宏源"), ("000333", "美的集团"), ("000338", "潍柴动力"),
    ("000538", "云南白药"), ("000568", "泸州老窖"), ("000596", "古井贡酒"), ("000625", "长安汽车"),
    ("000651", "格力电器"), ("000725", "京东方A"), ("000768", "中航西飞"), ("000776", "广发证券"),
    ("000786", "北新建材"), ("000858", "五粮液"), ("000876", "新希望"), ("000895", "双汇发展"),
    ("000938", "紫光股份"), ("000977", "浪潮信息"), ("001979", "招商蛇口"), ("002001", "新和成"),
    ("002027", "分众传媒"), ("002049", "紫光国微"), ("002050", "三花智控"), ("002142", "宁波银行"),
    ("002230", "科大讯飞"), ("002236", "大华股份"), ("002241", "歌尔股份"), ("002271", "东方雨虹"),
    ("002304", "洋河股份"), ("002352", "顺丰控股"), ("002371", "北方华创"), ("002415", "海康威视"),
    ("002460", "赣锋锂业"), ("002466", "天齐锂业"), ("002475", "立讯精密"), ("002594", "比亚迪"),
    ("002714", "牧原股份"), ("002812", "恩捷股份"), ("002821", "凯莱英"), ("003816", "中国广核"),
    ("300015", "爱尔眼科"), ("300033", "同花顺"), ("300059", "东方财富"), ("300122", "智飞生物"),
    ("300124", "汇川技术"), ("300142", "沃森生物"), ("300274", "阳光电源"), ("300308", "中际旭创"),
    ("300316", "晶盛机电"), ("300347", "泰格医药"), ("300408", "三环集团"), ("300433", "蓝思科技"),
    ("300442", "润泽科技"), ("300450", "先导智能"), ("300498", "温氏股份"), ("300750", "宁德时代"),
    ("300760", "迈瑞医疗"), ("300782", "卓胜微"), ("300896", "爱美客"), ("301236", "软通动力"),
]


def market_of(code: str) -> int:
    if code.startswith("3"):
        return 2
    if code.startswith("688"):
        return 3
    if code.startswith("8") or code.startswith("4"):
        return 4
    return 1


def sina_symbol(code: str) -> str:
    if code.startswith("6"):
        return "sh" + code
    return "sz" + code


def http_get(url: str, timeout: int = 60) -> str:
    req = Request(url, headers={"User-Agent": "Mozilla/5.0 quant-stock-batch"})
    with urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="ignore")


def fetch_sina_daily(code: str, datalen: int = 280) -> List[Dict[str, Any]]:
    sym = sina_symbol(code)
    url = (
        "http://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
        f"CN_MarketData.getKLineData?symbol={sym}&scale=240&ma=no&datalen={datalen}"
    )
    raw = http_get(url)
    arr = json.loads(raw)
    rows: List[Dict[str, Any]] = []
    for x in arr:
        rows.append(
            {
                "date": x["day"],
                "open": float(x["open"]),
                "high": float(x["high"]),
                "low": float(x["low"]),
                "close": float(x["close"]),
                "volume": int(float(x["volume"])),
                "amount": float(x["close"]) * float(x["volume"]),
            }
        )
    return rows


def fetch_tencent_daily(code: str, count: int = 280) -> List[Dict[str, Any]]:
    prefix = "sh" if code.startswith("6") else "sz"
    url = f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param={prefix}{code},day,,,{count},qfq"
    payload = json.loads(http_get(url))
    key = prefix + code
    block = (payload.get("data") or {}).get(key) or {}
    arr = block.get("qfqday") or block.get("day") or []
    rows: List[Dict[str, Any]] = []
    for x in arr:
        # date, open, close, high, low, volume(手?)
        vol = int(float(x[5]) * 100) if float(x[5]) < 1e7 else int(float(x[5]))
        o, c, h, low = float(x[1]), float(x[2]), float(x[3]), float(x[4])
        rows.append(
            {
                "date": x[0],
                "open": o,
                "close": c,
                "high": h,
                "low": low,
                "volume": vol,
                "amount": c * vol,
            }
        )
    return rows


def fetch_daily(code: str, datalen: int) -> List[Dict[str, Any]]:
    last: Optional[Exception] = None
    for attempt in range(4):
        try:
            if attempt % 2 == 0:
                rows = fetch_sina_daily(code, datalen)
            else:
                rows = fetch_tencent_daily(code, min(datalen, 320))
            if rows and len(rows) >= 60:
                return rows
            last = RuntimeError(f"too few bars: {0 if not rows else len(rows)}")
        except Exception as e:
            last = e
        time.sleep(1.2 * (attempt + 1))
    raise RuntimeError(f"{code}: {last}")


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


def synth_min5_bars(code: str, rows: List[Dict[str, Any]]) -> List[Tuple]:
    """返回可 executemany 的分钟行元组列表。"""
    rng = random.Random(hash(code) & 0xFFFFFFFF)
    out: List[Tuple] = []
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
            path.append(max(low, min(h, anchor + noise)))
        path.append(c)
        path[max(1, n // 4)] = h
        path[max(1, 3 * n // 4)] = low
        path[0], path[-1] = o, c
        vol_each = max(100, int(r["volume"] / n))
        for i, t0 in enumerate(times):
            p0 = path[i]
            p1 = path[i + 1] if i + 1 < len(path) else path[i]
            hi = min(h, max(p0, p1) + (h - low) * 0.01)
            lo = max(low, min(p0, p1) - (h - low) * 0.01)
            close = round(p1, 2)
            vol = vol_each
            out.append(
                (
                    code,
                    t0.strftime("%Y-%m-%d %H:%M:%S"),
                    round(p0, 2),
                    round(hi, 2),
                    round(lo, 2),
                    close,
                    vol,
                    round(close * vol, 4),
                )
            )
    return out


def day_json(code: str, rows: List[Dict[str, Any]]) -> Dict[str, Any]:
    bars = [
        [
            r["date"] + " 09:30:00",
            round(r["open"], 2),
            round(r["high"], 2),
            round(r["low"], 2),
            round(r["close"], 2),
            int(r["volume"]),
        ]
        for r in rows
    ]
    return {
        "stockCode": code,
        "period": "DAY",
        "fields": ["t", "o", "h", "l", "c", "v"],
        "count": len(bars),
        "source": "public_api_batch",
        "bars": bars,
    }


def upsert_mysql(code: str, name: str, rows: List[Dict[str, Any]], write_minute: bool) -> None:
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO stock_basic(symbol, name, market, industry, list_date, is_st, status)
                VALUES(%s,%s,%s,%s,%s,0,1)
                ON DUPLICATE KEY UPDATE name=VALUES(name), market=VALUES(market), status=1
                """,
                (code, name, market_of(code), "批量扩展", "2010-01-01"),
            )
            sql_d = """
                INSERT INTO market_daily(symbol, trade_date, open, high, low, close, volume, amount, limit_up, limit_down)
                VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)
                ON DUPLICATE KEY UPDATE open=VALUES(open), high=VALUES(high), low=VALUES(low),
                  close=VALUES(close), volume=VALUES(volume), amount=VALUES(amount),
                  limit_up=VALUES(limit_up), limit_down=VALUES(limit_down)
            """
            batch = []
            prev = None
            for r in rows:
                pct = 0.20 if code.startswith("3") else 0.10
                lu = round(prev * (1 + pct), 2) if prev else None
                ld = round(prev * (1 - pct), 2) if prev else None
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
                        lu,
                        ld,
                    )
                )
                prev = r["close"]
                if len(batch) >= 400:
                    cur.executemany(sql_d, batch)
                    batch.clear()
            if batch:
                cur.executemany(sql_d, batch)

            if write_minute:
                # 先删后写，避免旧合成残留
                cur.execute("DELETE FROM market_minute WHERE symbol=%s", (code,))
                sql_m = """
                    INSERT INTO market_minute(symbol, trade_time, open, high, low, close, volume, amount)
                    VALUES(%s,%s,%s,%s,%s,%s,%s,%s)
                """
                mb = synth_min5_bars(code, rows)
                for i in range(0, len(mb), 400):
                    cur.executemany(sql_m, mb[i : i + 400])
        conn.commit()
    finally:
        conn.close()


def update_meta(stocks: List[Tuple[str, str, float]]) -> None:
    meta_path = KLINE_DIR / "meta.json"
    if meta_path.exists():
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
    else:
        meta = {"stocks": [], "periods": ["DAY", "MIN_5"]}
    by = {s["code"]: s for s in meta.get("stocks") or []}
    for code, name, base in stocks:
        by[code] = {"code": code, "name": name, "basePrice": f"{base:.2f}"}
    meta["stocks"] = list(by.values())
    meta["description"] = "模拟种子 + 公开接口批量扩展日线（约100只）；MIN_5 合成仅落库"
    meta["batchExtendedAt"] = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    meta["batchCount"] = len(stocks)
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent="\t"), encoding="utf-8")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=100)
    ap.add_argument("--datalen", type=int, default=280, help="约一年交易日")
    ap.add_argument("--skip-json", action="store_true", help="不写 DAY.json")
    ap.add_argument("--skip-minute", action="store_true", help="不写合成分钟（更快，仅日线回测）")
    ap.add_argument("--sleep", type=float, default=0.8)
    args = ap.parse_args()

    universe = UNIVERSE_100[: max(1, min(args.limit, len(UNIVERSE_100)))]
    # 去重保序
    seen = set()
    uniq: List[Tuple[str, str]] = []
    for c, n in universe:
        if c in seen:
            continue
        seen.add(c)
        uniq.append((c, n))

    ok_meta: List[Tuple[str, str, float]] = []
    fail: List[str] = []
    print(f"target={len(uniq)} datalen={args.datalen} minute={not args.skip_minute}")

    for i, (code, name) in enumerate(uniq, 1):
        try:
            print(f"[{i}/{len(uniq)}] {code} {name} ...", flush=True)
            rows = fetch_daily(code, args.datalen)
            # 截近一年（按自然日）
            cutoff = (datetime.now().date() - timedelta(days=400)).isoformat()
            rows = [r for r in rows if r["date"] >= cutoff]
            if len(rows) < 60:
                raise RuntimeError(f"after cutoff only {len(rows)}")
            print(f"  days={len(rows)} {rows[0]['date']}..{rows[-1]['date']}", flush=True)
            upsert_mysql(code, name, rows, write_minute=not args.skip_minute)
            if not args.skip_json:
                path = KLINE_DIR / code / "DAY.json"
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(
                    json.dumps(day_json(code, rows), ensure_ascii=False, separators=(",", ":")),
                    encoding="utf-8",
                )
            ok_meta.append((code, name, rows[-1]["close"]))
        except Exception as e:
            print(f"  FAIL {code}: {e}", file=sys.stderr)
            fail.append(code)
        time.sleep(args.sleep)

    update_meta(ok_meta)
    BATCH_META.write_text(
        json.dumps(
            {
                "ok": [{"code": c, "name": n, "close": px} for c, n, px in ok_meta],
                "fail": fail,
                "at": datetime.now().isoformat(timespec="seconds"),
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"done ok={len(ok_meta)} fail={len(fail)} -> {BATCH_META}")
    return 0 if len(ok_meta) >= 80 else 1


if __name__ == "__main__":
    raise SystemExit(main())
