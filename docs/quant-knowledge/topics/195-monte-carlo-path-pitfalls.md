# 195 · 蒙特卡洛路径陷阱

对照第十五万轮 AH18–AH21、AH30、AH64、AH93；衔接 [46](46-dsr-pbo-validation.md)、[170](170-purged-cv-walkforward.md)、[163](163-research-reproducibility.md)。

## 1. 核心错误

单路径当稳健；打乱破坏自相关当真实；无成本 MC；用 MC 替代 WFA；路径数当 DSR trials；无种子哈希。

## 2. 改法

报告分布非单点；保留时序结构或显式声明；成本同开；MC 辅助非替代 purge/WFA；种子+配置哈希；不改开仓公式。

## 3. 本应用

MC 属验证门禁工具；金叉主路径锁定。
