# 110 · RL 与风控硬约束联立

对照 V37–V40、V48、V80；衔接 [50](50-batch6-audit-ops.md)、[37](37-portfolio-heat.md)、[56](56-participation-sizing.md)。

## 1. 原则

RL 输出仓位必须经过：单票/总仓硬顶、Heat、参与率、熔断——**取更严者**。

## 2. 错误

RL 绕过熔断；多标的独立训练忽略相关尖峰。

## 3. 本应用

硬门控不可软化；RL 沙箱默认关闭。  
