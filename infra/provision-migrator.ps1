#requires -Version 7.0
. (Join-Path $PSScriptRoot 'migration-common.ps1')
$target = Get-MigrationTarget
$values = @{
    FLOWDESK_MIGRATOR_DB_URL = "jdbc:postgresql://127.0.0.1:$($target.POSTGRES_PORT)/$($target.POSTGRES_DB)"
    FLOWDESK_MIGRATOR_DB_USERNAME = 'flowdesk_migrator'
    FLOWDESK_MIGRATION_ADMIN_USER = $target.POSTGRES_USER
    FLOWDESK_MIGRATION_ADMIN_PASSWORD = $target.POSTGRES_PASSWORD
}
$inspection = Invoke-MigrationProcess 'Inspect' $values
$path = Join-Path $PSScriptRoot '.env.migrator'
if (Test-Path -LiteralPath $path) {
    $credentials = Get-MigratorValues $target
} else {
    if ($inspection -notmatch 'Migrator role: absent') { throw 'Migrator role already exists but its credential file is missing; no password was changed.' }
    $password = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
    # CreateNew prevents concurrent invocations from overwriting another generated credential file.
    $stream = [IO.FileStream]::new($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $content = "FLOWDESK_MIGRATOR_DB_URL=$($values.FLOWDESK_MIGRATOR_DB_URL)`nFLOWDESK_MIGRATOR_DB_USERNAME=flowdesk_migrator`nFLOWDESK_MIGRATOR_DB_PASSWORD=$password`n"
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($content)
        $stream.Write($bytes)
    } finally { $stream.Dispose() }
    $credentials = Get-MigratorValues $target
}
foreach ($key in $credentials.Keys) { $values[$key] = $credentials[$key] }
$values.FLOWDESK_MIGRATION_ALLOW_CREATE = 'true'
Invoke-MigrationProcess 'Provision' $values
