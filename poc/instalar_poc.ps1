# Instala el mod LSGBridgePoC en la carpeta de mods de Project Zomboid y verifica los archivos.
# Uso: dejar este archivo junto a LSGBridgePoC_mod.zip, clic derecho -> "Ejecutar con PowerShell".

$ErrorActionPreference = "Stop"
$zip     = Join-Path $PSScriptRoot "LSGBridgePoC_mod.zip"
$mods    = Join-Path $env:USERPROFILE "Zomboid\mods"
$destino = Join-Path $mods "LSGBridgePoC"
$tmp     = Join-Path $env:TEMP "LSGBridgePoC_install"

if (-not (Test-Path $zip)) {
    Write-Host "No se encontro $zip. Deja este script en la misma carpeta que el ZIP." -ForegroundColor Red
    Read-Host "Enter para salir"; exit 1
}

# 1. Extraer a una carpeta temporal limpia
if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
Expand-Archive -Path $zip -DestinationPath $tmp -Force

# 2. Reemplazar cualquier instalacion anterior (incluida una carpeta vacia)
New-Item -ItemType Directory -Force -Path $mods | Out-Null
if (Test-Path $destino) { Remove-Item $destino -Recurse -Force }
Copy-Item -Path (Join-Path $tmp "LSGBridgePoC") -Destination $mods -Recurse
Remove-Item $tmp -Recurse -Force

# 3. Verificar que esten todos los archivos
$esperados = @(
    "42\mod.info",
    "42\media\java\LSGBridgePoC.jar",
    "42\media\lua\client\LSG_BridgePoC.lua"
)
$ok = $true
Write-Host "`nInstalado en: $destino`n"
foreach ($f in $esperados) {
    $ruta = Join-Path $destino $f
    if (Test-Path $ruta) {
        Write-Host ("  OK      " + $f + "  (" + (Get-Item $ruta).Length + " bytes)") -ForegroundColor Green
    } else {
        Write-Host ("  FALTA   " + $f) -ForegroundColor Red
        $ok = $false
    }
}
if (-not (Test-Path (Join-Path $destino "common"))) {
    New-Item -ItemType Directory -Path (Join-Path $destino "common") | Out-Null
}

if ($ok) {
    Write-Host "`nListo. Abre Project Zomboid y busca 'LSG Bridge PoC' en el menu Mods." -ForegroundColor Green
} else {
    Write-Host "`nFaltan archivos. Si falta el .jar, revisa si el antivirus lo puso en cuarentena." -ForegroundColor Yellow
}
Read-Host "`nEnter para salir"
