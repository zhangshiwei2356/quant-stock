# 11 · 回测与验证纠错清单

扩展 [05-ashare-pitfalls.md](05-ashare-pitfalls.md)；对照 ERRATA E10、E12。

## 1. 数据层

- [ ] as-of 宇宙 / 成分  
- [ ] PIT 财务与修订  
- [ ] 复权口径与用途分离  
- [ ] 停牌不可成交；涨跌停保守  

## 2. 信号层

- [ ] 无全样本归一化  
- [ ] rolling 不吞「未来」bar  
- [ ] 标签与特征无重叠泄漏（必要时 purge）  

## 3. 成交层

- [ ] 成本、滑点、冲击、成交额占比  
- [ ] 信号与成交时点分离  

## 4. 验证层（Walk-forward）

1. 事先写清 IS/OOS/步长/可优化参数  
2. 参数宜少（约 2–4）  
3. 多窗口；报告 OOS **分布**与最差窗口  
4. WFE（OOS/IS）作稳健参考，非利润保证  
5. 参数跨窗口乱跳 → 疑似拟合噪声  
6. Expectancy = \(p\cdot W - (1-p)\cdot L\)；正期望仍须过 WFA  

## 4b. 多重检验（DSR / PBO）

网格或「再试一版」必须登记试验次数 **N**。只报最优 Sharpe 不够。  
专深：[46-dsr-pbo-validation.md](46-dsr-pbo-validation.md)。

## 4c. 路径尾部（Bootstrap）

单次 MaxDD 偏乐观；用块 Bootstrap 看分位。  
专深：[53-bootstrap-drawdown.md](53-bootstrap-drawdown.md)。

## 5. 研发门禁建议

| 门禁 | 不通过则 |
|------|----------|
| 子区间全亏一段 | 查 regime 依赖 |
| 成本加倍后崩 | 换手过高 |
| 参数邻域尖锐 | 简化规则 |
| WFE 过低 | 回炉假设 |  
