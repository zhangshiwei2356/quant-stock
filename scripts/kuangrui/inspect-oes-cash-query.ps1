# 对照资料包 Demo / javap，排查「查资金无结果」
# Usage:
#   $env:QUANT_KUANGRUI_HOME = "D:\OESAPI-JAVA-v0.19.4.0-20260430\OESAPI-JAVA-v0.19.4.0-20260430"
#   .\scripts\kuangrui\inspect-oes-cash-query.ps1
#
# 输出：scripts/kuangrui/out/cash-query-inspect.txt（可粘贴给联调）

$ErrorActionPreference = "Continue"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$ReportDir = Join-Path $RepoRoot "scripts\kuangrui\out"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$Report = Join-Path $ReportDir "cash-query-inspect.txt"
$lines = New-Object System.Collections.Generic.List[string]
function Log([string]$m) {
    $lines.Add($m) | Out-Null
    Write-Host $m
}

Log ("=== OES cash-query inspect " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " ===")

$kuangruiHome = $env:QUANT_KUANGRUI_HOME
if (-not $kuangruiHome) {
    $preferred = "D:\OESAPI-JAVA-v0.19.4.0-20260430\OESAPI-JAVA-v0.19.4.0-20260430"
    if (Test-Path -LiteralPath $preferred) { $kuangruiHome = $preferred }
}
if (-not $kuangruiHome -or -not (Test-Path -LiteralPath $kuangruiHome)) {
    Log "FAIL: QUANT_KUANGRUI_HOME 无效。请先设置资料包根目录。"
    $lines | Set-Content -LiteralPath $Report -Encoding UTF8
    exit 1
}
Log ("HOME=" + $kuangruiHome)

$jar = Join-Path $kuangruiHome "all\quant360-all-api-0.19.4.0.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    $found = Get-ChildItem -LiteralPath (Join-Path $kuangruiHome "all") -Filter "quant360-all-api-*.jar" -File -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { $jar = $found.FullName }
}
Log ("JAR=" + $jar)
if (-not (Test-Path -LiteralPath $jar)) {
    Log "FAIL: 找不到 quant360-all-api jar"
    $lines | Set-Content -LiteralPath $Report -Encoding UTF8
    exit 1
}

Log ""
Log "--- 1) Demo 源码命中（queryCash / QueryMode / sendRptSync）---"
$demoRoots = @(
    (Join-Path $kuangruiHome "all\demo"),
    (Join-Path $kuangruiHome "oes\demo"),
    (Join-Path $kuangruiHome "all\src")
) | Where-Object { Test-Path -LiteralPath $_ }

$pattern = 'queryCashAsset|querySingleCashAsset|queryClientOverview|queryCounterCash|sendRptSync|QueryMode'
$hitCount = 0
foreach ($root in $demoRoots) {
    Log ("scan: " + $root)
    Get-ChildItem -LiteralPath $root -Recurse -Include *.java,*.txt,*.md -ErrorAction SilentlyContinue |
        ForEach-Object {
            $hits = Select-String -LiteralPath $_.FullName -Pattern $pattern -ErrorAction SilentlyContinue
            foreach ($h in $hits) {
                $hitCount++
                Log ("  {0}:{1}: {2}" -f $_.FullName, $h.LineNumber, $h.Line.Trim())
            }
        }
}
if ($hitCount -eq 0) {
    Log "WARN: demo 目录未命中；请人工打开 all/demo/OesExample.java"
}

Log ""
Log "--- 2) javap OesClientImpl 查询相关签名 ---"
$javap = Get-Command javap -ErrorAction SilentlyContinue
if (-not $javap) {
    Log "FAIL: javap 不在 PATH（需 JDK bin）"
} else {
    $oesOut = & javap -classpath $jar -public com.quant360.api.client.impl.OesClientImpl 2>&1 | Out-String
    foreach ($line in ($oesOut -split "`r?`n")) {
        if ($line -match "queryCash|queryClient|queryCounter|queryStkHolding|sendRptSync|QueryMode") {
            Log ("  " + $line.Trim())
        }
    }
    Log ""
    Log "--- 3) javap Client.QueryMode 枚举常量 ---"
    $modeOut = & javap -classpath $jar -public 'com.quant360.api.client.Client$QueryMode' 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($modeOut)) {
        $modeOut = & javap -classpath $jar -public com.quant360.api.client.Client`$QueryMode 2>&1 | Out-String
    }
    Log ($modeOut.Trim())
    Log ""
    Log "--- 4) javap -verbose QueryMode（含常量名）---"
    $verbose = & javap -classpath $jar -verbose 'com.quant360.api.client.Client$QueryMode' 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        $verbose = & javap -classpath $jar -verbose com.quant360.api.client.Client`$QueryMode 2>&1 | Out-String
    }
    foreach ($line in ($verbose -split "`r?`n")) {
        if ($line -match "Enum|#[0-9]|public static final|flags:|ConstantValue|QueryMode") {
            Log ("  " + $line.Trim())
        }
    }
}

Log ""
Log "--- 5) 对照本应用已知故障 ---"
Log "可用签名曾为: queryCashAsset(OesQryCashAssetFilter, QueryMode)"
Log "旧逻辑第二参传 null → NPE mode.ordinal() → cash=[]"
Log "修复后应看到日志: [oes] queryCashAsset 返回 N 条 via ...#<枚举名>"
Log "请确认已 git pull 且 IDEA Maven profile kuangrui Rebuild 后再点「查资金」。"
Log ""
Log ("报告已写: " + $Report)

$lines | Set-Content -LiteralPath $Report -Encoding UTF8
exit 0
