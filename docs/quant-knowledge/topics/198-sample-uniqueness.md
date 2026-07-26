# 198 · 样本唯一性与并发权重

对照第十六万轮 AI01–AI04、AI25、AI36、AI60；衔接 [191](191-triple-barrier-labeling.md)、[170](170-purged-cv-walkforward.md)、[46](46-dsr-pbo-validation.md)。

## 1. 核心错误

重叠标签当独立样本；IC/学习被重复事件灌水；等权所有并发事件；加权后仍不 purge。

## 2. 改法

并发/唯一性权重；与 purge/embargo 同开；报告有效样本量；权重属验证层，不改金叉方向。

## 3. 本应用

研究样本工程；主路径仍金叉+RSI 滤。
