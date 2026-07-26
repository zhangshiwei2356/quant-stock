# 14 · 策略研发储备清单（后续开发用）

把知识库落到「可开题」的研发项（**未实现、不改现码**）。  
**落地排期以对照代码的债务矩阵为准**：[212-p0-debt-matrix.md](212-p0-debt-matrix.md)（P0-88…127）。

## A. 选股增强

1. factor_daily 扩展：EP/BP、动量、低波（PIT）  
2. 截面中性化服务  
3. IC/ICIR 监控看板与衰减告警  
4. 拥挤度代理（换手、持仓市值分位）  

## B. 买卖点增强

1. Regime 门控（ADX）默认策略开关  
2. 突破：收盘确认 + 量能倍数配置化  
3. 止损：跳空不利情景成交模型  
4. 信号/成交分离的单元测试矩阵  

## C. 验证平台

1. Walk-forward 批跑与 WFE 报告  
2. 参数邻域热力图  
3. 成本压力（×1 / ×2 / ×3）  
4. 子区间（牛/熊/震荡）切片  

## D. 风控

1. 可选纯风险预算仓位与现行 ATR 调节 A/B  
2. 组合相关性/行业暴露上限  
3. 事件日历降仓  

## E. 文档门禁

每增策略：更新 ERRATA（新误区）+ topics + round 笔记 + 与 `rules.html` 差异表。

## F. 第二批百轮新增开题（R111–R210）

5. 隔夜/日内因子拆分与开盘 30 分钟评估（见 topics/16）  
6. 集合竞价不可撤单窗特征（研究用；注意成交约束）  
7. 北向资金：内生性处理 + 周频行业轮动残差  
8. PEAD/超预期：公告日 PIT + 成本与涨跌停掩码  
9. ML：Purged+Embargo CV / CPCV / Deflated Sharpe 接入验证平台  
10. 配对交易诚实流水线（滚动 β，禁全样本）  
11. 大资金：冲击进优化目标 + 规模扫描  
12. 波动率目标：杠杆帽与相关=1 压力（多头约束版）  
13. 金字塔规则回归测试（禁止摊平化改造）  

### 出门加严（相对第一批）

- [ ] 多日标签验证含 purge/embargo  
- [ ] 成本含冲击压力（×1/×2）  
- [ ] A股微观假设已相对美股做本土化声明  
- [ ] 新因子有 ERRATA 条目或明确「无已知误区」  

## G. 第三批百轮新增开题（R211–R310）

14. 动量/反转形成期×持有期网格 + 行业异质与失效监控（topics/23）  
15. 市场状态切换：赚钱效应→动量 / 亏钱→反转  
16. 三重障碍标签插件，障碍倍数与 `rules` 止损/trail 对齐（topics/24）  
17. Meta-label：主信号=金叉规则，meta=是否下单/仓位  
18. 组合优化沙箱：因子协方差、TE、冲击、多头约束（topics/25）  
19. **打板仅独立沙箱**，禁止并入主金叉引擎（topics/26）  
20. 时点行业分类表（支撑 E40）  
21. 流水线 CI：跑 [27](27-selection-to-execution-checklist.md) 检查项  

### 出门再加严（第三批）

- [ ] 主策略未混入打板成交假设  
- [ ] ML 标签非裸固定持有期，或已声明与引擎差异  
- [ ] 若做增强：有基准与 TE，非裸绝对收益 MVO  
- [ ] 动量窗与调仓频率已写明并做过子区间  

## H. 第四批百轮新增（R311–R410）

### P0（优先服务本应用金叉路径）

1. 因子/策略**拥挤监控**与风险预算下调（topics/30）  
2. **Implementation Shortfall** 日志与回测-纸面-实盘三轨对照（topics/31）  
3. 信号**半衰期** vs 次日开盘延迟联合验收（topics/34）  
4. 上线前跑 [36-go-live-checklist.md](36-go-live-checklist.md)  

### P1（独立沙箱，禁止并入主引擎）

5. L2/OBI 研究模块（topics/32）  
6. 可转债：强赎/涨停熔断（topics/33）  
7. ETF：折溢价与规模过滤（topics/33）  
8. 容量规模扫描仪表盘  

### 出门再加严（第四批）

- [ ] 纸面结果未当作冲击基准  
- [ ] 拥挤高时未加杠杆  
- [ ] 未把 L2/转债/ETF 逻辑并入股票金叉  
- [ ] 明确 fill rate&lt;1 与冲击压力后时期望仍为正  

## I. 第五批百轮新增（R411–R510）

