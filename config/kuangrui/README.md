# 宽睿 M0 配置说明

本目录配合「应用说明 → 宽睿文档梳理」**阶段 0（环境）**使用。  
**不入库密钥与真实地址**；业务主路径默认仍 `sim` + `db`。

## 目录

| 路径 | 说明 |
|------|------|
| `examples/` | 从官方包拷贝并脱敏的示例（地址为占位符） |
| `local/` | 你的本地真实配置（**已 gitignore**），探针优先读这里 |

## 准备步骤

1. 确认本机已安装 JDK（8+；当前开发机可用 21）。
2. 设置资料包根目录（含 `all/quant360-all-api-0.17.6.4.jar`）：

```powershell
$env:QUANT_KUANGRUI_HOME = "D:\宽睿-交易系统简称OES   行情系统简称MDS\OESAPI-JAVA-v0.17.6.4-20220705"
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
  "-Dfile=$env:QUANT_KUANGRUI_HOME\all\quant360-all-api-0.17.6.4.jar" `
  "-DgroupId=com.quant360" "-DartifactId=quant360-all-api" `
  "-Dversion=0.17.6.4" "-Dpackaging=jar"
```

## 本应用内 JUnit 联通测试（对齐 Demo）

资料包 `all/demo/OesExample` / `MdsExample` 登录三步：建客户端 → `initCallBack` → `start(logonReq)`。  
本仓库对应测试（**仅 profile `kuangrui`，不进 Spring 主路径**）：

`src/test-kuangrui/java/com/quant/stock/kuangrui/KuangruiLoginConnectivityTest.java`  
（**不要**放在 `src/main/java`；默认主工程不编译 quant360，会红、会污染生产 classpath）

```powershell
# 需已 install quant360-all-api 到本地 Maven 仓；local 配置与账号就绪
$env:QUANT_KUANGRUI_USER = "你的账号"
$env:QUANT_KUANGRUI_PASSWORD = "你的密码"
mvn -Pkuangrui test "-Dtest=KuangruiLoginConnectivityTest"
```

- 登录失败（当前常见）：测试失败，并提示日志中的 **Pre Logon 1045** → 即复现 M0 BLOCKED  
- 可选断言「仍为 1045」：`mvn -Pkuangrui test "-Dtest=KuangruiLoginConnectivityTest#oesStillBlockedWithPreLogon1045" "-Dkuangrui.expect1045=true"`  
- IDEA：启用 Maven profile `kuangrui` 后，直接运行该类中的测试方法  

默认 `mvn test` **不会**编译/运行该测试（无 `-Pkuangrui`）。

## 阿里云模拟环境（公开地址，非实盘）

| 服务 | Host | 端口 |
|------|------|------|
| OES 委托 / 回报 / 查询 | `106.15.58.119` | `6101` / `6301` / `6401` |
| MDS TCP / 查询 | `139.196.228.232` | `5103` / `5203` |

账号密码由宽睿发放，写入环境变量或 `local/`（**勿入库**）。UDP 组播在公网模拟通常不可用，探针会 SKIP。

### 当前实测（2026-07-26）

- 上述 TCP 端口：本机可达
- 登录：TCP 成功后 **Pre Logon `errorCode=1045`**（OES 与 MDS 相同）
- 手册 V0.17.6 错误表无 `1045`（止于 `1043`）；换加密方式 / `clEnvId` / 显式 IP·MAC **无效**（预登录尚未验密）
- 排查优先：向宽睿确认账号是否开通、是否需更新 API 包以匹配服务端协议版本

## M0 完成标准

- [x] `quant360-all-api` jar 可找到（或已 install 到本地 Maven 仓）
- [x] `local/` 下 OES/MDS 配置指向可达仿真地址（阿里云）
- [ ] `m0-env-check.ps1 -RunLoginProbe` 输出 `M0_STATUS=COMPLETE`（当前 BLOCKED：预登录 1045）
- [ ] 记下：价格单位 `1元=10000`、现货业务、`clEnvId`/加密方式与柜台一致

未完成前不要进入 M1（MDS 落库）。
