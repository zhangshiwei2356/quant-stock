# 宽睿 M0 配置说明

本目录配合「应用说明 → 宽睿文档梳理」**阶段 0（环境）**使用。  
**资料包以 `OESAPI-JAVA-v0.19.4.0-20260430` 为准。**  
**不入库密钥与真实地址**；业务主路径默认仍 `sim` + `db`。

## 目录

| 路径 | 说明 |
|------|------|
| `examples/` | 从官方包拷贝并脱敏的示例（地址为占位符） |
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

- 登录失败（当前常见）：测试失败，并提示日志中的 **Pre Logon 1045**  
  （0.19.4 枚举：`OESERR_INVALID_USERNAME_OR_PASSWORD`）→ 即复现 M0 BLOCKED  
- 可选断言「仍为 1045」：`mvn -Pkuangrui test "-Dtest=KuangruiLoginConnectivityTest#oesStillBlockedWithPreLogon1045" "-Dkuangrui.expect1045=true"`  
- IDEA：启用 Maven profile `kuangrui` 后，直接运行该类中的测试方法  

默认 `mvn test` **不会**编译/运行该测试（无 `-Pkuangrui`）。

## 阿里云模拟环境（公开地址，非实盘）

| 服务 | Host | 端口 |
|------|------|------|
| OES 委托 / 回报 / 查询 | `106.15.58.119` | `6101` / `6301` / `6401` |
| MDS TCP / 查询 | `139.196.228.232` | `5103` / `5203` |

账号密码由宽睿发放，写入环境变量或 `local/`（**勿入库**）。UDP 组播在公网模拟通常不可用，探针会 SKIP。

### 当前实测（截至 2026-08-03 · 资料包已升 0.19.4.0）

- 上述 TCP 端口：本机可达（历史实测）
- 登录：TCP 成功后 **Pre Logon `errorCode=1045`**（OES 与 MDS 相同）
- **0.19.4** 中 `1045 = OESERR_INVALID_USERNAME_OR_PASSWORD`（用户名或密码非法）；另有 `1046` 密码锁定、`1047` 可选终端信息未通过校验
- 排查优先：向宽睿确认账号开通、密码/加密与仿真柜台协议版本是否匹配新 API 包

## 官方手册

资料包根目录：

- `*OES*_JAVA*API使用手册V0.19.4.docx` / `.pdf`
- `*MDS*_JAVA*API使用手册V0.19.4.docx` / `.pdf`

（PDF 已由 docx 导出，与手册同目录。）

## M0 完成标准

- [x] `quant360-all-api` jar 可找到（或已 install 到本地 Maven 仓）
- [ ] OES + MDS 仿真登录成功（当前仍常为 Pre Logon 1045）
- [ ] `m0-env-check.ps1 -RunLoginProbe` 报告 `M0_STATUS=COMPLETE`
