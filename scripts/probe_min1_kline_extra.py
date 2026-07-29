# -*- coding: utf-8 -*-
"""补充探测：baostock / akshare / 东财 delay 深历史切片。"""
from __future__ import annotations

import json
from datetime import datetime, timedelta
from urllib.parse import urlencode
from urllib.request import Request, urlopen

UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
CODE = "600036"


def http_get(url: str) -> str:
    req = Request(
        url,
        headers={
            "User-Agent": UA,
            "Referer": "https://quote.eastmoney.com/sh600036.html",
            "Origin": "https://quote.eastmoney.com",
        },
    )
    with urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8", errors="ignore")


def probe_em_day_slice(day: str) -> None:
    """按单日 beg=end 试东财 1 分钟是否能回历史某天。"""
    qs = urlencode(
        {
            "secid": f"1.{CODE}",
            "fields1": "f1,f2,f3,f4,f5,f6",
            "fields2": "f51,f52,f53,f54,f55,f56,f57",
            "klt": "1",
            "fqt": "1",
            "beg": day,
            "end": day,
            "lmt": "1000000",
        }
    )
    for host in ("push2delay.eastmoney.com", "push2his.eastmoney.com"):
        url = f"https://{host}/api/qt/stock/kline/get?" + qs
        try:
            payload = json.loads(http_get(url))
            kl = (payload.get("data") or {}).get("klines") or []
            first = kl[0].split(",")[0] if kl else None
            last = kl[-1].split(",")[0] if kl else None
            print(f"EM {host} day={day} bars={len(kl)} {first} -> {last}")
        except Exception as e:
            print(f"EM {host} day={day} FAIL {e}")


def probe_baostock() -> None:
    import baostock as bs

    lg = bs.login()
    print("baostock login", lg.error_code, lg.error_msg)
    for freq in ("1", "5"):
        rs = bs.query_history_k_data_plus(
            "sh.600036",
            "date,time,code,open,high,low,close,volume,amount",
            start_date="2026-07-20",
            end_date="2026-07-28",
            frequency=freq,
            adjustflag="3",
        )
        rows = []
        while rs.error_code == "0" and rs.next():
            rows.append(rs.get_row_data())
        print(f"baostock freq={freq} err={rs.error_code}/{rs.error_msg} bars={len(rows)}")
        if rows:
            print("  first", rows[0])
            print("  last ", rows[-1])
    bs.logout()


def probe_akshare() -> None:
    import akshare as ak

    for period in ("1", "5"):
        try:
            df = ak.stock_zh_a_hist_min_em(
                symbol=CODE,
                start_date="2026-07-22 09:30:00",
                end_date="2026-07-28 15:00:00",
                period=period,
                adjust="qfq",
            )
            print(f"akshare period={period} shape={df.shape}")
            if len(df):
                print(df.head(2).to_string())
                print(df.tail(2).to_string())
        except Exception as e:
            print(f"akshare period={period} FAIL {type(e).__name__}: {e}")


def main() -> None:
    print("--- Eastmoney single-day slices ---")
    # 今天、上一交易日附近、更早
    for day in ("20260728", "20260727", "20260725", "20260722", "20260601"):
        probe_em_day_slice(day)

    print("\n--- baostock ---")
    try:
        probe_baostock()
    except Exception as e:
        print("baostock FAIL", e)

    print("\n--- akshare ---")
    try:
        probe_akshare()
    except Exception as e:
        print("akshare FAIL", e)


if __name__ == "__main__":
    main()
