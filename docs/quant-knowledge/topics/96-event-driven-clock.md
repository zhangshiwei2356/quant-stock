# 96 · 事件驱动时钟

对照万轮簇 T26 及并购/回购/宏观子簇；衔接 [75](75-pead-earnings-drift.md)、[58](58-index-reconstitution.md)、[51](51-unlock-reduce-events.md)。

## 1. 时钟契约

每条事件至少：`announce_ts` / `knowable_ts` / `effective_ts`（若有）。  
盘后公告不得假定当日连续竞价已可交易。

## 2. 常见错误

- 完成日当知晓日；业绩预告口径混用；deal break 无视  
- 事件重叠不降权；CAR 公开数字外推  

## 3. 本应用

事件模块分册；主路径不因公告简称改金叉。  
