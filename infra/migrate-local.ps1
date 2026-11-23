#requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('Info','Validate','Migrate')][string]$Action)
. (Join-Path $PSScriptRoot 'migration-common.ps1')
$target = Get-MigrationTarget
$values = Get-MigratorValues $target
Invoke-MigrationProcess $Action $values
