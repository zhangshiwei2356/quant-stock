# 40 · 基准、IR 与 TE

对照 ERRATA E66–E67、E73；衔接 [25](25-portfolio-opt-risk-model.md)。

## 1. 公式直觉

- Active return = 组合 − 基准  
- TE = active return 波动  
- IR = 主动收益均值 / TE  

**换基准，IR/Alpha 可变甚至变号**；Sharpe（无基准）不变。

## 2. 常见错

| 错误 | 改法 |
|------|------|
| 小盘对大盘基准 | 风格匹配指数 |
| 自选「好打」基准 | 预注册基准 |
| 高 IR 低 TE 自嗨 | 看 active share，防贴指数 |
| 绝对策略硬报 IR | 改报 Sharpe/回撤 |
| 毛费 IR | 净费后比较 |

## 3. 本应用

个股/组合回测偏**绝对收益**统计；若做指数增强产品，须另定基准与 TE 约束。  
