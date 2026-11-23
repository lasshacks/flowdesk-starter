#requires -Version 7.0
$ErrorActionPreference = 'Stop'
& ./gradlew.bat runAppWorkspaceMembershipManagementAccess --no-daemon --no-configuration-cache --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Workspace membership-management access task failed.' }