对齐 [43-p0-engineering-slices.md](43-p0-engineering-slices.md)；服务金叉主路径，**不**把配置再平衡并入趋势引擎。

### P0（与 43 表一致，优先）

1. **Heat 视图 + 新开门控**（P0-1/2）：板块/主题相关暴露；超限拒开或缩量（[37](37-portfolio-heat.md)、E61/E69）  
2. **隔夜跳空压力回测开关**（P0-3）：低开 X% 情景净值；对照 rules 止损成交（[38](38-gap-risk-stops.md)、E63）  
3. **事件日历降仓**（P0-4）：财报/重大事件窗禁新开或减隔夜（E70）  
4. 延续批四：IS/三轨、拥挤分位、半衰期检查（P0-5/6/7）  

### P1 / 沙箱（禁止污染金叉主路径）

5. 带宽再平衡引擎（[39](39-rebalancing-bands.md)）— 仅多因子/配置类  
6. 基准注册表 + 增强产品 IR 门禁（[40](40-benchmark-ir.md)）  
7. 组合层冲击系数与同向开仓错开（[41](41-portfolio-impact.md)）  
8. 题材标签 / 申万一级行业映射数据源  

### 出门再加严（第五批）

- [ ] 新开前检查 Heat/板块上限（非仅总仓 80%）  
- [ ] 回测含跳空不利情景，未把理想止损价当成交保证  
- [ ] 金叉路径未强制日频再平衡  
- [ ] 绝对收益回测未乱报 IR；增强才有预注册基准  

## J. 第六批百轮新增（R511–R610）

对齐 [50-batch6-audit-ops.md](50-batch6-audit-ops.md)；验证与数据卫生优先，**日历/纯估值不进金叉主引擎**。

### P0（与 50 表；可与批五 Heat 并行的验证项）

1. **as-of 宇宙声明**（P0-8）：成分/全A 时点与缺陷标注（[44](44-universe-pit-survivorship.md)、E76）  
2. **复权用途断言**（P0-9）：信号复权 / 撮合不复权单测级检查（[45](45-corporate-actions-adj.md)）  
3. **试验次数 N 入报告**（P0-10）：网格/手工试次 + DSR/WFA（[46](46-dsr-pbo-validation.md)）  
4. **可选高周期同向过滤**（P0-11）：日线金叉 ∧ MA60/周线（[47](47-mtf-confirmation.md)）  
5. 研究日志：试次与日历假设登记（P0-12）  

### P1 / 沙箱（禁止污染金叉）

6. 价值×质量选股沙箱（[49](49-value-quality-trap.md)）— PIT 财务  
7. 日历/风格月历研究模块（[48](48-ashare-calendar.md)）— 仅仓位倾斜  
8. 运营杀开关与纸面同路径（[50](50-batch6-audit-ops.md)）— 对齐 rules 熔断  

### 出门再加严（第六批）

- [ ] 回测宇宙为 as-of，或已明示「非可实施 / 缺退市」  
- [ ] 信号与撮合复权口径分离且可测  
- [ ] 参数搜索报告含 N；未只秀最优曲线  
- [ ] 未把日历异常或裸低估值并入主买卖引擎  

## K. 第七批百轮新增（R611–R710）

对齐 [57-batch7-audit.md](57-batch7-audit.md)；**题材/解禁/财务红旗不进金叉主买卖点**。

### P0（与 57 表）

1. **解禁窗降仓开关**（P0-13）：大额解禁/减持窗禁新开或降仓（[51](51-unlock-reduce-events.md)）  
2. **块 Bootstrap 分位报告**（P0-14）：MDD/Sharpe 尾部，非单次 MaxDD（[53](53-bootstrap-drawdown.md)）  
3. **ADV 参与率夹逼**（P0-15）：仓位取风险与流动性更严者（[56](56-participation-sizing.md)）  
4. **信号正交性检查**（P0-16）：滤网相关矩阵（[55](55-signal-ensemble.md)）  
5. 题材标签仅服务 Heat（P0-17），禁打板成交假设（[54](54-theme-concept-pitfalls.md)）  

### P1 / 沙箱

6. 盈余质量/造假红旗过滤（[52](52-earnings-quality-fraud.md)）— PIT + 行业分位  
7. 解禁事件驱动研究模块 — 非主引擎  
8. 概念热度 as-of 数据（无则不做历史热度回测）  

### 出门再加严（第七批）

