# 209 · 配股增发公司行为

对照第十七万轮 AJ17–AJ20、AJ29、AJ34、AJ38、AJ64；衔接 [45](45-corporate-actions-adj.md)、[86](86-exdiv-dynamic-adj-lookahead.md)、[91](91-div-tax-settlement.md)。

## 1. 核心错误

增发后股本用最终值回测历史；缴款/除权时钟前视；停牌可成交；流通冲击忽略；定增故事当 alpha。

## 2. 改法

股本/流通 **as-of**；公告与缴款截止时钟；停牌拒成；容量/参与率重估；事件分册，不改金叉。

## 3. 本应用

公司行为属数据与执行假设；冲突以 `rules.html`/代码为准。
