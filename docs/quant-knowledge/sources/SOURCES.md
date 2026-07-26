# 资料来源清单

整理日期：2026-07-23。链接可能失效，以当时抓取摘要为准。**不构成投资建议。**

## A股量化框架 / 工程

1. https://github.com/MisakaMikoto128/china-astock-quant — A股端到端脚手架：T+1、手数、费用、滑点、网格搜索  
2. https://bigquant.com/wiki/doc/Z0tjAS5sWx — 量化回测指南：未来函数、幸存者、成本  

## 多因子选股 / 指数增强

3. https://bigquant.com/square/c858d01d-2178-4437-b988-a2443eb4b409 — 基本面+技术面多因子  
4. https://github.com/xiaopengm3-ai/stock-picker — 七维评分选股架构说明  
5. https://blog.csdn.net/sljsz/article/details/162210327 — GTJA191 / IC·IR 加权合成  
6. https://pdf.dfcfw.com/pdf/H3_AP202304251585791417_1.pdf — 湘财：中证500指数增强与因子跟踪  
7. https://bigquant.com/wiki/doc/qjl2v0tXah — ElasticNet 滚动多因子  
8. https://bigquant.com/wiki/doc/J4Bwgm4r4j — Lasso 滚动多因子  
9. https://bigquant.com/wiki/doc/GpphcQ6EdE — 中证1000增强：因子预处理、中性化、组合约束示例  
10. https://www.hibor.com.cn/repinfodetail_5006361.html — 国泰海通：ICIR 加权相对 IC 均值更稳（摘要）  

## 买卖点 / 趋势跟踪

11. https://github.com/fmzquant/strategies （多篇 EMA×RSI×ATR）  
12. https://github.com/warm3snow/coft/tree/master/quant/backtrader-sma-atr — SMA+ADX+ATR 移动止损  
13. https://www.fmz.com/strategy/482900 — 多指标趋势 + ATR 止盈止损  
14. https://github.com/amainoyo/a-stock-analysis — A股技术指标与选股条件示例  

## 回测陷阱 / 复权

15. https://quant67.com/post/quant/06-survivorship-bias/06-survivorship-bias.html — 幸存者、前视、未来函数、复权、停牌涨跌停  
16. https://www.cnblogs.com/kobe-tech/p/20095525 — Point-in-Time 复权偏差复盘  
17. https://cloud.baidu.com/article/4466383 — Python 回测陷阱  

## 仓位与风险

18. https://volatilitybox.com/research/position-sizing-with-volatility/ — ATR 仓位、Kelly/半 Kelly、波动环境缩放  
19. https://trendsandbreakouts.com/position-sizing-methods — Fixed Fractional / ATR / Kelly 实务比较  

## 指标误用 / 买卖点纠错（R011+）

22. https://quantstock.org/blog/ema-vs-sma-crossover-strategy — SMA vs EMA、无过滤金叉误区  
23. https://indicator.trading/en/indicator/rsi — RSI 超买超卖神话与趋势中的正确用法  
24. https://quant-signals.com/rsi-trading-strategy/ — RSI 策略在趋势市失败的数据讨论  
25. https://www.investopedia.com/articles/active-trading/100115/why-macd-divergence-unreliable-signal.asp — MACD 背离不可靠  
26. https://thetaedge.ai/blog/trailing-stops-mistakes-to-avoid — 过紧 ATR trail 等错误  
27. https://tradesmarty.in/adx-indicator-trend-strength-avoid-choppy-markets/ — ADX 过滤震荡  

## 因子 / 多重检验 / 中性化

28. https://www.kuazhi.com/post/715747261.html — p-hacking、多重检验、t≥3 讨论  
29. https://cloud.tencent.com/developer/article/2084271 — 因子发表后衰减：拥挤 vs 过拟合  
30. https://quant67.com/post/quant/09-factor-zoo/09-factor-zoo.html — 因子动物园、EP/中性化、未来函数  
31. https://wenku.csdn.net/answer/923sdbci8rg2 — 为何中性化用截面回归而非减均值  
32. https://wenku.csdn.net/answer/gx106pt5sq8 — IC 衰减误区与多起点计算  

## 验证 / Walk-forward

