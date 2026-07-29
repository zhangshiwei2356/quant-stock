# -*- coding: utf-8 -*-
"""更多 1 分钟历史源探测：通达信 pytdx（相对公网新浪/腾讯更深）。

用法: python scripts/probe_min1_sources.py
依赖: pip install pytdx
"""
from __future__ import annotations

from pytdx.hq import TdxHq_API

CODE = "600036"
MARKET = 1  # 1=SH 0=SZ
HOSTS = [
    ("招商", "218.75.126.9", 7709),
    ("腾讯", "119.147.212.81", 7709),
    ("华泰", "60.12.136.159", 7709),
]


def ts(b) -> str:
    return b.get("datetime") or "%04d-%02d-%02d %02d:%02d" % (
        b["year"], b["month"], b["day"], b["hour"], b["minute"]
    )


def main() -> None:
    api = TdxHq_API(raise_exception=False)
    connected = None
    for name, ip, port in HOSTS:
        try:
            if api.connect(ip, port, time_out=6):
                connected = (name, ip, port)
                break
        except Exception as e:
            print("connect FAIL", name, e)
    if not connected:
        print("NO TDX SERVER")
        return
    print("USING", connected)

    bars = api.get_security_bars(8, MARKET, CODE, 0, 800)
    print("1min page0 n=", 0 if not bars else len(bars),
          ts(bars[0]) if bars else None, "->", ts(bars[-1]) if bars else None)

    # 找最深 offset（单次最多 800；本机实测总量约 21600≈90 交易日）
    lo, hi, last_ok = 10000, 30000, 0
    while lo <= hi:
        mid = (lo + hi) // 2
        chunk = api.get_security_bars(8, MARKET, CODE, mid, 800)
        n = 0 if not chunk else len(chunk)
        if n:
            last_ok = mid
            lo = mid + 1
        else:
            hi = mid - 1
    oldest = api.get_security_bars(8, MARKET, CODE, last_ok, 800)
    total = last_ok + (len(oldest) if oldest else 0)
    print("MAX_OFFSET", last_ok, "approx_total_bars", total,
          "approx_days", round(total / 240, 1),
          "oldest", ts(oldest[0]) if oldest else None)

    for d in (20260728, 20200102, 20150105, 20100104, 20070104):
        md = api.get_history_minute_time_data(MARKET, CODE, d)
        n = 0 if not md else len(md)
        print("history_minute_time", d, "n=", n, "(price+vol only, not full OHLC)")

    api.disconnect()
    print()
    print("NOTE: BaoStock 无 1min（仅 5/15/30/60）；Tushare stk_mins 号称 10年+ 但需分钟权限；")
    print("      公网新浪/腾讯约 3~5 日；TDX 完整 OHLC 约 90 交易日；按日分时可回溯到更早年份。")


if __name__ == "__main__":
    main()
