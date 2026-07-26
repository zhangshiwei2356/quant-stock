# 36 · 上线门禁总表（含万轮）

策略从研究到可试点前，至少勾选：

## 假设与范式

- [ ] 范式明确（[29](29-paradigm-matrix.md)）；打板/龙虎榜/另类/大宗/筹码/跨境等**不静默**并入金叉  
- [ ] A股：T+1、整手、涨跌停、停牌拒成  
- [ ] 品种边界：无权限期权/期货腿不进股票主路径（[99](99-mega10k-audit.md)、[103](103-crossborder-etf-boundary.md)）  
- [ ] 另类/特征：可知时点与训练-服务一致（[100](100-alt-data-pitfalls.md)、[104](104-feature-store-point-in-time.md)）  
- [ ] 程序化报备与异常交易约束已建模或声明缺口（[105](105-program-trading-compliance.md)）  

## 数据与公司行为

- [ ] as-of 宇宙；ST/次新；停牌状态（[65](65-ipo-xinxin-filter.md)–[66](66-st-pit-filter.md)、[84](84-suspension-halt.md)）  
- [ ] 复权 **as-of**（禁最终动态前复权冒充）（[86](86-exdiv-dynamic-adj-lookahead.md)、[45](45-corporate-actions-adj.md)）  
- [ ] 除权权益：股数+现金；红利税/到账假设显式（[91](91-div-tax-settlement.md)）  

## 撮合与微观

- [ ] 开盘/收盘价源诚实；tick/lot（[72](72-opening-auction.md)–[73](73-closing-auction-afterhours.md)、[80](80-tick-lot-constraints.md)）  

## 标签与验证

- [ ] purge/WFA/DSR；回撤深度+持续期  
- [ ] 若元标签：方向锁主信号（[87](87-meta-labeling.md)）  
- [ ] 容量/规模敏感性（[88](88-alpha-decay-capacity.md)）  

## 选股与买卖

- [ ] 池仓分离；伪高息/填权非主引擎（[90](90-dividend-yield-trap.md)）  
- [ ] 隔夜/日内若用须可交易落地（[89](89-overnight-intraday-tug.md)）  

## 定仓与风控

- [ ] 非全 Kelly；vol 目标不关熔断；Heat/压力相关  
- [ ] 对照至 [211](211-mega170k-audit.md) 各万轮 P0（优先最近活跃项）  
- [ ] 金叉主 + RSI 滤：契约一致；融合/确认**只滤不开**（[142](142-indicator-confirm-pitfalls.md)、[156](156-signal-ensemble-gating.md)）  
- [ ] 信号落地：禁同 K；收盘确认→次开（[122](122-samebar-gap-breakout.md)、[160](160-paper-live-reconciliation.md)）  
- [ ] 容量：参与率硬顶；扩容降频（[157](157-capacity-aum-scaling.md)）  
- [ ] 漂移：滚动 IC + 冷却 + Kill（[158](158-signal-drift-monitor.md)）  
- [ ] 回测：含退市池（[154](154-survivorship-delisting.md)）  
- [ ] 上线：影子闸达标；退役有冷却（[112](112-shadow-mode-gate.md)、[161](161-strategy-retirement.md)）  
- [ ] 研究可复现：配置哈希+种子；订单/部成假设显式（[163](163-research-reproducibility.md)–[165](165-partial-fill-cancel-replace.md)）  
- [ ] 压力/政权：预注册情景；ADV断崖降仓（[166](166-ashare-stress-scenarios.md)、[167](167-liquidity-regime.md)）  
- [ ] 预算软硬分层；告警不旁路 Kill（[168](168-risk-budget-soft-hard.md)）  
- [ ] 验证：purge+embargo；WFA成本同开；DSR按配置数（[170](170-purged-cv-walkforward.md)）  
- [ ] 滑点在线残差；ST/财报 as-of；融券边界或禁空（[171](171-slippage-online-calibration.md)–[175](175-securities-lending-pit.md)）  
- [ ] 优化硬顶优先；换手+印花税；相关崩溃监控（[177](177-portfolio-optimization-pitfalls.md)–[179](179-risk-model-corr-breakdown.md)）  
- [ ] 调样公告as-of；时段只滤；多源对账闸（[180](180-index-reconstitution.md)–[182](182-data-vendor-reconciliation.md)）  
- [ ] ATR先止损再定仓；vol不关Kill；宽度/事件只滤；POV硬帽（[184](184-atr-stop-sizing.md)–[189](189-pov-execution-caps.md)）  
- [ ] 屏障对齐实盘；移动/时间止损次开；缺口开盘成交；拥挤as-of；金字塔硬纪律（[191](191-triple-barrier-labeling.md)–[196](196-pyramid-add-discipline.md)）  
- [ ] 唯一性加权；行业/日历PIT；回撤深度+持续期（[198](198-sample-uniqueness.md)–[203](203-drawdown-duration.md)）  
- [ ] 预处理PIT；可见填充；IC半衰期；退出优先级（[205](205-factor-preprocess-pipeline.md)–[210](210-exit-rule-ensemble.md)）  
- [ ] 硬风控：熔断/单票/总仓不可软化（[50](50-batch6-audit-ops.md)）  
- [ ] 知识库轮次 **≠** 可实施绩效  

冲突以代码为准。非投资建议。  
