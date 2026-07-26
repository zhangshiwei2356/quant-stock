# 189 · POV 参与率执行

对照第十四万轮 AG22–AG25、AG30、AG47、AG65、AG91、AG98；衔接 [128](128-vwap-twap-execution.md)、[157](157-capacity-aum-scaling.md)、[171](171-slippage-online-calibration.md)。

## 1. 核心错误

无参与率硬帽；尾盘追进度冲击；被探测跟单；按意图非成交；调样日仍高 POV；无在线残差。

## 2. 改法

POV **硬帽** + 尾盘保护；按成交进度；与 ADV/调样窗联动降参与；残差日报；执行层不反馈改开仓。

## 3. 本应用

POV 属执行沙箱；金叉主路径锁定。
