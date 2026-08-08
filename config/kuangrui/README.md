# 宽睿 M0 配置说明

本目录配合「应用说明 → 宽睿文档梳理」**阶段 0（环境）**使用。  
**资料包以 `OESAPI-JAVA-v0.19.4.0-20260430` 为准。**  
**不入库密钥与真实地址**；业务主路径默认仍 `sim` + `db`。

## 目录

| 路径 | 说明 |
|------|------|
| `examples/` | 阿里云模拟示例配置（公开非实盘地址）；对照见 `aliyun-sim.md` |
| `local/` | 你的本地真实配置（**已 gitignore**），探针优先读这里 |

## 准备步骤

1. 确认本机已安装 JDK（8+；当前开发机可用 21）。
2. 设置资料包根目录（含 `all/quant360-all-api-0.19.4.0.jar`）：

```powershell
$env:QUANT_KUANGRUI_HOME = "D:\OESAPI-JAVA-v0.19.4.0-20260430\OESAPI-JAVA-v0.19.4.0-20260430"
```

3. 复制示例配置并改成仿真地址：

```powershell
Copy-Item config\kuangrui\examples\oes_api_config.example.json config\kuangrui\local\oes_api_config.json
Copy-Item config\kuangrui\examples\mds_api_config.example.json config\kuangrui\local\mds_api_config.json
# 编辑 local 下两个文件：主备 addr/port、clEnvId、encryptType、clDriverId
```

4. 设置仿真账号（勿写入仓库）：

```powershell
$env:QUANT_KUANGRUI_USER = "你的账号"
$env:QUANT_KUANGRUI_PASSWORD = "你的密码"
# 可选：$env:QUANT_KUANGRUI_DRIVER_ID = "硬盘序列号"
```

5. 跑 M0 检查（含登录探针）：

```powershell
.\scripts\kuangrui\m0-env-check.ps1 -RunLoginProbe
```

仅检查不登录：

```powershell
.\scripts\kuangrui\m0-env-check.ps1
```

报告输出：`scripts/kuangrui/out/m0-report.txt`（已 gitignore）。

## Maven 本地安装（已可由脚本完成）

```powershell
mvn install:install-file `
  "-Dfile=$env:QUANT_KUANGRUI_HOME\all\quant360-all-api-0.19.4.0.jar" `
  "-DgroupId=com.quant360" "-DartifactId=quant360-all-api" `
  "-Dversion=0.19.4.0" "-Dpackaging=jar"
```

## 本应用内 JUnit 联通测试（对齐 Demo）

资料包 `all/demo/OesExample` / `MdsExample` 登录三步：建客户端 → `initCallBack` → `start(logonReq)`。  
本仓库对应测试（**仅 profile `kuangrui`，不进 Spring 主路径**）：

`src/test-kuangrui/java/com/quant/stock/kuangrui/KuangruiLoginConnectivityTest.java`  
（**不要**放在 `src/main/java`；默认主工程不编译 quant360，会红、会污染生产 classpath）

```powershell
# 需已 install quant360-all-api 0.19.4.0 到本地 Maven 仓；local 配置与账号就绪
$env:QUANT_KUANGRUI_USER = "你的账号"
$env:QUANT_KUANGRUI_PASSWORD = "你的密码"
mvn -Pkuangrui test "-Dtest=KuangruiLoginConnectivityTest"
```

- 登录成功：测试通过（当前本机已验证 M0 COMPLETE）
- 若失败且日志含 **Pre Logon 1045**：`OESERR_INVALID_USERNAME_OR_PASSWORD` → 核对账号/加密
- 可选历史断言「仍为 1045」：`…#oesStillBlockedWithPreLogon1045 -Dkuangrui.expect1045=true`（M0 已 COMPLETE 后通常不再需要）
- IDEA：启用 Maven profile `kuangrui` 后，直接运行该类中的测试方法  

默认 `mvn test` **不会**编译/运行该测试（无 `-Pkuangrui`）。

## 阿里云模拟环境（公开地址，非实盘）

厂商下发原文对照见 [`examples/aliyun-sim.md`](examples/aliyun-sim.md)。

### OES 现货交易 `[oes_stk]`

| 通道 | URL |
|------|-----|
| 委托 ord | `tcp://106.15.58.119:6101` |
| 回报 rpt | `tcp://106.15.58.119:6301` |
| 查询 qry | `tcp://106.15.58.119:6401` |

### MDS 行情 `[mds_client]`

| 通道 | URL |
|------|-----|
| 行情 TCP | `tcp://139.196.228.232:5103` |
| 查询 qry | `tcp://139.196.228.232:5203` |

账号密码由宽睿发放，写入环境变量或 `local/`（**勿入库**）。UDP 组播在公网模拟通常不可用，探针会 SKIP。

### 当前实测（截至 2026-08-03 · 资料包 0.19.4.0）

- 上述 TCP 端口：可达
- 登录探针：OES 三通道 + MDS TCP/查询 **全部成功** → **`M0_STATUS=COMPLETE`**
- 服务端 ApplVerId≈`0.19.1`，客户端 API `0.19.4.0`（协议兼容范围内）
- 账号放 `local/credentials.ps1` 或环境变量（**勿入库**）

## 官方手册

资料包根目录：

- `*OES*_JAVA*API使用手册V0.19.4.docx` / `.pdf`
- `*MDS*_JAVA*API使用手册V0.19.4.docx` / `.pdf`

（PDF 已由 docx 导出，与手册同目录。）

## M0 完成标准

- [x] `quant360-all-api` jar 可找到（或已 install 到本地 Maven 仓）
- [x] OES + MDS 仿真登录成功（2026-08-03 本机探针）
- [x] `m0-env-check.ps1 -RunLoginProbe` 报告 `M0_STATUS=COMPLETE`

