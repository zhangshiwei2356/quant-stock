# Quant Stock · A股量化交易回测系统

基于 Spring Boot 2.7 + TA4J 的一体化回测工程（非前后端分离）。页面内嵌于 `static/`。

## 快速启动

```bash
cd quant-stock
mvn spring-boot:run
```

浏览器打开：http://localhost:8080/stock.html

默认连接本地 **MySQL**（`localhost:3306/quant_stock`，用户 `root` / 密码 `123456`）。  
启动前请执行一次建表脚本；空库时会自动从 classpath JSON 导入日线 + 5 分钟模拟数据。仍默认不连 Redis。

```bash
# 建库建表（Windows 示例）
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe" -uroot -p123456 < src/main/resources/mapper/schema.sql
mvn spring-boot:run
```

## 模拟数据 / 行情表

- 种子目录：`src/main/resources/data/kline/`（仅导入用）
- 股票：600036 招商银行、000001 平安银行、300059 东方财富
- 区间：约 `2025-07-17` ~ `2026-07-17`
- **物理表**：`market_daily`（日线）、`market_minute`（5 分钟）；其余周期运行时聚合
- 回测历史/分析：`bt_backtest_record` / `bt_backtest_analysis`
- 重新生成种子 JSON：`mvn -q compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator`

## 页面功能

- 行情浏览、多周期 K 线（MA/BOLL/RSI）、明细表
- 单只回测 / 批量扫描 / 组合回测
- 回测时间：**默认留空 = 全量 K 线**；单股 / 组合回测均可填写起止时间截取
- 初始资金默认：**100000**
- 进入应用先显示**初始化页**；侧栏一级菜单互斥展开进入功能，再点同一菜单可全部收起并回到初始化页
- 侧栏一级菜单（工作台）：**行情浏览** → **个股回测** → **组合回测** → **目标池**（当前池 / 扫描历史）→ **账户概览** → **运维中心**（任务管理 / 数据健康 / 运行参数）→ **数据表**；说明：**量化知识** / **应用说明**；知识阅读时隐藏工作台
- 说明文档为独立 HTML 片段：`src/main/resources/static/docs/*.html`，由工作台 `stock.html` 知识面板按需加载（非多页 SPA）
- 初始化欢迎页单独维护：`static/docs/home.html`，载入 `#viewHome`
- 各一级菜单介绍页：`static/docs/nav-*.html`，展开菜单时载入 `#viewNavIntro`（点二级项再进工作台/文档）
- 「量化知识 / 应用说明」介绍页可一键导出该菜单下全部子文档为 PDF（服务端 iText，`GET /api/docs/pdf/{stock|app}`）
- **量化知识**侧栏含：A股基础/交易时间/K线、均线与金叉·成交量·RSI·ATR·ADX·布林带、涨跌停、T+1、交易成本、仓位金字塔、账户风控、撮合时机、权益回撤、回测要点等（均标注「本系统用法」）
- 页头主题（`localStorage` 记住；无选择时默认）：**日间** / 夜盘 / 银河 / 极光
- 页面体验：交易台风格、输入聚焦动画、按钮 loading、表格斑马纹/粘性表头、Toast 提示、多股标签切换

## 安全与运维配置

| 配置 / 环境变量 | 说明 |
|----------------|------|
| `spring.datasource.*` | 默认 `localhost:3306/quant_stock`，`root` / `123456`（本地演示，勿用于公网） |
| `QUANT_API_KEY` / `quant.api-key` | 非空则 `/api/**` 需请求头 `X-API-Key`（`/api/config` 除外） |
| `QUANT_RATE_LIMIT` / `quant.rate-limit-per-minute` | 回测/组合/批量每 IP 每分钟上限（默认 30，≤0 关闭） |

前端在启用 API Key 时会提示并写入 `localStorage.quantApiKey`。

## 主要接口

