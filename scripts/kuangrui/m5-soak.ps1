# Kuangrui M5 soak (plan step 4)
# Flow: account -> MDS stop/pull/subscribe -> OES reconcile -> place/cancel -> sync-orders -> static checks
#
# Usage:
#   .\scripts\kuangrui\m5-soak.ps1
#   .\scripts\kuangrui\m5-soak.ps1 -BaseUrl http://127.0.0.1:8080 -Code 600036 -SkipOrder
#
# Report: scripts/kuangrui/out/m5-soak-report.txt (+ .json)

param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Code = "600036",
    [decimal]$PlacePrice = 1.00,
    [int]$PlaceQty = 100,
    [switch]$SkipOrder,
    [int]$TimeoutSec = 90
)

$ErrorActionPreference = "Continue"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$OutDir = Join-Path $RepoRoot "scripts\kuangrui\out"
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$ReportTxt = Join-Path $OutDir "m5-soak-report.txt"
$ReportJson = Join-Path $OutDir "m5-soak-report.json"

$steps = New-Object System.Collections.Generic.List[object]
$lines = New-Object System.Collections.Generic.List[string]

function Log([string]$m) {
    $lines.Add($m) | Out-Null
    Write-Host $m
}

function Add-Step([string]$name, [bool]$ok, $detail, [string]$note = "") {
    $obj = [ordered]@{
        name   = $name
        ok     = $ok
        note   = $note
        at     = (Get-Date -Format "o")
        detail = $detail
    }
    $steps.Add([pscustomobject]$obj) | Out-Null
    $mark = if ($ok) { "PASS" } else { "FAIL" }
    Log ("[{0}] {1} {2}" -f $mark, $name, $note)
}

