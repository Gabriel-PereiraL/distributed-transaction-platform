param([Parameter(Mandatory)][string]$Terraform)
$ErrorActionPreference = 'Stop'
$staticDirectory = Join-Path $PSScriptRoot 'environments/static'
foreach ($testFile in Get-ChildItem -LiteralPath (Join-Path $staticDirectory 'tests') -Filter '*.tftest.hcl') {
    $content = Get-Content -LiteralPath $testFile.FullName -Raw
    if ($content -notmatch 'mock_provider\s+"aws"' -or $content -match '(?m)^\s*provider\s+"' -or $content -match 'command\s*=\s*apply') {
        throw 'Only explicitly mocked, plan-only Terraform tests are permitted'
    }
}
& $Terraform "-chdir=$PSScriptRoot" fmt -check -recursive
if ($LASTEXITCODE -ne 0) { throw 'Terraform formatting gate failed' }
& $Terraform "-chdir=$staticDirectory" init -backend=false -input=false
if ($LASTEXITCODE -ne 0) { throw 'Static provider initialization failed' }
& $Terraform "-chdir=$staticDirectory" validate
if ($LASTEXITCODE -ne 0) { throw 'Static configuration validation failed' }
& $Terraform "-chdir=$staticDirectory" test
if ($LASTEXITCODE -ne 0) { throw 'Mocked Terraform tests failed' }
Write-Output 'Static Terraform gates passed; no account plan or deployment was executed.'