- [ ] 未把题材热度或解禁日效应并入金叉开仓  
- [ ] 资本规划未仅用单次回测 MaxDD（有 Bootstrap 或压力情景）  
- [ ] 仓位含参与率约束或已声明缺口  
- [ ] 多指标系统做过正交性，非同源堆票  

## L. 第八批百轮新增（R711–R810）

对齐 [64-batch8-audit.md](64-batch8-audit.md)；**调仓套利 / 两融 / LLM 情绪不进金叉主路径**。

### P0（与 64 表）

1. **费率分段 + 高压档**（P0-18）：印花税等按生效日（[63](63-fee-regime-turnover.md)）  
2. **止损/trail 邻域与 regime 报告**（P0-19）：对照现行 2×/1.5×ATR（[60](60-stop-placement.md)）  
3. 可选调仓窗降权（P0-20）（[58](58-index-reconstitution.md)）  
4. NLP 若启用：模型截止日门禁（P0-21）（[61](61-news-llm-sentiment.md)）  
5. 池排名 vs 开仓规则语义断言（P0-22）（[62](62-cs-vs-ts-momentum.md)）  

### P1 / 沙箱

6. 指数调仓事件模块 — 非主引擎  
7. 两融账户与可融券池 as-of（[59](59-margin-short-constraints.md)）  
8. 新闻情绪特征管线 — 截止后样本 only  

### 出门再加严（第八批）

- [ ] 长回测费率按制度分段，或已声明简化假设  
- [ ] 未假设任意融券卖空  
- [ ] 未在 LLM 训练窗内把情绪当可交易 Alpha  
- [ ] 截面排名未替代金叉触发；TS 组合未冒充中性  

## M. 第九批百轮新增（R811–R910）

对齐 [71-batch9-audit.md](71-batch9-audit.md)；**摘帽/因子择时/影线突破不静默并入金叉**。

### P0（与 71 表）

1. **上市日 N 过滤断言**（P0-23）：与 OpenFilter 对齐文档（[65](65-ipo-xinxin-filter.md)）  
2. **ST as-of 开仓过滤**（P0-24）（[66](66-st-pit-filter.md)）  
3. **回测报告回撤持续期/恢复期**（P0-25）（[70](70-drawdown-duration.md)）  
4. 大单路径：IS 主验收（P0-26）（[67](67-vwap-twap-execution.md)）  
5. 突破腿显式开关+计 N（P0-27）（[68](68-breakout-vs-pullback.md)）  

### P1 / 沙箱

6. 摘帽/弱转强事件模块 — 非主引擎  
7. 因子择时 / HMM 状态沙箱（[69](69-factor-timing-regime.md)）  
8. TWAP/VWAP 切片执行器（分钟路径）  

### 出门再加严（第九批）

- [ ] 宇宙含上市日门槛；无次新一字板完美成交  
- [ ] ST 按 as-of，非今日简称  
- [ ] 报告含回撤持续期，非仅 MaxDD 深度  
- [ ] 未用 VWAP 单独验收执行；未静默把突破并入金叉  

## N. 第十批百轮新增（R911–R1010）

对齐 [78-batch10-audit.md](78-batch10-audit.md)；**竞价打板 / PEAD / VP / 全 Kelly 不静默并入金叉**。

### P0（与 78 表）

1. **开盘成交价源字段**（P0-28）：auction vs continuous（[72](72-opening-auction.md)）  
2. **收盘/盘后路径 + T+1**（P0-29）：部分成交文档化（[73](73-closing-auction-afterhours.md)）  
3. **禁 VPVR 视窗回测**（P0-30）：固定区间 VP + 开关（[74](74-volume-profile.md)）  
4. PEAD/事件：公告日 PIT + 跳空过滤（P0-31）（[75](75-pead-earnings-drift.md)）  
5. 分数 Kelly 上界对照 + 压力相关情景（P0-32）（[76](76-kelly-fractional-sizing.md)、[77](77-correlation-spike.md)）  

### P1 / 沙箱

6. 竞价微观结构因子 — 显式开关，非主引擎  
7. 会话 Volume Profile 合流过滤  
8. 盈余事件多头小篮子模块 — 非金叉加分  

### 出门再加严（第十批）

- [ ] 开盘 fill 标注价源；未混用 9:25 与 9:30  
- [ ] 未假设收盘市价=日 K close；盘后未假设必成/当日平  
- [ ] 未用 VPVR 视窗做可复现回测；VP/PEAD 未静默并金叉  
- [ ] 定仓未用样本内全 Kelly；报告含压力相关或 Heat 等价约束  

## O. 第十一批百轮新增（R1011–R1110）