33. https://kiploks.com/research/what-is-walk-forward-analysis-complete-guide-for-algo-traders — WFA 协议与检查项  
34. https://www.smartfinancedata.com/what-is-walk-forward-analysis-why-backtests-fail-without-it/ — WFE 与过拟合  

## 第二批（R111–R210）

35. https://finance.sina.com.cn/wm/2025-11-26/doc-infystcp1980904.shtml — 隔夜-日内异象（中信建投综述入口）  
36. https://www.163.com/dy/article/KTK4LNB505568W0A.html — A股隔夜负收益与 T+1 微观结构讨论  
37. https://sumubai.cc/post/884 — 集合竞价可撤单 vs 不可撤单  
38. https://www.gankinterview.cn/zh-CN/blog/a-shares-vs-us-stocks-interviewing-at-top-domestic-private-funds-high-flyerjiuku — A股特色因子坑  
39. https://www.tpyzq.com/upload/20221028190053281.pdf — 北向资金行业轮动与内生性  
40. http://www.hibor.com.cn/repinfodetail_1649896.html — PEAD/超预期因子（国君摘要）  
41. https://waylandz.com/quant-book-en/Time-Series-Cross-Validation-Purged-CV/ — Purged CV  
42. https://github.com/eslazarev/purged-cross-validation — Purged/Embargo/CPCV 实现参考  
43. https://ariaanalyst.pro/blog/purgedkfold-financial-ml — 为何标准 K-Fold 在金融失效  
44. https://microalphas.com/pairs-trading/ — 协整配对实务与误区  
45. https://github.com/BancoMatt/cointegration-pairs-trading-study — 去未来函数后夏普塌陷案例  
46. https://quant67.com/post/quant/18-execution-cost/18-execution-cost.html — 四层交易成本与冲击  
47. https://bigquant.com/wiki/doc/zZuw5THZsS — 冲击成本分段线性 / 组合优化  
48. https://quantalpha.co/en/blog/volatility-targeting-is-risk-shaping-not-magic-alpha — 波动目标非 Alpha  
49. https://quantstrategy.io/blog/pyramiding-vs-averaging-down-why-one-is-a-strategy-and-the/ — 金字塔 vs 摊平  

## 第三批（R211–R310）

50. https://www.nxny.com/report/view_5516815.html — 开源：行业动量转反转、涨跌停扩散  
51. https://caifuhao.eastmoney.com/news/20260417162048102650060 — 反转因子难度与状态切换  
52. https://random-docs.readthedocs.io/en/latest/implementations/tb_meta_labeling.html — 三重障碍与 meta-labeling  
53. https://github.com/Neyt/How-To-Backtest-Correctly — TBM/meta/CPCV/DSR 清单  
54. https://quantpedia.com/why-mean-variance-optimization-breaks-down/ — MVO 崩溃与正则  
55. https://app2.msci.com/products/analytics/aegis/RI_Do_Risk_Models_Eat_Alphas_April_08.pdf — 风险模型吃掉 Alpha  
56. https://quant.10jqka.com.cn/view/article/4Z81JJGR1F1581529UVWQAK744 — 无差别追涨停十年回测  
57. https://news.qq.com/rain/a/20250825A03MNW00 — 涨停次日开盘买统计偏差  
58. https://quant.10jqka.com.cn/platform/html/article/155934.html — 封板时间/封成比等法则  

## 第四批（R311–R410）

59. https://bigquant.com/wiki/doc/leTJnZ9w9E — 海通：因子拥挤度改进  
60. https://bigquant.com/wiki/doc/buBqn2IIUF — 海通：因子拥挤度扩展  
61. https://www.sunspotfund.com/dynamic/128.html — 拥挤监测与动态风险预算  
62. https://quantdecoded.com/en/factor-crowding-index-real-time-measurement — 拥挤指数预警思路  
63. https://quant.zylotechnology.com/research/execution-gap-systematic-research — 执行落差与 IS  
64. https://trustedquant.com/quant-methods/execution-risk-in-quant-trading-kills-more-strategies-than-bad-backtests/ — 纸面验不了冲击  
65. https://qcaml.com/part-6-algorithmic-trading/ch23-execution/ — Implementation Shortfall 分解  
66. https://www.susanpotter.net/quant/order-flow-quantitative-methods/ — OBI/订单流与衰减  
67. https://heth.ink/ConvertibleBonds/ — 转债估值失效与 Alpha 选券  
68. https://www.caijingu.com/news/news-ongcrar2.html — 双低强赎暴雷  
69. https://finance.sina.com.cn/roll/2026-07-16/doc-inihycsn1165761.shtml — ETF 溢价与迷你基金坑  

