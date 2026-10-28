# Restaura un backup de backup.ps1 en una base NUEVA del mismo PostgreSQL, para comprobar que el backup sirve sin tocar
# la real. Nunca restaura sobre gastito ni gastito_prueba. Al final compara las filas de cada tabla con la base de origen.
#   restaurar.ps1                          el backup más reciente, en la base gastito_restaurada
#   restaurar.ps1 -Backup <carpeta|.dump> -BaseNueva otra_base
#   restaurar.ps1 -Reemplazar              si la base nueva ya existe, la borra antes (solo esa)
#   restaurar.ps1 -Borrar                  borra la base nueva al terminar (solo para probar el backup)
param(
    [string]$Backup,
    [string]$BaseNueva = 'gastito_restaurada',
    [string]$Destino,
    [string]$Comparar = 'gastito',
    [string]$Ssh = 'devbox',
    [string]$Contenedor = 'gastito-db',
    [switch]$Reemplazar,
    [switch]$Borrar
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raiz = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'vm.ps1')

$variables = Leer-Env $raiz
$usuario = if ($variables['DB_USER']) { $variables['DB_USER'] } else { 'gastito' }
foreach ($par in @(@($BaseNueva, 'Base nueva'), @($Comparar, 'Base a comparar'), @($Ssh, 'Host SSH'),
        @($Contenedor, 'Contenedor'), @($usuario, 'DB_USER'))) {
    Comprobar-Nombre $par[0] $par[1]
}
if ($BaseNueva -in @('gastito', 'gastito_prueba', 'postgres', 'template0', 'template1')) {
    throw "No restauro sobre la base ${BaseNueva}: elegí un nombre nuevo con -BaseNueva"
}

# El archivo: el indicado (carpeta de backup o .dump) o el backup más reciente de la carpeta de destino
if (-not $Backup) {
    if (-not $Destino) {
        $Destino = if ($variables['BACKUP_DIR']) { $variables['BACKUP_DIR'] } else { Join-Path $env:USERPROFILE 'Backups\gastito' }
    }
    $ultimo = Get-ChildItem $Destino -Directory -Filter 'gastito-*' -ErrorAction SilentlyContinue | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $ultimo) { throw "No hay backups en $Destino" }
    $Backup = $ultimo.FullName
}
$dump = if (Test-Path $Backup -PathType Container) {
    Get-ChildItem $Backup -Filter '*.dump' | Select-Object -First 1 -ExpandProperty FullName
} else { $Backup }
if (-not $dump -or -not (Test-Path $dump)) { throw "No encontré el .dump en $Backup" }
Write-Host "Backup: $dump" -ForegroundColor Cyan

$psql = "docker exec -i $Contenedor psql -X -q -tA -v ON_ERROR_STOP=1 -U $usuario -d postgres"
$existe = Invocar-Vm $Ssh $psql "select 1 from pg_database where datname = '$BaseNueva';"
if ($existe) {
    if (-not $Reemplazar) { throw "La base $BaseNueva ya existe. Usá -Reemplazar para borrarla y restaurar de nuevo." }
    Write-Host "-> Borrando la base $BaseNueva anterior" -ForegroundColor Cyan
    Invocar-Vm $Ssh "docker exec $Contenedor dropdb -U $usuario $BaseNueva" | Out-Null
}

Write-Host "-> Creando la base $BaseNueva y restaurando" -ForegroundColor Cyan
Invocar-Vm $Ssh "docker exec $Contenedor createdb -U $usuario -O $usuario $BaseNueva" | Out-Null
Invocar-VmBinario $Ssh "docker exec -i $Contenedor pg_restore -U $usuario -d $BaseNueva --no-owner --exit-on-error" -entradaDe $dump

# Comparar: tablas y filas de la restaurada contra la base de origen tal como está ahora
$restaurada = Contar-Filas $Ssh $Contenedor $usuario $BaseNueva
$origen = Contar-Filas $Ssh $Contenedor $usuario $Comparar
$distintas = 0
$tabla = foreach ($nombre in @($origen.Keys + $restaurada.Keys | Sort-Object -Unique)) {
    $a = if ($restaurada.Contains($nombre)) { $restaurada[$nombre] } else { '-' }
    $b = if ($origen.Contains($nombre)) { $origen[$nombre] } else { '-' }
    if ("$a" -ne "$b") { $distintas++ }
    [pscustomobject]@{ Tabla = $nombre; Restaurada = $a; $Comparar = $b; Igual = if ("$a" -eq "$b") { 'si' } else { 'NO' } }
}
$tabla | Format-Table -AutoSize | Out-String | Write-Host

if ($Borrar) {
    Invocar-Vm $Ssh "docker exec $Contenedor dropdb -U $usuario $BaseNueva" | Out-Null
    Write-Host "-> Borré la base $BaseNueva" -ForegroundColor Cyan
}
if ($restaurada.Count -eq 0) { Write-Host "X  La base restaurada no tiene tablas" -ForegroundColor Red; exit 1 }
if ($distintas -eq 0) {
    Write-Host "Restauración OK: $($restaurada.Count) tablas, las mismas filas que $Comparar." -ForegroundColor Green
} else {
    Write-Host "Restauración OK, pero $distintas tablas difieren de $Comparar (normal si el backup no es de recién)." -ForegroundColor Yellow
}