| 接口 | 说明 |
|------|------|
| GET `/api/config` | 公开配置（是否需 API Key、限流、历史目录等） |
| GET `/api/stock/pool` | 标的列表/股票池（含名称） |
| GET `/api/data/summary` | 模拟数据概览 |
| GET `/api/kline?code=&period=` | 统一周期 K 线（默认 DAY） |
| GET `/api/kline/minute?code=` | 分钟 K（兼容） |
| GET `/api/backtest/run?code=&period=&initCapital=&feeRate=&backStart=&backEnd=` | 单只回测（时间可空；结果写入历史 JSON） |
| GET `/api/backtest/history?code=` | 个股回测历史（可按股票过滤） |
| DELETE `/api/backtest/history?code=` | 清除某股票的全部个股回测记录 |
| GET `/api/backtest/analysis?id=` | 按回测历史 id 取对应分析（与历史一一对应） |
| GET `/api/batch/scanAllStock` | 批量扫描 |
| POST `/api/portfolio/run` | 组合回测（结果写入历史 JSON） |
| GET `/api/portfolio/history` | 组合回测历史 |
| DELETE `/api/portfolio/history` | 清除全部组合回测记录 |
| GET `/api/portfolio/analysis?id=` | 按组合历史 id 取对应分析 |
| GET `/api/stock/universe` | 全市场列表（`stock_basic`） |
| GET `/api/stock/trade-pool` | 唯一目标池总览 |
| GET `/api/stock/trade-pool/final` | 兼容别名，同 `trade-pool` |
| GET `/api/stock/trade-pool/{code}/analysis` | 单只入选分析报告（行展开） |
| GET `/api/stock/trade-pool/report/{id}` | 按报告 id 读入选分析 |
| POST `/api/stock/trade-pool/rebuild` | 扫描全市场 → 覆盖唯一目标池 |
| POST `/api/stock/trade-pool/analyze` | 打分 + 覆盖目标池 + Markdown 报告 |
| POST `/api/stock/trade-pool/{code}/remove` | 移出目标池（不停仓/不卖出） |
| GET `/api/schedule` | 定时任务状态与列表（读 MySQL `sys_schedule_job`） |
| GET `/api/schedule/jobs` | 任务列表 |
| PUT `/api/schedule/jobs/{code}` | 更新启停 / cron / 间隔 |
| POST `/api/schedule/jobs/{code}/toggle` | 切换启用 |
| POST `/api/schedule/jobs/{code}/run` | 立即执行一次 |
| POST `/api/schedule/reload` | 按库表重载调度 |

## 运维中心 · 定时任务（库表管理，种子默认全关）

- 表：`sys_schedule_job`（见 `mapper/schema.sql`；启动时自动建表+种子）
- 页面：侧栏「运维中心 → 任务管理」可启停、改 cron/固定间隔、执行一次；另有「数据健康」「运行参数」（参数项展示中文说明 + 配置键）
- 总闸：`quant.schedule.enabled`（默认 **true**）；为 false 时不注册触发器，库表仍可编辑
- **唯一目标池**：盘后 `pool-rebuild` / `after-market-batch-scan` 扫描后自动覆盖 `trade_pool`；无需人工确认；扫描无入选时清空池
- **入池打分**：多因子综合分（均线趋势 / MA60 斜率 / ADX / 动量排名 / ATR / 流动性），默认 ≥45 分且具备多头雏形才入选；回测收益率仅作参考
- 配置：`quant.pool-score-min`、`pool-min-list-days`、`pool-min-avg-amount20`（生产可开 5000 万成交额过滤）
- `pool-rebuild` 与 `after-market-batch-scan` **启用其一即可**；`scan-and-trade` 只扫目标池内活跃标的
- 页面「目标池」可手动扫描更新或移出（**移出≠卖出**）
- 行情浏览：全市场（`stock_basic`）
- 已实现：`scan-and-trade` / `pool-rebuild` / `after-market-batch-scan`
- 未实现（页面标「未实现」，待外部 API）：`settle-after-close` / `sync-orders` / `market-collect` / `position-pnl-sync` / `data-validate`

## 策略与风控（已实现）

