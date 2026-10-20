# Arranca Gastito: revisa que esté todo listo, compila si cambió el código y deja el bot corriendo en esta ventana.
# Se usa desde iniciar.bat (doble clic). Perfil por defecto: postgres. "iniciar.bat dev" arranca con H2.
param([ValidateSet('postgres', 'dev')][string]$Perfil = 'postgres')

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz
$Host.UI.RawUI.WindowTitle = "Gastito ($Perfil)"

function Paso($texto) { Write-Host "-> $texto" -ForegroundColor Cyan }
function Ok($texto) { Write-Host "   $texto" -ForegroundColor Green }
function Falla($texto) {
    Write-Host ""
    Write-Host "X  $texto" -ForegroundColor Red
    Write-Host ""
    Read-Host "Apretá Enter para cerrar"
    exit 1
}

function PuertoAbierto($servidor, $puerto) {
    $cliente = New-Object System.Net.Sockets.TcpClient
    try { return $cliente.ConnectAsync($servidor, $puerto).Wait(1000) } catch { return $false } finally { $cliente.Dispose() }
}

function JarCompilado {
    Get-ChildItem target -Filter 'gastito-*.jar' -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch 'original|sources|javadoc' } | Select-Object -First 1
}

# 1. Java (Maven no hace falta: se usa el wrapper del proyecto, mvnw.cmd)
Paso "Revisando Java"
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Falla "No encontré 'java' en el PATH." }
Ok "Instalado"

# 2. El .env, sin mostrar los valores
Paso "Revisando el .env"
if (-not (Test-Path .env)) { Falla "No encontré el archivo .env en $raiz. Copiá .env.example y completalo." }
$variables = @{}
foreach ($linea in Get-Content .env -Encoding UTF8) {
    if ($linea -match '^\s*([A-Z_]+)\s*=\s*(.*?)\s*$') { $variables[$Matches[1]] = $Matches[2] }
}
$necesarias = @('TELEGRAM_BOT_TOKEN', 'TELEGRAM_BOT_USERNAME', 'TELEGRAM_ALLOWED_USER_ID', 'ANTHROPIC_API_KEY')
if ($Perfil -eq 'postgres') { $necesarias += 'DB_URL', 'DB_USER', 'DB_PASSWORD' }
$faltan = @($necesarias | Where-Object { -not $variables[$_] })
if ($faltan.Count -gt 0) { Falla "En el .env faltan valores para: $($faltan -join ', ')" }
Ok "Completo"

# 3. Dos Gastitos a la vez se pisan: Telegram le entrega cada mensaje a uno solo.
Paso "Revisando que no haya otro Gastito prendido"
$otros = @(Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -match 'GastitoApplication|gastito-[\d.]+.*\.jar' })
if ($otros.Count -gt 0) {
    Write-Host "   Ya hay un Gastito corriendo (PID $($otros.ProcessId -join ', '))." -ForegroundColor Yellow
    $respuesta = Read-Host "   ¿Lo apago y arranco este? (S/N)"
    if ($respuesta -notmatch '^\s*[sS]') { exit 0 }
    $otros | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
    Start-Sleep -Seconds 2
    Ok "Apagado"
} else {
    Ok "Ninguno"
}

# 4. PostgreSQL (la VM de Ubuntu puede estar todavía arrancando)
if ($Perfil -eq 'postgres') {
    if ($variables['DB_URL'] -notmatch '^jdbc:postgresql://([^:/]+)(?::(\d+))?/') {
        Falla "DB_URL tiene que tener la forma jdbc:postgresql://servidor:puerto/base"
    }
    $servidor = $Matches[1]
    $puerto = if ($Matches[2]) { [int]$Matches[2] } else { 5432 }
    Paso "Buscando PostgreSQL en ${servidor}:$puerto"
    $limite = (Get-Date).AddMinutes(3)
    $avisado = $false
    while (-not (PuertoAbierto $servidor $puerto)) {
        if (-not $avisado) {
            Write-Host "   No responde. ¿Está prendida la VM de Ubuntu con Docker? Espero hasta 3 minutos..." -ForegroundColor Yellow
            $avisado = $true
        }
        if ((Get-Date) -gt $limite) { Falla "PostgreSQL no respondió en ${servidor}:$puerto. Prendé la VM y volvé a abrir iniciar.bat." }
        Start-Sleep -Seconds 3
    }
    Ok "Responde"
}

# 5. Whisper, para los audios. Es opcional: si no responde, el bot arranca igual y funciona con texto.
Paso "Buscando el servidor de Whisper (audios)"
$whisper = if ($variables['WHISPER_URL']) { $variables['WHISPER_URL'] } else { 'http://localhost:9000' }
if ($whisper -match '^https?://([^:/]+)(?::(\d+))?') {
    $puertoWhisper = if ($Matches[2]) { [int]$Matches[2] } elseif ($whisper -like 'https*') { 443 } else { 80 }
    if (PuertoAbierto $Matches[1] $puertoWhisper) {
        Ok "Responde"
    } else {
        Write-Host "   No responde: los audios no se van a poder transcribir hasta que prendas el contenedor de Whisper." -ForegroundColor Yellow
    }
} else {
    Write-Host "   WHISPER_URL no parece una URL (http://servidor:puerto): los audios no van a funcionar." -ForegroundColor Yellow
}
if (-not $variables['WHISPER_API_KEY']) {
    Write-Host "   Falta WHISPER_API_KEY en el .env: el servidor de Whisper va a rechazar los audios." -ForegroundColor Yellow
}

# 6. Compilar solo si no hay jar o si el código es más nuevo que el jar
$jar = JarCompilado
$fuentes = @(Get-Item pom.xml) + @(Get-ChildItem src\main -Recurse -File)
$ultimoCambio = ($fuentes | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
if (-not $jar -or $jar.LastWriteTime -lt $ultimoCambio) {
    Paso "Compilando (pasa la primera vez y cuando cambia el código; tarda alrededor de un minuto)"
    & .\mvnw.cmd -q -B -DskipTests package
    if ($LASTEXITCODE -ne 0) { Falla "La compilación falló. El error está arriba." }
    $jar = JarCompilado
    Ok "Listo"
} else {
    Paso "El código no cambió desde la última compilación: uso el jar que ya está"
}

# 7. Arrancar
Write-Host ""
Write-Host "Arrancando Gastito con el perfil $Perfil." -ForegroundColor Green
Write-Host "Cuando aparezca 'Bot de Telegram conectado y escuchando mensajes', ya lo podés usar."
Write-Host "Para apagarlo, cerrá esta ventana."
Write-Host ""
& java -jar $jar.FullName "--spring.profiles.active=$Perfil"
Write-Host ""
Read-Host "Gastito se detuvo. Apretá Enter para cerrar"
