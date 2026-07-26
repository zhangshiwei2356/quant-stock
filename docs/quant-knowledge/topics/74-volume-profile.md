# 74 · 成交量分布（Volume Profile）

对照 ERRATA E140–E141、E148；衔接 [08](08-indicator-semantics.md)、[68](68-breakout-vs-pullback.md)、[47](47-mtf-confirmation.md)。

## 1. 概念

- **POC**：区间内成交量最大价位（区，非精确线）  
- **Value Area**：约 70% 成交量所在价带（VAH/VAL）  
- **HVN / LVN**：高/低量节点；LVN 常加速穿越  

## 2. 常见错误

- Visible Range（VPVR）随缩放重算当历史真相（E140）  
- 趋势中迷信「必回 POC」（E141）  
- 把 LVN 当安全入场空隙（E148）  

## 3. 用法边界

会话或**固定区间** Profile 才可复现；作**合流过滤**，不作金叉替代触发。  
先判平衡 vs 趋势（ADX/形态）；假突破过滤可与 [68](68-breakout-vs-pullback.md) 联用。

## 4. 本应用

主引擎为均线/RSI/ATR；VP 属可选分钟/会话研究模块，须显式开关+计 N。  