对齐 [85-batch11-audit.md](85-batch11-audit.md)；**分析师修正 / RP / VPIN 不静默并入金叉**。

### P0（与 85 表）

1. **停牌拒成断言**（P0-33）：禁止昨收撮合（[84](84-suspension-halt.md)）  
2. **tick/lot + 现金拖累字段**（P0-34）（[80](80-tick-lot-constraints.md)）  
3. vol 缩放若启用：杠杆硬顶且不关熔断（P0-35）（[81](81-volatility-targeting.md)）  
4. 分析师/一致预期 as-of 契约（P0-36）（[79](79-analyst-revision-pit.md)）  
5. 禁止日频主路径 VPIN/OFI 开仓（P0-37）（[83](83-orderflow-vpin.md)）  

### P1 / 沙箱

6. 盈利修正事件池 — 非金叉加分  
7. 风险预算 / 波动倒数加权（非完整多资产 RP）  
8. 订单流毒性仅执行降速沙箱  

### 出门再加严（第十一批）

- [ ] 停牌日零成交假设；复牌有事件处理  
- [ ] 撮合强制整手/tick；报告可见现金拖累  
- [ ] 未用终库一致预期；未用 VPIN 开日线仓  
- [ ] vol 目标未取消回撤熔断；RP 未冒充实盘主引擎  

## P. 第十二批百轮新增（R1111–R1210）

对齐 [92-batch12-audit.md](92-batch12-audit.md)；**元标签改方向 / 抢权 / 隔夜段策略不静默并入金叉**。

### P0（与 92 表）

1. **复权 as-of 断言**（P0-38）：禁最终前复权冒充（[86](86-exdiv-dynamic-adj-lookahead.md)）  
2. **除权权益记账**（P0-39）：股数+现金（[86](86-exdiv-dynamic-adj-lookahead.md)、[91](91-div-tax-settlement.md)）  
3. 元标签：方向锁主信号 + purged CV（P0-40）（[87](87-meta-labeling.md)）  
4. 容量/参与率曲线进报告（P0-41）（[88](88-alpha-decay-capacity.md)）  
5. 红利税则与到账假设文档化（P0-42）（[91](91-div-tax-settlement.md)）  

### P1 / 沙箱

6. 隔夜/日内分解报告与段策略（[89](89-overnight-intraday-tug.md)）  
7. 红利质量过滤 / 抢权事件模块（[90](90-dividend-yield-trap.md)）  
8. 元标签滤网（主信号仍为金叉）  

### 出门再加严（第十二批）

- [ ] 长回测复权方法 as-of 可审计；除权日非裸价止损幻觉  
- [ ] 元标签未改写多空；有 purge/计 N  
- [ ] 报告含规模敏感性；未把拥挤当 Alpha  
- [ ] 红利按税后/到账假设；资金可用与券商一致或已声明  

## Q. 万轮新增（R1211–R11210）

对齐 [99-mega10k-audit.md](99-mega10k-audit.md) 与 [catalog-10k.md](catalog-10k.md)；**打板/龙虎榜/转债/事件/中性化不静默并入金叉**。

### P0（与 99 表）

1. **龙虎榜/席位 as-of**（P0-43）（[93](93-longhubang-seat-pit.md)）  
2. **事件三时间戳契约**（P0-44）（[96](96-event-driven-clock.md)）  
3. **Kill Switch / 日终对账**（P0-45）（[97](97-ops-killswitch.md)）  
4. 因子中性行业/股本 as-of（P0-46）（[98](98-factor-neutralization-pit.md)）  
5. 打板/转债/衍生产品禁并股票金叉（P0-47）（[95](95-limit-board-sentiment.md)、[94](94-cbond-deep-clauses.md)、[99](99-mega10k-audit.md)）  

### 出门再加严（万轮）

- [ ] 万轮块可检索；导航 INDEX=11210  
- [ ] 新专文 93–98 已链到 07/29/36  
- [ ] 未把游资战法/连板晋级率并入主路径  
- [ ] 运维硬停机与对账有验收条目  

## R. 第二万轮（R11211–R21210）

对齐 [106-mega20k-audit.md](106-mega20k-audit.md) 与 [catalog-20k.md](catalog-20k.md)。

### P0

1. **另类数据可知时点/版本**（P0-48）（[100](100-alt-data-pitfalls.md)）  
2. **大宗/两融 as-of 且禁并金叉**（P0-49）（[101](101-block-trade-margin-balance.md)）  
3. **筹码/户数方法学钉扎或禁用**（P0-50）（[102](102-chip-shareholder-count.md)）  
4. 特征点时训练-服务一致性（P0-51）（[104](104-feature-store-point-in-time.md)）  
5. 程序化报备/异常交易门禁（P0-52）（[105](105-program-trading-compliance.md)）  

