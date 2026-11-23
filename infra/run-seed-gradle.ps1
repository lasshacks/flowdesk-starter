#requires -Version 7.0
param([Parameter(Mandatory)][ValidateSet('Apply','Verify')][string]$Action)
$ErrorActionPreference = 'Stop'
& ./gradlew.bat runSeed "-PseedAction=$Action" --no-daemon --no-configuration-cache --console=plain
exit $LASTEXITCODE
