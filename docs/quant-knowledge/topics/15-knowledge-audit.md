# 15 · 知识库自检：旧文修订摘要

本轮（R011–R110）对 R01–R10 产物的修订结论。

## 已纠正的表述风险

1. **04 文**曾列「买入侧避免 RSI 超买」——方向对，但易被读成「70 卖出」。现以 [09](09-entry-exit-corrections.md) 与 ERRATA E01 为准：区分过滤追高 vs 反转做空。  
2. **02 文**趋势/回归弱点已有，但缺 regime 开关 → 补 [13](13-regime-switching.md)。  
3. **03 文**有中性化一句，缺「回归残差 vs 减均值」对比 → 补 [10](10-factor-selection-corrections.md)。  
4. **06 文**有 Kelly，缺「正期望≠可满仓」强调 → 补 [12](12-risk-corrections.md)。  
5. **05 文**有复权坑，缺 WFA 门禁 → 补 [11](11-validation-corrections.md)。  

## 仍以本应用为准的事实

- 金叉定义、RSI\<60、ATR 调节、2×ATR 止损、1.5×ATR trail、池仓分离等，见 [07](07-map-to-this-app.md)。  
- 知识库数字（胜率提升百分比、WFE 阈值）来自公开资料量级，**不得**当作本系统承诺。  

## 阅读顺序（研发向）

ERRATA（E01–E30）→ 08–15 → **16–22** → 14 backlog → 对照 07。

## 第二批（R111–R210）修订摘要

- 纠正「美股隔夜溢价」默认假设 → [16](16-ashare-overnight-auction.md)  
- 补北向内生性、PEAD 公告日 → [17](17-event-northbound-pead.md)  
- 补 Purged CV（相对仅 WFA）→ [18](18-ml-purged-cv.md)  
- 补配对全样本 β 崩塌教训 → [19](19-pairs-trading-corrections.md)  
- 补冲击四层成本 → [20](20-execution-cost-impact.md)  
- 明确本应用金字塔=加赢非摊平 → [22](22-pyramid-vs-averaging.md)  

## 第三批（R211–R310）修订摘要

- 动量/反转周期与行业翻转 → [23](23-momentum-reversal-rotation.md)  
- 固定持有期标签纠错 → [24](24-triple-barrier-meta.md)  
- MVO / 风险吃 Alpha → [25](25-portfolio-opt-risk-model.md)  
- 打板负期望与成交幻觉 → [26](26-limit-up-board-pitfalls.md)  
- 选股→成交对齐 → [27](27-selection-to-execution-checklist.md)  
- 范式矩阵 → [29](29-paradigm-matrix.md)  

阅读：ERRATA（E01–E45）→ 08–29 → 14 → 07。  

## 第四批（R311–R410）修订摘要

- 拥挤/容量 → [30](30-crowding-capacity.md)  
- 执行落差与 IS → [31](31-execution-gap-is.md)  
- L2 误用边界 → [32](32-orderbook-l2-pitfalls.md)  
- 转债强赎/ETF 溢价 → [33](33-cbond-etf-pitfalls.md)  
- 半衰期×执行 → [34](34-signal-half-life-execution.md)  
- 合并上线门禁 → [36](36-go-live-checklist.md)  

阅读：ERRATA（E01–E60）→ 08–36 → 14（P0）→ 07。  

## 第五批（R411–R510）修订摘要

- 组合 Heat / 假分散 → [37](37-portfolio-heat.md)  
- 跳空 vs 止损成交乐观假设 → [38](38-gap-risk-stops.md)  
- 带宽再平衡（禁并入金叉）→ [39](39-rebalancing-bands.md)  
- 基准错配与 IR → [40](40-benchmark-ir.md)  
- 组合冲击 → [41](41-portfolio-impact.md)  
- 审计 / P0 切片 → [42](42-batch5-audit.md)、[43](43-p0-engineering-slices.md)  
- 门禁升级 → [36](36-go-live-checklist.md)（五批合并）  

阅读：ERRATA（E01–E75）→ 08–43 → 14（§I）→ 07 → 上线前 36。  

## 第六批（R511–R610）修订摘要