### 出门再加严

- [ ] INDEX=21210；mega2 可检索  
- [ ] 另类/大宗/筹码/跨境未并入金叉  
- [ ] 特征点时与合规报备有验收条目  

## S. 第三万轮（R21211–R31210）

对齐 [113-mega30k-audit.md](113-mega30k-audit.md) 与 [catalog-30k.md](catalog-30k.md)。

### P0

1. **RL/ML 禁改金叉方向 + 训测同成本**（P0-53）（[107](107-rl-trading-pitfalls.md)）  
2. **模拟器对齐涨跌停/T+1/整手**（P0-54）（[108](108-market-simulator-bias.md)）  
3. **程序化报告与高频阈值监控字段**（P0-55）（[109](109-program-trading-reg-detail.md)）  
4. **回测输出撤单比/申报速率（或声明缺口）**（P0-56）（[111](111-compliance-metrics-in-backtest.md)）  
5. **影子闸门与 Kill Switch 联动**（P0-57）（[112](112-shadow-mode-gate.md)、[97](97-ops-killswitch.md)）  

### 出门再加严

- [ ] INDEX=31210；mega3 可检索  
- [ ] RL/高频手法未静默并入金叉主路径  
- [ ] 合规指标进回测或显式声明缺口；影子闸门有验收条目  

## T. 第四万轮（R31211–R41210）

对齐 [120-mega40k-audit.md](120-mega40k-audit.md) 与 [catalog-40k.md](catalog-40k.md)。

### P0

1. **多因子 PIT 标准化 + 滚动 IC；禁替代金叉**（P0-58）（[114](114-multifactor-selection-pitfalls.md)）  
2. **衰减/拥挤（尾部）+ MRP/政权因果标签**（P0-59）（[115](115-alpha-decay-regime.md)）  
3. **量价/形态沙箱；板状态与成本对齐**（P0-60）（[116](116-volume-price-signals.md)、[118](118-pattern-false-precision.md)）  
4. **行业轮动：成分 as-of + 行业顶 + 换手闸**（P0-61）（[117](117-industry-rotation-pitfalls.md)）  
5. **卖点对称验证；跳空；减仓对齐金字塔**（P0-62）（[119](119-exit-sell-gates.md)）  

### 出门再加严

- [ ] INDEX=41210；mega4 可检索  
- [ ] 多因子/形态/轮动未静默并入金叉主路径  
- [ ] 滚动 IC/衰减告警与卖点对称验证有验收条目  

## U. 第五万轮（R41211–R51210）

对齐 [127-mega50k-audit.md](127-mega50k-audit.md) 与 [catalog-50k.md](catalog-50k.md)。

### P0

1. **竞价时段状态机 + 废单捕获**（P0-63）（[121](121-call-auction-signals.md)）  
2. **同 K 禁成交；OHLC 悲观路径；次开/限价**（P0-64）（[122](122-samebar-gap-breakout.md)）  
3. **PEAD/事件：公告 PIT + ADV/成本同测**（P0-65）（[123](123-pead-earnings-drift.md)）  
4. **ADV/参与率硬过滤；与 Heat 取严**（P0-66）（[124](124-liquidity-adv-filter.md)）  
5. **再平衡优先级：止损/熔断优先；对齐金字塔**（P0-67）（[126](126-rebalance-scaling-calendar.md)）  

### 出门再加严

- [ ] INDEX=51210；mega5 可检索  
- [ ] 竞价/PEAD/增强未静默并入金叉主路径  
- [ ] 同 K/竞价废单/ADV 过滤有验收用例  

## V. 第六万轮（R51211–R61210）

对齐 [134-mega60k-audit.md](134-mega60k-audit.md) 与 [catalog-60k.md](catalog-60k.md)。

### P0

1. **执行：到达时 VWAP；切片 lot/参与率/限速；执行≠信号**（P0-68）（[128](128-vwap-twap-execution.md)）  
2. **隔夜/日内分列；A 股专用；开盘价=竞价**（P0-69）（[129](129-overnight-intraday-split.md)）  
3. **vol target：滞后波动 + 硬顶；不关熔断**（P0-70）（[130](130-vol-targeting-sizing.md)）  
4. **回撤状态机 + 冷却；统一 Kill Switch**（P0-71）（[131](131-drawdown-derisk.md)）  
5. **动量分册+ADV；公司行为 PIT；除权缺口过滤**（P0-72）（[132](132-cs-ts-momentum.md)、[133](133-corporate-action-signals.md)）  