## 第五批（R411–R510）

70. https://chartmini.com/blog/portfolio-heat-and-risk-management — Portfolio Heat 与相关暴露  
71. https://www.journalplus.live/blog/portfolio-heat — Heat 动态监控与权益基数  
72. https://pomegra.io/blog/overnight-gap-risk — 隔夜跳空与止损失效  
73. https://corporate.vanguard.com/content/corporatesite/us/en/corp/articles/rebalancing-best-practices.html — 再平衡实践（带宽思路）  
74. https://www.kitces.com/blog/rebalancing-frequency-tolerance-bands/ — 密监控稀交易 / 带宽  
75. https://www.nbim.no/en/publications/discussion-notes/rebalancing/ — 带宽调至内沿降换手  
76. https://www.oconnellconsulting.com/blog/information-ratio-pitfalls/ — IR 费用与样本坑  
77. https://www.aifinhub.com/posts/benchmark-selection-and-alpha/ — 基准敏感与 Alpha 变号  

## 第六批（R511–R610）

78. https://quant.10jqka.com.cn/view/article/JVUR6XTVOW1580260HRHWZEJ09 — 当前成分倒灌与 PIT 成员  
79. https://github.com/unliftedq/index-constitution — 指数历史成分 / 代码变更  
80. https://stockalpha.ai/alpha-learning/dataset-hygiene-masterclass-delistings-restatements-and-survivor-bias — 退市收益与重述 PIT  
81. https://sifting.io/blog/adjusted-vs-unadjusted-stock-prices-backtest-returns — 复权 vs 不复权分工  
82. https://www.davidhbailey.com/dhbpapers/deflated-sharpe.pdf — Deflated Sharpe Ratio  
83. https://papers.ssrn.com/sol3/papers.cfm?abstract_id=2326253 — Probability of Backtest Overfitting  
84. https://www.quantifiedstrategies.com/moving-average-crossover/ — 金叉与高周期过滤  
85. https://news.qq.com/rain/a/20250709A04G4G00 — A股大小盘月历效应（研究）  
86. https://quantdecoded.com/en/profitability-factor-gross-profitability-premium — 毛利率质量与价值陷阱  
87. https://www.fia.org/sites/default/files/2024-07/FIA_WP_AUTOMATED%20TRADING%20RISK%20CONTROLS_FINAL_0.pdf — 自动交易杀开关实践  

## 第七批（R611–R710）

88. https://bbs.pinggu.org/thread-11507030-1-1.html — 解禁前后收益事件研究（叙事待自检）  
89. https://bigquant.com/wiki/doc/EfRhhj4avM — 应计异象与选股  
90. https://qiniu-images.datayes.com/guotaijunan.pdf — 盈余质量代理变量  
91. https://bootstrap.marketmaker.cc/ — Bootstrap 对 MaxDD 覆盖偏乐观  
92. https://usekeel.io/lab/monte-carlo-backtest — 块 Bootstrap 回测分位  
93. https://aifinhub.io/articles/signal-orthogonality-ensembles/ — 信号正交性与假集成  
94. https://bigquant.com/wiki/doc/frm25SBxgq — 概念热度打板（对立沙箱参考）  
95. https://segmentfault.com/a/1190000047779927 — 复权/PIT 与分钟除权假突破  

## 第八批（R711–R810）

96. https://ag.yueniuzq.com/fund/mining-hidden-arbitrage-via-index-compilation-and-rebalancing-rules/ — 指数编制与调仓套利  
97. https://quant.10jqka.com.cn/view/article/JVUR6XTVOW1580260HRHWZEJ09 — 成分倒灌与调仓时点  
98. https://licai.cofool.com/user/guide_view_3324730.html — 两融量化与担保风控  
99. https://stratbase.ai/en/blog/average-true-range-trailing-stop — Chandelier / ATR trail  
100. https://quant-signals.com/atr-trailing-stop-strategy/ — trail 未必优于固定 ATR  
101. https://ar5iv.labs.arxiv.org/html/2309.17322 — GPT 情绪前视与匿名化  
102. https://www.iasg.com/blog/2017/10/18/time-series-cross-sectional-momentum-better-choice-may-matter — TS vs CS 动量  
103. https://www.gov.cn/zhengce/zhengceku/202308/content_6900443.htm — 2023 印花税减半公告  
104. https://licai.cofool.com/user/guide_view_2597583.html — 降税对高换手策略影响  

