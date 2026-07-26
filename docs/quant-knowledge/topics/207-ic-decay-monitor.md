# 207 · IC 衰减监控

对照第十七万轮 AJ09–AJ12、AJ27、AJ45、AJ62、AJ92；衔接 [158](158-signal-drift-monitor.md)、[156](156-signal-ensemble-gating.md)、[178](178-turnover-stamp-tax.md)。

## 1. 核心错误

只看当期 IC；半衰期不监控；衰减后满仓或高换手「救火」；IC 加权分改金叉方向；公开 MeanIC 外推。

## 2. 改法

滚动 IC/IR + **半衰期**；冷却 + Kill；救火受换手/成本帽；IC 只调仓位或过滤，不开仓方向。

## 3. 本应用

IC 监控属漂移/运维层；金叉主路径锁定。
