# Kuangrui M0 environment check + optional login probe
# Usage:
#   .\scripts\kuangrui\m0-env-check.ps1
#   $env:QUANT_KUANGRUI_USER='x'; $env:QUANT_KUANGRUI_PASSWORD='y'
#   .\scripts\kuangrui\m0-env-check.ps1 -RunLoginProbe
#
# Env:
#   QUANT_KUANGRUI_HOME         SDK root (contains all/mds/oes)
#   QUANT_KUANGRUI_CONFIG_DIR   local config dir (default: repo config/kuangrui/local)
#   QUANT_KUANGRUI_USER / QUANT_KUANGRUI_PASSWORD
#   QUANT_KUANGRUI_DRIVER_ID    optional

param(
    [switch]$RunLoginProbe,
    [switch]$OesOnly,
    [switch]$MdsOnly
)

$ErrorActionPreference = "Continue"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$ReportDir = Join-Path $RepoRoot "scripts\kuangrui\out"
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null
$Report = Join-Path $ReportDir "m0-report.txt"
$lines = New-Object System.Collections.Generic.List[string]
function Log([string]$m) {
    $lines.Add($m) | Out-Null
    Write-Host $m
}

Log ("=== Kuangrui M0 Env Check " + (Get-Date -Format "yyyy-MM-dd HH:mm:ss") + " ===")
Log ("RepoRoot=" + $RepoRoot)

# --- Java ---
$javaOk = $false
try {
    $jv = & java -version 2>&1 | Out-String
    Log ("Java: " + $jv.Trim())
    $javaOk = $true
} catch {
    Log "FAIL: java not available"
}

# --- Package home ---
$kuangruiHome = $env:QUANT_KUANGRUI_HOME
if (-not $kuangruiHome) {
    $preferred = "D:\OESAPI-JAVA-v0.19.4.0-20260430\OESAPI-JAVA-v0.19.4.0-20260430"
    if (Test-Path -LiteralPath $preferred) {
        $kuangruiHome = $preferred
    } else {
        $candRoots = @()
        Get-ChildItem "D:\" -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match "OESAPI-JAVA|OES|MDS|宽睿" } |
            ForEach-Object { $candRoots += $_ }
        foreach ($cand in $candRoots) {
            if ($cand.Name -match "^OESAPI-JAVA-") {
                $kuangruiHome = $cand.FullName
                break
            }
            $pkg = Get-ChildItem -LiteralPath $cand.FullName -Directory -Filter "OESAPI-JAVA-*" -ErrorAction SilentlyContinue |
                Sort-Object Name -Descending |
                Select-Object -First 1
            if ($pkg) { $kuangruiHome = $pkg.FullName; break }
        }
    }
}
Log ("QUANT_KUANGRUI_HOME=" + $kuangruiHome)
$allDir = $null
if ($kuangruiHome) { $allDir = Join-Path $kuangruiHome "all" }
$jar = $null
$apiVersion = $null
if ($allDir -and (Test-Path -LiteralPath $allDir)) {
    $jarFile = Get-ChildItem -LiteralPath $allDir -Filter "quant360-all-api-*.jar" -File -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($jarFile) {
        $jar = $jarFile.FullName
        if ($jarFile.Name -match "quant360-all-api-(.+)\.jar") { $apiVersion = $Matches[1] }
    }
}
$pkgOk = $jar -and (Test-Path -LiteralPath $jar)
if ($pkgOk) { Log ("OK: jar=" + $jar + " version=" + $apiVersion) } else { Log "FAIL: quant360-all-api-*.jar not found (set QUANT_KUANGRUI_HOME)" }

# --- Config ---
$cfgDir = $env:QUANT_KUANGRUI_CONFIG_DIR
if (-not $cfgDir) { $cfgDir = Join-Path $RepoRoot "config\kuangrui\local" }
$exDir = Join-Path $RepoRoot "config\kuangrui\examples"
Log ("CONFIG_DIR=" + $cfgDir)
$oesLocal = Join-Path $cfgDir "oes_api_config.json"
$mdsLocal = Join-Path $cfgDir "mds_api_config.json"
$oesEx = Join-Path $exDir "oes_api_config.example.json"
$mdsEx = Join-Path $exDir "mds_api_config.example.json"
if (Test-Path -LiteralPath $oesEx) { Log "OK: example oes config" } else { Log "FAIL: missing examples" }
if (Test-Path -LiteralPath $oesLocal) { Log "OK: local oes config present" } else { Log "WARN: missing local/oes_api_config.json (copy from examples)" }
if (Test-Path -LiteralPath $mdsLocal) { Log "OK: local mds config present" } else { Log "WARN: missing local/mds_api_config.json" }

