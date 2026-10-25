# Vuelve a generar desde cero los datos de la base de prueba (gastito_prueba). Se usa desde generar-datos-prueba.bat.
# Corre la aplicación con el perfil prueba, sin Telegram ni servidor web, y se apaga sola al terminar.
param([switch]$SinPreguntar)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz
$Host.UI.RawUI.WindowTitle = 'Gastito: datos de prueba'

function Falla($texto) {
    Write-Host ""
    Write-Host "X  $texto" -ForegroundColor Red
    Write-Host ""
    if (-not $SinPreguntar) { Read-Host "Apretá Enter para cerrar" }
    exit 1
}

function JarCompilado {
    Get-ChildItem target -Filter 'gastito-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'original|sources|javadoc' } | Select-Object -First 1
}

Write-Host "Esto BORRA los movimientos, ingresos, tickets y resúmenes de la base de prueba (gastito_prueba)" -ForegroundColor Yellow
Write-Host "y la llena con unos 3 meses de datos inventados. La base real (gastito) no se toca." -ForegroundColor Yellow
if (-not $SinPreguntar) {
    $respuesta = Read-Host "¿Seguir? (S/N)"
    if ($respuesta -notmatch '^\s*[sS]') { exit 0 }
}

# Compilar solo si no hay jar o si el código es más nuevo que el jar
$jar = JarCompilado
$fuentes = @(Get-Item pom.xml) + @(Get-ChildItem src\main -Recurse -File)
$ultimoCambio = ($fuentes | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
if (-not $jar -or $jar.LastWriteTime -lt $ultimoCambio) {
    Write-Host "-> Compilando" -ForegroundColor Cyan
    & .\mvnw.cmd -q -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { Falla "La compilación falló. El error está arriba." }
    $jar = JarCompilado
}

Write-Host "-> Generando (tarda menos de un minuto)" -ForegroundColor Cyan
& java -jar $jar.FullName --spring.profiles.active=prueba --gastito.prueba.generar=true `
    --telegrambots.enabled=false --spring.main.web-application-type=none
if ($LASTEXITCODE -ne 0) { Falla "No se pudieron generar los datos. El error está arriba." }

Write-Host ""
Write-Host "Listo. Arrancá Gastito con iniciar.bat (usa la base de prueba)." -ForegroundColor Green
if (-not $SinPreguntar) { Read-Host "Apretá Enter para cerrar" }