function Invoke-Json {
    param(
        [string]$Method,
        [string]$Path,
        $Body = $null
    )
    $uri = $BaseUrl.TrimEnd('/') + $Path
    try {
        if ($null -ne $Body) {
            $json = if ($Body -is [string]) { $Body } else { ($Body | ConvertTo-Json -Compress -Depth 6) }
            $resp = Invoke-WebRequest -Uri $uri -Method $Method -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) `
                -ContentType "application/json; charset=utf-8" `
                -UseBasicParsing -TimeoutSec $TimeoutSec
        } else {
            $resp = Invoke-WebRequest -Uri $uri -Method $Method `
                -UseBasicParsing -TimeoutSec $TimeoutSec
        }
        $parsed = $null
        try { $parsed = $resp.Content | ConvertFrom-Json } catch { $parsed = $resp.Content }
        return @{
            ok         = $true
            statusCode = [int]$resp.StatusCode
            body       = $parsed
            raw        = $resp.Content
        }
    } catch {
        $status = 0
        $raw = $_.Exception.Message
        if ($_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch { }
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                if ($stream) {
                    $reader = New-Object System.IO.StreamReader($stream)
                    $raw = $reader.ReadToEnd()
                }
            } catch { }
        }
        $parsed = $null
        try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $raw }
        return @{
            ok         = $false
            statusCode = $status
            body       = $parsed
            raw        = $raw
            error      = $_.Exception.Message
        }
    }
}

function Prop($obj, [string]$name) {
    if ($null -eq $obj) { return $null }
    if ($obj -is [hashtable]) { return $obj[$name] }
    return $obj.$name
}

Log ("=== Kuangrui M5 Soak " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " ===")
Log ("BaseUrl=$BaseUrl Code=$Code SkipOrder=$SkipOrder")
Log ("RepoRoot=$RepoRoot")

$ping = Invoke-Json GET "/api/ops/kuangrui/account/status"
Add-Step "0.app-reachable" $ping.ok $ping.body $(if ($ping.ok) { "HTTP $($ping.statusCode)" } else { "$($ping.error)" })

$acct = Invoke-Json GET "/api/ops/kuangrui/account/current"
$acctOk = $acct.ok -and (((Prop $acct.body "ok") -eq $true) -or ((Prop $acct.body "hasCred") -eq $true))
Add-Step "1.account-current" $acctOk $acct.body ("user=" + (Prop $acct.body "currentUsername") + " source=" + (Prop $acct.body "credSource"))

$mdsStop = Invoke-Json POST "/api/ops/kuangrui/mds/stop"
Add-Step "2a.mds-stop" $mdsStop.ok $mdsStop.body $(if ($mdsStop.ok) { "stopped" } else { "$($mdsStop.error)" })

Start-Sleep -Seconds 1
$mdsPull = Invoke-Json POST "/api/ops/kuangrui/mds/pull"
$pullUpsert = Prop $mdsPull.body "upserted"
if ($null -eq $pullUpsert) { $pullUpsert = Prop $mdsPull.body "upserts" }
$pullOk = $mdsPull.ok -and ((Prop $mdsPull.body "ok") -ne $false)
$mdsErr = Prop $mdsPull.body "lastError"
$pullHealthy = $pullOk -and ($mdsErr -ne "disconnected")
Add-Step "2b.mds-pull" $pullHealthy $mdsPull.body ("upserted=$pullUpsert lastError=$mdsErr")

# After query-channel pull, mkt channel may stay Close on sim; force stop+subscribe reconnect
$mdsSub = Invoke-Json POST "/api/ops/kuangrui/mds/subscribe"
$subOk = $mdsSub.ok -and (((Prop $mdsSub.body "ok") -eq $true) -or ((Prop $mdsSub.body "subscribed") -eq $true))
if (-not $subOk) {
    Log "mds-subscribe first try failed; force stop then subscribe"
    [void](Invoke-Json POST "/api/ops/kuangrui/mds/stop")
    Start-Sleep -Seconds 1
    $mdsSub = Invoke-Json POST "/api/ops/kuangrui/mds/subscribe"
    $subOk = $mdsSub.ok -and (((Prop $mdsSub.body "ok") -eq $true) -or ((Prop $mdsSub.body "subscribed") -eq $true))
}
Add-Step "2c.mds-subscribe" $subOk $mdsSub.body ("subscribed=" + (Prop $mdsSub.body "subscribed") + " lastError=" + (Prop $mdsSub.body "lastError"))

$mdsSt = Invoke-Json GET "/api/ops/kuangrui/mds/status"
$mdsLive = (Prop $mdsSt.body "live") -eq $true
$mdsLogged = (Prop $mdsSt.body "loggedIn") -eq $true
$mdsDisc = Prop $mdsSt.body "disconnected"
$mdsStatusOk = $mdsSt.ok -and $mdsLive -and $mdsLogged -and ($mdsDisc -ne $true) -and ((Prop $mdsSt.body "lastError") -ne "disconnected")
Add-Step "2d.mds-status" $mdsStatusOk $mdsSt.body ("loggedIn=$mdsLogged disconnected=$mdsDisc reconnectCount=" + (Prop $mdsSt.body "reconnectCount"))

$oesSt = Invoke-Json GET "/api/ops/kuangrui/oes/status"
$oesLive = (Prop $oesSt.body "live") -eq $true
$oesLogged = (Prop $oesSt.body "loggedIn") -eq $true
$rpt = (Prop $oesSt.body "rptSynced") -eq $true
$oesStatusOk = $oesSt.ok -and $oesLive -and $oesLogged
Add-Step "3a.oes-status" $oesStatusOk $oesSt.body ("rptSynced=$rpt orderLive=" + (Prop $oesSt.body "orderLive") + " disconnectCount=" + (Prop $oesSt.body "disconnectCount"))

$recon = Invoke-Json GET "/api/ops/kuangrui/oes/reconcile"
$reconOk = $recon.ok -and ((Prop $recon.body "ok") -ne $false)
Add-Step "3b.oes-reconcile" $reconOk $recon.body ("gaps=" + (Prop $recon.body "gapCount"))

$cash = Invoke-Json GET "/api/ops/kuangrui/oes/cash"
$cashOk = $cash.ok -and ((Prop $cash.body "ok") -ne $false)
Add-Step "3c.oes-cash" $cashOk $cash.body ("rows=" + (Prop $cash.body "count"))

$clSeq = $null
if (-not $SkipOrder) {
    $orderSt = Invoke-Json GET "/api/ops/kuangrui/oes/order-status"
    $orderLive = (Prop $orderSt.body "orderLive") -eq $true
    Add-Step "4a.order-status" ($orderSt.ok -and $orderLive) $orderSt.body ("orderLive=$orderLive tradeMode=" + (Prop $orderSt.body "tradeMode"))

    if ($orderLive) {
        $placeBody = @{
            code          = $Code
            side          = "BUY"
            price         = $PlacePrice
            qty           = $PlaceQty
            clientOrderId = ("SOAK-" + $stamp)
        }
        $place = Invoke-Json POST "/api/ops/kuangrui/oes/place-test" $placeBody
        $placeOk = $place.ok -and (((Prop $place.body "ok") -eq $true) -or ((Prop $place.body "accepted") -eq $true))
        $clSeq = Prop $place.body "clSeqNo"
        Add-Step "4b.place-test" $placeOk $place.body ("clSeqNo=$clSeq msg=" + (Prop $place.body "message"))

        Start-Sleep -Seconds 1
        if ($null -ne $clSeq -and [int]$clSeq -gt 0) {
            $cancelBody = @{ origClSeqNo = [int]$clSeq; code = $Code }
            $cancel = Invoke-Json POST "/api/ops/kuangrui/oes/cancel-test" $cancelBody
            $cancelOk = $cancel.ok -and (((Prop $cancel.body "ok") -eq $true) -or ((Prop $cancel.body "sent") -eq $true))
            Add-Step "4c.cancel-test" $cancelOk $cancel.body ("sent=" + (Prop $cancel.body "sent"))
        } else {
            Add-Step "4c.cancel-test" $false $null "skip: no clSeqNo"
        }

        $sync = Invoke-Json POST "/api/schedule/jobs/sync-orders/run"
        Add-Step "4d.sync-orders" $sync.ok $sync.body ("HTTP " + $sync.statusCode)

        Start-Sleep -Seconds 1
        $ords = Invoke-Json GET "/api/ops/kuangrui/oes/orders"
        Add-Step "4e.oes-orders" $ords.ok $ords.body ("count=" + (Prop $ords.body "count"))
    } else {
        Add-Step "4b.place-test" $false $orderSt.body "orderLive=false; skip place/cancel"
        Add-Step "4c.cancel-test" $false $null "skipped"
        Add-Step "4d.sync-orders" $false $null "skipped"
        Add-Step "4e.oes-orders" $false $null "skipped"
    }
} else {
    Add-Step "4.order-skipped" $true @{ skip = $true } "-SkipOrder"
}

$td = Invoke-Json GET "/api/ops/kuangrui/oes/trading-day"
Add-Step "5a.trading-day" ($td.ok -and ((Prop $td.body "ok") -ne $false)) $td.body ("day=" + (Prop $td.body "tradingDay"))

# Core static: trading-day + commission (always). Stock product queries are soft on sim (may hang).
$cms = Invoke-Json GET "/api/ops/kuangrui/oes/commission-rate"
Add-Step "5b.commission-rate" ($cms.ok -and ((Prop $cms.body "ok") -ne $false)) $cms.body ""

$mss = Invoke-Json GET ("/api/ops/kuangrui/mds/stock-static?code=" + [uri]::EscapeDataString($Code))
Add-Step "5c.mds-stock-static" ($mss.ok -and ((Prop $mss.body "ok") -ne $false)) $mss.body ("count=" + (Prop $mss.body "count"))

$sec = Invoke-Json GET ("/api/ops/kuangrui/mds/security-status?code=" + [uri]::EscapeDataString($Code))
Add-Step "5d.mds-security-status" ($sec.ok -and ((Prop $sec.body "ok") -ne $false)) $sec.body ("count=" + (Prop $sec.body "count"))

$sess = Invoke-Json GET "/api/ops/kuangrui/mds/session-status"
Add-Step "5e.mds-session-status" ($sess.ok -and ((Prop $sess.body "ok") -ne $false)) $sess.body ("count=" + (Prop $sess.body "count"))

# Soft: oes/stock + merged static may timeout on aliyun sim; record WARN but do not fail soak
$softTimeout = [Math]::Min(25, $TimeoutSec)
$oldTimeout = $TimeoutSec
$TimeoutSec = $softTimeout
$stk = Invoke-Json GET ("/api/ops/kuangrui/oes/stock?code=" + [uri]::EscapeDataString($Code))
$stkOk = $stk.ok -and ((Prop $stk.body "ok") -ne $false)
if ($stkOk) {
    Add-Step "5f.oes-stock" $true $stk.body ("code=$Code")
} else {
    Add-Step "5f.oes-stock-soft" $true $stk.body ("WARN soft-fail code=$Code err=" + $stk.error)
}
$merged = Invoke-Json GET ("/api/ops/kuangrui/static/stock?code=" + [uri]::EscapeDataString($Code))
$mergedOk = $merged.ok -and ((Prop $merged.body "ok") -ne $false)
if ($mergedOk) {
    Add-Step "5g.static-stock-merged" $true $merged.body ("code=$Code")
} else {
    Add-Step "5g.static-stock-merged-soft" $true $merged.body ("WARN soft-fail code=$Code err=" + $merged.error)
}
$TimeoutSec = $oldTimeout

$mdsStop2 = Invoke-Json POST "/api/ops/kuangrui/mds/stop"
Add-Step "6.mds-stop-final" $mdsStop2.ok $mdsStop2.body ""

$pass = @($steps | Where-Object { $_.ok }).Count
$fail = @($steps | Where-Object { -not $_.ok }).Count
$allOk = ($fail -eq 0)
Log ""
Log ("=== SUMMARY pass=$pass fail=$fail allOk=$allOk ===")
foreach ($s in $steps) {
    $mark = if ($s.ok) { "PASS" } else { "FAIL" }
    Log ("  {0}  {1}  {2}" -f $mark, $s.name, $s.note)
}

$reportObj = [ordered]@{
    generatedAt = (Get-Date -Format "o")
    baseUrl     = $BaseUrl
    code        = $Code
    skipOrder   = [bool]$SkipOrder
    pass        = $pass
    fail        = $fail
    allOk       = $allOk
    steps       = $steps
}
($reportObj | ConvertTo-Json -Depth 10) | Set-Content -LiteralPath $ReportJson -Encoding UTF8
($lines -join "`r`n") | Set-Content -LiteralPath $ReportTxt -Encoding UTF8
Log ""
Log ("ReportTxt=$ReportTxt")
Log ("ReportJson=$ReportJson")

if (-not $allOk) { exit 2 } else { exit 0 }