$oesCfg = $null
$mdsCfg = $null
if (Test-Path -LiteralPath $oesLocal) {
    $oesCfg = $oesLocal
} elseif ($allDir -and (Test-Path -LiteralPath (Join-Path $allDir "config\oes_api_config.json"))) {
    $oesCfg = Join-Path $allDir "config\oes_api_config.json"
}
if (Test-Path -LiteralPath $mdsLocal) {
    $mdsCfg = $mdsLocal
} elseif ($allDir -and (Test-Path -LiteralPath (Join-Path $allDir "config\mds_api_config.json"))) {
    $mdsCfg = Join-Path $allDir "config\mds_api_config.json"
}
Log ("Effective oesCfg=" + $oesCfg)
Log ("Effective mdsCfg=" + $mdsCfg)

function Get-HostsFromJsonC([string]$path) {
    $result = @()
    if (-not $path -or -not (Test-Path -LiteralPath $path)) { return $result }
    $pendingAddr = $null
    foreach ($line in (Get-Content -LiteralPath $path -Encoding UTF8)) {
        $t = $line.Trim()
        if ($t -like '*"addr"*') {
            $parts = $t.Split('"')
            for ($i = 0; $i -lt $parts.Length - 1; $i++) {
                if ($parts[$i] -eq "addr" -and ($i + 2) -lt $parts.Length) {
                    $pendingAddr = $parts[$i + 2]
                    break
                }
            }
        }
        if ($pendingAddr -and ($t -like '*"port"*')) {
            $parts = $t.Split('"')
            for ($i = 0; $i -lt $parts.Length - 1; $i++) {
                if ($parts[$i] -eq "port" -and ($i + 2) -lt $parts.Length) {
                    $portTok = $parts[$i + 2]
                    $portNum = 0
                    if ([int]::TryParse($portTok, [ref]$portNum)) {
                        $result += [pscustomobject]@{ Host = $pendingAddr; Port = $portNum }
                    }
                    $pendingAddr = $null
                    break
                }
            }
        }
    }
    return $result
}

