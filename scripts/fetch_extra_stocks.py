# -*- coding: utf-8 -*-
"""[已废弃] 原公开接口日线扩样本脚本。

行情真相源已改为仅 `market_1min`。请改用：

    python scripts/fetch_min1_tdx.py --codes 600519,000568,002415,600276,601166
"""
from __future__ import annotations

import sys


def main() -> int:
    print(
        "fetch_extra_stocks.py 已废弃：应用不再使用 market_daily / market_minute。\n"
        "请改用: python scripts/fetch_min1_tdx.py --codes 600519,000568,...",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