## 第九批（R811–R910）

105. https://licai.cofool.com/user/guide_view_3418443.html — 回测不宜含新股次新  
106. https://quant67.com/post/quant/06-survivorship-bias/06-survivorship-bias.html — IPO/ST 等 PIT 数据陷阱  
107. https://easyquant.ai/e/joinquant/joinquant-stock-list-future-function-guide/ — 动态股票池与未来函数  
108. https://marketmaker.cc/en/blog/post/twap-vwap-pov-execution-algorithms/ — VWAP 验收陷阱与 IS  
109. https://www.snappchart.app/blog/strategy-playbooks/break-and-retest-vs-breakout — 突破 vs 回踩  
110. https://jacobslevycenter.wharton.upenn.edu/wp-content/uploads/2017/08/The-Promises-and-Pitfalls-of-Factor-Timing-2.pdf — 因子择时承诺与陷阱  
111. https://arxiv.org/pdf/2410.14841 — 状态识别 vs 因子择时  
112. https://quanterlab.com/articles/foundations-drawdown — 回撤深度/持续期/恢复  

## 第十批（R911–R1010）

113. https://quant67.com/post/quant/20-backtest-pitfalls/20-backtest-pitfalls.html — 竞价价 vs 连续 open、close 成交陷阱  
114. https://quant.10jqka.com.cn/view/article/BX62DNRUU4157234J1FWBULP87 — A 股分时段撮合规则  
115. https://sumubai.cc/post/884 — 9:15–9:20 可撤单噪音与竞价因子窗  
116. https://ttf248.life/p/close-price-trading-moc-cas/ — 盘后定价 vs MOC/CAS  
117. https://arxum.com/volume-profile-trading/ — POC/VA/LVN 误用与趋势过滤  
118. https://www.sciencedirect.com/science/article/abs/pii/S1057521924003922 — A 股 PEAD / ORJ 惊喜度量  
119. https://www.momentumq.com/blog/kelly-criterion-optimal-position-sizing — 全 Kelly 过赌与相关尖峰  
120. https://dtsystems.dev/blog/kelly-criterion-position-sizing — 分数 Kelly 与相关仓合并暴露  

## 第十一批（R1011–R1110）

121. https://quant67.com/post/quant/06-survivorship-bias/06-survivorship-bias.html — 分析师预测发布日/入库日与覆盖区间  
122. https://licai.cofool.com/user/guide_view_3398578.html — 财务/预期调仓滞后溢出  
123. https://licai.cofool.com/user/guide_view_3409435.html — 停牌非法撮合陷阱  
124. https://people.duke.edu/~charvey/Research/Published_Papers/P135_The_impact_of.pdf — 波动率目标效应  
125. https://www.ecb.europa.eu/press/financial-stability-publications/fsr/focus/2020/html/ecb.fsrbox202005_02~f6616db9be.et.html — vol targeting / 风险平价亲周期  
126. https://www.msci.com/research-and-insights/blog-post/stress-testing-risk-parity-strategies — 风险平价压力测试  
127. https://www.quantresearch.org/VPIN.pdf — VPIN 实现要点与常见误实现  
128. https://quantpedia.com/an-introduction-to-volatility-targeting/ — 波动缩放与动量滤镜  

## 第十二批（R1111–R1210）

129. https://m.caijingu.com/news/news-2ae1irw9.html — 除权息未来函数 / 动态前复权  
130. https://licai.cofool.com/user/guide_view_3418556.html — 除权除息跳空数据幻象  
131. https://www.garp.org/hubfs/Whitepapers/a1Z1W0000054x6lUAA.pdf — 元标签：方向与仓位分离  
132. https://hudsonthames.org/meta-labeling-a-toy-example/ — 元标签滤假阳  
133. https://hftradingbook.com/performance/capacity-and-alpha-decay — 容量与 Alpha 半衰期  
134. https://arxiv.org/abs/2512.11913 — 拥挤衰减：尾部风险 vs 均值 Alpha  
135. https://doi.org/10.1016/j.irfa.2020.101654 — A 股隔夜/日内动量拉锯  
136. https://blog.51cto.com/u_16213693/14735428 — 伪高股息与复权卫生  

