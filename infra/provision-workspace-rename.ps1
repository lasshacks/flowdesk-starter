#requires -Version 7.0
. (Join-Path $PSScriptRoot 'migration-common.ps1')
$target = Get-MigrationTarget
$app = Read-MigrationEnv ([IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../backend/.env'))) @('FLOWDESK_DB_URL','FLOWDESK_DB_USERNAME','FLOWDESK_DB_PASSWORD')
$url = "jdbc:postgresql://127.0.0.1:$($target.POSTGRES_PORT)/$($target.POSTGRES_DB)"
if ($app.FLOWDESK_DB_URL -cne $url -or $app.FLOWDESK_DB_USERNAME -cne 'flowdesk_app' -or $app.FLOWDESK_DB_PASSWORD -cnotmatch '^[a-f0-9]{64}$') {
    throw 'App configuration differs from the expected local target; no privileges changed.'
}
$values = @{
    FLOWDESK_MIGRATOR_DB_URL = $url
    FLOWDESK_MIGRATION_ADMIN_USER = $target.POSTGRES_USER
    FLOWDESK_MIGRATION_ADMIN_PASSWORD = $target.POSTGRES_PASSWORD
    FLOWDESK_READ_APP_PASSWORD = $app.FLOWDESK_DB_PASSWORD
}
Invoke-MigrationProcess 'Apply' $values -Kind AppRename
