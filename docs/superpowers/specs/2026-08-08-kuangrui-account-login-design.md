# 宽睿账号登录（库内存凭据）设计

日期：2026-08-08  
状态：待实现（设计已确认）

## 背景

宽睿 OES/MDS 登录目前仅读环境变量 `QUANT_KUANGRUI_USER` / `QUANT_KUANGRUI_PASSWORD`，IDEA 每次配置成本高。  
需要在「宽睿联调」下提供账号登录页：验柜成功后将账号入库（用户名明文、密码密文），后续取密优先读库。

## 目标

1. 宽睿联调新增二级菜单 **账号登录**（与接入总览同级）。
2. 填写用户名/密码 → **先连宽睿柜台 logon 验证** → 成功再加密落库并标为当前账号；失败只提示、不写库。
3. 密码 AES 密文；解密密钥存放在**独立表**（本机仿真可接受边界见下）。
4. 取凭据顺序：**库内 active 账号优先**，否则回退环境变量。
5. 接口与页面**永不返回**密码明文、密文或主密钥。

## 非目标

- 多租户 / 应用登录态（本应用仍无用户体系；「已登录」= 库内 active 记录）。
- 生产级密钥托管（HSM、KMS）；密钥与密文同库时，整库泄露仍可还原密码——仅防误读账号表/日志。
- 修改金叉主路径；不默认改 `trade-mode`。
- 在「数据表」白名单中展示密钥表或密码列。

## 安全边界（必须写进说明页）

| 能防 | 防不了 |
|------|--------|
| 账号表被单独导出、页面误展示、日志打印 password | 整库备份泄露且含密钥表；有 SQL 权限者读两张表 |

主密钥**不**写入 Git / `application.yml`；由服务端首次需要时生成并写入密钥表。

## 信息架构

```
宽睿联调
  接入总览          — 增加只读：activeUser / hasDbAccount / hasCred
  账号登录          ← 新增
  OES 只读
  MDS 行情
  报撤试单
```

## 数据模型

### `kuangrui_crypto_key`

| 列 | 说明 |
|----|------|
| id | PK，自增 |
| key_material | Base64 编码的 AES-256 密钥（仅服务端读） |
| algo | 如 `AES/GCM/NoPadding` |
| created_at | 创建时间 |

约束：业务上只使用**最新一行**（或 `id` 最大且有效）；不提供对外查询 API 返回 `key_material`。

### `kuangrui_account`

| 列 | 说明 |
|----|------|
| id | PK |
| username | 明文 |
| password_cipher | Base64 密文 |
| iv | GCM IV（Base64） |
| active | 当前选用；**同时最多一条** `active=1` |
| last_login_at | 最近一次验柜成功时间 |
| last_login_ok | 布尔 |
| updated_at | 更新时间 |

策略：登录成功时——加密写入/更新该 username 行，将该行 `active=1`，其余 `active=0`。  
本版不强制「只保留一条」物理删除；允许多历史行，但仅 active 用于取密。

建表：schema 增量 SQL + 启动可选自动建表（与现有 `sys_schedule_job` 等风格一致，若项目有统一 migrate 则跟之）。

## 加密

- 算法：AES-256-GCM（Java `AES/GCM/NoPadding`）。
- 密钥：32 字节随机，首次 `ensureKey()` 时生成写入 `kuangrui_crypto_key`。
- 明文密码 UTF-8 → 密文 + IV 分列存储。
- 解密失败：视为无有效库凭据，回退 env，并打 warn（不打密码）。

## 登录流程

```
页面提交 username + password
  → POST /api/ops/kuangrui/account/login
  → 校验非空
  → 调用宽睿验柜（OES 与/或 MDS：优先复用现有 ensureReady/logon 路径；须 -Pkuangrui 且开关开）
  → 失败：ok=false + message（柜台/未编译/未开开关），不写库
  → 成功：ensureKey → encrypt → upsert account + set active
  → 返回：ok、username、lastLoginAt（无密码字段）
```

验柜实现要点：

- 无 `-Pkuangrui` / noop：直接失败并 hint「需 Maven profile kuangrui」。
- 有实现：用**表单提交的**账号密码临时 logon（不要先读旧库密码）；成功后再落库。
- 尽量短连接：验通后可 disconnect，避免占住会话；正式 OES/MDS 业务仍走现有 client 生命周期，取密改为 CredentialStore。

## 取凭据（CredentialStore）

统一接口供 `KuangruiOesReadonlyService` / `KuangruiMdsMinuteIngestService`（及联调试单若需）使用：

1. 查 `kuangrui_account` where `active=1` → 解密  
2. 否则 `System.getenv("QUANT_KUANGRUI_USER/PASSWORD")`  
3. 否则空 → 现有 IllegalStateException / hint  

status 中增加：

- `hasDbAccount`、`activeUsername`（可脱敏部分）、`credSource`=`db|env|none`

## API（均在 `/api/ops/kuangrui/account`）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/status` | 当前 active 用户名、是否有库账号、是否有 env 回退、无密钥/密文 |
| POST | `/login` | body: `{username,password}`；验柜+落库 |
| POST | `/logout` | 将 active 置 0（或清除 active）；**不删历史行**（可选后续「删除」） |

请求体密码仅用于本次验柜与加密，响应与日志禁止回显。

## 前端

- `stock.html`：二级「账号登录」+ `viewKuangruiAccount`
- 表单：用户名、密码、登录并保存、清除当前（logout）
- 结果区：成功/失败提示 + 当前账号状态（可复用左右分栏或单栏表单+状态卡）
- `nav-kuangrui.html`：补充二级说明与入口按钮
- 接入总览：展示 `activeUsername` / `credSource`（只读）

## 配置与文档

- 不把账号写进 `application-local.yml`。
- README / 系统概述 / 能力与待办 / 宽睿文档：说明库优先、验柜入库、密钥同库边界、仍需 `-Pkuangrui`。
- `DbTableCatalog`：**不要**把 `kuangrui_crypto_key` 加入可浏览白名单；`kuangrui_account` 若加入则密码列永不查出或整表不入白名单（推荐两表都不进白名单）。

## 验收

1. 无 profile / noop：登录页提示需 kuangrui，不写库。  
2. 错误密码：柜台失败提示，库无新 active。  
3. 正确仿真账号：验通 → 库有密文 → status 显示用户名 → 查资金不依赖 IDEA env 亦可 live（仍须 `-Pkuangrui`+开关）。  
4. logout 后无 env 则查资金回到缺凭据；有 env 则回退 env。  
5. 任意 API 响应 JSON 无 password / key_material / cipher。

## 实现顺序建议

1. SQL + 实体/仓库 + CryptoKeyService + AccountCredentialStore  
2. 验柜 login API + 改 main-kuangrui 取密  
3. 前端账号登录页 + 总览字段  
4. 文档同步  

## 已确认决策摘要

| 项 | 决策 |
|----|------|
| 密钥位置 | DB 独立表 |
| 登录 | 必须验柜成功再入库 |
| 取密 | DB active 优先，env 回退 |
| 菜单 | 宽睿联调二级「账号登录」 |
| 多账号 | 允许多行历史，仅一条 active |
