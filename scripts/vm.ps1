# Funciones comunes de backup.ps1 y restaurar.ps1: hablar con el PostgreSQL de la VM por SSH (docker exec gastito-db).
# Se cargan con ". $PSScriptRoot\vm.ps1". Los comandos remotos se arman solo con nombres validados (sin espacios ni comillas).

# -4: "localhost" en Windows resuelve primero a ::1, y el reenvío de puertos de VirtualBox escucha solo en IPv4.
$script:OpcionesSsh = @('-4', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15')

function Leer-Env($raiz) {
    $variables = @{}
    $archivo = Join-Path $raiz '.env'
    if (Test-Path $archivo) {
        foreach ($linea in Get-Content $archivo -Encoding UTF8) {
            if ($linea -match '^\s*([A-Z_]+)\s*=\s*(.*?)\s*$') { $variables[$Matches[1]] = $Matches[2] }
        }
    }
    return $variables
}

function Comprobar-Nombre($nombre, $que) {
    if ($nombre -notmatch '^[A-Za-z0-9_.-]+$') { throw "$que no válido: '$nombre'" }
}

# Corre un comando en la VM. Si se pasa -Entrada (texto), va por stdin. Devuelve las líneas de la salida.
function Invocar-Vm($ssh, $comando, $entrada = $null) {
    if ($null -ne $entrada) {
        $salida = $entrada | & ssh @script:OpcionesSsh $ssh $comando
    } else {
        $salida = & ssh @script:OpcionesSsh $ssh $comando
    }
    if ($LASTEXITCODE -ne 0) { throw "Falló en la VM ($LASTEXITCODE): $comando" }
    return $salida
}

# Igual que Invocar-Vm, pero copiando bytes sin tocar: la salida a un archivo o un archivo como entrada.
# (En Windows PowerShell 5.1, ">" y "<" con programas externos pasan todo como texto y rompen un dump binario.)
function Invocar-VmBinario($ssh, $comando, [string]$salidaA = $null, [string]$entradaDe = $null) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = 'ssh'
    $argumentos = @($script:OpcionesSsh) + @($ssh, "`"$comando`"")
    $info.Arguments = $argumentos -join ' '
    $info.UseShellExecute = $false
    $info.RedirectStandardOutput = [bool]$salidaA
    $info.RedirectStandardInput = [bool]$entradaDe
    # El stdin redirigido usa la codificación de entrada de la consola: si es UTF-8 con BOM, antepone 3 bytes al dump.
    $codificacionAnterior = [Console]::InputEncoding
    [Console]::InputEncoding = New-Object System.Text.UTF8Encoding $false
    try { $proceso = [System.Diagnostics.Process]::Start($info) } finally { [Console]::InputEncoding = $codificacionAnterior }
    try {
        if ($entradaDe) {
            $origen = [System.IO.File]::OpenRead($entradaDe)
            try { $origen.CopyTo($proceso.StandardInput.BaseStream) } finally { $origen.Dispose() }
            $proceso.StandardInput.Close()
        }
        if ($salidaA) {
            $destino = [System.IO.File]::Create($salidaA)
            try { $proceso.StandardOutput.BaseStream.CopyTo($destino) } finally { $destino.Dispose() }
        }
        $proceso.WaitForExit()
        if ($proceso.ExitCode -ne 0) { throw "Falló en la VM ($($proceso.ExitCode)): $comando" }
    } finally {
        $proceso.Dispose()
    }
}

# Cantidad de filas de cada tabla de una base, como tabla -> número.
function Contar-Filas($ssh, $contenedor, $usuario, $base) {
    $sql = @"
select table_name || '|' || (xpath('/row/c/text()', query_to_xml(format('select count(*) as c from %I', table_name), false, true, '')))[1]::text
from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1;
"@
    $filas = [ordered]@{}
    foreach ($linea in Invocar-Vm $ssh "docker exec -i $contenedor psql -X -q -tA -U $usuario -d $base" $sql) {
        if ($linea -match '^(.+)\|(\d+)$') { $filas[$Matches[1]] = [long]$Matches[2] }
    }
    return $filas
}