### 出门再加严

- [ ] INDEX=61210；mega6 可检索  
- [ ] VWAP/隔夜/动量/公司行为未静默并入金叉  
- [ ] 到达时基准评测、回撤冷却、除权过滤有验收条目  

## W. 第七万轮（R61211–R71210）

对齐 [141-mega70k-audit.md](141-mega70k-audit.md) 与 [catalog-70k.md](catalog-70k.md)。

### P0

1. **IS 四分量 + 决策/到达价钉扎**（P0-73）（[135](135-is-decomposition-tca.md)）  
2. **质量/低波：PIT + ADV + 硬顶；禁替代金叉**（P0-74）（[136](136-quality-factor-pitfalls.md)、[137](137-lowvol-anomaly-pitfalls.md)）  
3. **分析师修订 knowable_ts；覆盖掩码**（P0-75）（[138](138-analyst-revision-pitfalls.md)）  
4. **熔断/停牌/半日市状态机；复牌降仓**（P0-76）（[139](139-circuit-halt-calendar.md)）  
5. **解禁/减持公告 PIT；预注册规避窗**（P0-77）（[140](140-lockup-unlock-pressure.md)）  

### 出门再加严

- [ ] INDEX=71210；mega7 可检索  
- [ ] 质量/修订/解禁/TCA 未静默并入金叉  
- [ ] IS 分项日报、修订 PIT、停牌拒成有验收用例  

## X. 第八万轮（R71211–R81210）

对齐 [148-mega80k-audit.md](148-mega80k-audit.md) 与 [catalog-80k.md](catalog-80k.md)。

### P0

1. **确认层：互补性+最小N；RSI只滤不开；契约测试**（P0-78）（[142](142-indicator-confirm-pitfalls.md)）  
2. **挤压：收盘确认+量能+板状态**（P0-79）（[143](143-squeeze-breakout-pitfalls.md)）  
3. **季节/口诀：农历PIT+样本外；只降权**（P0-80）（[144](144-ashare-seasonality.md)）  
4. **分红捕获：预案as-of+费后；禁主引擎**（P0-81）（[145](145-dividend-capture-pitfalls.md)）  
5. **基差/增减持：分账户+披露PIT；禁改个股方向**（P0-82）（[146](146-index-futures-basis-boundary.md)、[147](147-insider-filing-pitfalls.md)）  

### 出门再加严

- [ ] INDEX=81210；mega8 可检索  
- [ ] 确认/季节/捕获/基差未静默并入金叉  
- [ ] RSI 契约、假突破、农历对齐有验收用例  

## Y. 第九万轮（R81211–R91210）

对齐 [155-mega90k-audit.md](155-mega90k-audit.md) 与 [catalog-90k.md](catalog-90k.md)。

### P0

1. **题材：成分/热度PIT；拥挤降仓；禁改金叉**（P0-83）（[149](149-theme-rotation-pitfalls.md)）  
2. **政策：落地时钟；冲突降权**（P0-84）（[150](150-policy-event-trading.md)）  
3. **北向：as-of+连续确认；验证非触发**（P0-85）（[151](151-northbound-flow-timing.md)）  
4. **入场：回撤/突破分册；收盘确认；止损优先**（P0-86）（[152](152-pullback-vs-breakout.md)、[153](153-mtf-entry-clock.md)）  
5. **回测：含退市池+退市路径；成分as-of**（P0-87）（[154](154-survivorship-delisting.md)）  

### 出门再加严

- [ ] INDEX=91210；mega9 可检索  
- [ ] 题材/政策/北向/择时未静默并入金叉  
- [ ] 退市池、北向PIT、入场同K 有验收用例  

## Z. 第十万轮（R91211–R101210）

对齐 [162-mega100k-audit.md](162-mega100k-audit.md) 与 [catalog-100k.md](catalog-100k.md)。

### P0

1. **融合：显式闸门；只滤不开；贡献日志**（P0-88）（[156](156-signal-ensemble-gating.md)）  
2. **容量：参与率硬顶+容量感知回测；扩容降频**（P0-89）（[157](157-capacity-aum-scaling.md)）  
3. **漂移：滚动IC/IR+冷却+Kill**（P0-90）（[158](158-signal-drift-monitor.md)）  
4. **纸面-实盘：闪烁/成本/选股对账；影子闸**（P0-91）（[160](160-paper-live-reconciliation.md)）  
5. **退役：预注册清单+冷却+双人；主路径锁定**（P0-92）（[161](161-strategy-retirement.md)）  

