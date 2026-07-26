# 07 · 映射到本应用（只对照，不改代码）

> 权威来源：`src/main/resources/static/docs/rules.html`、`risk.html`、`ma.html`、`atr.html` 与对应 Java 实现。  
> 本文帮助把通用量化知识「落到」本仓库已有能力上。

## 1. 能力分层对照

| 知识层 | 本应用对应 |
|--------|------------|
| L0 硬过滤 | `OpenFilterService`：涨跌停、停牌、流动性、市值、分钟静默 |
| L1 选股/池 | `TradePoolService`：宇宙粗筛（`factor_daily` 趋势门）→ 扫描打分 → TopN 目标池 |
| L2 入场 | 金叉首开 + RSI/ATR 等过滤；可选 MA60/放量/ADX |
| L3 出场 | 死叉、ATR 止损、移动止盈、跌停强平路径 |
| L4 账户风控 | 单日亏、连亏、回撤降仓/熔断 |

## 2. 选股：本应用怎么做

1. **粗筛（有 factor_daily 时）**：保留 `ma5>ma20` **或** `ma60` 向上 **或** 放量突破类条件。  
2. **扫描打分**：综合分 ≥ `poolScoreMin`；可入池条件含「金叉可买」或「MA5>MA20 且 RSI 未过热」等（以代码/报告文案为准）。  
3. **语义**：目标池 ≠ 持仓；移出池不自动卖出。  

对照通用多因子：[03-stock-selection.md](03-stock-selection.md) 中的估值/质量/中性化在本应用**未完整实现**——当前偏技术面趋势池。

## 3. 买入点：本应用公式

**金叉**（收盘确认）：

- 上一根 MA5 ≤ MA20，本根 MA5 > MA20  

**必过过滤（默认）**：

- RSI14 < 60；ATR14 > 0.001  
- 模拟市值门槛（可关）  

**仓位**：

\[
\text{目标股数} = \frac{\text{可用资金}\times 30\% \times ATR调节 \times 仓位系数}{\text{现价}}\ \text{取整手}
\]

- ATR调节 = `0.05 / ATR`，夹到 `[0.2, 1.5]`  
- 仓位系数：回撤 <15%→1；[15%,25%)→0.5；≥25%→0  
- 总仓 ≤ 权益 × 80%  
- 金字塔：浮盈达标后 50%/30%/20% 加仓切片（默认开）

**撮合**：日 K 默认次日开盘；分钟路径信号日次日 ≥09:45。

## 4. 卖出点：本应用公式

| 类型 | 规则摘要 |
|------|----------|
| 死叉 | 挂卖，下一有效撮合清该标的（含今仓） |
| 止损 | max(成本−2×ATR, 成本−权益×2%/股数)，只上移；**仅老仓**（T+1） |
| 移动止盈 | 持仓最高 − 1.5×ATR，只上移；次日盘中老仓触及 |
| 熔断 | 峰值回撤 ≥25% → 挂清仓+禁新开（粘性） |

同日优先级（回测）：先止损/trail；若已止损清仓则忽略同日死叉。

## 5. 通用知识 vs 本应用差异（学习时注意）

| 通用做法 | 本应用现状 |
|----------|------------|
| 纯 \(Equity\times r/(k\cdot ATR)\) 风险预算 | 用「资金比例 × ATR 调节」近似波动缩放 |
| 多因子 ICIR + 行业中性 | 目标池以技术/回测扫描分为主 |
| 涨跌停概率成交模型 | 软阈值拒开；跌停卖失败累计后强平 |
| 最低佣金 5 元 | 规则文档：佣金万三，**无**最低 5 元 |
| PIT 基本面 | 当前主路径为行情/技术因子 |

## 6. 建议阅读顺序（站内）

1. 本文件 + `rules.html`  
2. `ma.html` / `atr.html` / `rsi.html` / `tplus1.html` / `limit.html`  
3. 通用深化：本库 `01`–`06`  
4. 纠错与进阶：`08`–`15`，第二批 `16`–`22`  
5. 陷阱自检：`05` + `errata/ERRATA.md`（含 E16+）  

### 第二批补充对照指针

