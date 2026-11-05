# Fotokiosek – instalace na bývalý automat
# Vypne původní prodejní aplikaci, nainstaluje fotokiosek a zamkne ho jako kiosek.
# Spuštění: dvojklik na spustit-instalaci.bat

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$pkg = 'cz.fotokiosek'
$ourAdmin = "$pkg/.KioskAdminReceiver"
$ip = $null

function Step([string]$title) { Write-Host ''; Write-Host "=== $title ===" -ForegroundColor Cyan }
function Info([string]$text) { Write-Host $text }
function Warn([string]$text) { Write-Host $text -ForegroundColor Yellow }
function Fail([string]$text) { Write-Host $text -ForegroundColor Red }
function Ok([string]$text) { Write-Host $text -ForegroundColor Green }
function Ask([string]$question) {
    $answer = Read-Host "$question [a/n]"
    return ($answer -match '^\s*(a|ano|y|yes)\s*$')
}

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        (Join-Path $here 'platform-tools\adb.exe'),
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:LOCALAPPDATA\Microsoft\WinGet\Links\adb.exe"
    )
    foreach ($p in $candidates) { if (Test-Path $p) { return $p } }
    return $null
}

$adbExe = Find-Adb
if (-not $adbExe) {
    Fail 'Nenašel jsem program adb.'
    Info 'Nainstaluj ho v PowerShellu příkazem:   winget install Google.PlatformTools'
    Info 'Pak tohle okno zavři a spusť instalaci znovu.'
    exit 1
}

function Invoke-Adb {
    $out = & $adbExe @args 2>&1 | ForEach-Object { "$_".TrimEnd("`r") }
    return (($out -join "`n").Trim())
}
function Sh([string]$command) { return (Invoke-Adb shell $command) }

function Get-Devices {
    return @((Invoke-Adb devices) -split "`n" | Select-Object -Skip 1 | ForEach-Object { $_.Trim() } | Where-Object { $_ })
}

function Wait-Boot {
    Info 'Čekám, až automat naběhne…'
    if ($script:ip) { for ($i = 0; $i -lt 30; $i++) { if ((Invoke-Adb connect $script:ip) -match 'connected') { break }; Start-Sleep 3 } }
    Invoke-Adb wait-for-device | Out-Null
    for ($i = 0; $i -lt 90; $i++) {
        if ((Sh 'getprop sys.boot_completed') -eq '1') { Start-Sleep 5; return $true }
        Start-Sleep 2
    }
    return $false
}

$ownerPattern = 'Device Owner:[^\n]*\n\s*admin=ComponentInfo\{([^}]+)\}'

# ---------------------------------------------------------------------------
Step '1/7  Připojení k automatu'
Info 'Automat připoj USB kabelem (se zapnutým laděním USB), nebo ho měj ve stejné síti jako tento počítač.'
Invoke-Adb start-server | Out-Null
$devices = Get-Devices
if (-not ($devices -match '\tdevice$')) {
    if ($devices -match 'unauthorized') {
        Warn 'Automat čeká na povolení: na jeho obrazovce potvrď „Povolit ladění USB“ a spusť instalaci znovu.'
        exit 1
    }
    $ip = Read-Host 'Přes USB nic nevidím. Zadej IP adresu automatu (Enter = konec)'
    if (-not $ip) { exit 1 }
    $ip = $ip.Trim()
    if ($ip -notmatch ':') { $ip = "${ip}:5555" }
    Info (Invoke-Adb connect $ip)
    Start-Sleep 2
    $devices = Get-Devices
    if (-not ($devices -match '\tdevice$')) {
        Fail 'Připojení se nepovedlo.'
        Info ($devices -join "`n")
        exit 1
    }
}
$ready = @($devices | Where-Object { $_ -match '\tdevice$' })
if ($ready.Count -gt 1) {
    Info ($ready -join "`n")
    $env:ANDROID_SERIAL = (Read-Host 'Je připojeno víc zařízení. Opiš sériové číslo automatu (první sloupec)').Trim()
}
Ok 'Automat je připojený.'

