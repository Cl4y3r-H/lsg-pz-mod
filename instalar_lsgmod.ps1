# Instala el mod LSGMod en Project Zomboid y, opcionalmente, crea tu archivo de configuración.
# Uso: dejar este archivo junto a LSGMod_mod.zip, clic derecho -> "Ejecutar con PowerShell".

$ErrorActionPreference = "Stop"
$zip     = Join-Path $PSScriptRoot "LSGMod_mod.zip"
$mods    = Join-Path $env:USERPROFILE "Zomboid\mods"
$destino = Join-Path $mods "LSGMod"
$cfgDir  = Join-Path $env:USERPROFILE "Zomboid\LSG"
$cfg     = Join-Path $cfgDir "lsg.properties"
$tmp     = Join-Path $env:TEMP "LSGMod_install"

if (-not (Test-Path $zip)) {
    Write-Host "No se encontro $zip. Deja este script en la misma carpeta que el ZIP." -ForegroundColor Red
    Read-Host "Enter para salir"; exit 1
}

# 1. Instalar el mod (reemplaza una version anterior)
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $tmp -Force
New-Item -ItemType Directory -Force -Path $mods | Out-Null
if (Test-Path $destino) { Remove-Item $destino -Recurse -Force }
Copy-Item -Path (Join-Path $tmp "LSGMod") -Destination $mods -Recurse
Remove-Item $tmp -Recurse -Force

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

if ($ok) { Write-Host "`nListo. Desactiva 'LSG Bridge PoC' y activa 'LifeSync-Games (LSG)' junto a ZombieBuddy." -ForegroundColor Green }
Read-Host "`nEnter para salir"
