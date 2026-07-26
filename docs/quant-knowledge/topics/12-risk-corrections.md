# 12 · 仓位风控纠错

扩展 [06-risk-position.md](06-risk-position.md)；对照 ERRATA E05、E14。

## 对比表

| 方法 | 常见错误 | 修正 |
|------|----------|------|
| 固定股数 | 波动放大时风险失控 | 按风险或波动缩放 |
| 固定 % 止损 | 低波过宽/高波过紧 | ATR 倍数 |
| Full Kelly | 回撤与估计误差 | ≤½ Kelly |
| 只控单票 | 组合相关同向爆仓 | 总仓、单日亏、熔断 |
| Trail 过紧 | 趋势中反复扫出 | 放宽 k 或降仓保距离 |

## 跳空与滑点

止损触发价 ≠ 成交价。仓位应按**不利跳空情景**压力测试，而非理想触及价。  
专深：[38-gap-risk-stops.md](38-gap-risk-stops.md)（E63、E70）。

## 组合相关（假分散）

只控单票/名义总仓不够；同主题高相关会使有效风险远大于「票数」。  
专深：[37-portfolio-heat.md](37-portfolio-heat.md)（E61、E69、E74）。

## 与本应用

「30%×ATR调节×回撤系数」是波动缩放的工程近似；研发新策略时可并行评估纯 \(r/(k\cdot ATR)\) 并对照。  
全 Kelly 过赌与分数 Kelly 上界：[76](76-kelly-fractional-sizing.md)；压力相关：[77](77-correlation-spike.md)。  
