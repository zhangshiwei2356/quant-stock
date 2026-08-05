# -*- coding: utf-8 -*-
"""同步 A 股全市场代码到 quant_stock.stock_basic（扫池 / day-collect 的 universe）。

默认从东方财富列表拉取（约 5000+）；失败时回退通达信 get_security_list。

用法：
    python scripts/sync_stock_basic.py
    python scripts/sync_stock_basic.py --deactivate-missing

依赖：pip install pymysql（东方财富仅用标准库；TDX 回退需 pytdx）
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
from datetime import date
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

import pymysql

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

EM_HOSTS = (
    "https://push2.eastmoney.com",
    "https://82.push2.eastmoney.com",
    "https://7.push2.eastmoney.com",
    "https://push2delay.eastmoney.com",
)
# 沪深京 A 股（主板/创业/科创/北交）
EM_FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23,m:0+t:81+s:2048"
EM_PATH = (
    "/api/qt/clist/get"
    "?pn={pn}&pz={pz}&po=1&np=1&fltt=2&invt=2&fid=f12"
    "&fs=" + EM_FS + "&fields=f12,f14,f13"
)

UPSERT = """
INSERT INTO stock_basic
  (symbol, name, market, industry, list_date, delist_date, is_st, status)
VALUES (%s,%s,%s,%s,%s,NULL,%s,1)
ON DUPLICATE KEY UPDATE
  name=VALUES(name), market=VALUES(market),
  is_st=VALUES(is_st), status=1
