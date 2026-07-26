# 25 · 组合优化与「风险吃掉 Alpha」

对照 ERRATA E35–E36、E43；衔接 [10](10-factor-selection-corrections.md)、[20](20-execution-cost-impact.md)。

## 1. 均值方差为何碎

样本 μ 噪声极大；Σ 病态 → 优化器把误差当机会 → 权重极端、OOS 崩。

**改法**：协方差收缩/因子模型、权重与换手约束、稳健/惩罚项、冲击进目标。

## 2. Risk factors eat alphas

Alpha 因子与风险因子**定义不一致**时，优化器偏好「风险模型看不见」的那截暴露 → 意外下注。

**改法**：对齐因子；把 Alpha 纳入风险模型或 emulation；检查优化后真实因子暴露。

## 3. 产品化

指数增强：最大化 Alpha − 风险惩罚 − 冲击，约束 TE、行业、个股权重。  
勿把绝对收益最优组合直接当增强交付。  

评价口径专深：[40-benchmark-ir.md](40-benchmark-ir.md)（IR / 基准错配）；组合冲击衔接 [41](41-portfolio-impact.md)。  
