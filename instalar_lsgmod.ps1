# Instala el mod LSGMod en Project Zomboid y, opcionalmente, crea tu archivo de configuracion.
# Funciona de dos formas:
#   - junto a LSGMod_mod.zip (descarga), o
#   - desde la carpeta del repositorio lsg-pz-mod (usa mod\LSGMod directamente).
# Uso: powershell -ExecutionPolicy Bypass -File .\instalar_lsgmod.ps1

$ErrorActionPreference = "Stop"
$zip     = Join-Path $PSScriptRoot "LSGMod_mod.zip"
$repoMod = Join-Path $PSScriptRoot "mod\LSGMod"
$mods    = Join-Path $env:USERPROFILE "Zomboid\mods"
$destino = Join-Path $mods "LSGMod"
$cfgDir  = Join-Path $env:USERPROFILE "Zomboid\LSG"
$cfg     = Join-Path $cfgDir "lsg.properties"
$tmp     = Join-Path $env:TEMP "LSGMod_install"

if (Test-Path $zip) {
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    Expand-Archive -Path $zip -DestinationPath $tmp -Force
    $origen = Join-Path $tmp "LSGMod"
    Write-Host "Origen: $zip"
} elseif (Test-Path $repoMod) {
    $origen = $repoMod
    Write-Host "Origen: carpeta del repositorio ($repoMod)"
} else {
    Write-Host "No se encontro LSGMod_mod.zip ni la carpeta mod\LSGMod junto a este script." -ForegroundColor Red
    Read-Host "Enter para salir"; exit 1
}

# 1. Instalar el mod (reemplaza una version anterior)
New-Item -ItemType Directory -Force -Path $mods | Out-Null
if (Test-Path $destino) { Remove-Item $destino -Recurse -Force }
Copy-Item -Path $origen -Destination $mods -Recurse
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }

$ok = $true
foreach ($f in @("42\mod.info", "42\media\java\LSGMod.jar", "42\media\lua\client\LSG_Main.lua", "42\media\lua\client\UI\ISLifeSyncWindow.lua")) {
    if (Test-Path (Join-Path $destino $f)) { Write-Host "  OK      $f" -ForegroundColor Green }
    else { Write-Host "  FALTA   $f" -ForegroundColor Red; $ok = $false }
}
if (-not (Test-Path (Join-Path $destino "common"))) { New-Item -ItemType Directory -Path (Join-Path $destino "common") | Out-Null }

# 2. Configuracion (opcional)
Write-Host ""
$resp = Read-Host "Quieres crear o actualizar tu configuracion LSG ahora? (s/n)"
if ($resp -eq "s") {
    $email = Read-Host "Correo de LifeSync-Games"
    $sec   = Read-Host "Contrasena de LifeSync-Games" -AsSecureString
    $pass  = [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($sec))
    $pass  = $pass -replace '\\', '\\'   # en .properties una barra invertida se escribe doble (patron regex \\ = una barra)
    New-Item -ItemType Directory -Force -Path $cfgDir | Out-Null
    $texto = @(
        "# Configuracion del mod LSG (LifeSync-Games). Archivo personal: no lo compartas.",
        "email=$email",
        "password=$pass",
        "auth_url=https://lsg.diinf.usach.cl/lsg-auth",
        "core_url=https://lsg.diinf.usach.cl/lsg-core-api",
        "# dim.2=Fisico"
    ) -join "`n"
    [System.IO.File]::WriteAllText($cfg, $texto, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Configuracion guardada en $cfg" -ForegroundColor Green
}

$ver = Select-String -Path (Join-Path $destino "42\media\lua\client\LSG_Main.lua") -Pattern "Versi.{1,2}n (\d+\.\d+\.\d+)" | Select-Object -First 1
if ($ver) { Write-Host ("Version instalada: " + $ver.Matches[0].Groups[1].Value) }
if ($ok) { Write-Host "`nListo. Desactiva 'LSG Bridge PoC' y activa 'LifeSync-Games (LSG)' junto a ZombieBuddy." -ForegroundColor Green }
Read-Host "`nEnter para salir"
