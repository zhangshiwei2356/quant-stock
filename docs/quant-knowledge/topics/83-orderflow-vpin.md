# 83 · 订单流失衡与 VPIN

对照 ERRATA E156、E161；衔接 [32](32-orderbook-l2-pitfalls.md)、[67](67-vwap-twap-execution.md)、[72](72-opening-auction.md)。

## 1. 边界

OFI/VPIN 属**微观结构**毒性/失衡度量；依赖成交分类、volume bar、延迟模型。  
作者实现要点：volume 同步桶 + Bulk Classification（非随意 tick-rule 冒充，E161）。

## 2. 常见错误

- 塞进日线金叉当买卖点（E156）  
- 零延迟、中间价成交回测订单流  
- 忽视与波动/成交量共线 → 假增量  

## 3. 用法

执行风控（降速/暂停）或 HFT/做市沙箱；公开「预警闪崩」叙事**不可**当本系统 Alpha 保证。

## 4. 本应用

无 L3 全消息流则不做 VPIN 回测绩效宣称；主路径保持日频技术规则。  
