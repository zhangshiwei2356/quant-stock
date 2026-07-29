# -*- coding: utf-8 -*-
"""探测公开接口能否拉到 A 股 1 分钟历史 K 线（只读探测，不写库）。

结论摘要（本机实测，标的默认 600036）：
- 旧新浪 money.finance... scale=1：空
- 旧腾讯 web.ifzq fqkline m1：空
- 可用：腾讯 ifzq.gtimg.cn kline/mkline m1（最多约 640 根 ≈ 近 3 个交易日）
- 可用：新浪 quotes.sina.cn CN_MarketDataService.getKLineData scale=1（最多约 1023 根 ≈ 近 4～5 个交易日）
- 东财 push2delay klt=1 / trends2：仅当日约 240 根；push2his 本机常被断开
- 更长历史分钟：公网免费源基本不够，需 baostock(多为5分钟)/Tushare 等授权源
"""
from __future__ import annotations

import json
from typing import Any, List, Tuple
from urllib.request import Request, urlopen

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
CODE = "600036"


def http_get(url: str, referer: str) -> str:
    req = Request(url, headers={"User-Agent": UA, "Referer": referer})
    with urlopen(req, timeout=45) as resp:
        return resp.read().decode("utf-8", errors="ignore")


def show(name: str, n: int, first: Any = None, last: Any = None, extra: str = "") -> None:
    print(f"=== {name} ===")
    print(f"  bars={n}" + (f"  {extra}" if extra else ""))
    if first is not None:
        print(f"  first={first}")
    if last is not None:
        print(f"  last ={last}")
    print()


def main() -> None:
    summary: List[Tuple[str, int, str]] = []

    # 旧新浪（日线脚本同域）scale=1
    try:
        url = (
            "http://money.finance.sina.com.cn/quotes_service/api/json_v2.php/"
            f"CN_MarketData.getKLineData?symbol=sh{CODE}&scale=1&ma=no&datalen=240"
        )
        raw = http_get(url, "https://finance.sina.com.cn/")
        arr = json.loads(raw) if raw and raw != "null" else []
        rows = arr if isinstance(arr, list) else []
        show("旧新浪 money.finance scale=1", len(rows), rows[0] if rows else None, rows[-1] if rows else None)
        summary.append(("sina_old_scale1", len(rows), "empty=不可用" if not rows else "ok"))
    except Exception as e:
        show("旧新浪 money.finance scale=1", -1, extra=f"FAIL {e}")
        summary.append(("sina_old_scale1", -1, str(e)))

    # 新浪 quotes.sina.cn（可用）
    try:
        url = (
            "https://quotes.sina.cn/cn/api/json_v2.php/CN_MarketDataService.getKLineData"
            f"?symbol=sh{CODE}&scale=1&ma=no&datalen=1023"
        )
        arr = json.loads(http_get(url, "https://finance.sina.com.cn/"))
        rows = arr if isinstance(arr, list) else []
        show(
            "新浪 quotes.sina.cn scale=1 (datalen=1023)",
            len(rows),
            rows[0] if rows else None,
            rows[-1] if rows else None,
        )
        summary.append(("sina_quotes_scale1", len(rows), "推荐短窗1分钟"))
    except Exception as e:
        show("新浪 quotes.sina.cn scale=1", -1, extra=f"FAIL {e}")
        summary.append(("sina_quotes_scale1", -1, str(e)))

    # 旧腾讯 fqkline m1
    try:
        url = f"https://web.ifzq.gtimg.cn/appstock/app/fqkline/get?param=sh{CODE},m1,,,320,qfq"
        payload = json.loads(http_get(url, "https://finance.qq.com/"))
        block = (payload.get("data") or {}).get(f"sh{CODE}") or {}
        arr = block.get("qfqm1") or block.get("m1") or []
        if not isinstance(arr, list):
            arr = []
        show("旧腾讯 web.ifzq fqkline m1", len(arr), arr[0] if arr else None, arr[-1] if arr else None, extra=f"keys={list(block.keys())}")
        summary.append(("tencent_fq_m1", len(arr), "empty=不可用" if not arr else "ok"))
    except Exception as e:
        show("旧腾讯 fqkline m1", -1, extra=f"FAIL {e}")
        summary.append(("tencent_fq_m1", -1, str(e)))

    # 腾讯 ifzq mkline（可用）
    try:
        url = f"https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=sh{CODE},m1,,640"
        payload = json.loads(http_get(url, "https://finance.qq.com/"))
        arr = ((payload.get("data") or {}).get(f"sh{CODE}") or {}).get("m1") or []
        if not isinstance(arr, list):
            arr = []
        show("腾讯 ifzq mkline m1 (req=640)", len(arr), arr[0] if arr else None, arr[-1] if arr else None)
        summary.append(("tencent_ifzq_mkline_m1", len(arr), "推荐短窗1分钟"))
    except Exception as e:
        show("腾讯 ifzq mkline m1", -1, extra=f"FAIL {e}")
        summary.append(("tencent_ifzq_mkline_m1", -1, str(e)))

    # 东财 delay 当日
    try:
        from urllib.parse import urlencode
        from datetime import datetime

        end = datetime.now().strftime("%Y%m%d")
        qs = urlencode(
            {
                "secid": f"1.{CODE}",
                "fields1": "f1,f2,f3,f4,f5,f6",
                "fields2": "f51,f52,f53,f54,f55,f56,f57",
                "klt": "1",
                "fqt": "1",
                "beg": end,
                "end": end,
                "lmt": "1000",
            }
        )
        url = "https://push2delay.eastmoney.com/api/qt/stock/kline/get?" + qs
        payload = json.loads(http_get(url, "https://quote.eastmoney.com/"))
        kl = (payload.get("data") or {}).get("klines") or []
        show(
            "东财 push2delay klt=1 当日",
            len(kl),
            kl[0] if kl else None,
            kl[-1] if kl else None,
            extra="beg/end 改历史日仍返回当日",
        )
        summary.append(("eastmoney_delay_klt1", len(kl), "仅当日"))
    except Exception as e:
        show("东财 push2delay klt=1", -1, extra=f"FAIL {e}")
        summary.append(("eastmoney_delay_klt1", -1, str(e)))

    print("SUMMARY")
    for name, n, note in summary:
        status = "OK" if n > 0 else ("EMPTY" if n == 0 else "FAIL")
        print(f"  [{status:5}] {name:28} bars={n:<6} {note}")


if __name__ == "__main__":
    main()