- 均线金叉死叉 + 可配置过滤：MA60 趋势 / 放量 / ADX / RSI（见 `application.yml`，模拟行情上趋势/ADX/放量默认关闭）
- 止损：相对**综合成本**的 ATR 止损与权益硬止损（只上移）；移动止盈盘后上移、次日老仓触及
- **T+1 分档**：仅买入日早于今日的老仓可止损；当日加仓不影响昨仓止损
- 金字塔分批建仓 50/30/20（**成交后**占档；加仓不重验 RSI/ATR；总仓≤80%）
- 开仓过滤：涨跌停（相对昨收）/停牌/流动性/市值≥50亿/静默时段（分钟级）
- 账户熔断：相对昨收权益的单日亏损、完整开平回合连亏、回撤降仓/停机
- 回测撮合（单股引擎）：
  - **日K/大周期**：信号收盘确认 → **下一根开盘价**
  - **分钟序列**：有效成交 ≥ **次日 09:45**；09:30–09:45 禁止新开成交
  - 死叉挂卖不刷新已有信号日；买单挂单超过 5 日历日未成交则取消
  - 跌停卖出连续 3 日失败 → 跌停价×0.99 强平
- 成本：佣金、卖出印花税、分级滑点、冲击成本
- 组合回测：日K、共享资金池；已对齐单股核心（次日开盘、成本、开仓过滤、金字塔、ATR/trail、账户熔断、T+1 分档、分股回撤）
- 实盘 `StrategyTask`：分钟扫描已对齐挂单撮合 / 开仓过滤 / 成本 / 金字塔 / 止损 / 账户熔断更新（模拟账户）；由 `DynamicScheduleService` + `sys_schedule_job` 调度，**种子默认关**
- 市值：`quant.float-shares-yi.<code>` 可配流通股本（亿股）；`market-cap-filter-enabled` 可关
- 涨跌停：`LimitBoardHelper`（主板 10% / 创业板·科创板 20%，含封板判定）
- 回测历史落盘：由 `quant.history-dir` 配置（默认 `data/backtest/*.json`，已 gitignore）
- 回测分析落盘：与历史**同一 id** 写入 `*-analysis.json`；页面点击历史行展示分析，清除历史时同步清除分析
- 下单：`clientOrderId` 幂等；`quant.trade-mode=sim|sdk`（sdk 为 SUBMITTED→sync 确认）

## 组合回测页面

- 工作台选股：模糊搜索多选；已选标签可点移除；「选前3只 / 清空」快捷操作
- 参数区：起止时间、初始资金
- 权益曲线 + **收益看板**（总盈亏/期末资产/成交概况）
- **成交流水**（含股票代码、本笔盈亏、该股持仓）
- **分股表现**（买/卖次数手数、金额、费用、已实现盈亏、胜率、最大回撤）
- 组合历史 JSON（含 tradeStats）

默认已启用 MySQL（`quant.db-enabled=true`，`market-mode=db`）。

1. 执行 `src/main/resources/mapper/schema.sql`
2. `mvn spring-boot:run`（空库自动导入 DAY + MIN_5）

查询：`MarketDataService#getKline`（MySQL 核心表 → 聚合 → 兜底 mock）。

## 扩展点

- `KlineSdkClient` / `NoopKlineSdkClient` — 行情 SDK（`market-mode=sdk`）
- `TradeGatewayService#placeOrderSdk` — 券商 SDK（`trade-mode=sdk`）
- `BarStorageService#rebuildPeriod` — 聚合表修复重算

## 已知限制

- 行情/券商 SDK 默认为 Noop 桩；真实对接需实现 `KlineSdkClient` 与 `placeOrderSdk`
- 市值无交易所接口时依赖 `float-shares-yi` 或板块默认启发式
- 实盘路径使用模拟现金账本，生产前须对接真实资金与对账

## 维护约定

每次实质性改动需**同时**更新：

1. 本 `README.md`
2. 页面「应用说明 → 系统概述」（`static/docs/app.html`）
3. 规则变更时同步「应用说明 → 交易规则」（`static/docs/rules.html`）
4. 能力/待办落地时同步「应用说明 → 待办清单」（`static/docs/memo.html`）

规则见 `.cursor/rules/sync-readme.mdc`、`.cursor/rules/sync-memo.mdc`。
