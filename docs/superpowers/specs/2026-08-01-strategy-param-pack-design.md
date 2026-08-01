# 设计：按策略隔离的参数包（strategy_param）

**日期：** 2026-08-01  
**状态：** 已批准（对话确认）  
**范围：** 每个注册策略 id 可有稀疏参数包；三层叠层生效；运维可编辑；回测按 strategyId 自动叠包

## 已确认决策

| 项 | 选择 |
|---|---|
| 覆盖对象 | 每个已注册策略 id（含画像）；有包覆盖，无包回退 |
| 包内键集 | 与现有运维白名单一致（过滤/仓位/止损/金字塔/费率滑点/ADV/涨跌停保护/次 bar 开盘/常用风控） |
| 叠层 | `yml 默认` → `quant.prop.*` 全局 → `strategy_param` 稀疏包 → 生效视图 |
| 存储语义 | 稀疏覆盖（只存相对全局改过的键） |
| UI 范围 | 运维策略选择器 + 稀疏编辑 + 生效预览；回测仅按所选 strategyId 叠包 |
| 实现路径 | **新建表 `strategy_param` + JSON**（非 `system_config` 前缀） |

## 1. 存储

### 表 `strategy_param`

| 列 | 类型 | 说明 |
|---|---|---|
| `strategy_id` | VARCHAR PK | 注册表稳定 id（如 `maCross`、`maCrossTrend`） |
| `params_json` | TEXT/JSON | 稀疏对象，仅白名单 camelCase 键 |
| `version` | INT | 乐观锁，每次成功保存 +1，默认 0 |
| `updated_at` | DATETIME | 更新时间 |
| `updated_by` | VARCHAR | 审计；本期固定 `ops` |

示例：

```json
{"rsiBuyMax": "55", "trendFilterEnabled": true}
```

### 规则

- 无行或 `params_json` 为 `{}` / null：该策略无覆盖，完全继承全局生效底
- 保存时拒绝非白名单键、非法类型值、未注册 `strategy_id`
- `clearKeys`：从 JSON 删除指定键，恢复对该键的全局继承
- **不**使用 `system_config` 的 `strategy.{id}.*`（避免双源）
- 全局热写仍走现有 `quant.prop.*` / `POST /api/ops/params`

### schema

- `schema.sql`：`CREATE TABLE IF NOT EXISTS strategy_param (...)`
- `DbTableCatalog` 登记表白名单浏览
- 启动不强制灌种子行（按需由运维保存产生）

## 2. 解析与运行时

### EffectiveParamsService

- `resolve(strategyId)`：以当前全局 `QuantProperties`（已含 yml + 已加载的 `quant.prop.*`）为底做拷贝，再叠该策略 `params_json`，返回**生效快照**（不写回 Spring 单例 `QuantProperties`）
- `getSparse(strategyId)`：读表稀疏 map
- `saveSparse(strategyId, updates, clearKeys, confirm, expectedVersion?)`：合并白名单键写表；`confirm` 必须为 true

### 纸面

- `scan-and-trade` / 目标池扫描等纸面路径：`resolve(quant.active-strategy)` 得到快照，本轮扫描使用该快照
- 切换激活策略只改 `quant.active-strategy`，**不**改写全局 `quant.prop.*`，也不自动复制参数包

### 回测

- 个股/组合回测按请求 `strategyId`（缺省=当前激活）调用 `resolve`
- 引擎/服务入口接受本次快照（scoped 注入或方法参数），避免并发任务串参
- 本期**不做**回测工作台「本次表单临时改参」

### 画像策略（maCrossTrend 等）

- **无包**：过滤逻辑仍用代码内固定 `MaCrossFilterProfile`；仓位/止损等非过滤键走生效快照（通常等于全局）
- **有包**：白名单中的过滤键改读生效快照（包覆盖全局后的值）；未出现在稀疏包中的过滤键：仍以 Profile 固定值为准（避免「空包行」误伤画像）  
  - 更简一致口径（若实现时更易）：有任意稀疏键即「该策略进入 props 过滤模式」，过滤开关/阈值全部以生效快照为准（快照=全局⊕稀疏）。**采用此口径**，减少 Profile 与 props 混读分支。
- 不修改 `MaCrossStrategy` 买卖信号骨架

### 指纹

- `ConfigFingerprint` 对**生效快照**计算（含 strategyId + 白名单相关生效值）
- 同一全局底、不同策略包 → 指纹不同，便于对照实验

## 3. API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ops/params?strategyId=` | 扩展现有视图：全局项 + 稀疏差 + 生效预览 + writable + 指纹（生效视图）+ 策略列表；缺省 strategyId=激活策略 |
| POST | `/api/ops/params` | **仅全局** `quant.prop.*`（保持现语义） |
| POST | `/api/ops/strategy-params` | body: `{ strategyId, updates, clearKeys?, confirm:true, version? }` 写策略包 |

`GET` 每项建议字段：`key`, `label`, `globalValue`, `overrideValue`（可 null）, `effectiveValue`, `writable`, `type`, `overridden`。

## 4. 运维 UI

- 「运行参数」增加策略下拉（全部注册 id）
- 展示：全局可写编辑区（保存走 `POST /params`）与策略稀疏覆盖区（保存走 `POST /strategy-params`）
- 覆盖中的键标记「覆盖」；支持清除该键覆盖
- 展示当前生效指纹（随 strategyId / 包变化）
- 保存前 confirm；按钮 loading；结果 toast
- 保留现有 id / 事件绑定习惯；不引入 React

## 5. 测试

- 叠层：全局修改不影响已覆盖键的生效值；清除覆盖后继承全局
- 未知 strategyId / 非白名单键 / `confirm=false` 拒绝
- 画像：无包行为与现网一致；有包后过滤随生效快照
- 回测：同行情不同策略包 → fingerprint 或关键成交路径可区分（至少指纹断言）

## 6. 文档同步

同一轮实现须更新：`README.md`、`app.html`、`memo.html`（「按策略隔离的参数包」标已落地）、必要时 `rules.html` / `nav-schedule.html`。

## 7. 非目标

- 回测「本次表单临时改参」
- 多策略并行分账本、信号冲突裁决、按策略拆目标池
- 参数版本历史回滚 UI / 多版本并存
- 新策略实现；改写 `MaCrossStrategy` 核心买卖逻辑
- `system_config` 与 `strategy_param` 双写
- 分钟级/多分支策略引擎（另项）

## 8. 实现顺序（供计划拆解）

1. schema + DO/Mapper + DbTableCatalog  
2. EffectiveParamsService（resolve/save/clear）+ 单测  
3. 纸面/回测入口接快照  
4. Ops API 扩展 + 运维 UI  
5. 指纹对齐 + 文档同步  