- 时点宇宙 / 退市 / 借壳 → [44](44-universe-pit-survivorship.md)  
- 复权分工 → [45](45-corporate-actions-adj.md)  
- DSR/PBO/MinBTL → [46](46-dsr-pbo-validation.md)  
- 多周期确认 → [47](47-mtf-confirmation.md)  
- A股日历（非主引擎）→ [48](48-ashare-calendar.md)  
- 价值陷阱 × 质量 → [49](49-value-quality-trap.md)  
- 审计 / 运维 / P0-8…12 → [50](50-batch6-audit-ops.md)  

阅读：ERRATA（E01–E90）→ 08–50 → 14（§J）→ 07 → 上线前 36。  

## 第七批（R611–R710）修订摘要

- 解禁/减持 → [51](51-unlock-reduce-events.md)  
- 盈余质量/红旗 → [52](52-earnings-quality-fraud.md)  
- Bootstrap MDD → [53](53-bootstrap-drawdown.md)  
- 题材禁并金叉 → [54](54-theme-concept-pitfalls.md)  
- 信号正交集成 → [55](55-signal-ensemble.md)  
- 参与率定仓 → [56](56-participation-sizing.md)  
- 审计 / P0-13…17 → [57](57-batch7-audit.md)  

阅读：ERRATA（E01–E105）→ 08–57 → 14（§K）→ 07 → 上线前 36。  

## 第八批（R711–R810）修订摘要

- 指数调仓 → [58](58-index-reconstitution.md)  
- 两融约束 → [59](59-margin-short-constraints.md)  
- 止损形态 → [60](60-stop-placement.md)  
- LLM 情绪前视 → [61](61-news-llm-sentiment.md)  
- CS vs TS 语义 → [62](62-cs-vs-ts-momentum.md)  
- 费率分段 → [63](63-fee-regime-turnover.md)  
- 审计 / P0-18…22 → [64](64-batch8-audit.md)  

阅读：ERRATA（E01–E120）→ 08–64 → 14（§L）→ 07 → 上线前 36。  

## 第九批（R811–R910）修订摘要

- 次新/IPO 过滤 → [65](65-ipo-xinxin-filter.md)  
- ST 时点 → [66](66-st-pit-filter.md)  
- VWAP/TWAP/IS → [67](67-vwap-twap-execution.md)  
- 突破 vs 回踩 → [68](68-breakout-vs-pullback.md)  
- 因子择时 → [69](69-factor-timing-regime.md)  
- 回撤持续期 → [70](70-drawdown-duration.md)  
- 审计 / P0-23…27 → [71](71-batch9-audit.md)  

阅读：ERRATA（E01–E135）→ 08–71 → 14（§M）→ 07 → 上线前 36。  

## 第十批（R911–R1010）修订摘要

- 开盘集合竞价 → [72](72-opening-auction.md)  
- 尾盘 / 盘后 → [73](73-closing-auction-afterhours.md)  
- Volume Profile → [74](74-volume-profile.md)  
- PEAD → [75](75-pead-earnings-drift.md)  
- Kelly 定仓 → [76](76-kelly-fractional-sizing.md)  
- 相关尖峰 → [77](77-correlation-spike.md)  
- 审计 / P0-28…32 → [78](78-batch10-audit.md)  

阅读：ERRATA（E01–E150）→ 08–78 → 14（§N）→ 07 → 上线前 36。  

## 第十一批（R1011–R1110）修订摘要

- 分析师修正 PIT → [79](79-analyst-revision-pit.md)  
- tick / 整手 → [80](80-tick-lot-constraints.md)  
- 波动率目标 → [81](81-volatility-targeting.md)  
- 风险平价误区 → [82](82-risk-parity-pitfalls.md)  
- VPIN / OFI → [83](83-orderflow-vpin.md)  
- 停牌 / 复牌 → [84](84-suspension-halt.md)  
- 审计 / P0-33…37 → [85](85-batch11-audit.md)  

阅读：ERRATA（E01–E165）→ 08–85 → 14（§O）→ 07 → 上线前 36。  

## 第十二批（R1111–R1210）修订摘要

