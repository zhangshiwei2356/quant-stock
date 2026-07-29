# -*- coding: utf-8 -*-
"""[已废弃] 原批量日线/5 分钟灌库脚本。

行情真相源已改为仅 `market_1min`。请改用：

    python scripts/fetch_min1_tdx.py --from-pool
    python scripts/fetch_min1_tdx.py --codes 600036,000001

历史清单仍可参考：scripts/batch100_universe.json
"""
from __future__ import annotations

import sys


def main() -> int:
    print(
        "fetch_stocks_batch.py 已废弃：应用不再使用 market_daily / market_minute。\n"
        "请改用: python scripts/fetch_min1_tdx.py --from-pool",
        file=sys.stderr,
    )
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