### 出门再加严

- [x] INDEX≥101210；mega10 可检索  
- [x] 融合/容量/漂移未静默并入金叉（文档边界）  
- [ ] 参与率日报、纸面差异用例、退役冷却有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AA. 第十一万轮（R101211–R111210）

对齐 [169-mega110k-audit.md](169-mega110k-audit.md) 与 [catalog-110k.md](catalog-110k.md)。

### P0

1. **复现：配置哈希+种子+泄漏台账；研究沙箱**（P0-93）（[163](163-research-reproducibility.md)）  
2. **订单：限价保护+五档/ADV上限；拒单进回测**（P0-94）（[164](164-ashare-order-types.md)）  
3. **部成：状态机+撤单延迟+改价重置队尾；部成率日报**（P0-95）（[165](165-partial-fill-cancel-replace.md)）  
4. **压力/政权：预注册情景+ADV断崖降仓；不改金叉**（P0-96）（[166](166-ashare-stress-scenarios.md)、[167](167-liquidity-regime.md)）  
5. **预算/告警：软硬分层+告警分级冷却；硬风控优先**（P0-97）（[168](168-risk-budget-soft-hard.md)）  

### 出门再加严

- [x] INDEX≥111210；mega11 可检索  
- [x] 订单/部成/压力未静默并入金叉（文档边界）  
- [ ] 配置哈希、部成率日报、压力用例有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AB. 第十二万轮（R111211–R121210）

对齐 [176-mega120k-audit.md](176-mega120k-audit.md) 与 [catalog-120k.md](catalog-120k.md)。

### P0

1. **验证：purge+embargo+折内标准化；WFA成本同开；DSR按配置数**（P0-98）（[170](170-purged-cv-walkforward.md)）  
2. **滑点：ADV分段+在线残差日报；扩容重校准**（P0-99）（[171](171-slippage-online-calibration.md)）  
3. **择时：与选股分册；只滤/缩放不开；IC监控**（P0-100）（[172](172-factor-timing-vs-selection.md)）  
4. **ST/财报：as-of日切；公告日+延迟；修订append**（P0-101）（[173](173-st-path-pit.md)、[174](174-earnings-calendar-pit.md)）  
5. **融券：PIT或明确多头边界禁空头腿；主路径锁定**（P0-102）（[175](175-securities-lending-pit.md)）  

### 出门再加严

- [x] INDEX≥121210；mega12 可检索  
- [x] 验证/择时/财报未静默并入金叉（文档边界）  
- [ ] purge报告、滑点残差日报、ST/公告用例有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AC. 第十三万轮（R121211–R131210）

对齐 [183-mega130k-audit.md](183-mega130k-audit.md) 与 [catalog-130k.md](catalog-130k.md)。

### P0

1. **优化：协方差收缩；硬顶/lot优先；不改金叉方向**（P0-103）（[177](177-portfolio-optimization-pitfalls.md)）  
2. **换手：L1/硬顶+印花税as-of；换手日报**（P0-104）（[178](178-turnover-stamp-tax.md)）  
3. **风险：相关崩溃情景+监控；触发降仓/Kill**（P0-105）（[179](179-risk-model-corr-breakdown.md)）  
4. **调样/时段：公告as-of；事件分册；时段只滤不开**（P0-106）（[180](180-index-reconstitution.md)、[181](181-intraday-session-effects.md)）  
5. **对账：主源+分歧闸+版本哈希；数据门禁优先**（P0-107）（[182](182-data-vendor-reconciliation.md)）  

### 出门再加严

- [x] INDEX≥131210；mega13 可检索  
- [x] 优化/调样/时段未静默并入金叉（文档边界）  
- [ ] 换手日报、相关监控、多源对账用例有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AD. 第十四万轮（R131211–R141210）

对齐 [190-mega140k-audit.md](190-mega140k-audit.md) 与 [catalog-140k.md](catalog-140k.md)。

### P0

1. **ATR：先止损距再定仓；地板/天花板；R含成本**（P0-108）（[184](184-atr-stop-sizing.md)）  
2. **vol目标：不关Kill；杠杆/账面硬顶；换手帽**（P0-109）（[185](185-vol-targeting-pitfalls.md)）  
3. **配对：协整+止损；无空权限则禁空腿/分册**（P0-110）（[186](186-pairs-short-boundary.md)）  
4. **宽度/事件：只滤不开；公告as-of；CAR≠下单**（P0-111）（[187](187-market-breadth-filter.md)、[188](188-event-study-tradability.md)）  
5. **POV：参与率硬帽+尾盘保护；残差日报；不改金叉**（P0-112）（[189](189-pov-execution-caps.md)）  

