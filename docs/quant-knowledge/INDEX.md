# 量化知识库（本地研习笔记）

> **不构成投资建议。** 只完善文档，**不改交易逻辑代码**。  
> 更新：2026-07-25 · **共 171210 轮**（1210 精细批 + 万轮×17）  
> **研发优先**：[212 P0 债务矩阵](topics/212-p0-debt-matrix.md)（停扩万轮，先落地可小改 P0）

## 怎么读

1. **上线门禁**：[36](topics/36-go-live-checklist.md)  
2. **纠错总表**：[ERRATA](errata/ERRATA.md)（E01–E350）  
3. **本应用**：[07](topics/07-map-to-this-app.md)  
4. **研发开题**：[14](topics/14-rnd-backlog.md)（§Q–Z、§AA–AG）· **[212 债务矩阵](topics/212-p0-debt-matrix.md)**  
5. **万轮入口**  
   - ①–⑯：catalog-10k…160k · mega…mega16  
   - **⑰**：[catalog-170k](topics/catalog-170k.md) · [211](topics/211-mega170k-audit.md) · [mega17/](rounds/mega17/)  
6. 主题 01–212 → MANIFEST → [SOURCES](sources/SOURCES.md)

## 第十七万轮新增专文

| 文 | 内容 |
|----|------|
| [catalog-170k](topics/catalog-170k.md) | **AJ01–AJ100 簇目录** |
| [205](topics/205-factor-preprocess-pipeline.md) | 因子预处理流水线 |
| [206](topics/206-missing-imputation-lookahead.md) | 缺失填充前视 |
| [207](topics/207-ic-decay-monitor.md) | IC 衰减监控 |
| [208](topics/208-factor-orthogonalization.md) | 因子正交 |
| [209](topics/209-rights-seo-actions.md) | 配股增发 |
| [210](topics/210-exit-rule-ensemble.md) | 退出规则组合 |
| [211](topics/211-mega170k-audit.md) | **第十七万轮审计 · P0-123…127** |
| [212](topics/212-p0-debt-matrix.md) | **P0-88…127 对照代码债务矩阵（研发优先）** |

## 速查

| 问题 | 文档 |
|------|------|
| 下一步该落地哪些 P0？ | [212](topics/212-p0-debt-matrix.md) |
| 第十七万轮从哪读？ | [catalog-170k](topics/catalog-170k.md) |
| 预处理正确顺序？ | [205](topics/205-factor-preprocess-pipeline.md) |
| 缺失值怎么填才不泄漏？ | [206](topics/206-missing-imputation-lookahead.md) |
| 多退出规则谁优先？ | [210](topics/210-exit-rule-ensemble.md) |
| 能不能上线？ | [36](topics/36-go-live-checklist.md) |

## 迭代轮次

| 批 | 轮次 | 清单 |
|----|------|------|
| 精细批 | R01–R1210 | 各 MANIFEST |
| 万轮①–⑯ | R1211–R161210 | catalog-10k…160k |
| **万轮⑰** | R161211–R171210 | [M](rounds/R161211-R171210-MANIFEST.md) |

每轮模式：承上不足 → 对比错误 → 解析 → 优化沉淀（块文件存储）。  

**储备边界**：知识库轮次 ≠ 绩效；冲突以 `rules.html` / 代码为准。  
