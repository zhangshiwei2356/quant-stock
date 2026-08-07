# 宽睿账号登录 Implementation Plan

> 实现按 specs/2026-08-08-kuangrui-account-login-design.md

**Goal:** 宽睿联调「账号登录」：验柜成功后密文入库；取密 DB 优先。

**Architecture:** 主工程 CredentialStore + AccountLoginService；OES probeLogon；main-kuangrui 改取密。

## Status

- [x] SQL + KuangruiCredentialStore / Credentials
- [x] KuangruiAccountLoginService + Ops API
- [x] OesReadonlyService.probeLogon + main-kuangrui 取密
- [x] 前端账号登录 + 总览凭据卡
- [x] README / app / memo / kuangrui / nav 同步