- 动态复权前视 → [86](86-exdiv-dynamic-adj-lookahead.md)  
- 元标签 → [87](87-meta-labeling.md)  
- Alpha 衰减/容量 → [88](88-alpha-decay-capacity.md)  
- 隔夜/日内 → [89](89-overnight-intraday-tug.md)  
- 伪高息/抢权 → [90](90-dividend-yield-trap.md)  
- 红利税/结算 → [91](91-div-tax-settlement.md)  
- 审计 / P0-38…42 → [92](92-batch12-audit.md)  

阅读：ERRATA（E01–E180）→ 08–92 → 14（§P）→ 07 → 上线前 36。  

## 万轮（R1211–R11210）修订摘要

- 簇目录 → [catalog-10k.md](catalog-10k.md)  
- 龙虎榜 PIT → [93](93-longhubang-seat-pit.md)  
- 可转债条款 → [94](94-cbond-deep-clauses.md)  
- 打板情绪沙箱 → [95](95-limit-board-sentiment.md)  
- 事件时钟 → [96](96-event-driven-clock.md)  
- Kill Switch → [97](97-ops-killswitch.md)  
- 因子中性化 → [98](98-factor-neutralization-pit.md)  
- 审计 / P0-43…47 → [99](99-mega10k-audit.md)  
- 正文块 → `rounds/mega/B001`…`B100`（10000 轮）  

阅读：ERRATA（E01–E190）→ catalog-10k → 93–99 → 14（§Q）→ 07 → 上线前 36。  

## 第二万轮（R11211–R21210）修订摘要

- 目录 → [catalog-20k.md](catalog-20k.md)  
- 另类数据 → [100](100-alt-data-pitfalls.md)  
- 大宗/两融 → [101](101-block-trade-margin-balance.md)  
- 筹码/户数 → [102](102-chip-shareholder-count.md)  
- 跨境/ETF → [103](103-crossborder-etf-boundary.md)  
- 特征点时 → [104](104-feature-store-point-in-time.md)  
- 程序化合规 → [105](105-program-trading-compliance.md)  
- 审计 / P0-48…52 → [106](106-mega20k-audit.md)  

阅读：ERRATA（E01–E200）→ catalog-20k → 100–106 → 14（§R）→ 07 → 36。  

## 第三万轮（R21211–R31210）修订摘要

- 目录 → [catalog-30k.md](catalog-30k.md)  
- RL 陷阱 → [107](107-rl-trading-pitfalls.md)  
- 模拟器偏差 → [108](108-market-simulator-bias.md)  
- 程序化细则 → [109](109-program-trading-reg-detail.md)  
- RL×硬风控 → [110](110-rl-risk-hard-constraints.md)  
- 合规进回测 → [111](111-compliance-metrics-in-backtest.md)  
- 影子闸门 → [112](112-shadow-mode-gate.md)  
- 审计 / P0-53…57 → [113](113-mega30k-audit.md)  

阅读：ERRATA（E01–E210）→ catalog-30k → 107–113 → 14（§S）→ 07 → 36。  

## 第四万轮（R31211–R41210）修订摘要

- 目录 → [catalog-40k.md](catalog-40k.md)  
- 多因子选股 → [114](114-multifactor-selection-pitfalls.md)  
- 衰减/拥挤/政权 → [115](115-alpha-decay-regime.md)  
- 量价信号 → [116](116-volume-price-signals.md)  
- 行业轮动 → [117](117-industry-rotation-pitfalls.md)  
- 形态伪精度 → [118](118-pattern-false-precision.md)  
- 卖点闸门 → [119](119-exit-sell-gates.md)  
- 审计 / P0-58…62 → [120](120-mega40k-audit.md)  

阅读：ERRATA（E01–E220）→ catalog-40k → 114–120 → 14（§T）→ 07 → 36。  

## 第五万轮（R41211–R51210）修订摘要

- 目录 → [catalog-50k.md](catalog-50k.md)  
- 集合竞价 → [121](121-call-auction-signals.md)  
- 同K/缺口 → [122](122-samebar-gap-breakout.md)  
- PEAD → [123](123-pead-earnings-drift.md)  
- ADV过滤 → [124](124-liquidity-adv-filter.md)  
- 增强/配对 → [125](125-index-enhance-pairs.md)  
- 再平衡 → [126](126-rebalance-scaling-calendar.md)  
- 审计 / P0-63…67 → [127](127-mega50k-audit.md)  