"""

PLACEHOLDER_LIST_DATE = date(1990, 1, 1)
Row = Tuple[str, str, int, Optional[str], date, int]


def market_board(code: str) -> int:
    """1主板 2创业板 3科创板 4北交所（与 schema / MockDataImporter 一致）。"""
    if code.startswith("688"):
        return 3
    if code.startswith("8") or code.startswith("4"):
        return 4
    if code.startswith("3"):
        return 2
    return 1


def is_a_share(code: str) -> bool:
    if not (code.isdigit() and len(code) == 6):
        return False
    # 沪主板 / 科创
    if code.startswith(("600", "601", "603", "605", "688")):
        return True
    # 深主板 / 中小 / 创业
    if code.startswith(("000", "001", "002", "003", "300", "301")):
        return True
    # 北交所常见 8xxxxx / 4xxxxx
    if code.startswith(("8", "4")) and not code.startswith("399"):
        return True
    return False


def detect_st(name: str) -> int:
    text = (name or "").upper().replace(" ", "")
    return 1 if ("ST" in text or "退" in (name or "")) else 0


def http_json(url: str, retries: int = 5) -> dict:
    last: Exception | None = None
    for i in range(retries):
        try:
            req = urllib.request.Request(
                url,
                headers={
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer": "https://quote.eastmoney.com/",
                    "Accept": "application/json,*/*",
                },
            )
            with urllib.request.urlopen(req, timeout=25) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, json.JSONDecodeError) as exc:
            last = exc
            time.sleep(0.8 * (i + 1))
    raise RuntimeError(f"HTTP 列表请求失败: {last}")


def fetch_eastmoney(page_size: int = 100) -> List[Row]:
    last_err: Exception | None = None
    first = None
    used_host = ""
    for host in EM_HOSTS:
        try:
            first = http_json(host + EM_PATH.format(pn=1, pz=page_size))
            used_host = host
            break
        except Exception as exc:
            last_err = exc
            print(f"eastmoney host fail {host}: {exc}", flush=True)
    if first is None:
        raise RuntimeError(f"东方财富列表请求失败: {last_err}")

    data = first.get("data") or {}
    total = int(data.get("total") or 0)
    pages = max(1, (total + page_size - 1) // page_size)
    print(f"eastmoney host={used_host} total={total} pages={pages}", flush=True)
    rows: Dict[str, Row] = {}

    def ingest(diff: Iterable[dict]) -> None:
        for item in diff or []:
            code = str(item.get("f12") or "").strip()
            name = str(item.get("f14") or "").strip() or code
            if not is_a_share(code):
                continue
            rows[code] = (
                code,
                name[:20],
                market_board(code),
                None,
                PLACEHOLDER_LIST_DATE,
                detect_st(name),
            )

    ingest(data.get("diff") or [])
    for pn in range(2, pages + 1):
        payload = None
        err = None
        for host in EM_HOSTS:
            try:
                payload = http_json(host + EM_PATH.format(pn=pn, pz=page_size), retries=3)
                break
            except Exception as exc:
                err = exc
        if payload is None:
            raise RuntimeError(f"东方财富第 {pn} 页失败: {err}")
        ingest((payload.get("data") or {}).get("diff") or [])
        if (pn % 5 == 0 or pn == pages:
            print(f"  eastmoney page {pn}/{pages} unique={len(rows)}", flush=True)
        time.sleep(0.08)
    if len(rows) < 3000:
        raise RuntimeError(f"东方财富有效 A 股过少: {len(rows)}")
    return [rows[k] for k in sorted(rows)]


def fetch_tdx() -> List[Row]:
    try:
        from pytdx.hq import TdxHq_API
    except ImportError as exc:
        raise RuntimeError("未安装 pytdx，无法回退 TDX 证券列表") from exc

    api = TdxHq_API(raise_exception=False)
    connected = False
    for name, host, port in TDX_HOSTS:
        try:
            if api.connect(host, port, time_out=6):
                print(f"TDX server: {name} {host}:{port}", flush=True)
                connected = True
                break
        except Exception as exc:
            print(f"TDX connect failed {name}: {exc}", file=sys.stderr, flush=True)
    if not connected:
        raise RuntimeError("无法连接任何 TDX 行情服务器")

    rows: Dict[str, Row] = {}
    try:
        for market in (0, 1):  # 0深 1沪
            try:
                count = int(api.get_security_count(market) or 0)
            except Exception:
                count = 30000
            start = 0
            empty_streak = 0
            # 部分节点 start=0 对沪市返回 None，仍继续翻页
            while start < max(count, 1000) + 2000:
                chunk = api.get_security_list(market, start)
                if not chunk:
                    empty_streak += 1
                    start += 1000
                    if empty_streak >= 8:
                        break
                    continue
                empty_streak = 0
                for item in chunk:
                    code = str(item.get("code") or "").strip()
                    name = str(item.get("name") or "").strip() or code
                    if not is_a_share(code):
                        continue
                    rows[code] = (
                        code,
                        name[:20],
                        market_board(code),
                        None,
                        PLACEHOLDER_LIST_DATE,
                        detect_st(name),
                    )
                got = len(chunk)
                start += got if got > 0 else 1000
                if start % 2000 < 1000:
                    print(f"  tdx market={market} start={start} unique={len(rows)}", flush=True)
                if got < 1000 and empty_streak == 0 and start > 1000:
                    # 正常末页
                    if start >= count:
                        break
    finally:
        api.disconnect()
    if len(rows) < 1000:
        raise RuntimeError(f"TDX 有效 A 股过少: {len(rows)}")
    return [rows[k] for k in sorted(rows)]


def upsert_rows(rows: Sequence[Row], deactivate_missing: bool) -> Dict[str, Any]:
    if not rows:
        raise RuntimeError("证券列表为空，未写入")
    conn = pymysql.connect(**DB)
    try:
        with conn.cursor() as cur:
            cur.executemany(UPSERT, rows)
            deactivated = 0
            if deactivate_missing:
                symbols = [r[0] for r in rows]
                # 分批 NOT IN，避免超长 SQL
                cur.execute("UPDATE stock_basic SET status=0")
                batch = 800
                for i in range(0, len(symbols), batch):
                    part = symbols[i : i + batch]
                    placeholders = ",".join(["%s"] * len(part))
                    cur.execute(
                        f"UPDATE stock_basic SET status=1 WHERE symbol IN ({placeholders})",
                        part,
                    )
                cur.execute("SELECT COUNT(1) FROM stock_basic WHERE status=0")
                deactivated = int(cur.fetchone()[0])
            cur.execute("SELECT COUNT(1) FROM stock_basic WHERE status=1")
            active = int(cur.fetchone()[0])
            cur.execute("SELECT COUNT(1) FROM stock_basic")
            total = int(cur.fetchone()[0])
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()
    return {
        "upserted": len(rows),
        "active": active,
        "total": total,
        "deactivated": deactivated,
    }


def sync_universe(deactivate_missing: bool = False, source: str = "auto") -> Dict[str, Any]:
    source = (source or "auto").strip().lower()
    rows: List[Row] = []
    used = ""
    errors: List[str] = []
    if source in ("auto", "eastmoney", "em"):
        try:
            rows = fetch_eastmoney()
            used = "eastmoney"
        except Exception as exc:
            errors.append(f"eastmoney: {exc}")
            if source != "auto":
                raise
    if not rows and source in ("auto", "tdx"):
        try:
            rows = fetch_tdx()
            used = "tdx"
        except Exception as exc:
            errors.append(f"tdx: {exc}")
            raise RuntimeError("；".join(errors) if errors else str(exc)) from exc
    if not rows:
        raise RuntimeError("；".join(errors) if errors else "未能获取证券列表")
    result = upsert_rows(rows, deactivate_missing=deactivate_missing)
    result["source"] = used
    result["ok"] = True
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description="同步 A 股全市场到 stock_basic")
    parser.add_argument(
        "--source",
        default="auto",
        choices=("auto", "eastmoney", "tdx"),
        help="列表来源（默认 auto=东方财富优先，失败回退 TDX）",
    )
    parser.add_argument(
        "--deactivate-missing",
        action="store_true",
        help="本次列表中不存在的标的将 status 置 0",
    )
    args = parser.parse_args()
    try:
        result = sync_universe(
            deactivate_missing=args.deactivate_missing,
            source=args.source,
        )
    except Exception as exc:
        print(f"FAIL {exc}", file=sys.stderr, flush=True)
        return 1
    print(
        f"done source={result['source']} upserted={result['upserted']} "
        f"active={result['active']} total={result['total']} "
        f"deactivated={result['deactivated']}",
        flush=True,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
