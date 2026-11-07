# Arranca Gastito: revisa que esté todo listo, compila si cambió el código y deja el bot corriendo en esta ventana.
# Se usa desde iniciar.bat (doble clic). Perfil por defecto: postgres (la base real, gastito).
# "iniciar.bat prueba" arranca con la base de prueba (gastito_prueba), solo para probar cambios, y "iniciar.bat dev" con H2.
# -Automatico es para el Programador de tareas: no pregunta nada y espera hasta 10 minutos a que la base responda.
# -Reiniciar (reiniciar.bat): cierra el Gastito que está corriendo sin preguntar, recompila si hace falta y lo vuelve a abrir
# con el mismo perfil que tenía (salvo que se pase otro).
param(
    [ValidateSet('prueba', 'postgres', 'dev')][string]$Perfil = 'postgres',
    [switch]$Automatico,
    [switch]$Reiniciar
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raiz = Split-Path -Parent $PSScriptRoot
Set-Location $raiz

function Paso($texto) { Write-Host "-> $texto" -ForegroundColor Cyan }
function Ok($texto) { Write-Host "   $texto" -ForegroundColor Green }
function Falla($texto) {
    Write-Host ""
    Write-Host "X  $texto" -ForegroundColor Red
    Write-Host ""
    if (-not $Automatico) { Read-Host "Apretá Enter para cerrar" }
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

# Un jar de Spring Boot ejecutable dice en el manifiesto cuál es la aplicación (Start-Class). Si un package se cortó
# a la mitad (por ejemplo, con Gastito prendido usando el jar), queda un jar común, más nuevo que el código pero que
# no arranca ("no hay ningún atributo de manifiesto principal").
function JarEjecutable($jar) {
    if (-not $jar) { return $false }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
        try {
            $entrada = $zip.GetEntry('META-INF/MANIFEST.MF')
            if (-not $entrada) { return $false }
            $lector = New-Object System.IO.StreamReader($entrada.Open())
            try { return $lector.ReadToEnd() -match '(?m)^Start-Class:' } finally { $lector.Dispose() }
        } finally { $zip.Dispose() }
    } catch { return $false }
}

# Gastito corriendo: el java de este proyecto (el jar de target\ o GastitoApplication desde el IDE/mvnw).
function GastitosCorriendo {
    $procesos = @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine -match 'GastitoApplication|gastito-[\d.]+[^\\/]*\.jar' })
    $propios = @($procesos | Where-Object { $_.CommandLine -match 'GastitoApplication' -or $_.CommandLine.Contains($raiz) })
    if ($propios.Count -gt 0) { return $propios }
    return $procesos
}
# Las ventanas de iniciar.ps1 que lo abrieron (menos esta), para que no queden abiertas esperando un Enter.
function VentanasDeIniciar {
    @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe' OR Name='pwsh.exe'" |
        Where-Object { $_.ProcessId -ne $PID -and $_.CommandLine -match 'iniciar\.ps1' })
}
function Apagar($procesos) {
    VentanasDeIniciar | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $procesos | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    $limite = (Get-Date).AddSeconds(15)
    while (@(GastitosCorriendo).Count -gt 0 -and (Get-Date) -lt $limite) { Start-Sleep -Milliseconds 500 }
    Start-Sleep -Seconds 1  # que Windows suelte el jar antes de recompilarlo
}

# Al reiniciar sin decir el perfil, el mismo que tenía el que está corriendo.
if ($Reiniciar -and -not $PSBoundParameters.ContainsKey('Perfil')) {
    $anterior = @(GastitosCorriendo) | Select-Object -First 1
    if ($anterior -and $anterior.CommandLine -match '--spring\.profiles\.active=(prueba|postgres|dev)\b') { $Perfil = $Matches[1] }
}
$Host.UI.RawUI.WindowTitle = "Gastito ($Perfil)"

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
if ($Perfil -ne 'dev') { $necesarias += 'DB_URL', 'DB_USER', 'DB_PASSWORD' }
$faltan = @($necesarias | Where-Object { -not $variables[$_] })
if ($faltan.Count -gt 0) { Falla "En el .env faltan valores para: $($faltan -join ', ')" }
Ok "Completo"

