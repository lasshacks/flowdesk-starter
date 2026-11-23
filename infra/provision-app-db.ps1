$ErrorActionPreference = 'Stop'

function Read-LocalEnv([string]$Path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line -match '^([A-Z_]+)=(.*)$') {
            $values[$matches[1]] = $matches[2].Trim()
        }
    }
    return $values
}

$infraEnvPath = Join-Path $PSScriptRoot '.env'
if (-not (Test-Path -LiteralPath $infraEnvPath)) {
    throw 'Run init-local-env.ps1 and start PostgreSQL first.'
}
$infraEnv = Read-LocalEnv $infraEnvPath
$dbName = $infraEnv['POSTGRES_DB']
$adminUser = $infraEnv['POSTGRES_USER']
$port = $infraEnv['POSTGRES_PORT']
if ($dbName -notmatch '^[a-z_][a-z0-9_]*$' -or $adminUser -notmatch '^[a-z_][a-z0-9_]*$' -or
    $port -notmatch '^\d+$' -or [int]$port -lt 1 -or [int]$port -gt 65535) {
    throw 'Expected simple local database/user names and a valid POSTGRES_PORT.'
}

$appEnvPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../backend/.env'))
if (Test-Path -LiteralPath $appEnvPath) {
    $appEnv = Read-LocalEnv $appEnvPath
    if ($appEnv['FLOWDESK_DB_USERNAME'] -ne 'flowdesk_app' -or
        $appEnv['FLOWDESK_DB_URL'] -ne "jdbc:postgresql://127.0.0.1:$port/$dbName" -or
        $appEnv['FLOWDESK_DB_PASSWORD'] -notmatch '^[a-f0-9]{64}$') {
        throw 'Existing backend/.env differs from the generated local configuration; no changes made.'
    }
    $appPassword = $appEnv['FLOWDESK_DB_PASSWORD']
} else {
    $appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    Set-Content -LiteralPath $appEnvPath -Encoding utf8 -Value @(
        "FLOWDESK_DB_URL=jdbc:postgresql://127.0.0.1:$port/$dbName"
        'FLOWDESK_DB_USERNAME=flowdesk_app'
        "FLOWDESK_DB_PASSWORD=$appPassword"
    )
}

# psql reads the password assignment from stdin, not process arguments or a SQL file.
$policy = Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'app-role.sql')
$sql = "\set app_password '$appPassword'`n" +
    "SELECT set_config('flowdesk.app_password', :'app_password', false) AS ignored \gset`n" + $policy
$sql | docker compose --file (Join-Path $PSScriptRoot 'compose.yaml') --env-file $infraEnvPath `
    exec -T postgres psql -U $adminUser -d $dbName -v ON_ERROR_STOP=1
if ($LASTEXITCODE -ne 0) {
    throw 'Application role provisioning failed. Credentials were kept for a retry; no password was printed.'
}
Write-Output 'Provisioned flowdesk_app with CONNECT only; backend/.env contains its local credentials.'