## 万轮（R1211–R11210）

137. https://quant67.com/post/quant/11-event-driven/11-event-driven.html — 事件驱动公告/生效时钟  
138. https://www.quantvero.com/quant-trading/what-is-quantitative-trading/ — 研究-回测-执行-风控四段  
139. https://adventuresofgreg.com/blog/2025/12/15/algorithmic-trading-strategy-checklist-key-elements/ — Kill Switch / 预交易检查  
140. https://www.quantt.co.uk/resources/quantitative-investing-guide — 执行冲击与风险否决权  
141. https://hftradingbook.com/performance/capacity-and-alpha-decay — 容量与衰减（簇深化）  
142. https://m.caijingu.com/news/news-2ae1irw9.html — 除权未来函数（簇深化）  

## 第二万轮（R11211–R21210）

143. https://daloopa.com/blog/analyst-best-practices/the-growing-impact-of-alternative-data-on-hedge-fund-performance — 另类数据碎片/噪音  
144. https://www.luxalgo.com/blog/alternative-data-for-algorithmic-trading-what-works/ — 另类类型与治理  
145. https://baike.baidu.com/item/龙虎榜数据/7470985 — 龙虎榜滞后与分仓局限  
146. http://pdf.dfcfw.com/pdf/H301_AP202110311526206229_1.pdf — 量化席位龙虎榜研究（公开数字不可外推）  

## 第三万轮（R21211–R31210）

147. http://www.csrc.gov.cn/csrc/c100028/c7480577/content.shtml — 《证券市场程序化交易管理规定（试行）》先报告后交易  
148. https://www.sse.com.cn/aboutus/mediacenter/hotandd/c/c_20250403_10776805.shtml — 上交所实施细则答记者问（四类异常、HF 300/2万）  
149. https://www.cnstock.com/commonDetail/390318 — 沪深北实施细则综述（2025-07-07 施行）  
150. https://ml4trading.io/third-edition/chapters/21_rl_execution_hedging/ — RL 交易：奖励黑客/模拟器/非平稳  
151. https://www.ijcai.org/proceedings/2023/0553.pdf — ORDC：执行 RL 过拟合有限上下文  

## 第四万轮（R31211–R41210）

152. https://arxiv.org/abs/2604.08356 — Minimum Regime Performance（策略衰减/政权稳健）  
153. https://arxiv.org/html/2512.11913v1 — 因子拥挤：衰减形态与尾部（非均值择时）  
154. https://www.vertoxquant.com/p/strategy-decay-detection — 衰减预警与 MRP 实践解读  
155. https://blog.51cto.com/u_16099272/14730740 — 多因子标准化未来函数与截面排名  
156. https://bigquant.com/wiki/doc/Hn333yYkfS — 量价背离因子构建注意点（公开结果不可外推）  

## 第五万轮（R41211–R51210）

157. https://quant.csdn.net/6874ab51bb9d8e0ecec22d70.html — 回测未来函数四坑（同K/复权/高低序/财报修正）  
158. https://www.sohu.com/a/811786485_121118711 — 集合竞价相关因子（公开IC不可外推）  
159. https://licai.cofool.com/user/guide_view_3414705.html — 竞价废单与9:25–9:30静默滑点  
160. https://ag.yueniuzq.com/stock/call-auction-identify-fake-limit-up-bull-traps/ — 竞价假涨停/诱多分段  
161. https://www.tandfonline.com/doi/abs/10.2469/faj.v65.n4.3 — PEAD 与非流动性/交易成本  

## 第六万轮（R51211–R61210）

162. https://par.nsf.gov/servlets/purl/10278879 — TWAP/VWAP 均衡：盘中压力与流动性  
163. https://personal.lse.ac.uk/polk/research/LouPolkSkouras.pdf — 隔夜/日内分解与预测（海外，不可直接套A）  
164. https://finance.sina.com.cn/wm/2025-11-26/doc-infystcp1980904.shtml — A股隔夜负收益/日内溢价与因子拆解  
165. https://www.pbcsf.tsinghua.edu.cn/__local/F/D5/7A/66259A0664B091F2A94F23D78AD_C6E23DF5_21B1EF.pdf?e=.pdf — A股动量与T+1日内隔夜反转  
166. https://pmc.ncbi.nlm.nih.gov/articles/PMC7276139/ — 算法单对动态VWAP敏感（执行评测）  