# 3. Dos Gastitos a la vez se pisan: Telegram le entrega cada mensaje a uno solo.
Paso "Revisando que no haya otro Gastito prendido"
$otros = @(GastitosCorriendo)
if ($otros.Count -gt 0) {
    Write-Host "   Ya hay un Gastito corriendo (PID $($otros.ProcessId -join ', '))." -ForegroundColor Yellow
    if (-not $Reiniciar) {
        if ($Automatico) { exit 0 }
        $respuesta = Read-Host "   ¿Lo apago y arranco este? (S/N)"
        if ($respuesta -notmatch '^\s*[sS]') { exit 0 }
    }
    Apagar $otros
    if (@(GastitosCorriendo).Count -gt 0) { Falla "No pude cerrar el Gastito que estaba corriendo." }
    Ok "Apagado"
} else {
    Ok "Ninguno"
}

# 4. PostgreSQL (la VM de Ubuntu puede estar todavía arrancando). Las dos bases están en el mismo servidor.
if ($Perfil -ne 'dev') {
    if ($variables['DB_URL'] -notmatch '^jdbc:postgresql://([^:/]+)(?::(\d+))?/') {
        Falla "DB_URL tiene que tener la forma jdbc:postgresql://servidor:puerto/base"
    }
    $servidor = $Matches[1]
    $puerto = if ($Matches[2]) { [int]$Matches[2] } else { 5432 }
    Paso "Buscando PostgreSQL en ${servidor}:$puerto"
    $minutos = if ($Automatico) { 10 } else { 3 }
    $limite = (Get-Date).AddMinutes($minutos)
    $avisado = $false
    while (-not (PuertoAbierto $servidor $puerto)) {
        if (-not $avisado) {
            Write-Host "   No responde. ¿Está prendida la VM de Ubuntu con Docker? Espero hasta $minutos minutos..." -ForegroundColor Yellow
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

# 6. Compilar solo si no hay jar o si el código (src\ o pom.xml) es más nuevo que el jar
$jar = JarCompilado
$fuentes = @(Get-Item pom.xml) + @(Get-ChildItem src -Recurse -File)
$ultimoCambio = ($fuentes | Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
if (-not $jar -or $jar.LastWriteTime -lt $ultimoCambio -or -not (JarEjecutable $jar)) {
    Paso "Actualizando Gastito… (compila el código nuevo; tarda alrededor de un minuto)"
    & .\mvnw.cmd -q -B -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        $viejo = JarCompilado
        if (-not (JarEjecutable $viejo) -or $Automatico) {
            Falla "La compilación falló (el error está arriba). No arranco la versión anterior: corregí el error y volvé a abrir iniciar.bat o reiniciar.bat."
        }
        Write-Host ""
        Write-Host "X  La compilación falló (el error está arriba). El jar que quedó es la versión ANTERIOR, sin los últimos cambios." -ForegroundColor Red
        $respuesta = Read-Host "   ¿Arranco igual con la versión anterior? (S/N)"
        if ($respuesta -notmatch '^\s*[sS]') { exit 1 }
        Write-Host "   Arrancando con la versión ANTERIOR." -ForegroundColor Yellow
        $jar = $viejo
    } else {
        $jar = JarCompilado
        Ok "Listo"
    }
} else {
    Paso "El código no cambió desde la última compilación: uso el jar que ya está"
}

# 7. Arrancar
Write-Host ""
Write-Host "Arrancando Gastito con el perfil $Perfil." -ForegroundColor Green
if ($Perfil -eq 'prueba') {
    Write-Host "Base de PRUEBA (gastito_prueba): lo que cargues acá no va a la base real." -ForegroundColor Yellow
} elseif ($Perfil -eq 'postgres') {
    Write-Host "Base REAL (gastito)." -ForegroundColor Yellow
}
Write-Host "Cuando aparezca 'Bot de Telegram conectado y escuchando mensajes', ya lo podés usar."
Write-Host "Para apagarlo, cerrá esta ventana."
Write-Host ""
& java -jar $jar.FullName "--spring.profiles.active=$Perfil"
Write-Host ""
if (-not $Automatico) { Read-Host "Gastito se detuvo. Apretá Enter para cerrar" }
