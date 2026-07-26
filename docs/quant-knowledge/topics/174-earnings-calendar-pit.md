# 174 · 财报日历与公告可用时点

对照第十二万轮 AE28–AE32、AE41、AE42、AE74；衔接 [104](104-feature-store-point-in-time.md)、[123](123-pead-earnings-drift.md)、[86](86-exdiv-dynamic-adj-lookahead.md)。

## 1. 核心错误

报告期末当日用财报；修订覆盖旧版；公告当日盘中即用；特征店无公告时点；公开 PEAD 数字外推。

## 2. 改法

**公告日（+可选 T+1 保守）** 才可用；修订 append-only + version；特征 `effective_from`；事件落地与 T+1/涨跌停同检。

## 3. 本应用

财报因子属选股/事件沙箱；禁并金叉主路径；公开数字不可外推。
