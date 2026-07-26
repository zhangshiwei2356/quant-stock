# 53 · Bootstrap / 蒙特卡洛与回撤尾部

对照 ERRATA E95–E96、E103；衔接 [11](11-validation-corrections.md)、[46](46-dsr-pbo-validation.md)。

## 1. 单次 MaxDD 不够

历史最大回撤是**一条路径**的极端统计量，不能当未来上限。资本规划应看尾部分位。

## 2. iid Bootstrap 的错

逐 bar/逐笔独立重抽样会毁掉自相关与波动聚集 → **MDD 置信区间偏乐观**（覆盖常远差于名义水平）。

**改法**：块 Bootstrap / 平稳 Bootstrap；块长贴近相关尺度；分位作**下界**再加波动聚集压力。

## 3. 与其它验证的分工

| 工具 | 回答 |
|------|------|
| WFA | 时间外推 / 参数漂移 |
| DSR/PBO | 选择偏差 / 多重检验 |
| Bootstrap MC | 同过程下路径不确定性 |

三者**并联**，MC 不能替代 DSR。

## 4. 本应用

回测报告除点估计外，可开题输出收益序列的块 Bootstrap MDD/Sharpe 分位；对照 `rules` 回撤熔断是否在压力分位下仍可接受。深度之外须报**持续期/恢复期**，见 [70](70-drawdown-duration.md)。  
