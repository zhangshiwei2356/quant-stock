# 宽睿联调控制台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox syntax.

**Goal:** 工作台末尾一级「宽睿联调」，可点测 OES/MDS 接口并展示入参出参；报撤试单二次确认且校验 orderLive。

**Architecture:** 复用 `/api/ops/kuangrui/*`；新增 place-test/cancel-test；前端新 mode=`kuangrui`，四面板 + 通用调用结果区。

**Tech Stack:** Spring Boot、jQuery、现有 stock.html / stock-chart.js / style.css

## Global Constraints

- 不引入 React；不静默改金叉；order-enabled 仅 yml；报撤页面二次弹框 + 后端 orderLive 校验。
- 同步 README / app.html / memo.html / kuangrui.html。

---

### Task 1: 后端试单 API

- [x] `KuangruiOesOpsFacade.placeTest` / `cancelTest`
- [x] `OpsController` POST `place-test` / `cancel-test`
- [x] 未 live 返回 ok:false + hint

### Task 2: 导航 + 面板 HTML

- [x] `nav-kuangrui.html`
- [x] `stock.html` 一级菜单（数据表后）+ 四 view 面板骨架

### Task 3: 前端 JS 点测

- [x] mode kuangrui、面板切换、总览刷新、通用 invoke（入参/出参/耗时）、报撤 confirm

### Task 4: 文档同步 + 提交

- [x] README / app / memo / kuangrui / nav-app-related / page-ux
- [ ] commit（中文）
