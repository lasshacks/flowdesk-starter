#requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('Apply')][string]$Action)
$ErrorActionPreference = 'Stop'
& ./gradlew.bat runAppTaskStatusChangeAccess --no-daemon --no-configuration-cache --console=plain
exit $LASTEXITCODE
