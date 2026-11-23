$ErrorActionPreference = 'Stop'
$envPath = Join-Path $PSScriptRoot '.env'

if (Test-Path -LiteralPath $envPath) {
    Write-Output 'Local .env already exists; no values were changed.'
    return
}

$passwordBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(32)
$password = [Convert]::ToHexString($passwordBytes).ToLowerInvariant()
$content = @(
    'POSTGRES_DB=flowdesk'
    'POSTGRES_USER=postgres'
    "POSTGRES_PASSWORD=$password"
    'POSTGRES_PORT=5432'
)
Set-Content -LiteralPath $envPath -Value $content -Encoding utf8
Write-Output 'Created local .env with a random password. The password was not printed.'