阅读：ERRATA（E01–E230）→ catalog-50k → 121–127 → 14（§U）→ 07 → 36。  

## 第六万轮（R51211–R61210）修订摘要

- 目录 → [catalog-60k.md](catalog-60k.md)  
- VWAP/TWAP → [128](128-vwap-twap-execution.md)  
- 隔夜-日内 → [129](129-overnight-intraday-split.md)  
- vol目标仓 → [130](130-vol-targeting-sizing.md)  
- 回撤降险 → [131](131-drawdown-derisk.md)  
- 截面/时序动量 → [132](132-cs-ts-momentum.md)  
- 公司行为 → [133](133-corporate-action-signals.md)  
- 审计 / P0-68…72 → [134](134-mega60k-audit.md)  

阅读：ERRATA（E01–E240）→ catalog-60k → 128–134 → 14（§V）→ 07 → 36。  

## 第七万轮（R61211–R71210）修订摘要

- 目录 → [catalog-70k.md](catalog-70k.md)  
- IS/TCA → [135](135-is-decomposition-tca.md)  
- 质量因子 → [136](136-quality-factor-pitfalls.md)  
- 低波异象 → [137](137-lowvol-anomaly-pitfalls.md)  
- 分析师修订 → [138](138-analyst-revision-pitfalls.md)  
- 熔断停牌 → [139](139-circuit-halt-calendar.md)  
- 限售解禁 → [140](140-lockup-unlock-pressure.md)  
- 审计 / P0-73…77 → [141](141-mega70k-audit.md)  

阅读：ERRATA（E01–E250）→ catalog-70k → 135–141 → 14（§W）→ 07 → 36。  

## 第八万轮（R71211–R81210）修订摘要

- 目录 → [catalog-80k.md](catalog-80k.md)  
- 指标确认 → [142](142-indicator-confirm-pitfalls.md)  
- 挤压突破 → [143](143-squeeze-breakout-pitfalls.md)  
- 季节口诀 → [144](144-ashare-seasonality.md)  
- 分红捕获 → [145](145-dividend-capture-pitfalls.md)  
- 期指基差 → [146](146-index-futures-basis-boundary.md)  
- 增减持 → [147](147-insider-filing-pitfalls.md)  
- 审计 / P0-78…82 → [148](148-mega80k-audit.md)  

阅读：ERRATA（E01–E260）→ catalog-80k → 142–148 → 14（§X）→ 07 → 36。  

## 第九万轮（R81211–R91210）修订摘要

- 目录 → [catalog-90k.md](catalog-90k.md)  
- 题材轮动 → [149](149-theme-rotation-pitfalls.md)  
- 政策事件 → [150](150-policy-event-trading.md)  
- 北向资金 → [151](151-northbound-flow-timing.md)  
- 回撤/突破 → [152](152-pullback-vs-breakout.md)  
- 多周期时钟 → [153](153-mtf-entry-clock.md)  
- 幸存者退市 → [154](154-survivorship-delisting.md)  
- 审计 / P0-83…87 → [155](155-mega90k-audit.md)  

阅读：ERRATA（E01–E270）→ catalog-90k → 149–155 → 14（§Y）→ 07 → 36。  

## 第十万轮（R91211–R101210）修订摘要

- 目录 → [catalog-100k.md](catalog-100k.md)  
- 信号融合 → [156](156-signal-ensemble-gating.md)  
- 容量AUM → [157](157-capacity-aum-scaling.md)  
- 漂移监控 → [158](158-signal-drift-monitor.md)  
- 基准选择 → [159](159-benchmark-selection-pitfalls.md)  
- 纸面实盘 → [160](160-paper-live-reconciliation.md)  
- 策略退役 → [161](161-strategy-retirement.md)  
- 审计 / P0-88…92 → [162](162-mega100k-audit.md)  

阅读：ERRATA（E01–E280）→ catalog-100k → 156–162 → 14（§Z）→ 07 → 36。  
**里程碑**：万轮×10 收束；知识库轮次 ≠ 绩效。  
