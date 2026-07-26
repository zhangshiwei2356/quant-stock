# 98 · 因子中性化时点

对照万轮簇 T28；衔接 [10](10-factor-selection-corrections.md)、[25](25-portfolio-opt-risk-model.md)、[44](44-universe-pit-survivorship.md)。

## 1. 常见错误

- 行业/市值用终值分类做历史中性  
- 过度中性后误以为无风险；共线堆因子  
- 中性权重当开仓触发  

## 2. 改法

行业与股本 **as-of**；残差检验；中性是风险控制不是 Alpha 保证。

## 3. 本应用

若做多因子池：中性在选股层声明；开仓仍金叉或显式事件规则。  