# ---------------------------------------------------------------------------
Step '2/7  Diagnostika'
$sections = [ordered]@{
    'Android'                = 'getprop ro.build.version.release'
    'SDK'                    = 'getprop ro.build.version.sdk'
    'Model'                  = 'getprop ro.product.model'
    'Deska'                  = 'getprop ro.board.platform'
    'Typ systému'            = 'getprop ro.build.type'
    'Displej'                = 'wm size; wm density'
    'Kamery v Androidu'      = "dumpsys media.camera | grep -iE 'Number of camera|Facing'"
    'Video zařízení'         = 'ls -l /dev/video* 2>/dev/null'
    'USB zařízení'           = 'cat /sys/bus/usb/devices/*/product 2>/dev/null'
    'Root (su)'              = 'which su'
    'Domovská aplikace'      = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
    'Správce zařízení'       = "dumpsys device_policy | grep -iE 'Device Owner|admin=' | head -n 10"
    'Účty'                   = "dumpsys account | grep -i -A3 'Accounts:' | head -n 10"
    'Aplikace třetích stran' = 'pm list packages -3'
}
$report = foreach ($key in $sections.Keys) { "## $key"; Sh $sections[$key]; '' }
$report = $report -join "`n"
$report | Out-File -Encoding utf8 (Join-Path $here 'diagnostika.txt')
Info $report
Ok 'Uloženo do diagnostika.txt'

# ---------------------------------------------------------------------------
Step '3/7  Správce zařízení'
$owner = $null
if ((Sh 'dumpsys device_policy') -match $ownerPattern) { $owner = $Matches[1] }
if (-not $owner) {
    Ok 'Žádný jiný správce zařízení – v pořádku.'
} elseif ($owner -like "$pkg/*") {
    Ok 'Fotokiosek už je správcem zařízení.'
} else {
    Warn "Automat má jako správce zařízení: $owner"
    Warn 'Dokud tam je, jejich aplikace nejde vypnout a fotokiosek nejde zamknout.'
    $r = Sh "dpm remove-active-admin $owner"
    if ($r -match 'Success') {
        Ok 'Správce zařízení odebrán.'
    } else {
        Info "Běžným příkazem to nejde ($r). Zkouším root…"
        $rootVia = $null
        $rootOut = Invoke-Adb root
        if ($rootOut -notmatch 'cannot|production') {
            Start-Sleep 3
            if ($ip) { Invoke-Adb connect $ip | Out-Null }
            Invoke-Adb wait-for-device | Out-Null
        }
        if ((Sh 'id') -match 'uid=0') { $rootVia = 'adb' }
        elseif ((Sh 'su -c id') -match 'uid=0') { $rootVia = 'su' }

        if ($rootVia) {
            Warn 'Automat má root. Správce zařízení jde odstranit smazáním jeho záznamu a restartem.'
            if (Ask 'Smazat záznam správce zařízení a restartovat automat?') {
                $rm = 'rm -f /data/system/device_owner_2.xml /data/system/device_policies.xml'
                if ($rootVia -eq 'adb') { Sh $rm | Out-Null } else { Sh "su -c '$rm'" | Out-Null }
                Invoke-Adb reboot | Out-Null
                Start-Sleep 10
                if (-not (Wait-Boot)) { Fail 'Automat nenaběhl včas. Až naběhne, spusť instalaci znovu.'; exit 1 }
                if ((Sh 'dumpsys device_policy') -match $ownerPattern) {
                    Fail "Správce zařízení tam pořád je: $($Matches[1])"
                } else {
                    Ok 'Správce zařízení odstraněn.'
                }
            }
        } else {
            Fail 'Root není k dispozici.'
            Info 'Zbývá obnovení továrního nastavení (pokud ho výrobce nezablokoval), případně přeflashování firmwaru.'
            Info 'Pošli soubor diagnostika.txt, poradíme dál. Instalace teď pokračuje, jen kiosek nepůjde úplně zamknout.'
        }
    }
}

