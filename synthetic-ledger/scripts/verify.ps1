param([switch]$Integration)
$ErrorActionPreference = 'Stop'
$taskProject = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $taskProject
try {
    if ($Integration) { & .\mvnw.cmd -B -ntp -Pintegration clean verify }
    else { & .\mvnw.cmd -B -ntp clean verify }
    if ($LASTEXITCODE -ne 0) { throw "Maven validation failed ($LASTEXITCODE)." }
} finally { Pop-Location }