function Test-TcpFast([string]$hostName, [int]$port, [int]$timeoutMs = 800) {
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $ar = $client.BeginConnect($hostName, $port, $null, $null)
        $ok = $ar.AsyncWaitHandle.WaitOne($timeoutMs, $false)
        if (-not $ok) { return $false }
        $client.EndConnect($ar)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

$portOkAny = $false
$seen = @{}
foreach ($c in @($oesCfg, $mdsCfg)) {
    foreach ($h in (Get-HostsFromJsonC $c)) {
        $key = $h.Host + ":" + $h.Port
        if ($seen.ContainsKey($key)) { continue }
        $seen[$key] = $true
        if ($h.Host -like "YOUR_*") {
            Log ("SKIP port placeholder " + $key)
            continue
        }
        # skip UDP multicast sample addresses
        if ($h.Host -like "232.*" -or $h.Host -like "233.*") {
            Log ("SKIP multicast " + $key)
            continue
        }
        $ok = Test-TcpFast $h.Host $h.Port
        if ($ok) { $portOkAny = $true }
        if ($ok) {
            Log ("OK: TCP " + $key)
        } else {
            Log ("FAIL: TCP " + $key + " closed")
        }
    }
}

$m2Root = $null
try {
    $m2Root = (& mvn -q help:evaluate "-Dexpression=settings.localRepository" "-DforceStdout" 2>$null)
} catch { }
if (-not $m2Root) { $m2Root = Join-Path $env:USERPROFILE ".m2\repository" }
$m2ApiDir = Join-Path $m2Root "com\quant360\quant360-all-api"
$m2Jar = $null
if (Test-Path -LiteralPath $m2ApiDir) {
    $m2Jar = Get-ChildItem -LiteralPath $m2ApiDir -Recurse -Filter "quant360-all-api-*.jar" -File -ErrorAction SilentlyContinue |
        Sort-Object FullName -Descending |
        Select-Object -First 1
}
if ($m2Jar -and (Test-Path -LiteralPath $m2Jar.FullName)) {
    Log ("OK: Maven local " + $m2Jar.FullName)
} else {
    Log ("WARN: quant360-all-api not in Maven repo (localRepository=" + $m2Root + "); install 0.19.4.0 via install-file")
}

$hasCred = [bool]($env:QUANT_KUANGRUI_USER -and $env:QUANT_KUANGRUI_PASSWORD)
if ($hasCred) {
    Log "OK: login env vars set (password not printed)"
} else {
    Log "WARN: QUANT_KUANGRUI_USER / QUANT_KUANGRUI_PASSWORD not set"
}

$loginExit = $null
if ($RunLoginProbe) {
    if (-not $pkgOk -or -not $javaOk) {
        Log "FAIL: cannot run login probe (missing jar or java)"
        $loginExit = 2
    } elseif (-not $hasCred) {
        Log "FAIL: -RunLoginProbe requires QUANT_KUANGRUI_USER/PASSWORD"
        $loginExit = 2
    } elseif (-not $oesCfg -or -not $mdsCfg) {
        Log "FAIL: missing oes/mds config files"
        $loginExit = 2
    } else {
        $libDir = Join-Path $allDir "lib"
        $cpList = New-Object System.Collections.Generic.List[string]
        $cpList.Add($jar) | Out-Null
        Get-ChildItem -LiteralPath $libDir -Filter "*.jar" | ForEach-Object { $cpList.Add($_.FullName) | Out-Null }
        $cpStr = [string]::Join(";", $cpList)
        $outCls = Join-Path $ReportDir "classes"
        New-Item -ItemType Directory -Force -Path $outCls | Out-Null
        $src = Join-Path $PSScriptRoot "M0LoginProbe.java"
        Log ("Compile " + $src)
        & javac -encoding UTF-8 -cp $cpStr -d $outCls $src 2>&1 | ForEach-Object { Log ("$_") }
        if ($LASTEXITCODE -ne 0) {
            Log "FAIL: javac failed"
            $loginExit = 2
        } else {
            $probeCp = $outCls + ";" + $cpStr
            $javaArgs = New-Object System.Collections.Generic.List[string]
            $javaArgs.Add("-Dfile.encoding=UTF-8") | Out-Null
            $javaArgs.Add("-cp") | Out-Null
            $javaArgs.Add($probeCp) | Out-Null
            $javaArgs.Add("M0LoginProbe") | Out-Null
            $javaArgs.Add("--oes-config=" + $oesCfg) | Out-Null
            $javaArgs.Add("--mds-config=" + $mdsCfg) | Out-Null
            if ($OesOnly) { $javaArgs.Add("--oes-only") | Out-Null }
            if ($MdsOnly) { $javaArgs.Add("--mds-only") | Out-Null }
            Push-Location $allDir
            try {
                Log "Running login probe..."
                & java @($javaArgs.ToArray()) 2>&1 | ForEach-Object { Log ("$_") }
                $loginExit = $LASTEXITCODE
            } finally {
                Pop-Location
            }
        }
    }
}

Log "--- Summary ---"
Log ("package_ok=" + $pkgOk + " java_ok=" + $javaOk + " port_any_ok=" + $portOkAny + " creds=" + $hasCred + " login_exit=" + $loginExit)
$m0Complete = $pkgOk -and $javaOk -and $portOkAny -and ($loginExit -eq 0)
if ($m0Complete) {
    Log "M0_STATUS=COMPLETE"
} elseif ($portOkAny -and $hasCred -and ($loginExit -ne 0) -and ($loginExit -ne -1)) {
    Log "M0_STATUS=BLOCKED (TCP OK but login/pre-logon failed; 0.19.4: 1045=INVALID_USERNAME_OR_PASSWORD; check account + API version)"
} elseif (-not $portOkAny) {
    Log "M0_STATUS=BLOCKED (sim host TCP unreachable; scaffolding ready)"
} else {
    Log "M0_STATUS=BLOCKED (need reachable sim host + credentials; scaffolding ready)"
}
$lines | Set-Content -LiteralPath $Report -Encoding UTF8
Log ("Report: " + $Report)
if ($m0Complete) { exit 0 } else { exit 1 }
