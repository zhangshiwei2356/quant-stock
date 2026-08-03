# 阿里云模拟测试环境（非实盘）

> 宽睿公开仿真地址；**不是实盘**。账号/密码由宽睿发放，只放环境变量或 `config/kuangrui/local/`（勿入库）。  
> 更新：2026-08-03 · 与厂商下发的 `[oes_stk]` / `[mds_client]` 一致。

## 1) 模拟交易 OES（现货）

厂商表述：

```ini
[oes_stk]
ordServer = tcp://106.15.58.119:6101
rptServer = tcp://106.15.58.119:6301
qryServer = tcp://106.15.58.119:6401
```

| 通道 | Host | Port | 对应 Java JSON |
|------|------|------|----------------|
| 委托 ord | `106.15.58.119` | `6101` | `OESCLIENT.ordChannel.address` |
| 回报 rpt | `106.15.58.119` | `6301` | `OESCLIENT.rptChannel.address` |
| 查询 qry | `106.15.58.119` | `6401` | `OESCLIENT.queryChannel.address` |

仓库示例：`examples/oes_api_config.example.json`（复制到 `local/oes_api_config.json` 后可再改 `clEnvId` / `encryptType` / `clDriverId`）。

## 2) 模拟行情 MDS

厂商表述：

```ini
[mds_client]
tcpServer = tcp://139.196.228.232:5103
qryServer = tcp://139.196.228.232:5203
```

| 通道 | Host | Port | 对应 Java JSON |
|------|------|------|----------------|
| 行情 TCP | `139.196.228.232` | `5103` | `MDSCLIENT.mktChannel.address` |
| 查询 qry | `139.196.228.232` | `5203` | `MDSCLIENT.queryChannel.address` |

仓库示例：`examples/mds_api_config.example.json`。  
公网模拟下 **UDP 组播通常不可用**，示例中 `isUdpSubMktEnable=false` 且 UDP 通道 `enable=false`。

## 3) 联通检查

```powershell
Copy-Item config\kuangrui\examples\oes_api_config.example.json config\kuangrui\local\oes_api_config.json
Copy-Item config\kuangrui\examples\mds_api_config.example.json config\kuangrui\local\mds_api_config.json
$env:QUANT_KUANGRUI_USER = "你的账号"
$env:QUANT_KUANGRUI_PASSWORD = "你的密码"
.\scripts\kuangrui\m0-env-check.ps1 -RunLoginProbe
# 或
mvn -Pkuangrui test "-Dtest=KuangruiLoginConnectivityTest"
```

TCP 可达但 Pre Logon `1045`（`OESERR_INVALID_USERNAME_OR_PASSWORD`）时，向宽睿核对仿真账号开通与加密方式。