### 出门再加严

- [x] INDEX≥141210；mega14 可检索  
- [x] 止损/宽度/事件未静默并入金叉（文档边界）  
- [ ] 止损触发日报、vol实现日报、POV帽用例有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AE. 第十五万轮（R141211–R151210）

对齐 [197-mega150k-audit.md](197-mega150k-audit.md) 与 [catalog-150k.md](catalog-150k.md)。

### P0

1. **屏障：与止盈止损对齐；禁调美；报分布；不改金叉**（P0-113）（[191](191-triple-barrier-labeling.md)）  
2. **止损：移动/时间预注册；次开确认；穿价规则**（P0-114）（[192](192-trailing-time-stops.md)）  
3. **缺口：开盘/涨跌停成交；隔夜因子只验证；不并金叉**（P0-115）（[193](193-overnight-gap-risk.md)）  
4. **拥挤：披露as-of；只滤/降仓；参与率联动**（P0-116）（[194](194-crowding-pit-filter.md)）  
5. **金字塔：50/30/20硬纪律；禁报复加；MC辅助非替代**（P0-117）（[196](196-pyramid-add-discipline.md)、[195](195-monte-carlo-path-pitfalls.md)）  

### 出门再加严

- [x] INDEX≥151210；mega15 可检索  
- [x] 屏障/缺口/拥挤未静默并入金叉（文档边界）  
- [ ] 标签分布日报、缺口用例、破80拒加用例有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AF. 第十六万轮（R151211–R161210）

对齐 [204-mega160k-audit.md](204-mega160k-audit.md) 与 [catalog-160k.md](catalog-160k.md)。

### P0

1. **唯一性：并发权重+purge同开；报有效N**（P0-118）（[198](198-sample-uniqueness.md)）  
2. **分数差分：d预注册；PIT窗；不改金叉**（P0-119）（[199](199-fractional-diff-pitfalls.md)）  
3. **突变：预注册检验；只触发冷却/Kill**（P0-120）（[200](200-structural-break-vs-memory.md)）  
4. **行业/日历：as-of日志；薄市降仓；只滤不开**（P0-121）（[201](201-industry-class-pit.md)、[202](202-holiday-thin-liquidity.md)）  
5. **回撤：深度+持续期双门禁；主路径锁定**（P0-122）（[203](203-drawdown-duration.md)）  

### 出门再加严

- [x] INDEX≥161210；mega16 可检索  
- [x] 行业/日历/分数差分未静默并入金叉（文档边界）  
- [ ] 行业as-of用例、薄市降仓用例、回撤持续期门禁有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AG. 第十七万轮（R161211–R171210）

对齐 [211-mega170k-audit.md](211-mega170k-audit.md) 与 [catalog-170k.md](catalog-170k.md)。

### P0

1. **预处理：顺序固定；截面PIT；配置哈希；不改金叉**（P0-123）（[205](205-factor-preprocess-pipeline.md)）  
2. **填充：仅可见信息；覆盖率门禁；停牌≠成交**（P0-124）（[206](206-missing-imputation-lookahead.md)）  
3. **IC衰减：半衰期+冷却/Kill；禁无帽救火**（P0-125）（[207](207-ic-decay-monitor.md)）  
4. **正交/增发：自变量与股本as-of；事件分册**（P0-126）（[208](208-factor-orthogonalization.md)、[209](209-rights-seo-actions.md)）  
5. **退出：优先级预注册；与屏障对齐；主路径锁定**（P0-127）（[210](210-exit-rule-ensemble.md)）  

### 出门再加严

- [x] INDEX≥171210；mega17 可检索  
- [x] 预处理/正交/IC加权未静默并入金叉（文档边界）  
- [ ] 流水线哈希、IC半衰期日报、退出优先级用例有验收（见 [212](212-p0-debt-matrix.md)）  
- [x] 明确：知识库轮次 ≠ 策略绩效  

## AH. 落地排期（对照代码）

见 **[212 · P0 债务矩阵](212-p0-debt-matrix.md)**。建议下一代码包：P0-93 → P0-127 → P0-89；再开万轮⑱须先过 `.cursor/rules/kb-mega-round-gate.mdc` 提醒。