| 主题 | 知识文 | 本应用相关 |
|------|--------|------------|
| 开盘噪声 / 静默 | [16](16-ashare-overnight-auction.md) | 分钟静默 09:30–09:45 |
| 金字塔加赢 | [22](22-pyramid-vs-averaging.md) | 浮盈≥1% + 50/30/20 |
| 冲击/滑点 | [20](20-execution-cost-impact.md) | 分级滑点 + 冲击公式 |
| 验证深化 | [18](18-ml-purged-cv.md) | 回测引擎可后续接 purge |
| **打板（对立）** | [26](26-limit-up-board-pitfalls.md) | **涨停拒开**，主路径非打板 |
| **路径标签** | [24](24-triple-barrier-meta.md) | 止损/trail 应对齐标签障碍 |
| **拥挤/容量** | [30](30-crowding-capacity.md) | 池与仓位可后续接拥挤降权 |
| **执行落差** | [31](31-execution-gap-is.md) | 分级滑点≠纸面冲击；模拟盘验机械 |
| **L2/转债/ETF** | [32](32-orderbook-l2-pitfalls.md)/[33](33-cbond-etf-pitfalls.md) | **禁止并入主金叉路径** |
| **组合 Heat** | [37](37-portfolio-heat.md) | 总仓≤80%≠风险分散；待 P0-1/2 |
| **跳空止损** | [38](38-gap-risk-stops.md) | 回测按止损价成交偏乐观；对照 rules |
| **再平衡** | [39](39-rebalancing-bands.md) | **主路径不加**日频配置再平衡 |
| **IR/基准** | [40](40-benchmark-ir.md) | 单股回测偏绝对收益，勿硬套 IR |
| **P0 切片** | [43](43-p0-engineering-slices.md) | 工程开题，非已实现 |
| **时点宇宙** | [44](44-universe-pit-survivorship.md) | 池扫描应 as-of；缺数据须声明 |
| **复权分工** | [45](45-corporate-actions-adj.md) | 信号复权 / 撮合不复权 |
| **DSR/试次** | [46](46-dsr-pbo-validation.md) | MA/RSI/ATR 网格须计 N |
| **多周期滤** | [47](47-mtf-confirmation.md) | 可选 MA60/周线同向；已有 RSI/ADX |
| **日历** | [48](48-ashare-calendar.md) | **禁止**作主买卖引擎 |
| **价值质量** | [49](49-value-quality-trap.md) | 基本面沙箱；禁裸估值并金叉 |
| **运维 P0** | [50](50-batch6-audit-ops.md) | 风控硬门控；P0-8…12 |
| **解禁/减持** | [51](51-unlock-reduce-events.md) | 可选事件降仓；非开仓主信号 |
| **盈余红旗** | [52](52-earnings-quality-fraud.md) | 基本面沙箱减法 |
| **Bootstrap MDD** | [53](53-bootstrap-drawdown.md) | 报告分位；对照回撤熔断 |
| **题材** | [54](54-theme-concept-pitfalls.md) | **禁止**并入金叉；可服务 Heat |
| **信号正交** | [55](55-signal-ensemble.md) | 保持主信号+滤网；勿堆同源指标 |
| **参与率定仓** | [56](56-participation-sizing.md) | 与 ATR 仓位取更严 |
| **批七 P0** | [57](57-batch7-audit.md) | P0-13…17 开题 |
| **指数调仓** | [58](58-index-reconstitution.md) | 可选降权；禁作主开仓 |
| **两融** | [59](59-margin-short-constraints.md) | 主路径纯多；禁自由卖空回测 |
| **止损形态** | [60](60-stop-placement.md) | 对照 2×ATR 止损 / 1.5×ATR trail |
| **LLM 情绪** | [61](61-news-llm-sentiment.md) | **禁止**训练窗回测当绩效 |
| **CS vs TS** | [62](62-cs-vs-ts-momentum.md) | 池可相对强弱；开仓仍金叉 |
| **费率分段** | [63](63-fee-regime-turnover.md) | 长回测对照 rules 现行费 |
| **批八 P0** | [64](64-batch8-audit.md) | P0-18…22 开题 |
| **次新过滤** | [65](65-ipo-xinxin-filter.md) | 对齐 OpenFilter 上市日/涨停拒开 |
| **ST 时点** | [66](66-st-pit-filter.md) | as-of 排除；摘帽沙箱另册 |
| **VWAP/TWAP** | [67](67-vwap-twap-execution.md) | 主看 IS；避开盘薄量 |
| **突破/回踩** | [68](68-breakout-vs-pullback.md) | 主路径金叉；突破须开关 |
| **因子择时** | [69](69-factor-timing-regime.md) | 非主引擎；轻量 regime 已有 ADX/MA60 |
| **回撤持续期** | [70](70-drawdown-duration.md) | 对照 25% 熔断 + 报告水下时间 |
| **批九 P0** | [71](71-batch9-audit.md) | P0-23…27 开题 |
| **开盘竞价** | [72](72-opening-auction.md) | 价源分字段；打板腿须开关 |
| **尾盘/盘后** | [73](73-closing-auction-afterhours.md) | close≠连续市价；盘后部分成交 |
| **Volume Profile** | [74](74-volume-profile.md) | 合流过滤；禁 VPVR 回测 |
| **PEAD** | [75](75-pead-earnings-drift.md) | 公告日 PIT；事件沙箱 |
| **Kelly 定仓** | [76](76-kelly-fractional-sizing.md) | 分数 Kelly 上界；不覆盖硬顶 |
| **相关尖峰** | [77](77-correlation-spike.md) | 压力情景；对齐 Heat |
| **批十 P0** | [78](78-batch10-audit.md) | P0-28…32 开题 |
| **分析师修正** | [79](79-analyst-revision-pit.md) | PIT 沙箱；禁并金叉 |
| **tick/整手** | [80](80-tick-lot-constraints.md) | 对齐 rules 撮合 |
| **波动目标** | [81](81-volatility-targeting.md) | 可选缩放；不关熔断 |
| **风险平价** | [82](82-risk-parity-pitfalls.md) | 非默认；压力相关 |
| **VPIN/OFI** | [83](83-orderflow-vpin.md) | 禁日频主开仓 |
| **停牌状态** | [84](84-suspension-halt.md) | 拒成断言优先 |
| **批十一 P0** | [85](85-batch11-audit.md) | P0-33…37 开题 |
| **动态复权** | [86](86-exdiv-dynamic-adj-lookahead.md) | as-of 因子；深化 45 |
| **元标签** | [87](87-meta-labeling.md) | 只滤/定仓；不改方向 |
| **Alpha 容量** | [88](88-alpha-decay-capacity.md) | 规模曲线；拥挤非 Alpha |
| **隔夜/日内** | [89](89-overnight-intraday-tug.md) | 分解报告；慎照搬美股 |
| **伪高息** | [90](90-dividend-yield-trap.md) | 质量过滤；禁抢权并金叉 |
| **红利税/结算** | [91](91-div-tax-settlement.md) | 税则+资金状态机 |
| **批十二 P0** | [92](92-batch12-audit.md) | P0-38…42 开题 |
| **万轮目录** | [catalog-10k](catalog-10k.md) | T01–T100 检索 |
| **龙虎榜** | [93](93-longhubang-seat-pit.md) | 沙箱；禁并金叉 |
| **转债条款** | [94](94-cbond-deep-clauses.md) | 分册；禁套股票账户 |
| **打板情绪** | [95](95-limit-board-sentiment.md) | 沙箱；涨停拒开 |
| **事件时钟** | [96](96-event-driven-clock.md) | 三时间戳 |
| **运维停机** | [97](97-ops-killswitch.md) | 硬门控/对账 |
| **因子中性** | [98](98-factor-neutralization-pit.md) | 行业/股本 as-of |
| **万轮① P0** | [99](99-mega10k-audit.md) | P0-43…47 |
| **另类数据** | [100](100-alt-data-pitfalls.md) | 沙箱；可知时点 |
| **大宗/两融余额** | [101](101-block-trade-margin-balance.md) | 禁并金叉 |
| **筹码/户数** | [102](102-chip-shareholder-count.md) | 方法学钉扎或禁用 |
| **跨境/ETF** | [103](103-crossborder-etf-boundary.md) | 分账户/分市场 |
| **特征点时** | [104](104-feature-store-point-in-time.md) | 训练=服务点时 |
| **程序化合规** | [105](105-program-trading-compliance.md) | 报备/版权/监控 |
| **万轮② P0** | [106](106-mega20k-audit.md) | P0-48…52 |
| **RL 陷阱** | [107](107-rl-trading-pitfalls.md) | 沙箱；禁替代金叉 |
| **模拟器偏差** | [108](108-market-simulator-bias.md) | 对齐 rules；影子 IS |
| **程序化细则** | [109](109-program-trading-reg-detail.md) | 报告/阈值/四类异常 |
| **RL×硬风控** | [110](110-rl-risk-hard-constraints.md) | 取更严 |
| **合规进回测** | [111](111-compliance-metrics-in-backtest.md) | 字段或声明缺口 |
| **影子闸门** | [112](112-shadow-mode-gate.md) | 灰度/回滚 |
| **万轮③ P0** | [113](113-mega30k-audit.md) | P0-53…57 |
| **多因子选股** | [114](114-multifactor-selection-pitfalls.md) | 沙箱；禁替代金叉 |
| **衰减/政权** | [115](115-alpha-decay-regime.md) | 监控/降权 |
| **量价信号** | [116](116-volume-price-signals.md) | 沙箱；成本对齐 |
| **行业轮动** | [117](117-industry-rotation-pitfalls.md) | 成分 as-of；行业顶 |
| **形态伪精度** | [118](118-pattern-false-precision.md) | 规则化或禁 |
| **卖点闸门** | [119](119-exit-sell-gates.md) | 买卖对称 |
| **万轮④ P0** | [120](120-mega40k-audit.md) | P0-58…62 |
| **集合竞价** | [121](121-call-auction-signals.md) | 时段状态机；沙箱 |
| **同K/缺口** | [122](122-samebar-gap-breakout.md) | 次开成交；悲观路径 |
| **PEAD** | [123](123-pead-earnings-drift.md) | 事件沙箱；费后 |
| **ADV过滤** | [124](124-liquidity-adv-filter.md) | 参与率/Heat |
| **增强/配对** | [125](125-index-enhance-pairs.md) | 分册；TE |
| **再平衡** | [126](126-rebalance-scaling-calendar.md) | 止损优先 |
| **万轮⑤ P0** | [127](127-mega50k-audit.md) | P0-63…67 |
| **VWAP/TWAP** | [128](128-vwap-twap-execution.md) | 执行≠信号 |
| **隔夜日内** | [129](129-overnight-intraday-split.md) | A股专用分解 |
| **vol目标仓** | [130](130-vol-targeting-sizing.md) | 硬顶覆盖 |
| **回撤降险** | [131](131-drawdown-derisk.md) | 冷却+Kill |
| **截面/时序动量** | [132](132-cs-ts-momentum.md) | 分册沙箱 |
| **公司行为** | [133](133-corporate-action-signals.md) | 日历PIT |
| **万轮⑥ P0** | [134](134-mega60k-audit.md) | P0-68…72 |
| **IS/TCA** | [135](135-is-decomposition-tca.md) | 四分量归因 |
| **质量因子** | [136](136-quality-factor-pitfalls.md) | PIT；池过滤 |
| **低波异象** | [137](137-lowvol-anomaly-pitfalls.md) | 硬顶；ADV |
| **分析师修订** | [138](138-analyst-revision-pitfalls.md) | knowable_ts |
| **熔断停牌** | [139](139-circuit-halt-calendar.md) | 日历状态机 |
| **限售解禁** | [140](140-lockup-unlock-pressure.md) | 事件沙箱 |
| **万轮⑦ P0** | [141](141-mega70k-audit.md) | P0-73…77 |
| **指标确认** | [142](142-indicator-confirm-pitfalls.md) | RSI只滤不开 |
| **挤压突破** | [143](143-squeeze-breakout-pitfalls.md) | 次级滤镜 |
| **季节口诀** | [144](144-ashare-seasonality.md) | 只降权 |
| **分红捕获** | [145](145-dividend-capture-pitfalls.md) | 禁主引擎 |
| **期指基差** | [146](146-index-futures-basis-boundary.md) | 分账户 |
| **增减持** | [147](147-insider-filing-pitfalls.md) | 披露PIT |
| **万轮⑧ P0** | [148](148-mega80k-audit.md) | P0-78…82 |
| **题材轮动** | [149](149-theme-rotation-pitfalls.md) | 沙箱；拥挤 |
| **政策事件** | [150](150-policy-event-trading.md) | 落地时钟 |
| **北向资金** | [151](151-northbound-flow-timing.md) | 验证非触发 |
| **回撤/突破** | [152](152-pullback-vs-breakout.md) | 金叉后择时 |
| **多周期时钟** | [153](153-mtf-entry-clock.md) | 只滤不开 |
| **幸存者退市** | [154](154-survivorship-delisting.md) | 回测门禁 |
| **万轮⑨ P0** | [155](155-mega90k-audit.md) | P0-83…87 |
| **信号融合** | [156](156-signal-ensemble-gating.md) | 只滤不开 |
| **容量AUM** | [157](157-capacity-aum-scaling.md) | 参与率/降频 |
| **漂移监控** | [158](158-signal-drift-monitor.md) | IC+Kill |
| **基准选择** | [159](159-benchmark-selection-pitfalls.md) | 预注册 |
| **纸面实盘** | [160](160-paper-live-reconciliation.md) | 影子闸 |
| **策略退役** | [161](161-strategy-retirement.md) | 冷却+双人 |
| **万轮⑩ P0** | [162](162-mega100k-audit.md) | P0-88…92 |
| **可复现研究** | [163](163-research-reproducibility.md) | 哈希/种子沙箱 |
| **A股订单类型** | [164](164-ashare-order-types.md) | 限价保护 |
| **部成撤改** | [165](165-partial-fill-cancel-replace.md) | 状态机 |
| **压力情景** | [166](166-ashare-stress-scenarios.md) | 预注册 |
| **流动性政权** | [167](167-liquidity-regime.md) | 降仓硬顶 |
| **风险预算** | [168](168-risk-budget-soft-hard.md) | 软硬分层 |
| **万轮⑪ P0** | [169](169-mega110k-audit.md) | P0-93…97 |
| **净化CV/WFA** | [170](170-purged-cv-walkforward.md) | purge+embargo |
| **滑点校准** | [171](171-slippage-online-calibration.md) | 残差日报 |
| **因子择时分册** | [172](172-factor-timing-vs-selection.md) | 只滤不开 |
| **ST路径PIT** | [173](173-st-path-pit.md) | 日切as-of |
| **财报日历** | [174](174-earnings-calendar-pit.md) | 公告可用 |
| **融券边界** | [175](175-securities-lending-pit.md) | 多头边界 |
| **万轮⑫ P0** | [176](176-mega120k-audit.md) | P0-98…102 |
| **组合优化** | [177](177-portfolio-optimization-pitfalls.md) | 收缩+硬顶 |
| **换手印花税** | [178](178-turnover-stamp-tax.md) | 换手进目标 |
| **相关崩溃** | [179](179-risk-model-corr-breakdown.md) | 压力相关 |
| **指数调样** | [180](180-index-reconstitution.md) | 公告as-of |
| **日内时段** | [181](181-intraday-session-effects.md) | 只滤不开 |
| **多源对账** | [182](182-data-vendor-reconciliation.md) | 分歧闸 |
| **万轮⑬ P0** | [183](183-mega130k-audit.md) | P0-103…107 |
| **ATR止损定仓** | [184](184-atr-stop-sizing.md) | 先止损再定仓 |
| **vol目标** | [185](185-vol-targeting-pitfalls.md) | 不关Kill |
| **配对空头边界** | [186](186-pairs-short-boundary.md) | 分册/禁空 |
| **市场宽度** | [187](187-market-breadth-filter.md) | 只滤不开 |
| **事件可交易** | [188](188-event-study-tradability.md) | as-of |
| **POV执行** | [189](189-pov-execution-caps.md) | 参与率帽 |
| **万轮⑭ P0** | [190](190-mega140k-audit.md) | P0-108…112 |
| **三重屏障** | [191](191-triple-barrier-labeling.md) | 对齐实盘 |
| **移动/时间止损** | [192](192-trailing-time-stops.md) | 次开确认 |
| **隔夜缺口** | [193](193-overnight-gap-risk.md) | 开盘成交 |
| **拥挤度PIT** | [194](194-crowding-pit-filter.md) | 只滤降仓 |
| **蒙特卡洛** | [195](195-monte-carlo-path-pitfalls.md) | 辅助非替代 |
| **金字塔纪律** | [196](196-pyramid-add-discipline.md) | 50/30/20 |
| **万轮⑮ P0** | [197](197-mega150k-audit.md) | P0-113…117 |
| **样本唯一性** | [198](198-sample-uniqueness.md) | 并发权重 |
| **分数差分** | [199](199-fractional-diff-pitfalls.md) | d预注册 |
| **结构突变** | [200](200-structural-break-vs-memory.md) | 冷却/Kill |
| **行业PIT** | [201](201-industry-class-pit.md) | reclass日志 |
| **节假日薄市** | [202](202-holiday-thin-liquidity.md) | 降仓只滤 |
| **回撤持续期** | [203](203-drawdown-duration.md) | 双门禁 |
| **万轮⑯ P0** | [204](204-mega160k-audit.md) | P0-118…122 |
| **因子预处理** | [205](205-factor-preprocess-pipeline.md) | 顺序+PIT |
| **缺失填充** | [206](206-missing-imputation-lookahead.md) | 可见填充 |
| **IC衰减** | [207](207-ic-decay-monitor.md) | 半衰期 |
| **因子正交** | [208](208-factor-orthogonalization.md) | 残差只滤 |
| **配股增发** | [209](209-rights-seo-actions.md) | 股本as-of |
| **退出组合** | [210](210-exit-rule-ensemble.md) | 优先级 |
| **万轮⑰ P0** | [211](211-mega170k-audit.md) | P0-123…127 |
| **P0 债务矩阵** | [212](212-p0-debt-matrix.md) | 对照代码排期；停扩万轮优先落地 |

## 7. 边界声明

- 知识库**不修改**交易逻辑；若与代码冲突，**以代码与 rules 为准**。  
- 非投资建议。  
