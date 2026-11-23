#requires -Version 7.0
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-MigrationEnv([string]$Path, [string[]]$Keys) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw 'Required local configuration file is missing.' }
    $values = @{}
    foreach ($line in [IO.File]::ReadAllLines($Path)) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith('#')) { continue }
        if ($line -notmatch '^([A-Z_]+)=(.+)$') { throw 'Invalid configuration line; values were not printed.' }
        $key, $value = $Matches[1], $Matches[2]
        if ($key -notin $Keys -or $values.ContainsKey($key)) { throw 'Unexpected or duplicate configuration key.' }
        $values[$key] = $value
    }
    foreach ($key in $Keys) {
        if (-not $values.ContainsKey($key)) { throw 'Required configuration key is missing.' }
    }
    return $values
}

function Get-MigrationTarget {
    $settings = Read-MigrationEnv (Join-Path $PSScriptRoot '.env') @('POSTGRES_DB','POSTGRES_USER','POSTGRES_PASSWORD','POSTGRES_PORT')
    if ($settings.POSTGRES_DB -notmatch '^[a-z_][a-z0-9_]*$' -or $settings.POSTGRES_USER -notmatch '^[a-z_][a-z0-9_]*$' -or
        $settings.POSTGRES_PORT -notmatch '^[0-9]{1,5}$' -or [int]$settings.POSTGRES_PORT -notin 1..65535) {
        throw 'Invalid local database target.'
    }
    $composePath = Join-Path $PSScriptRoot 'compose.yaml'
    $nameLine = [IO.File]::ReadAllLines($composePath)[0]
    if ($nameLine -cnotmatch '^name: (flowdesk-local|flowdesk-migration-check-[a-f0-9]{32})$') {
        throw 'Unexpected Compose project name.'
    }
    $projectName = $Matches[1]
    $portOutput = & docker compose --project-name $projectName --file $composePath --env-file (Join-Path $PSScriptRoot '.env') port postgres 5432 2>&1
    if ($LASTEXITCODE -ne 0 -or ($portOutput -join '').Trim() -ne "127.0.0.1:$($settings.POSTGRES_PORT)") {
        throw 'Running Compose PostgreSQL does not match the configured loopback port.'
    }
    return $settings
}

function Get-MigratorValues($Target) {
    $values = Read-MigrationEnv (Join-Path $PSScriptRoot '.env.migrator') @('FLOWDESK_MIGRATOR_DB_URL','FLOWDESK_MIGRATOR_DB_USERNAME','FLOWDESK_MIGRATOR_DB_PASSWORD')
    if ($values.FLOWDESK_MIGRATOR_DB_URL -cne "jdbc:postgresql://127.0.0.1:$($Target.POSTGRES_PORT)/$($Target.POSTGRES_DB)" -or
        $values.FLOWDESK_MIGRATOR_DB_USERNAME -cne 'flowdesk_migrator' -or
        $values.FLOWDESK_MIGRATOR_DB_PASSWORD -cnotmatch '^[a-f0-9]{64}$') { throw 'Migrator credentials do not match the expected local configuration.' }
    return $values
}

function Invoke-MigrationProcess([string]$Action, [hashtable]$Values, [ValidateSet('Migration','Seed','AppRead','AppCreate','AppRename','AppProject','AppProjectUpdate','AppProjectArchive','AppTask','AppTaskUpdate','AppTaskStatusChange','AppTaskStatusHistoryRead','AppTaskAssigneeDueDate','AppWorkspaceMemberListRead','AppWorkspaceMembershipManagement','AppTaskArchive')][string]$Kind = 'Migration') {
    $allowed = switch ($Kind) { 'Seed' { @('Apply','Verify') } 'AppRead' { @('Apply') } 'AppCreate' { @('Apply') } 'AppRename' { @('Apply') } 'AppProject' { @('Apply') } 'AppProjectUpdate' { @('Apply') } 'AppProjectArchive' { @('Apply') } 'AppTask' { @('Apply') } 'AppTaskUpdate' { @('Apply') } 'AppTaskStatusChange' { @('Apply') } 'AppTaskStatusHistoryRead' { @('Apply') } 'AppTaskAssigneeDueDate' { @('Apply') } 'AppWorkspaceMemberListRead' { @('Apply') } 'AppWorkspaceMembershipManagement' { @('Apply') } 'AppTaskArchive' { @('Apply') } default { @('Inspect','Provision','Info','Validate','Migrate') } }
    if ($Action -cnotin $allowed) { throw 'Invalid dedicated database action.' }
    $runner = switch ($Kind) { 'Seed' { 'run-seed-gradle.ps1' } 'AppRead' { 'run-app-read-gradle.ps1' } 'AppCreate' { 'run-app-create-gradle.ps1' } 'AppRename' { 'run-app-rename-gradle.ps1' } 'AppProject' { 'run-app-project-gradle.ps1' } 'AppProjectUpdate' { 'run-app-project-update-gradle.ps1' } 'AppProjectArchive' { 'run-app-project-archive-gradle.ps1' } 'AppTask' { 'run-app-task-gradle.ps1' } 'AppTaskUpdate' { 'run-app-task-update-gradle.ps1' } 'AppTaskStatusChange' { 'run-app-task-status-change-gradle.ps1' } 'AppTaskStatusHistoryRead' { 'run-app-task-status-history-read-gradle.ps1' } 'AppTaskAssigneeDueDate' { 'run-app-task-assignee-due-date-gradle.ps1' } 'AppWorkspaceMemberListRead' { 'run-app-workspace-member-list-gradle.ps1' } 'AppWorkspaceMembershipManagement' { 'run-app-workspace-membership-management-gradle.ps1' } 'AppTaskArchive' { 'run-app-task-archive-gradle.ps1' } default { 'run-migration-gradle.ps1' } }
    $backendPath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../backend'))
    $start = [Diagnostics.ProcessStartInfo]::new()
    # Run the wrapper in a child PowerShell to avoid cmd.exe string interpolation.
    $start.FileName = (Get-Command pwsh -ErrorAction Stop).Source
    $start.WorkingDirectory = $backendPath
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @('-NoProfile','-NonInteractive','-File',(Join-Path $PSScriptRoot $runner),$Action)) {
        $start.ArgumentList.Add($argument)
    }
    # Inherit OS tools, but scrub application/admin/Flyway configuration from this process tree.
    foreach ($key in @($start.Environment.Keys)) {
        if ($key -match '^(FLOWDESK_|POSTGRES_|PGPASSWORD$|PGSERVICE|PGOPTIONS$|FLYWAY_|SPRING_|DEBUG$)') {
            [void]$start.Environment.Remove($key)
        }
    }
    foreach ($key in $Values.Keys) { $start.Environment[$key] = $Values[$key] }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        # Defense in depth: never forward supplied secrets, even if a tool unexpectedly prints one.
        foreach ($key in $Values.Keys) {
            if ($key -match 'PASSWORD' -and $Values[$key]) { $output = $output.Replace($Values[$key], '[REDACTED]') }
        }
        if ($process.ExitCode -ne 0) {
            # Do not forward arbitrary tool diagnostics on failure (SQL/configuration may be included).
            $category = [regex]::Match($output, '(Migration|Seed) task failed \((configuration|mismatch|schema/version|SQLState [A-Z0-9]{5}|validation/checksum|database/migration)\)')
            $reason = if ($category.Success) { $category.Value } else { 'Gradle/Java execution failed' }
            throw "$reason. Check target, credentials, ownership or checksum; no repair performed."
        }
        return $output
    } finally { $process.Dispose() }
}