## 第七万轮（R61211–R71210）

167. https://ryanoconnellfinance.com/implementation-shortfall/ — Perold/扩展IS四分量  
168. https://analystprep.com/study-notes/cfa-level-iii/measurement-and-determination-of-cost-of-trade/ — 延迟/成交/机会/费用  
169. https://marketmaker.cc/en/blog/post/implementation-shortfall-tca-execution/ — IS分项误诊与到达价坑  
170. https://finance.sina.com.cn/stock/stockzmt/2024-12-06/doc-incynmey9130907.shtml — 分析师盈利修订因子（公开IC不可外推）  
171. https://wt.hibor.com.cn/data/0e20b49a3c5f17dd3c186c94c53d08ca.html — 限售解禁冲击窗口复盘（CAR不可当保证）  

## 第八万轮（R71211–R81210）

172. https://www.coinquant.ai/blog/how-to-combine-multiple-indicators-without-overfitting-your-strategy — 多指标冗余与过拟合  
173. https://quant-signals.com/rsi-macd-strategy/ — RSI+MACD过滤后样本枯竭风险  
174. https://blofin.com/en/academy/education/rsi-vs-macd-vs-bollinger-bands — 指标分工与挤压方向未知  
175. http://pdf.dfcfw.com/pdf/H3_AP201906261336465719_1.pdf — A股季节性口诀数据复核（不可当保证）  
176. https://www.ccbfutures.com/main/a/20230628/61674.shtml — 分红季期指基差季节性（分账户）  

## 第九万轮（R81211–R91210）

177. https://www.hibor.com.cn/data/aba72ae528966a22aea82e9d3afde3a4.html — 主题热度vs政策风向（公开超额不可外推）  
178. https://licai.cofool.com/user/guide_view_3410625.html — 北向精细化调仓：验证非触发  
179. https://fund.10jqka.com.cn/20260721/c678331181.shtml — 风格切换与拥挤踩踏风险  
180. https://blog.csdn.net/wayz11/article/details/160258909 — 幸存者/前视/成本低估叠加  
181. https://licai.cofool.com/user/guide_view_3395062.html — A股退市股必须进入回测池  

## 第十万轮（R91211–R101210）

182. https://foxholm.com/q/concepts/signal-decay/ — 信号衰减与容量纪律  
183. https://atick.ai/api-guide/%E7%AD%96%E7%95%A5%E5%AE%B9%E9%87%8F%E4%BC%B0%E7%AE%97%E4%BD%A0%E7%9A%84%E7%AD%96%E7%95%A5%E8%83%BD%E6%89%BF%E8%BD%BD%E5%A4%9A%E5%B0%91%E8%B5%84%E9%87%91-mpafs8zz — 容量与ADV参与率  
184. https://licai.cofool.com/user/guide_view_3405743.html — 大资金流动性踩踏  
185. https://easyquant.ai/e/qmt/backtest-vs-live-discrepancy — 纸面实盘：闪烁/滑点/流动性  
186. https://bigquant.com/wiki/doc/muD2XDiJRG — 回测与实盘差异与选股一致性  

## 第十一万轮（R101211–R111210）

187. https://marketmaker.cc/en/blog/post/fill-simulation-partial-fills-backtest/ — 部成/撤改/毒成交与队列  
188. https://github.com/tradingexpert/ordersim — 订单生命周期与可审计成交台账  
189. https://licai.cofool.com/user/guide_view_3414726.html — 流动性陷阱与成交量截断  
190. https://licai.cofool.com/user/guide_view_3314284.html — 限价/市价/最优五档选用  
191. https://akquant.akfamily.xyz/textbook/06_stock_a/ — A股微观结构与涨跌停成交判定  

## 第十二万轮（R111211–R121210）

