# Quant Stock · A股量化交易回测系统

基于 Spring Boot 2.7 + TA4J 的一体化回测工程（非前后端分离）。页面内嵌于 `static/`。

## 快速启动

```bash
cd quant-stock
mvn spring-boot:run
```

浏览器打开：http://localhost:8080/stock.html

默认 **无需 Redis / MySQL**：使用 classpath 下 JSON 模拟 K 线即可演示。

## 模拟数据

- 目录：`src/main/resources/data/kline/`
- 股票：600036 招商银行、000001 平安银行、300059 东方财富
- 区间：约 `2025-07-17` ~ `2026-07-17`
- 周期文件：`MIN_1` / `MIN_5` / `MIN_15` / `MIN_30` / `MIN_60` / `DAY` / `WEEK` / `MONTH`（对应分表设计）
- 重新生成：`mvn -q compile exec:java -Dexec.mainClass=com.quant.stock.market.mock.MockKlineDataGenerator`

## 页面功能

- 股票池、多周期 K 线（MA/BOLL/RSI）、明细表
- 单只回测 / 批量扫描 / 组合回测
- 回测时间：**默认留空 = 全量 K 线**；单股 / 组合回测均可填写起止时间截取
- 初始资金默认：**100000**
- 侧栏一级菜单：**股票池**（仅行情，可同时开启多只）、**单股回测**（K线买卖点 + 成交明细/收益汇总 + 回测/扫描）、**组合回测**、**股票知识**、**本应用相关**（介绍 / 交易规则 / **备忘录**）；菜单互斥展开，知识阅读时隐藏工作台
- 页头主题选择：夜盘深色 / 日间浅色 / 青松深色（`localStorage` 记住）
- 页面体验：交易台风格、输入聚焦动画、按钮 loading、表格斑马纹/粘性表头、Toast 提示、多股标签切换

## 安全与运维配置

| 配置 / 环境变量 | 说明 |
|----------------|------|
| `MYSQL_USER` / `MYSQL_PASSWORD` | 数据源账号（默认 root/root，勿用于公网） |
| `QUANT_API_KEY` / `quant.api-key` | 非空则 `/api/**` 需请求头 `X-API-Key`（`/api/config` 除外） |
| `QUANT_RATE_LIMIT` / `quant.rate-limit-per-minute` | 回测/组合/批量每 IP 每分钟上限（默认 30，≤0 关闭） |
| `QUANT_HISTORY_DIR` / `quant.history-dir` | 回测历史目录（默认 `data/backtest`） |

前端在启用 API Key 时会提示并写入 `localStorage.quantApiKey`。

## 主要接口

| 接口 | 说明 |
|------|------|
| GET `/api/config` | 公开配置（是否需 API Key、限流、历史目录等） |
| GET `/api/stock/pool` | 股票池（含名称） |
| GET `/api/data/summary` | 模拟数据概览 |
| GET `/api/kline?code=&period=` | 统一周期 K 线（默认 DAY） |
| GET `/api/kline/minute?code=` | 分钟 K（兼容） |
| GET `/api/backtest/run?code=&period=&initCapital=&feeRate=&backStart=&backEnd=` | 单只回测（时间可空；结果写入历史 JSON） |
| GET `/api/backtest/history?code=` | 单股回测历史（可按股票过滤） |
| DELETE `/api/backtest/history?code=` | 清除某股票的全部单股回测记录 |
| GET `/api/backtest/analysis?id=` | 按回测历史 id 取对应分析（与历史一一对应） |
| GET `/api/batch/scanAllStock` | 批量扫描 |
| POST `/api/portfolio/run` | 组合回测（结果写入历史 JSON） |
| GET `/api/portfolio/history` | 组合回测历史 |
| DELETE `/api/portfolio/history` | 清除全部组合回测记录 |
| GET `/api/portfolio/analysis?id=` | 按组合历史 id 取对应分析 |

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
- 实盘 `StrategyTask`：分钟扫描已对齐挂单撮合 / 开仓过滤 / 成本 / 金字塔 / 止损 / 账户熔断更新（模拟账户）
- 市值：`quant.float-shares-yi.<code>` 可配流通股本（亿股）；`market-cap-filter-enabled` 可关
- 涨跌停：`LimitBoardHelper`（主板 10% / 创业板·科创板 20%，含封板判定）
- 回测历史落盘：由 `quant.history-dir` 配置（默认 `data/backtest/*.json`，已 gitignore）
- 回测分析落盘：与历史**同一 id** 写入 `*-analysis.json`；页面点击历史行展示分析，清除历史时同步清除分析
- 下单：`clientOrderId` 幂等；`quant.trade-mode=sim|sdk`（sdk 为 SUBMITTED→sync 确认）

## 组合回测页面

- 参数区：成分股摘要栏、起止时间、初始资金
- 权益曲线 + **收益看板**（总盈亏/期末资产/成交概况）
- **成交流水**（含股票代码、本笔盈亏、该股持仓）
- **分股表现**（买/卖次数手数、金额、费用、已实现盈亏、胜率、最大回撤）
- 组合历史 JSON（含 tradeStats）

1. 执行 `src/main/resources/mapper/schema.sql`
2. 通过环境变量设置 `MYSQL_PASSWORD`（勿把真实密码提交进仓库）
3. 启用 profile：

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=db
```

查询：`MarketDataService#getKline`（JSON → 分表 → 降级聚合 → mock）。  
收盘任务 15:30：写 1 分钟并聚合各大周期。

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
2. 页面「本应用相关 → 本应用介绍」（`static/js/stock-chart.js` 中 `id: 'app'`）
3. 规则变更时同步「本应用相关 → 交易规则」（`id: 'rules'`）
4. 数据能力/待办落地时同步「本应用相关 → 备忘录」（`id: 'memo'`）

规则见 `.cursor/rules/sync-readme.mdc`、`.cursor/rules/sync-memo.mdc`。
