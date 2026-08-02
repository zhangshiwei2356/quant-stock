# Session Branch Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 旁路 MIN_1 会话回测引擎 + `branchScaffold` 脚手架，按三分支形状验收；不改金叉、不接纸面、不实现隔日高开公式。

**Architecture:** `com.quant.stock.session` 包；`SessionBackTestEngine` 推进分钟轴并调度 OPEN/MID/CLOSE；经典 `BackTestEngine` 默认不变；回测 API `engine=classic|session`。

**Tech Stack:** Spring Boot, existing MarketDataService/MIN_1, JUnit 5, jQuery 回测下拉

**Spec:** `docs/superpowers/specs/2026-08-02-session-branch-engine-design.md`

## Global Constraints

- 不改 `MaCrossStrategy`；纸面不接 session；不下假指数/竞价数据
- 默认 `failOnMissingDep=false`（分支 UNAVAILABLE）；可 true 整单失败
- 脚手架 deps=`MIN1`，不发真实买卖单
- 提交仅用户要求时；同步 README/app/memo

## File map

| Path | Responsibility |
|------|----------------|
| `session/SessionBranch.java` | OPEN/MID/CLOSE + 默认窗口 |
| `session/DataDep.java` | MIN1/INDEX/AUCTION/ORDER_BOOK |
| `session/HoldDayState.java` | FLAT/HOLD_D* |
| `session/SessionContext.java` | bar/日/分支/态/降级 |
| `session/SessionStrategy.java` | 契约 |
| `session/SessionBackTestResult.java` | 事件+degradedBranches+fingerprint |
| `session/SessionBackTestEngine.java` | MIN_1 推进 |
| `session/BranchScaffoldStrategy.java` | 脚手架 Bean + BaseStrategy 适配注册 |
| `controller` / backtest service | engine 开关 |
| `stock.html` / `stock-chart.js` | 下拉与结果展示 |
| docs | README/app/memo |
| tests | UNAVAILABLE / 三分支事件 |

### Task 1: session 核心类型 + Engine 事件循环
### Task 2: BranchScaffold + Registry 注册
### Task 3: API/UI engine 开关
### Task 4: 单测 + 文档

---
