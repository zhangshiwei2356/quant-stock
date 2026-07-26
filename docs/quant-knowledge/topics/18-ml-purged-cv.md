# 18 · 金融 ML 验证纠错（Purged CV）

扩展 [11-validation-corrections.md](11-validation-corrections.md)；对照 ERRATA E20、E29。

## 1. 为何普通 K-Fold 会错

金融样本非 IID：标签常是未来 N 日收益（重叠），特征有序列相关。  
打乱训练 → 测试信息泄漏 → 夏普虚高。合成「不可预测」标签时，naive CV 仍可报出正 R²。

## 2. 必做两步

1. **Purge**：删掉标签窗与测试集时间重叠的训练样本  
2. **Embargo**：测试结束后再空一段，防滚动特征沾测试期  

超参搜索必须用同一套约束，否则选到的是泄漏参数。

## 3. 与 Walk-forward

WFA 仍可能在边界泄漏；多日标签时 IS/OOS 之间加 purge。  
进阶：CPCV 多路径、Deflated Sharpe / PBO 评估过拟合概率。  
专深公式与试次登记：[46-dsr-pbo-validation.md](46-dsr-pbo-validation.md)。

## 4. Purged CV 解决不了什么

特征过多、反复试错、数据修订、宇宙幸存者、成交模型过松——仍须 ERRATA 其它条。  
LLM/新闻情绪另有训练窗前视风险：[61-news-llm-sentiment.md](61-news-llm-sentiment.md)。  
