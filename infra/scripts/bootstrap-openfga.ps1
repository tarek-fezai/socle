$ErrorActionPreference = "Stop"

$OpenFgaApiUrl = if ($env:OPENFGA_API_URL) { $env:OPENFGA_API_URL } else { "http://localhost:8082" }
$ModelFile = Join-Path $PSScriptRoot "..\openfga\model.json"
$EnvFile = Join-Path $PSScriptRoot "..\..\.env"

Write-Host "OpenFGA API: $OpenFgaApiUrl"

$storeId = $env:OPENFGA_STORE_ID
if (-not $storeId -and (Test-Path $EnvFile)) {
    $line = Get-Content $EnvFile | Where-Object { $_ -match '^\s*OPENFGA_STORE_ID=(.+)$' } | Select-Object -First 1
    if ($line -match '^\s*OPENFGA_STORE_ID=(.+)$') { $storeId = $Matches[1].Trim() }
}

if (-not $storeId) {
    Write-Host "Creating OpenFGA store..."
    $storeResp = Invoke-RestMethod -Method Post -Uri "$OpenFgaApiUrl/stores" `
        -ContentType "application/json" `
        -Body '{"name":"socle"}'
    $storeId = $storeResp.id
    Write-Host "Store ID: $storeId"
} else {
    Write-Host "Reusing store: $storeId"
}

Write-Host "Writing authorization model..."
$modelJson = Get-Content -Raw -Path $ModelFile
$modelResp = Invoke-RestMethod -Method Post -Uri "$OpenFgaApiUrl/stores/$storeId/authorization-models" `
    -ContentType "application/json" `
    -Body $modelJson
$modelId = $modelResp.authorization_model_id
Write-Host "Model ID: $modelId"
Write-Host ""
Write-Host "Export these into .env:"
Write-Host "OPENFGA_STORE_ID=$storeId"
Write-Host "OPENFGA_MODEL_ID=$modelId"

if (Test-Path $EnvFile) {
    $content = Get-Content $EnvFile -Raw
    if ($content -match '(?m)^OPENFGA_STORE_ID=.*$') {
        $content = $content -replace '(?m)^OPENFGA_STORE_ID=.*$', "OPENFGA_STORE_ID=$storeId"
    } else {
        $content = $content.TrimEnd() + "`r`nOPENFGA_STORE_ID=$storeId`r`n"
    }
    if ($content -match '(?m)^OPENFGA_MODEL_ID=.*$') {
        $content = $content -replace '(?m)^OPENFGA_MODEL_ID=.*$', "OPENFGA_MODEL_ID=$modelId"
    } else {
        $content = $content.TrimEnd() + "`r`nOPENFGA_MODEL_ID=$modelId`r`n"
    }
    Set-Content -Path $EnvFile -Value $content -NoNewline
    Write-Host "Updated $EnvFile"
}