## M1 MDS L1 落库（可选）

默认业务路径不变。启用步骤：

1. `mvn install:install-file` 安装 `quant360-all-api`（同上）
2. 准备 `local/mds_api_config.json` + 环境变量账号
3. 以 profile 启动：

```powershell
mvn -Pkuangrui spring-boot:run
# 或 IDEA 勾选 Maven profile kuangrui 后运行主类
```

4. 配置（勿入库密钥）：

默认 profile=`local` 时看 `src/main/resources/application-local.yml`（已可开开关）。  
或临时写在启动参数 / 环境变量。仓库基线 `application.yml` 保持默认关。

```yaml
# application-local.yml 示例（本仓库已类似开启）
quant:
  kuangrui:
    enabled: true
    mds:
      enabled: true
```

真客户端：`mvn -Pkuangrui spring-boot:run` 或 IDEA 勾选 Maven profile `kuangrui`。

5. 运维验收：
   - `GET /api/ops/kuangrui/mds/status` → `live=true`
   - `POST /api/ops/kuangrui/mds/pull` → `market_1min` 出现 `data_source=MDS`
   - 或 `POST .../subscribe` 后由 `market-collect` / `flush` 落库

实现位置：`src/main-kuangrui/java/.../KuangruiMdsMinuteIngestService.java`（仅 `-Pkuangrui` 编译）；主工程门面与换算在 `src/main/java/.../kuangrui/`。

## M2 OES 只读对账（可选）

默认业务路径不变；**不下单**（`oes.order-enabled` 保持 false）。启用步骤：

1. 同 M1：安装 `quant360-all-api`、准备账号
2. 准备 `local/oes_api_config.json`（可从 `examples/oes_api_config.example.json` 复制）
3. `mvn -Pkuangrui spring-boot:run`
4. 配置：

```yaml
quant:
  kuangrui:
    enabled: true
    oes:
      enabled: true
      # order-enabled: false   # M3 才开
```

5. 运维验收：
   - `GET /api/ops/kuangrui/oes/status` → `live=true`
   - `GET /api/ops/kuangrui/oes/cash|holdings|orders|trades`
   - `GET /api/ops/kuangrui/oes/reconcile` → 本地纸面 vs 柜台差异
   - 定时任务 `sync-orders` / `position-pnl-sync` 在 OES live 时打对账日志（不改本地账本）

排障（查资金报 `rptSynced=false` / `sendRptSync` 失败）：

- 登录成功但回报同步失败时，`lastError` 会带**可用方法签名与真实异常**（不再只写「请核对 API 版本」）
- 核对 `local/oes_api_config.json` 回报通道 `rpt` URL（模拟默认 `tcp://106.15.58.119:6301`）可达
- 服务端 ApplVerId≈`0.19.1`、客户端 jar `0.19.4.0` 属兼容范围；须 `-Pkuangrui` 且 jar 已 install
- 失败后会关闭半登录连接，下次查询会整链重登 + 再 sync

实现：`src/main-kuangrui/.../KuangruiOesReadonlyService.java` + `OesRptSyncInvoker`；门面 `KuangruiOesOpsFacade`。

## M3 OES 报撤（可选）

默认仍不下单。启用步骤（仿真）：

1. 同 M2：`-Pkuangrui`、OES 配置与账号
2. 配置：

```yaml
quant:
  trade-mode: sdk
  kuangrui:
    enabled: true
    oes:
      enabled: true
      order-enabled: true
```

3. 验收：
   - `GET /api/ops/kuangrui/oes/order-status` → `orderLive=true`
   - `trade-mode=sdk` 下单走 `sendOrdReq`（限价）；撤单走 `sendOrdCancelReq`
   - `sync-orders` 按回报/查询推进 FILLED（**不再假推进**）

实现与 M2 共用 `KuangruiOesReadonlyService`（实现 `OesOrderService`）；网关 `TradeGatewayService` 在 orderLive 时切换路径。

## M4 静态 / 费率（可选）

默认不覆盖本地启发式。启用步骤（仿真）：

1. 同 M1/M2：`-Pkuangrui`、MDS 和/或 OES 配置与账号
2. 配置：

```yaml
quant:
  kuangrui:
    enabled: true
    static-enabled: true   # 业务覆盖总闸；失败回退本地
    mds:
      enabled: true        # 涨跌停/停牌/时段
    oes:
      enabled: true        # 产品/交易日/佣金
```

3. 验收：
   - `GET /api/ops/kuangrui/static/status` → `applyEnabled=true`
   - `GET /api/ops/kuangrui/oes/stock?code=600036` / `trading-day` / `commission-rate`
   - `GET /api/ops/kuangrui/mds/stock-static?code=` / `security-status` / `session-status`
   - `GET /api/ops/kuangrui/static/stock?code=` → MDS+OES 合并视图

业务挂钩（仅 `static-enabled`）：开仓停牌/涨跌停/股本、当日交易日、`TradeCostModel` 默认佣金。

## 下一步（M5，未落地 · 2026-08-07）

对照资料包 Demo/Javadoc 与当前实现，现货主路径 M0～M4 已可选落地；下一刀：

1. **M5a MDS**：`onDisConn` → 异步 `closeClient`；退避重登；需则重订阅；`market-collect` 死连接/全失败勿挡本地回退  
2. **M5b OES**：断线先 `close` 再重建 + `sendRptSync`  
3. **小修**：`PARTIALLY_CANCELED(6)`→本地 `CANCELLED`；撤单确认制  
4. 仿真浸泡验收后再考虑 overview/批量快照/银证/L2  

详情见页面「应用说明 → 宽睿文档梳理」待做表。
