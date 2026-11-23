#requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('Inspect','Provision','Info','Validate','Migrate')][string]$Action)
$ErrorActionPreference = 'Stop'
& ./gradlew.bat runMigration "-PmigrationAction=$Action" --no-daemon --no-configuration-cache --console=plain
exit $LASTEXITCODE
