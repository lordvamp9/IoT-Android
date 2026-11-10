$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot
$localDeps = Join-Path (Split-Path $PSScriptRoot) '.python-deps'
if (Test-Path -LiteralPath $localDeps) { $env:PYTHONPATH = $localDeps }
if (-not (Test-Path -LiteralPath '.env')) { throw 'Falta gateway_raspberrypi/.env' }
Get-Content -LiteralPath '.env' | ForEach-Object {
    if ($_ -match '^\s*([^#=\s]+)=(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2], 'Process')
    }
}
if (-not (Test-Path -LiteralPath $env:GOOGLE_APPLICATION_CREDENTIALS)) {
    throw 'Falta gateway/service-account.json: descarga la clave de cuenta de servicio de Firebase.'
}
$pythonProyecto = Join-Path (Split-Path $PSScriptRoot) '.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonProyecto)) { throw 'Falta el entorno Python del proyecto (.venv).' }
& $pythonProyecto -u aws_firestore_bridge.py