# ---------------------------------------------------------------------------
Step '4/7  Původní aplikace automatu'
$homePkg = $null
$homeLine = ((Sh 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') -split "`n")[-1].Trim()
if ($homeLine -match '^([\w\.]+)/') { $homePkg = $Matches[1] }
$standard = @('android', 'com.android.launcher3', 'com.android.launcher', 'com.google.android.apps.nexuslauncher', 'com.android.settings', $pkg)
$apps = @((Sh 'pm list packages -3') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() } | Where-Object { $_ -and $_ -ne $pkg })
if ($homePkg -and ($standard -notcontains $homePkg) -and ($apps -notcontains $homePkg)) { $apps = @($homePkg) + $apps }

if ($apps.Count -eq 0) {
    Info 'Nenašel jsem žádné další aplikace.'
} else {
    Info 'Aplikace na automatu (kromě systémových):'
    for ($i = 0; $i -lt $apps.Count; $i++) {
        $mark = ''
        if ($apps[$i] -eq $homePkg) { $mark = '   <- spouští se po zapnutí (domovská obrazovka)' }
        Info ('  [{0}] {1}{2}' -f ($i + 1), $apps[$i], $mark)
    }
    $default = ''
    if ($homePkg -and ($apps -contains $homePkg)) { $default = [string]([array]::IndexOf($apps, $homePkg) + 1) }
    Info ''
    Info 'Které aplikace vypnout? Napiš čísla oddělená čárkou.'
    Info 'Vypnuté aplikace v automatu zůstanou a jdou zase zapnout; navíc se zazálohují do složky zaloha.'
    $choice = Read-Host "Čísla (Enter = $(if ($default) { $default } else { 'žádné' }))"
    if (-not $choice) { $choice = $default }
    $selected = @($choice -split '[,\s]+' |
        Where-Object { $_ -match '^\d+$' -and [int]$_ -ge 1 -and [int]$_ -le $apps.Count } |
        ForEach-Object { $apps[[int]$_ - 1] })

    $backupDir = Join-Path $here 'zaloha'
    foreach ($app in $selected) {
        New-Item -ItemType Directory -Force $backupDir | Out-Null
        $path = (((Sh "pm path $app") -split "`n" | Select-Object -First 1) -replace '^package:', '').Trim()
        if ($path) { Invoke-Adb pull $path (Join-Path $backupDir "$app.apk") | Out-Null }
        Sh "am force-stop $app" | Out-Null
        $r = Sh "pm disable-user --user 0 $app"
        if ($r -match 'disabled') {
            Ok "Vypnuto: $app"
        } else {
            $r2 = Sh "pm uninstall -k --user 0 $app"
            if ($r2 -match 'Success') { Ok "Odebráno: $app (vrátí se příkazem: adb shell cmd package install-existing $app)" }
            else { Fail "Nepovedlo se vypnout $app – $r / $r2" }
        }
    }
}

# ---------------------------------------------------------------------------
Step '5/7  Instalace fotokiosku'
$releaseUrl = 'https://github.com/dominikperlik-png/fotokiosek/releases/latest/download/fotokiosek.apk'
$localApk = Join-Path $here 'Fotokiosek.apk'
if (-not (Test-Path $localApk) -or (Ask 'Stáhnout nejnovější verzi z GitHubu (jinak se použije Fotokiosek.apk ze složky)?')) {
    try {
        Info "Stahuji $releaseUrl"
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        # Nejdřív do dočasného souboru – nepovedené stažení nesmí přepsat funkční Fotokiosek.apk.
        $tmpApk = "$localApk.stahuje"
        Invoke-WebRequest -Uri $releaseUrl -OutFile $tmpApk -UseBasicParsing
        Move-Item -Force $tmpApk $localApk
        Ok 'Staženo.'
    } catch {
        Warn "Stažení se nepovedlo ($($_.Exception.Message)), použiju APK ze složky."
        if ($tmpApk) { Remove-Item -Force -ErrorAction SilentlyContinue $tmpApk }
    }
}
$apk = @(
    $localApk,
    (Join-Path $here 'app\build\outputs\apk\release\app-release.apk'),
    (Join-Path $here 'app\build\outputs\apk\debug\app-debug.apk')
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apk) { Fail 'Nenašel jsem Fotokiosek.apk vedle tohoto skriptu.'; exit 1 }
$sdk = 0
[int]::TryParse((Sh 'getprop ro.build.version.sdk'), [ref]$sdk) | Out-Null
function Install-Apk { if ($sdk -ge 23) { return (Invoke-Adb install -r -g $apk) } else { return (Invoke-Adb install -r $apk) } }
$r = Install-Apk
if ($r -match 'UPDATE_INCOMPATIBLE|signatures do not match') {
    # Starší verze byla podepsaná jiným klíčem. Jednou je potřeba odinstalovat – pak už jdou aktualizace z menu.
    Warn 'Na automatu je starší fotokiosek podepsaný jiným klíčem. Je potřeba ho jednou odinstalovat'
    Warn '(nastavení se smaže – config.properties ho na konci nahraje znovu).'
    $isOwner = ((Sh 'dumpsys device_policy') -match $ownerPattern) -and ($Matches[1] -like "$pkg/*")
    if ($isOwner) {
        Fail 'Fotokiosek je správcem zařízení, takže nejde odinstalovat.'
        Info 'Na automatu otevři menu správce → Systém → „Odebrat správce zařízení“ a spusť instalaci znovu.'
        exit 1
    }
    if (Ask 'Odinstalovat starou verzi a nainstalovat novou?') {
        Info (Invoke-Adb uninstall $pkg)
        $r = Install-Apk
    }
} elseif ($r -match 'VERSION_DOWNGRADE') {
    Warn 'Na automatu je novější verze než tohle APK. Aktualizuj přes menu správce → Aktualizace, nebo stáhni nejnovější verzi.'
}
if ($r -match 'Success') { Ok 'Fotokiosek nainstalován.' } elseif ($r -notmatch 'VERSION_DOWNGRADE') { Fail "Instalace selhala: $r"; exit 1 }

# ---------------------------------------------------------------------------
Step '6/7  Zamčení kiosku (správce zařízení)'
$owner = $null
if ((Sh 'dumpsys device_policy') -match $ownerPattern) { $owner = $Matches[1] }
if ($owner -like "$pkg/*") {
    Ok 'Už nastaveno.'
} else {
    $r = Sh "dpm set-device-owner $ourAdmin"
    if ($r -match 'Success') {
        Ok 'Fotokiosek je správcem zařízení – kiosek je zamčený.'
    } else {
        Fail "Nastavení správce zařízení selhalo: $r"
        if ($r -match 'account') {
            Warn 'Na automatu je přihlášený účet (např. Google). Odeber ho v Nastavení → Účty a spusť instalaci znovu.'
            Info (Sh "dumpsys account | grep -i -A3 'Accounts:'")
        } elseif ($r -match 'already') {
            Warn 'Na automatu je pořád jiný správce zařízení (viz krok 3).'
        }
        Warn 'Fotokiosek poběží i tak, jen nebude úplně zamčený.'
    }
}

# ---------------------------------------------------------------------------
Step '7/7  Nastavení a spuštění'
$remoteDir = "/sdcard/Android/data/$pkg/files"
Sh "am start -n $pkg/.MainActivity" | Out-Null
Start-Sleep 4
$cfg = Join-Path $here 'config.properties'
if (Test-Path $cfg) {
    Sh "mkdir -p $remoteDir" | Out-Null
    $r = Invoke-Adb push $cfg "$remoteDir/config.properties"
    if ($r -match 'pushed|1 file') { Ok 'Nastavení z config.properties nahráno.' } else { Warn "Nahrání config.properties selhalo: $r" }
    Sh "am force-stop $pkg" | Out-Null
    Start-Sleep 1
    Sh "am start -n $pkg/.MainActivity" | Out-Null
} else {
    Info 'Soubor config.properties nenalezen – e-mail nastav přímo v aplikaci'
    Info '(5× klepnout do levého horního rohu, PIN 1234).'
}
$logo = @('logo.png', 'logo.jpg', 'logo.jpeg', 'logo.svg', 'logo.webp') | ForEach-Object { Join-Path $here $_ } | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($logo) {
    Sh "mkdir -p $remoteDir" | Out-Null
    $r = Invoke-Adb push $logo "$remoteDir/$(Split-Path -Leaf $logo)"
    if ($r -match 'pushed|1 file') {
        Ok "Logo $(Split-Path -Leaf $logo) nahráno."
        Sh "am force-stop $pkg" | Out-Null
        Start-Sleep 1
        Sh "am start -n $pkg/.MainActivity" | Out-Null
    } else { Warn "Nahrání loga selhalo: $r" }
}

Write-Host ''
Ok 'Hotovo!'
if (Ask 'Restartovat teď automat a ověřit, že po zapnutí naběhne fotokiosek?') {
    Invoke-Adb reboot | Out-Null
    Info 'Automat se restartuje.'
}