192. https://github.com/eslazarev/purged-cross-validation — purge/embargo/WFA/CPCV/DSR  
193. https://solana.garden/guides/purged-cross-validation-backtesting-explained/ — 标签重叠与诚实OOS  
194. https://aifinhub.io/articles/walk-forward-validation-cookbook/ — WFA窗口与泄漏陷阱  
195. https://bigquant.com/wiki/doc/Z0tjAS5sWx — 财报公告日/幸存者/未来函数  
196. https://quant67.com/post/quant/06-survivorship-bias/06-survivorship-bias.html — ST/融券/财报PIT三维时点  

## 第十三万轮（R121211–R131210）

197. https://ar5iv.labs.arxiv.org/html/1310.3396 — 组合优化七宗罪（估计/数值）  
198. https://arxiv.org/pdf/1709.06296 — 交易成本与换手正则下的组合配置  
199. http://www.hibor.net/data/e2fbaab5792547ab730a9542af7c5b2c.html — 指数调样成交分布与冲击  
200. https://finance.sina.com.cn/money/fund/jjgsgd/2026-07-23/doc-iniivazf2085859.shtml — 调样纳入≠未来alpha  
201. https://www.sinoss.net/uploadfile/2010/1130/9323.pdf — 印花税与换手/持有期  

## 第十四万轮（R131211–R141210）

202. https://www.darwintiq.com/articles/atr-position-manager — ATR止损/目标与政权滞后  
203. https://algovantis.com/atr-based-indicator-position-sizing-for-risk-controlled-automation/ — ATR定仓与组合风险叠加  
204. https://quant67.com/post/quant/17-position-sizing/17-position-sizing.html — Kelly/vol目标/风险预算工程边界  
205. https://www.erbcc.com/reference/cb-pub-wenzhai/chaoyuqi_pdjy.html — A股配对相关/协整/止损实践注意  
206. https://quantstrategy.io/blog/using-atr-to-adjust-position-size-volatility-based-risk/ — 先止损距再定仓逻辑  

## 第十五万轮（R141211–R151210）

207. https://paperswithbacktest.com/course/triple-barrier-method — 三重屏障与垂直审查  
208. https://ml4trading.io/docs/engineer/user-guide/labeling/ — 屏障/移动止损标签工程  
209. https://quantfoo.com/triple-barrier-targets-explained/ — 标签须对齐实盘规则与缺口  
210. https://finance.sina.com.cn/wm/2025-11-26/doc-infystcp1980904.shtml — A股隔夜-日内异象（不可外推）  
211. https://www.163.com/dy/article/KTK4LNB505568W0A.html — T+1与隔夜负收益微观结构  

## 第十六万轮（R151211–R161210）

212. https://quant67.com/post/quant/06-survivorship-bias/06-survivorship-bias.html — 行业/成分PIT与流动性约束  
213. https://quant67.com/post/quant/20-backtest-pitfalls/20-backtest-pitfalls.html — 分类前视与中性化错篮  
214. https://licai.cofool.com/user/guide_view_3395275.html — 财务因子时间漂移与修订  
215. https://doi.org/10.1080/23322039.2020.1733280 — 长记忆与结构突变混淆  
216. https://akquant.akfamily.xyz/guide/quant_basics/ — 回撤等基础风险指标语境  

## 第十七万轮（R161211–R171210）

217. https://bigquant.com/wiki/doc/N2BYAP8vO2 — 去极值→填充→标准化→中性化顺序  
218. https://dev.to/linou518/quant-factor-research-in-practice-ic-ir-and-the-barra-multi-factor-model-1h8k — IC/IR与中性化实践陷阱  
219. https://arxiv.org/abs/2202.00871 — 填充的前视偏差与方差权衡  
220. https://stockalpha.ai/alpha-learning/custom-factor-investing-building-your-own-alpha-factors — 去极值/正交/泄漏要点  
221. https://aicoding.csdn.net/6a22a563662f9a54cb7a0719.html — A股因子评估流水线与中性化  

## 本仓库权威规则（对照用）

20. `src/main/resources/static/docs/rules.html` — 买卖、撮合、成本、风控  
21. `src/main/resources/static/docs/risk.html` / `ma.html` / `atr.html` / `tplus1.html` / `limit.html`  

## 说明

- 公开数字**不可外推**为本系统绩效。  
- 冲突以代码与 rules.html 为准。  
- 纠错见 `errata/ERRATA.md`（E01–E350）。  
- 知识库整理轮次 **≠** 策略收益保证。  
