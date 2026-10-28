# Backup de Gastito: pg_dump de la base real (desde el contenedor gastito-db de la VM, por SSH) y una copia de data/.
# Cada backup es una carpeta gastito-AAAA-MM-DD_HHmm con gastito.dump y data\. Se conservan los últimos 30.
# Carpeta de destino: -Destino, o BACKUP_DIR en el .env, o %USERPROFILE%\Backups\gastito.
# Para probar que sirve: restaurar.ps1 (restaura en una base nueva, sin tocar la real).
param(
    [string]$Destino,
    [string]$Base = 'gastito',
    [string]$Ssh = 'devbox',
    [string]$Contenedor = 'gastito-db',
    [int]$Conservar = 30
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raiz = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'vm.ps1')

$variables = Leer-Env $raiz
$usuario = if ($variables['DB_USER']) { $variables['DB_USER'] } else { 'gastito' }
if (-not $Destino) {
    if ($variables['BACKUP_DIR']) {
        $Destino = $variables['BACKUP_DIR']
    } else {
        $Destino = Join-Path $env:USERPROFILE 'Backups\gastito'
        Write-Host "Falta BACKUP_DIR en el .env: uso $Destino, que no se sincroniza con la nube." -ForegroundColor Yellow
    }
}
foreach ($par in @(@($Base, 'Base'), @($Ssh, 'Host SSH'), @($Contenedor, 'Contenedor'), @($usuario, 'DB_USER'))) {
    Comprobar-Nombre $par[0] $par[1]
}

$carpeta = Join-Path $Destino ("gastito-" + (Get-Date -Format 'yyyy-MM-dd_HHmm'))
if (Test-Path $carpeta) { throw "Ya existe $carpeta (¿dos backups en el mismo minuto?)" }
New-Item -ItemType Directory -Force $carpeta | Out-Null

try {
    # 1. La base
    Write-Host "-> pg_dump de la base $Base" -ForegroundColor Cyan
    $dump = Join-Path $carpeta "$Base.dump"
    Invocar-VmBinario $Ssh "docker exec $Contenedor pg_dump -U $usuario -d $Base --format=custom" -salidaA $dump
    $cabecera = New-Object byte[] 5
    $lector = [System.IO.File]::OpenRead($dump)
    try { [void]$lector.Read($cabecera, 0, 5) } finally { $lector.Dispose() }
    if ([System.Text.Encoding]::ASCII.GetString($cabecera) -ne 'PGDMP') { throw "El dump no tiene el formato de pg_dump" }
    Write-Host ("   {0:N0} KB" -f ((Get-Item $dump).Length / 1KB)) -ForegroundColor Green

    # 2. data/ (fotos de tickets, recibos...), salvo los backups viejos que pudiera haber adentro
    Write-Host "-> Copiando data\" -ForegroundColor Cyan
    $origenData = Join-Path $raiz 'data'
    if (Test-Path $origenData) {
        $excluir = @((Join-Path $origenData 'backups'), $Destino)
        & robocopy $origenData (Join-Path $carpeta 'data') /E /R:1 /W:1 /NFL /NDL /NJH /NJS /NP /XD @excluir | Out-Null
        if ($LASTEXITCODE -ge 8) { throw "robocopy falló copiando data\ (código $LASTEXITCODE)" }
        $archivos = @(Get-ChildItem (Join-Path $carpeta 'data') -Recurse -File -ErrorAction SilentlyContinue).Count
        Write-Host "   $archivos archivos" -ForegroundColor Green
    } else {
        Write-Host "   No hay carpeta data\" -ForegroundColor Yellow
    }
} catch {
    Remove-Item -Recurse -Force $carpeta -ErrorAction SilentlyContinue
    Write-Host "X  El backup falló: $($_.Exception.Message)" -ForegroundColor Red
    exit 1
}

# 3. Conservar solo los últimos
$viejos = @(Get-ChildItem $Destino -Directory -Filter 'gastito-*' | Sort-Object Name -Descending | Select-Object -Skip $Conservar)
$viejos | ForEach-Object { Remove-Item -Recurse -Force $_.FullName }
if ($viejos.Count -gt 0) { Write-Host "-> Borré $($viejos.Count) backups viejos (quedan $Conservar)" -ForegroundColor Cyan }

Write-Host "Backup listo: $carpeta" -ForegroundColor Green
