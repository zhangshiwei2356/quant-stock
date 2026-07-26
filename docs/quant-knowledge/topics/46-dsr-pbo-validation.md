# 46 · DSR / PBO / MinBTL（验证深化）

对照 ERRATA E82–E83；扩展 [11](11-validation-corrections.md)、[18](18-ml-purged-cv.md)。

## 1. 问题

搜参 / 多策略比较后，**期望最大 Sharpe** 即使真实 SR=0 也会 >0。只报最优曲线 = 选择偏差。

## 2. 工具分工

| 工具 | 作用 |
|------|------|
| **DSR** | 校正多重检验 + 非正态后的 Sharpe 显著性 |
| **PBO**（CSCV） | 估计「选中的策略 OOS 差于中位」的概率 |
| **MinBTL** | 给定试次 N，避免过拟合所需的最小回测年数量级 |

输入关键：独立试验次数 **N**、各试 SR 的离散度、样本长度、偏度/峰度。

## 3. 常见错

- 不登记 N（含手工「再试一版」）  
- N=1 却宣称已防过拟合  
- 只看 IS Sharpe，不看 OOS 分布 / WFE  

## 4. 实践

1. 研究日志强制记试次与参数网格  
2. 试次多 → 提高接受阈值或加长样本（MinBTL 直觉）  
3. 与 WFA、成本压力、子区间并联，非替代  

## 5. 本应用

金叉参数（MA 周期、RSI 阈、ATR 倍）一旦网格化，必须计 N 并过 DSR/WFA，才能进 [36](36-go-live-checklist.md)。  

路径不确定性另见 [53-bootstrap-drawdown.md](53-bootstrap-drawdown.md)（与 DSR 并联，不替代）。  
