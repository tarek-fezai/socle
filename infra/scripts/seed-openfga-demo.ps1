# OpenFGA tuples for demo-seed.sql (dev)
# Makes seeded spaces/docs visible to contributeur / admin / analyste.
#
# Usage (PowerShell):
#   .\infra\scripts\seed-openfga-demo.ps1

$ErrorActionPreference = "Stop"
$OpenFgaApiUrl = if ($env:OPENFGA_API_URL) { $env:OPENFGA_API_URL } else { "http://localhost:8082" }
$EnvFile = Join-Path $PSScriptRoot "..\..\.env"

$storeId = $env:OPENFGA_STORE_ID
$modelId = $env:OPENFGA_MODEL_ID
if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match '^\s*OPENFGA_STORE_ID=(.+)$') { $storeId = $Matches[1].Trim() }
        if ($_ -match '^\s*OPENFGA_MODEL_ID=(.+)$') { $modelId = $Matches[1].Trim() }
    }
}
if (-not $storeId -or -not $modelId) {
    throw "OPENFGA_STORE_ID / OPENFGA_MODEL_ID manquants. Lancer bootstrap-openfga.ps1 d abord."
}

$U_CONTRIB = "user:11111111-1111-1111-1111-111111111111"
$U_ANALYST = "user:22222222-2222-2222-2222-222222222222"
$U_ADMIN   = "user:33333333-3333-3333-3333-333333333333"

$SPACE_DEF  = "space:00000000-0000-0000-0000-000000000001"
$SPACE_IAM  = "space:00000000-0000-0000-0000-000000000002"
$SPACE_INFRA = "space:00000000-0000-0000-0000-000000000003"
$SPACE_CONF = "space:00000000-0000-0000-0000-000000000004"

$writes = @(
    @{ user = $U_CONTRIB; relation = "owner"; object = $SPACE_DEF },
    @{ user = $U_CONTRIB; relation = "owner"; object = $SPACE_IAM },
    @{ user = $U_ANALYST; relation = "editor"; object = $SPACE_IAM },
    @{ user = $U_ADMIN;   relation = "owner"; object = $SPACE_INFRA },
    @{ user = $U_ADMIN;   relation = "owner"; object = $SPACE_CONF },
    @{ user = $U_ANALYST; relation = "editor"; object = $SPACE_CONF },
    # Documents : créateur = editor + direct_access ; parent + inherit_from (visibility space)
    @{ user = $U_CONTRIB; relation = "editor"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd1" },
    @{ user = $U_CONTRIB; relation = "direct_access"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd1" },
    @{ user = $SPACE_IAM; relation = "parent"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd1" },
    @{ user = $SPACE_IAM; relation = "inherit_from"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd1" },
    @{ user = $U_ANALYST; relation = "editor"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd2" },
    @{ user = $U_ANALYST; relation = "direct_access"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd2" },
    @{ user = $SPACE_IAM; relation = "parent"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd2" },
    @{ user = $SPACE_IAM; relation = "inherit_from"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd2" },
    @{ user = $U_ADMIN;   relation = "editor"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd3" },
    @{ user = $U_ADMIN;   relation = "direct_access"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd3" },
    @{ user = $SPACE_INFRA; relation = "parent"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd3" },
    @{ user = $SPACE_INFRA; relation = "inherit_from"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd3" },
    @{ user = $U_ANALYST; relation = "editor"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd4" },
    @{ user = $U_ANALYST; relation = "direct_access"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd4" },
    @{ user = $SPACE_CONF; relation = "parent"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd4" },
    @{ user = $SPACE_CONF; relation = "inherit_from"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd4" },
    @{ user = $U_CONTRIB; relation = "editor"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd5" },
    @{ user = $U_CONTRIB; relation = "direct_access"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd5" },
    @{ user = $SPACE_IAM; relation = "parent"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd5" },
    @{ user = $SPACE_IAM; relation = "inherit_from"; object = "document:dddddddd-dddd-dddd-dddd-ddddddddddd5" },
    @{ user = $SPACE_IAM; relation = "parent"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc1" },
    @{ user = $SPACE_IAM; relation = "inherit_from"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc1" },
    @{ user = $U_CONTRIB; relation = "owner"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc1" },
    @{ user = $SPACE_INFRA; relation = "parent"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc2" },
    @{ user = $SPACE_INFRA; relation = "inherit_from"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc2" },
    @{ user = $U_ADMIN; relation = "owner"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc2" },
    @{ user = $SPACE_CONF; relation = "parent"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc3" },
    @{ user = $SPACE_CONF; relation = "inherit_from"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc3" },
    @{ user = $U_ADMIN; relation = "owner"; object = "folder:cccccccc-cccc-cccc-cccc-ccccccccccc3" }
)

$body = @{
    authorization_model_id = $modelId
    writes = @{
        tuple_keys = $writes
    }
} | ConvertTo-Json -Depth 6

Write-Host "Writing $($writes.Count) OpenFGA tuples to store $storeId ..."
Invoke-RestMethod -Method Post -Uri "$OpenFgaApiUrl/stores/$storeId/write" `
    -ContentType "application/json" `
    -Body $body | Out-Null
Write-Host "OK"
