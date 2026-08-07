# 宽睿联调控制台（一级菜单）设计

日期：2026-08-08  
状态：待实现（设计已确认）

## 背景

宽睿 OES/MDS 运维 HTTP 已落地（`/api/ops/kuangrui/*`），但无专门页面点测。需要工作台末尾一级菜单，展示接入状态，并对接口点击调用、展示入参/出参（含报撤试单二次确认）。

## 目标

1. 侧栏「工作台」最下方新增一级菜单 **宽睿联调**（数据表之后、「说明」之前）。
2. 二级四面板：接入总览 / OES 只读 / MDS / 报撤试单。
3. 每个可调接口可「调用」并展示：接口名、HTTP、入参 JSON、出参 JSON、耗时。
4. 报撤：仅 `quant.kuangrui.oes.order-enabled=true` 且 `orderLive` 时可用；**页面二次弹框确认**；后端再校验。
5. 不引入 React；沿用 jQuery + `style.css`；不静默改金叉主路径。

## 非目标

- 页面热改 `order-enabled` / 账号密码（仍改 yml / 环境变量后重启）。
- 地址白名单校验、批量报单、两融/期权、L2。
- M5 断线重连实现（另项；本页可展示 status/hint）。

## 导航与信息架构

```
工作台
  …（行情 / 回测 / 目标池 / 账户 / 运维 / 策略 / 数据表）
  宽睿联调          ← 新增一级（最下）
    接入总览
    OES 只读
    MDS
    报撤试单
说明
  量化知识 / 应用说明
```

- 一级介绍页：`/docs/nav-kuangrui.html`（与运维介绍同模式）。
- 模式名建议：`kuangrui`（与 `schedule` / `account` 并列）。

## UI 行为

### 接入总览

- 并行请求：`GET .../mds/status`、`.../oes/status`、`.../oes/order-status`、`.../static/status`。
- 卡片展示：`enabled` / `live` / `orderEnabled` / `orderLive` / `applyEnabled`、hint、刷新按钮。
- 文案提示：报撤需 yml 中 `quant.kuangrui.oes.order-enabled=true`（及 enabled/oes.enabled、`-Pkuangrui`）；运行参数页不热改此开关。

### OES 只读 / MDS

每接口一卡：

| 字段 | 说明 |
|------|------|
| 标题 | 中文名 + SDK 名（如 `queryCashAsset`） |
| 路径 | `GET/POST /api/ops/kuangrui/...` |
| 入参表单 | 可选（如 `code`） |
| 调用 | loading + Toast |
| 结果区 | 入参 JSON、出参 JSON、HTTP 状态、耗时 ms |

OES 列表（复用现有）：cash、holdings、orders、trades、snapshot、reconcile、order-status、stock、trading-day、commission-rate、stop（POST，二次确认）。

MDS 列表：status、stock-static、security-status、session-status、pull、subscribe、flush、stop（写操作二次确认）。

静态：static/status、static/stock。

### 报撤试单

- 表单：代码、方向买/卖、限价（元）、数量、可选 clientOrderId；撤单：origClSeqNo + code。
- 按钮灰态：`orderLive≠true` 时禁用并显示 hint。
- **二次弹框**：展示将发送的代码/方向/价/量（或撤单流水），确认后才请求。
- 结果区同只读面板。

## 后端契约

### 复用

现有 `OpsController`：`/api/ops/kuangrui/oes/*`、`/mds/*`、`/static/*`。响应保持 Map（含 ok/live/message/业务字段）。

### 新增

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/ops/kuangrui/oes/place-test` | body：code、side、price、qty、可选 clientOrderId；校验 `OesOrderService.isOrderLive()`；调用 `placeLimit` |
| POST | `/api/ops/kuangrui/oes/cancel-test` | body：origClSeqNo、code；校验 orderLive；调用 `cancelByClSeqNo` |

失败时返回 `ok:false` + `hint`（未开开关时明确写出需 `order-enabled` 等），不抛未处理 500 掩盖配置问题。

实现位置：`OpsController` + `KuangruiOesOpsFacade`（或薄封装）；真实逻辑仍走 `OesOrderService`。

## `order-enabled` 说明（文档与总览页同步）

- 配置键：`quant.kuangrui.oes.order-enabled`（`application.yml`，默认 false）。
- 真报撤还需：`quant.kuangrui.enabled`、`oes.enabled`、`-Pkuangrui`、OES 本地配置与账号；纸面 sync 通常还需 `trade-mode=sdk`。
- **不在**运维「运行参数」白名单热改。

## 文件改动清单

| 区域 | 文件 |
|------|------|
| 导航/面板 | `stock.html`、`stock-chart.js`、`css/style.css`（必要时） |
| 介绍 | `static/docs/nav-kuangrui.html` |
| 后端 | `OpsController.java`、`KuangruiOesOpsFacade.java`（试单） |
| 文档 | `README.md`、`docs/app.html`、`memo.html`、`kuangrui.html`、`nav-app-related.html`（按需） |

## 验收标准

1. 侧栏工作台末可见「宽睿联调」，二级四项可切。
2. 总览刷新可见 MDS/OES/order/static 状态。
3. 点 OES `cash`/`holdings`：展示入参与出参 JSON。
4. `order-enabled=false`：试单按钮不可用或调用返回明确失败，无真实报单。
5. `orderLive=true`：确认弹框后可发 place-test / cancel-test，结果区可见出参。
6. 默认业务仍 sim/db；未开宽睿开关时页面可用且 hint 清晰。

## 风险

- 试单误触仿真账户：靠二次确认 + 默认 order-enabled=false。
- 未 `-Pkuangrui` 时一律 noop hint，避免前端当成功。
