#requires -Version 7.0
param([switch]$IncludeSeed, [switch]$IncludeReadAccess, [switch]$IncludeWorkspaceCreate, [switch]$IncludeWorkspaceRename, [switch]$IncludeProjectCreate, [switch]$IncludeProjectUpdate, [switch]$IncludeProjectArchive, [switch]$IncludeTaskCreate, [switch]$IncludeTaskUpdate, [switch]$IncludeTaskStatusChange, [switch]$IncludeTaskStatusHistoryRead, [switch]$IncludeTaskAssigneeDueDate, [switch]$IncludeWorkspaceMemberListRead, [switch]$IncludeWorkspaceMembershipManagement, [switch]$IncludeTaskArchive)
if ($IncludeTaskArchive) { $IncludeWorkspaceMembershipManagement = $true }
if ($IncludeProjectArchive) { $IncludeProjectUpdate = $true }
if ($IncludeWorkspaceMembershipManagement) { $IncludeWorkspaceMemberListRead = $true }
if ($IncludeWorkspaceMemberListRead) { $IncludeTaskAssigneeDueDate = $true }
if ($IncludeTaskAssigneeDueDate) { $IncludeTaskStatusHistoryRead = $true }
if ($IncludeTaskStatusHistoryRead) { $IncludeTaskStatusChange = $true }
if ($IncludeTaskStatusChange) { $IncludeTaskUpdate = $true }
if ($IncludeTaskUpdate) { $IncludeTaskCreate = $true }
if ($IncludeTaskCreate) { $IncludeProjectUpdate = $true }
if ($IncludeProjectUpdate) { $IncludeProjectCreate = $true }
if ($IncludeProjectCreate) { $IncludeWorkspaceRename = $true }
if ($IncludeWorkspaceRename) { $IncludeWorkspaceCreate = $true }
if ($IncludeWorkspaceCreate) { $IncludeReadAccess = $true }
if ($IncludeReadAccess) { $IncludeSeed = $true }
# End-to-end verification on a unique Compose project. Never uses infra/.env or local DB.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$id = [Guid]::NewGuid().ToString('N')
$project = "flowdesk-migration-check-$id"
$workspace = Join-Path $root "backend/build/migration-check/$id"
$testInfra = Join-Path $workspace 'infra'
$testBackend = Join-Path $workspace 'backend'
[void](New-Item -ItemType Directory -Path $testInfra,$testBackend -Force)
foreach ($file in @('migration-common.ps1','run-migration-gradle.ps1','provision-migrator.ps1','migrate-local.ps1','seed-local.ps1','run-seed-gradle.ps1','provision-app-read.ps1','run-app-read-gradle.ps1','provision-workspace-create.ps1','run-app-create-gradle.ps1','provision-workspace-rename.ps1','run-app-rename-gradle.ps1','provision-project-create.ps1','run-app-project-gradle.ps1','provision-project-update.ps1','run-app-project-update-gradle.ps1','provision-project-archive.ps1','run-app-project-archive-gradle.ps1','provision-task-create.ps1','run-app-task-gradle.ps1','provision-task-update.ps1','run-app-task-update-gradle.ps1','provision-task-status-change.ps1','run-app-task-status-change-gradle.ps1','provision-task-status-history-read.ps1','run-app-task-status-history-read-gradle.ps1','provision-task-assignee-due-date.ps1','run-app-task-assignee-due-date-gradle.ps1','provision-workspace-member-list-read.ps1','run-app-workspace-member-list-gradle.ps1','provision-workspace-membership-management.ps1','run-app-workspace-membership-management-gradle.ps1','provision-task-archive.ps1','run-app-task-archive-gradle.ps1','app-role.sql')) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot $file) -Destination $testInfra
}
foreach ($file in @('gradlew.bat','build.gradle','settings.gradle','gradle.properties')) {
    $source = Join-Path $root "backend/$file"
    if (Test-Path -LiteralPath $source) { Copy-Item -LiteralPath $source -Destination $testBackend }
}
foreach ($directory in @('gradle','src')) {
    Copy-Item -LiteralPath (Join-Path $root "backend/$directory") -Destination $testBackend -Recurse
}
$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0)
$listener.Start()
$port = $listener.LocalEndpoint.Port
$listener.Stop()
$adminPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$appPassword = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)).ToLowerInvariant()
$compose = (Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot 'compose.yaml')).Replace('name: flowdesk-local', "name: $project")
[IO.File]::WriteAllText((Join-Path $testInfra 'compose.yaml'),$compose)
$envPath = Join-Path $testInfra '.env'
[IO.File]::WriteAllText($envPath,"POSTGRES_DB=flowdesk_verify`nPOSTGRES_USER=postgres`nPOSTGRES_PASSWORD=$adminPassword`nPOSTGRES_PORT=$port`n")
$credentialPath = Join-Path $testInfra '.env.migrator'
$testAppEnv = Join-Path $testBackend '.env'
if ($IncludeReadAccess) {
    [IO.File]::WriteAllText($testAppEnv,"FLOWDESK_DB_URL=jdbc:postgresql://127.0.0.1:$port/flowdesk_verify`nFLOWDESK_DB_USERNAME=flowdesk_app`nFLOWDESK_DB_PASSWORD=$appPassword`n")
}
$composeArgs = @('compose','--project-name',$project,'--file',(Join-Path $testInfra 'compose.yaml'),'--env-file',$envPath)
$checks = 0
$secrets = [Collections.Generic.List[string]]::new()
$secrets.Add($adminPassword)
$secrets.Add($appPassword)
$parentEnvironment = [Environment]::GetEnvironmentVariables('Process')

function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "Verification failed: $Message" }
    $script:checks++
    Write-Output "PASS $Message"
}
function Sql([string]$Query) {
    $result = $Query | & docker @composeArgs exec -T postgres psql -U postgres -d flowdesk_verify -v ON_ERROR_STOP=1 -Atq 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Isolated SQL verification failed; output withheld.' }
    return ($result -join "`n").Trim()
}
function Run([string]$Script, [string[]]$Arguments = @(), [bool]$ShouldSucceed = $true) {
    $start = [Diagnostics.ProcessStartInfo]::new((Get-Command pwsh).Source)
    $start.CreateNoWindow = $true
    $start.UseShellExecute = $false
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in (@('-NoProfile','-NonInteractive','-File',(Join-Path $testInfra $Script)) + $Arguments)) { $start.ArgumentList.Add($argument) }
    # Poison the inherited environment. The wrapper must remove these before running Java.
    $start.Environment['FLOWDESK_DB_PASSWORD'] = 'inherited-app-password-must-not-reach-runner'
    $start.Environment['POSTGRES_PASSWORD'] = 'inherited-admin-password-must-not-reach-runner'
    $start.Environment['FLOWDESK_MIGRATION_ADMIN_PASSWORD'] = 'inherited-bootstrap-password-must-be-replaced-or-removed'
    $start.Environment['COMPOSE_PROJECT_NAME'] = 'flowdesk-inherited-project-must-not-be-used'
    $process = [Diagnostics.Process]::Start($start)
    try {
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $code = $process.ExitCode
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
    } finally { $process.Dispose() }
    foreach ($secret in $secrets) {
        if ($output.Contains($secret)) { throw 'Secret appeared in child output (value withheld).' }
    }
    if (($code -eq 0) -ne $ShouldSucceed) {
        $category = [regex]::Match($output, '(Migration|Seed) task failed \([a-zA-Z0-9 /-]+\)|Gradle/Java execution failed')
        throw "Unexpected exit code from $Script ($($category.Value); output withheld)."
    }
    return $output
}

try {
    & docker @composeArgs up -d --wait --wait-timeout 120
    if ($LASTEXITCODE -ne 0) { throw 'Isolated PostgreSQL startup failed.' }
    Write-Output 'Isolated PostgreSQL ready; preparing existing application account policy.'
    $policy = Get-Content -Raw -LiteralPath (Join-Path $testInfra 'app-role.sql')
    [void](Run 'migrate-local.ps1' @('-Action','Migrate') $false)
    Assert-Check ((Sql "SELECT count(*) FROM pg_namespace WHERE nspname='flowdesk'") -eq '0') 'missing credentials rejected before DDL'
    [void](Run 'migrate-local.ps1' @('-Action','Clean') $false)
    [void](Run 'migrate-local.ps1' @() $false)
    Assert-Check (-not (Test-Path $credentialPath)) 'invalid or missing action cannot provision'
    $failedProvision = Run 'provision-migrator.ps1' @() $false
    $retryCredentials = [IO.File]::ReadAllText($credentialPath)
    $generatedPassword = ([regex]::Match($retryCredentials,'FLOWDESK_MIGRATOR_DB_PASSWORD=([a-f0-9]{64})')).Groups[1].Value
    $secrets.Add($generatedPassword)
    Assert-Check (-not $failedProvision.Contains($generatedPassword)) 'bootstrap failure output does not expose generated password'
    Assert-Check ((Sql "SELECT count(*) FROM pg_roles WHERE rolname='flowdesk_migrator'") -eq '0') 'unsafe PUBLIC defaults roll back bootstrap; credential file retained for retry'
    [void](Sql ("SELECT set_config('flowdesk.app_password','$appPassword',false);`n" + $policy))
    Write-Output 'Running actual provisioning wrapper and Gradle JavaExec.'
    [void](Run 'provision-migrator.ps1')
    $originalCredentials = [IO.File]::ReadAllText($credentialPath)
    Assert-Check ($retryCredentials -ceq $originalCredentials) 'retry after transaction failure reuses the generated password'
    $migratorPassword = ([regex]::Match($originalCredentials,'FLOWDESK_MIGRATOR_DB_PASSWORD=([a-f0-9]{64})')).Groups[1].Value
    $secrets.Add($migratorPassword)
    Assert-Check ($migratorPassword.Length -eq 64 -and $migratorPassword -ne $adminPassword -and $migratorPassword -ne $appPassword) 'separate generated 32-byte password'
    Assert-Check ((Sql "SELECT count(*) FROM pg_tables WHERE schemaname='flowdesk'") -eq '0') 'provisioning creates no business or history tables'
    [void](Run 'provision-migrator.ps1')
    Assert-Check ([IO.File]::ReadAllText($credentialPath) -ceq $originalCredentials) 'repeat provisioning preserves credentials'
    $info = Run 'migrate-local.ps1' @('-Action','Info')
    Assert-Check ($info -match 'PENDING') 'Info reports V1 pending'
    [void](Run 'migrate-local.ps1' @('-Action','Validate') $false)
    $first = Run 'migrate-local.ps1' @('-Action','Migrate')
    Assert-Check ($first -match 'Migrations executed: 10') 'fresh migration executes V1 through V10 once'
    [void](Run 'migrate-local.ps1' @('-Action','Validate'))
    $info = Run 'migrate-local.ps1' @('-Action','Info')
    Assert-Check ($info -match 'SUCCESS') 'post-migration validation and success history'
    $repeat = Run 'migrate-local.ps1' @('-Action','Migrate')
    Assert-Check ($repeat -match 'Migrations executed: 0') 'second migration executes zero changes'
    Assert-Check ((Sql "SELECT count(*) FROM pg_tables WHERE schemaname='flowdesk' AND tableowner='flowdesk_migrator'") -eq '8') 'workspace tables, status activity, invitations and history owned by migrator'
    Assert-Check ((Sql 'SELECT (SELECT count(*) FROM flowdesk.users)+(SELECT count(*) FROM flowdesk.workspaces)+(SELECT count(*) FROM flowdesk.workspace_memberships)') -eq '0') 'no development seed inserted'
    Assert-Check ((Sql "SELECT rolcanlogin AND NOT (rolsuper OR rolcreatedb OR rolcreaterole OR rolinherit OR rolreplication OR rolbypassrls) AND NOT has_database_privilege(rolname,current_database(),'CREATE') AND NOT has_database_privilege(rolname,current_database(),'TEMP') FROM pg_roles WHERE rolname='flowdesk_migrator'") -eq 't') 'migrator has no administrator or database DDL privileges'
    $appProbe = @'
DO $check$
BEGIN
 SET LOCAL ROLE flowdesk_app;
 PERFORM 1;
 BEGIN PERFORM * FROM flowdesk.users; RAISE EXCEPTION 'Unexpected SELECT'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN INSERT INTO flowdesk.users(email,display_name) VALUES('blocked@example.test','Blocked'); RAISE EXCEPTION 'Unexpected INSERT'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN CREATE TABLE flowdesk.forbidden(id int); RAISE EXCEPTION 'Unexpected DDL'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.flyway_schema_history; RAISE EXCEPTION 'Unexpected history write'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
'@
    [void](Sql $appProbe)
    Assert-Check $true 'app SELECT 1 works; business read/write, DDL and history modification rejected'
    foreach ($replacement in @(
        $originalCredentials.Replace('flowdesk_migrator','flowdesk_app'),
        $originalCredentials.Replace('127.0.0.1','192.0.2.1'),
        $originalCredentials.Replace('/flowdesk_verify','/wrong_database'),
        $originalCredentials.Replace($migratorPassword,('0' * 64)))) {
        [IO.File]::WriteAllText($credentialPath,$replacement)
        [void](Run 'migrate-local.ps1' @('-Action','Migrate') $false)
    }
    [IO.File]::WriteAllText($credentialPath,$originalCredentials)
    Assert-Check $true 'wrong user, remote URL, wrong database and wrong password rejected'
    [IO.File]::Delete($credentialPath)
    [void](Run 'provision-migrator.ps1' @() $false)
    Assert-Check (-not (Test-Path $credentialPath)) 'existing role without file cannot rotate credentials'
    [IO.File]::WriteAllText($credentialPath,$originalCredentials)
    [void](Sql 'ALTER SCHEMA flowdesk OWNER TO postgres')
    [void](Run 'provision-migrator.ps1' @() $false)
    [void](Run 'migrate-local.ps1' @('-Action','Migrate') $false)
    [void](Sql 'ALTER SCHEMA flowdesk OWNER TO flowdesk_migrator')
    Assert-Check $true 'unexpected schema owner blocks provisioning and migration'
    [void](Sql 'ALTER ROLE flowdesk_migrator CREATEDB')
    [void](Run 'provision-migrator.ps1' @() $false)
    [void](Sql 'ALTER ROLE flowdesk_migrator NOCREATEDB')
    Assert-Check $true 'unexpected role flags cannot be silently corrected'
    [void](Sql 'GRANT USAGE ON SCHEMA flowdesk TO PUBLIC')
    [void](Run 'migrate-local.ps1' @('-Action','Migrate') $false)
    [void](Sql 'REVOKE USAGE ON SCHEMA flowdesk FROM PUBLIC')
    Assert-Check $true 'unexpected PUBLIC schema grants rejected'
    [void](Sql 'ALTER TABLE flowdesk.flyway_schema_history RENAME TO verification_history')
    [void](Run 'migrate-local.ps1' @('-Action','Info') $false)
    [void](Run 'migrate-local.ps1' @('-Action','Migrate') $false)
    [void](Sql 'ALTER TABLE flowdesk.verification_history RENAME TO flyway_schema_history')
    Assert-Check $true 'nonempty schema without history cannot be baselined or migrated'
    $sqlPath = Join-Path $testBackend 'src/main/resources/db/migration/V1__create_identity_and_workspace_tables.sql'
    $originalSql = [IO.File]::ReadAllText($sqlPath)
    [IO.File]::WriteAllText($sqlPath,$originalSql.Replace('varchar(80)','varchar(81)'))
    [void](Run 'migrate-local.ps1' @('-Action','Validate') $false)
    [void](Run 'migrate-local.ps1' @('-Action','Migrate') $false)
    [IO.File]::WriteAllText($sqlPath,$originalSql)
    [void](Run 'migrate-local.ps1' @('-Action','Validate'))
    Assert-Check $true 'modified applied SQL fails checksum; original restored without repair'
    Assert-Check ((Sql "SELECT count(*) FROM flowdesk.flyway_schema_history WHERE version='1' AND success") -eq '1') 'failure cases leave successful V1 history unchanged'
    if ($IncludeSeed) {
        Write-Output 'Verifying the actual seed PowerShell/Gradle entry point.'
        $historyBefore = Sql 'SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x'
        [void](Run 'seed-local.ps1' @('-Action','Verify') $false)
        Assert-Check ((Sql 'SELECT (SELECT count(*) FROM flowdesk.users)+(SELECT count(*) FROM flowdesk.workspaces)+(SELECT count(*) FROM flowdesk.workspace_memberships)') -eq '0') 'seed Verify on empty tables writes nothing'
        [void](Run 'seed-local.ps1' @('-Action','Reset') $false)
        [void](Run 'seed-local.ps1' @() $false)
        Assert-Check $true 'invalid or missing seed action rejected'
        $firstSeed = Run 'seed-local.ps1' @('-Action','Apply')
        $seedCounts = Sql "SELECT (SELECT count(*) FROM flowdesk.users)||'/'||(SELECT count(*) FROM flowdesk.workspaces)||'/'||(SELECT count(*) FROM flowdesk.workspace_memberships)"
        Assert-Check ($firstSeed -match 'Seed rows inserted: 12' -and $seedCounts -eq '5/2/5') 'seed Apply inserts 5 users, 2 workspaces and 5 memberships'
        $snapshotSql = "SELECT coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.users x),'[]')||coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspaces x),'[]')||coalesce((SELECT json_agg(x ORDER BY id)::text FROM flowdesk.workspace_memberships x),'[]')"
        $seedBefore = Sql $snapshotSql
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        $repeatSeed = Run 'seed-local.ps1' @('-Action','Apply')
        Assert-Check ($repeatSeed -match 'Seed rows inserted: 0' -and (Sql $snapshotSql) -ceq $seedBefore) 'seed Verify and repeated Apply preserve IDs, timestamps and values'
        [void](Sql "UPDATE flowdesk.users SET display_name='Edited locally' WHERE email='alice@example.test'")
        $changedSeed = Sql $snapshotSql
        [void](Run 'seed-local.ps1' @('-Action','Apply') $false)
        [void](Run 'seed-local.ps1' @('-Action','Verify') $false)
        Assert-Check ((Sql $snapshotSql) -ceq $changedSeed) 'seed mismatch preserves the edited dataset'
        [void](Sql "UPDATE flowdesk.users SET display_name='Alice' WHERE email='alice@example.test'")
        foreach ($replacement in @(
            $originalCredentials.Replace('flowdesk_migrator','flowdesk_app'),
            $originalCredentials.Replace('127.0.0.1','192.0.2.1'),
            $originalCredentials.Replace($migratorPassword,('0' * 64)))) {
            [IO.File]::WriteAllText($credentialPath,$replacement)
            [void](Run 'seed-local.ps1' @('-Action','Apply') $false)
        }
        [IO.File]::WriteAllText($credentialPath,$originalCredentials)
        Assert-Check ((Sql $snapshotSql) -ceq $seedBefore) 'seed wrapper rejects wrong user, URL and password without writing'
        Assert-Check ((Sql 'SELECT json_agg(x ORDER BY installed_rank)::text FROM flowdesk.flyway_schema_history x') -ceq $historyBefore) 'seed does not change Flyway history'
        [void](Sql $appProbe)
        Assert-Check $true 'seed does not grant application read/write or DDL permissions'
    }
    if ($IncludeReadAccess) {
        Write-Output 'Verifying explicit read-access provisioning and READ_ONLY tool compatibility.'
        if ($IncludeWorkspaceCreate) {
            [void](Run 'provision-workspace-create.ps1' @() $false)
            Assert-Check ((Sql "SELECT has_table_privilege('flowdesk_app','flowdesk.workspaces','INSERT')") -eq 'f') 'creation provisioning requires READ_ONLY first'
        }
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        $snapshotBeforeRead = Sql $snapshotSql
        [IO.File]::WriteAllText($testAppEnv,$appBefore.Replace('127.0.0.1','192.0.2.1'))
        [void](Run 'provision-app-read.ps1' @() $false)
        [IO.File]::WriteAllText($testAppEnv,$appBefore)
        Assert-Check ((Sql "SELECT has_schema_privilege('flowdesk_app','flowdesk','USAGE')") -eq 'f') 'wrong app target rejected before grants'
        $grant = Run 'provision-app-read.ps1'
        Assert-Check ($grant -match 'Application read access: READ_ONLY') 'explicit wrapper grants the approved READ_ONLY policy'
        [void](Run 'provision-app-read.ps1')
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'repeat read-access provisioning preserves app credentials'
        $readProbe = @'
DO $check$
BEGIN
 SET LOCAL ROLE flowdesk_app;
 PERFORM * FROM flowdesk.users;
 PERFORM * FROM flowdesk.workspaces;
 PERFORM * FROM flowdesk.workspace_memberships;
 BEGIN INSERT INTO flowdesk.users(email,display_name) VALUES('blocked@example.test','Blocked'); RAISE EXCEPTION 'Unexpected INSERT'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.users SET display_name='Blocked'; RAISE EXCEPTION 'Unexpected UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.workspace_memberships; RAISE EXCEPTION 'Unexpected DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN TRUNCATE flowdesk.workspace_memberships; RAISE EXCEPTION 'Unexpected TRUNCATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN CREATE TABLE flowdesk.forbidden(id int); RAISE EXCEPTION 'Unexpected DDL'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM nextval('flowdesk.users_id_seq'); RAISE EXCEPTION 'Unexpected sequence'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM * FROM flowdesk.flyway_schema_history; RAISE EXCEPTION 'Unexpected history read'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
'@
        [void](Sql $readProbe)
        Assert-Check $true 'READ_ONLY app can SELECT all three tables but cannot write, DDL, use sequences or read history'
        [void](Run 'migrate-local.ps1' @('-Action','Info'))
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        $readMigrate = Run 'migrate-local.ps1' @('-Action','Migrate')
        Assert-Check ($readMigrate -match 'Migrations executed: 0') 'migration remains compatible with READ_ONLY'
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        $readSeed = Run 'seed-local.ps1' @('-Action','Apply')
        Assert-Check ($readSeed -match 'Seed rows inserted: 0') 'seed remains compatible with READ_ONLY'
        [void](Sql 'GRANT UPDATE ON flowdesk.users TO flowdesk_app')
        [void](Run 'provision-app-read.ps1' @() $false)
        [void](Run 'migrate-local.ps1' @('-Action','Validate') $false)
        [void](Run 'seed-local.ps1' @('-Action','Verify') $false)
        Assert-Check ((Sql "SELECT has_table_privilege('flowdesk_app','flowdesk.users','UPDATE')") -eq 't') 'excessive privileges block every tool without automatic repair'
        [void](Sql 'REVOKE UPDATE ON flowdesk.users FROM flowdesk_app')
        Assert-Check ((Sql $snapshotSql) -ceq $snapshotBeforeRead) 'read provisioning and tool checks preserve all data'
    }
    if ($IncludeWorkspaceCreate) {
        $beforeCreate = Sql $snapshotSql
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        [IO.File]::WriteAllText($testAppEnv,$appBefore.Replace('127.0.0.1','192.0.2.1'))
        [void](Run 'provision-workspace-create.ps1' @() $false)
        [IO.File]::WriteAllText($testAppEnv,$appBefore)
        Assert-Check ((Sql "SELECT has_table_privilege('flowdesk_app','flowdesk.workspaces','INSERT')") -eq 'f') 'wrong create target rejected before grants'
        $created = Run 'provision-workspace-create.ps1'
        Assert-Check ($created -match 'Application workspace-create access: WORKSPACE_CREATE') 'explicit create wrapper grants only approved INSERT policy'
        [void](Run 'provision-workspace-create.ps1')
        $readAgain = Run 'provision-app-read.ps1'
        Assert-Check ($readAgain -match 'WORKSPACE_CREATE') 'read wrapper preserves approved INSERT grants'
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        $createdMigration = Run 'migrate-local.ps1' @('-Action','Migrate')
        Assert-Check ($createdMigration -match 'Migrations executed: 0') 'migration compatible with create state'
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        $createdSeed = Run 'seed-local.ps1' @('-Action','Apply')
        Assert-Check ($createdSeed -match 'Seed rows inserted: 0') 'seed fixture compatible with create state'
        [void](Sql @'
BEGIN;
SET LOCAL ROLE flowdesk_app;
WITH w AS (INSERT INTO flowdesk.workspaces(name) VALUES ('Create permission probe') RETURNING id)
INSERT INTO flowdesk.workspace_memberships(workspace_id,user_id,role)
SELECT w.id,u.id,'OWNER' FROM w CROSS JOIN flowdesk.users u WHERE u.email='alice@example.test';
ROLLBACK;
'@)
        Assert-Check ((Sql "SELECT bool_and(NOT has_sequence_privilege('flowdesk_app',c.oid,'USAGE,SELECT,UPDATE')) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='flowdesk' AND c.relkind='S'") -eq 't') 'identity INSERT works without direct sequence privileges'
        [void](Sql 'REVOKE INSERT ON flowdesk.workspace_memberships FROM flowdesk_app')
        [void](Run 'provision-workspace-create.ps1' @() $false)
        [void](Run 'migrate-local.ps1' @('-Action','Validate') $false)
        [void](Run 'seed-local.ps1' @('-Action','Verify') $false)
        Assert-Check ((Sql "SELECT NOT has_table_privilege('flowdesk_app','flowdesk.workspace_memberships','INSERT')") -eq 't') 'partial INSERT state rejected without automatic repair'
        [void](Sql 'GRANT INSERT ON flowdesk.workspace_memberships TO flowdesk_app')
        [void](Sql 'GRANT UPDATE ON flowdesk.users TO flowdesk_app')
        [void](Run 'provision-workspace-create.ps1' @() $false)
        Assert-Check ((Sql "SELECT has_table_privilege('flowdesk_app','flowdesk.users','UPDATE')") -eq 't') 'create wrapper rejects excess privileges without revocation'
        [void](Sql 'REVOKE UPDATE ON flowdesk.users FROM flowdesk_app')
        Assert-Check ((Sql $snapshotSql) -ceq $beforeCreate) 'create grants and rollback probe preserve business data and history'
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'create wrapper preserves app credential file'
    }
    if ($IncludeWorkspaceRename) {
        Write-Output 'Verifying column UPDATE provisioning and WORKSPACE_RENAME compatibility.'
        $beforeRename = Sql $snapshotSql
        $rename = Run 'provision-workspace-rename.ps1'
        Assert-Check ($rename -match 'Application workspace-rename access: WORKSPACE_RENAME') 'rename wrapper grants approved column UPDATE'
        [void](Run 'provision-workspace-rename.ps1')
        Assert-Check ((Run 'provision-workspace-create.ps1') -match 'WORKSPACE_RENAME') 'create wrapper preserves rename grants'
        Assert-Check ((Run 'provision-app-read.ps1') -match 'WORKSPACE_RENAME') 'read wrapper preserves rename grants'
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        $renameProbe = @'
BEGIN;
SET LOCAL ROLE flowdesk_app;
UPDATE flowdesk.workspaces SET name='Rollback rename probe',updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE version=0;
DO $check$ BEGIN
 BEGIN UPDATE flowdesk.workspaces SET id=DEFAULT WHERE false; RAISE EXCEPTION 'Unexpected identity UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.workspaces SET created_at=created_at; RAISE EXCEPTION 'Unexpected timestamp UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.workspace_memberships SET role='OWNER'; RAISE EXCEPTION 'Unexpected role UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
ROLLBACK;
'@
        [void](Sql $renameProbe)
        Assert-Check ((Sql $snapshotSql) -ceq $beforeRename) 'allowed column UPDATE and rollback preserve rows'
        Assert-Check ((Sql "SELECT NOT has_table_privilege('flowdesk_app','flowdesk.workspaces','UPDATE') AND has_column_privilege('flowdesk_app','flowdesk.workspaces','version','UPDATE')") -eq 't') 'column grants exclude table-wide UPDATE'
        [void](Sql 'REVOKE UPDATE(version) ON flowdesk.workspaces FROM flowdesk_app')
        [void](Run 'provision-workspace-rename.ps1' @() $false)
        [void](Run 'provision-workspace-create.ps1' @() $false)
        [void](Run 'migrate-local.ps1' @('-Action','Validate') $false)
        [void](Run 'seed-local.ps1' @('-Action','Verify') $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.workspaces','version','UPDATE')") -eq 't') 'partial rename state rejected without repair'
        [void](Sql 'GRANT UPDATE(version) ON flowdesk.workspaces TO flowdesk_app')
        [void](Sql 'GRANT UPDATE(created_at) ON flowdesk.workspaces TO flowdesk_app')
        [void](Run 'provision-workspace-rename.ps1' @() $false)
        Assert-Check ((Sql "SELECT has_column_privilege('flowdesk_app','flowdesk.workspaces','created_at','UPDATE')") -eq 't') 'excessive columns rejected without revocation'
        [void](Sql 'REVOKE UPDATE(created_at) ON flowdesk.workspaces FROM flowdesk_app')
        [void](Run 'provision-workspace-rename.ps1')
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'rename wrapper preserves app credentials'
    }
    if ($IncludeProjectCreate) {
        $beforeProject = Sql $snapshotSql
        Assert-Check ((Run 'provision-project-create.ps1') -match 'PROJECT_CREATE') 'Project wrapper grants exact SELECT and INSERT'
        Assert-Check ((Run 'provision-project-create.ps1') -match 'PROJECT_CREATE') 'Project grant repeats without credential rotation'
        Assert-Check ((Run 'provision-app-read.ps1') -match 'PROJECT_CREATE') 'read wrapper preserves Project state'
        Assert-Check ((Run 'provision-workspace-create.ps1') -match 'PROJECT_CREATE') 'create wrapper preserves Project state'
        Assert-Check ((Run 'provision-workspace-rename.ps1') -match 'PROJECT_CREATE') 'rename wrapper preserves Project state'
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Sql "BEGIN; SET LOCAL ROLE flowdesk_app; INSERT INTO flowdesk.projects(workspace_id,name) SELECT id,'Rollback project' FROM flowdesk.workspaces LIMIT 1 RETURNING id; ROLLBACK;")
        Assert-Check ((Sql $snapshotSql) -ceq $beforeProject) 'Project identity insert rollback preserves existing rows and history'
        Assert-Check ((Sql "SELECT has_table_privilege('flowdesk_app','flowdesk.projects','SELECT') AND has_table_privilege('flowdesk_app','flowdesk.projects','INSERT') AND NOT has_table_privilege('flowdesk_app','flowdesk.projects','UPDATE,DELETE,TRUNCATE') AND NOT has_sequence_privilege('flowdesk_app','flowdesk.projects_id_seq','SELECT,USAGE,UPDATE')") -eq 't') 'Project privileges exclude update, delete, truncate and sequence access'
        [void](Sql 'REVOKE INSERT ON flowdesk.projects FROM flowdesk_app')
        [void](Run 'provision-project-create.ps1' @() $false)
        Assert-Check ((Sql "SELECT NOT has_table_privilege('flowdesk_app','flowdesk.projects','INSERT')") -eq 't') 'partial Project grants rejected without repair'
        [void](Sql 'GRANT INSERT ON flowdesk.projects TO flowdesk_app')
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'Project wrapper preserves credentials'
    }
    if ($IncludeProjectUpdate) {
        $beforeUpdate = Sql $snapshotSql
        Assert-Check ((Run 'provision-project-update.ps1') -match 'PROJECT_UPDATE') 'Project update grants exact four columns'
        Assert-Check ((Run 'provision-project-update.ps1') -match 'PROJECT_UPDATE') 'Project update repeats without rotation'
        Assert-Check ((Run 'provision-project-create.ps1') -match 'PROJECT_UPDATE') 'Project create preserves update state'
        Assert-Check ((Run 'provision-app-read.ps1') -match 'PROJECT_UPDATE') 'read preserves Project update state'
        Assert-Check ((Run 'provision-workspace-create.ps1') -match 'PROJECT_UPDATE') 'workspace create preserves Project update state'
        Assert-Check ((Run 'provision-workspace-rename.ps1') -match 'PROJECT_UPDATE') 'workspace rename preserves Project update state'
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        $updateProbe = @'
BEGIN;
SET LOCAL ROLE flowdesk_app;
INSERT INTO flowdesk.projects(workspace_id,name) SELECT id,'Rollback Project update' FROM flowdesk.workspaces LIMIT 1;
UPDATE flowdesk.projects SET name='Updated',description='Description',updated_at=CURRENT_TIMESTAMP,version=version+1;
DO $probe$ BEGIN
 BEGIN UPDATE flowdesk.projects SET workspace_id=workspace_id WHERE false; RAISE EXCEPTION 'Unexpected parent UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.projects SET created_at=created_at WHERE false; RAISE EXCEPTION 'Unexpected timestamp UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $probe$;
ROLLBACK;
'@
        [void](Sql $updateProbe)
        Assert-Check ((Sql $snapshotSql) -ceq $beforeUpdate) 'Project update rollback preserves existing rows and history'
        Assert-Check ((Sql "SELECT count(*)=4 AND bool_and(attname IN ('name','description','updated_at','version')) FROM pg_attribute WHERE attrelid='flowdesk.projects'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')") -eq 't') 'effective Project UPDATE columns exactly match policy'
        [void](Sql 'REVOKE UPDATE(version) ON flowdesk.projects FROM flowdesk_app')
        [void](Run 'provision-project-update.ps1' @() $false)
        [void](Run 'provision-project-create.ps1' @() $false)
        [void](Run 'migrate-local.ps1' @('-Action','Validate') $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.projects','version','UPDATE')") -eq 't') 'partial Project update rejected without repair'
        [void](Sql 'GRANT UPDATE(version) ON flowdesk.projects TO flowdesk_app')
        [void](Sql 'GRANT UPDATE(workspace_id) ON flowdesk.projects TO flowdesk_app')
        [void](Run 'provision-project-update.ps1' @() $false)
        Assert-Check ((Sql "SELECT has_column_privilege('flowdesk_app','flowdesk.projects','workspace_id','UPDATE')") -eq 't') 'excess Project parent grant rejected without revocation'
        [void](Sql 'REVOKE UPDATE(workspace_id) ON flowdesk.projects FROM flowdesk_app')
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'Project update preserves app credential file'
    }

    if ($IncludeProjectArchive) {
        $beforeArchive = Sql $snapshotSql
        Assert-Check ((Run 'provision-project-archive.ps1') -match 'PROJECT_UPDATE') 'Project archive grants archived_at only'
        Assert-Check ((Run 'provision-project-archive.ps1') -match 'PROJECT_UPDATE') 'Project archive grant repeats without rotation'
        foreach ($tool in @('provision-project-update.ps1','provision-project-create.ps1','provision-app-read.ps1','provision-workspace-create.ps1','provision-workspace-rename.ps1')) {
            Assert-Check ((Run $tool) -match 'PROJECT_UPDATE') "$tool preserves Project archive capability"
        }
        Assert-Check ((Sql "SELECT count(*)=5 AND bool_and(attname IN ('name','description','updated_at','version','archived_at')) FROM pg_attribute WHERE attrelid='flowdesk.projects'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')") -eq 't') 'effective Project UPDATE remains five approved columns'
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ((Sql $snapshotSql) -ceq $beforeArchive) 'Project archive ACL changes preserve seeded rows and migration history'
        [void](Sql 'GRANT UPDATE(workspace_id) ON flowdesk.projects TO flowdesk_app')
        [void](Run 'provision-project-archive.ps1' @() $false)
        Assert-Check ((Sql "SELECT has_column_privilege('flowdesk_app','flowdesk.projects','workspace_id','UPDATE')") -eq 't') 'Project archive runner rejects excess Project UPDATE without revocation'
        [void](Sql 'REVOKE UPDATE(workspace_id) ON flowdesk.projects FROM flowdesk_app')
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'Project archive tool preserves app credential file'
    }

    if ($IncludeTaskCreate) {
        $beforeTask = Sql $snapshotSql
        Assert-Check ((Run 'provision-task-create.ps1') -match 'TASK_CREATE') 'Task wrapper grants approved SELECT and INSERT'
        Assert-Check ((Run 'provision-task-create.ps1') -match 'TASK_CREATE') 'Task wrapper repeat preserves credentials'
        foreach($legacy in @('provision-app-read.ps1','provision-workspace-create.ps1','provision-workspace-rename.ps1','provision-project-create.ps1','provision-project-update.ps1')) {
            Assert-Check ((Run $legacy) -match 'TASK_CREATE') "legacy $legacy preserves Task state"
        }
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        $probe = @'
BEGIN;
SET LOCAL ROLE flowdesk_app;
INSERT INTO flowdesk.projects(workspace_id,name) SELECT id,'Task parent probe' FROM flowdesk.workspaces ORDER BY id LIMIT 1;
INSERT INTO flowdesk.tasks(project_id,title) SELECT id,'Task probe' FROM flowdesk.projects ORDER BY id LIMIT 1;
DO $check$ BEGIN
 BEGIN UPDATE flowdesk.tasks SET title=title WHERE false; RAISE EXCEPTION 'Unexpected Task UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.tasks WHERE false; RAISE EXCEPTION 'Unexpected Task DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
ROLLBACK;
'@
        [void](Sql $probe)
        Assert-Check ((Sql $snapshotSql) -ceq $beforeTask -and (Sql 'SELECT count(*) FROM flowdesk.tasks') -eq '0' -and (Sql 'SELECT count(*) FROM flowdesk.projects') -eq '0') 'Task identity INSERT rollback preserves data'
        Assert-Check ((Sql "SELECT has_table_privilege('flowdesk_app','flowdesk.tasks','SELECT') AND has_table_privilege('flowdesk_app','flowdesk.tasks','INSERT') AND NOT has_table_privilege('flowdesk_app','flowdesk.tasks','UPDATE,DELETE,TRUNCATE') AND NOT has_any_column_privilege('flowdesk_app','flowdesk.tasks','UPDATE') AND NOT has_sequence_privilege('flowdesk_app','flowdesk.tasks_id_seq','USAGE,SELECT,UPDATE')") -eq 't') 'Task permissions exactly exclude UPDATE and sequences'
        [void](Sql 'REVOKE INSERT ON flowdesk.tasks FROM flowdesk_app')
        [void](Run 'provision-task-create.ps1' @() $false)
        [void](Run 'provision-project-update.ps1' @() $false)
        Assert-Check ((Sql "SELECT NOT has_table_privilege('flowdesk_app','flowdesk.tasks','INSERT')") -eq 't') 'partial Task state rejected without repair'
        [void](Sql 'GRANT INSERT ON flowdesk.tasks TO flowdesk_app')
        [void](Sql 'GRANT UPDATE(title) ON flowdesk.tasks TO flowdesk_app')
        [void](Run 'provision-task-create.ps1' @() $false)
        Assert-Check ((Sql "SELECT has_column_privilege('flowdesk_app','flowdesk.tasks','title','UPDATE')") -eq 't') 'excess Task column rejected without revocation'
        [void](Sql 'REVOKE UPDATE(title) ON flowdesk.tasks FROM flowdesk_app')
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'Task wrapper preserves application credential file'
    }
    if ($IncludeTaskUpdate) {
        $beforeTaskUpdate = Sql $snapshotSql
        Assert-Check ((Run 'provision-task-update.ps1') -match 'TASK_UPDATE') 'Task update grants exactly five columns'
        Assert-Check ((Run 'provision-task-update.ps1') -match 'TASK_UPDATE') 'Task update repeat preserves credentials'
        foreach($legacy in @('provision-task-create.ps1','provision-project-update.ps1','provision-project-create.ps1','provision-workspace-rename.ps1','provision-workspace-create.ps1','provision-app-read.ps1')) {
            Assert-Check ((Run $legacy) -match 'TASK_UPDATE') "legacy $legacy preserves Task update state"
        }
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        $updateProbe = @'
BEGIN;
SET LOCAL ROLE flowdesk_app;
INSERT INTO flowdesk.projects(workspace_id,name) SELECT id,'Task update parent' FROM flowdesk.workspaces ORDER BY id LIMIT 1;
INSERT INTO flowdesk.tasks(project_id,title) SELECT id,'Task update probe' FROM flowdesk.projects ORDER BY id LIMIT 1;
UPDATE flowdesk.tasks SET title='Changed',description='Text',priority='HIGH',updated_at=CURRENT_TIMESTAMP,version=version+1;
DO $check$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM flowdesk.tasks WHERE title='Changed' AND description='Text' AND priority='HIGH' AND status='TODO' AND version=1) THEN RAISE EXCEPTION 'Task update failed'; END IF;
 BEGIN UPDATE flowdesk.tasks SET status=status WHERE false; RAISE EXCEPTION 'Unexpected status UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.tasks SET project_id=project_id WHERE false; RAISE EXCEPTION 'Unexpected parent UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.tasks SET created_at=created_at WHERE false; RAISE EXCEPTION 'Unexpected time UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.tasks SET id=DEFAULT WHERE false; RAISE EXCEPTION 'Unexpected id UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.tasks WHERE false; RAISE EXCEPTION 'Unexpected DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
ROLLBACK;
'@
        [void](Sql $updateProbe)
        Assert-Check ((Sql $snapshotSql) -ceq $beforeTaskUpdate -and (Sql 'SELECT count(*) FROM flowdesk.tasks') -eq '0' -and (Sql 'SELECT count(*) FROM flowdesk.projects') -eq '0') 'Task update rollback preserves business rows and history'
        Assert-Check ((Sql "SELECT count(*)=5 AND bool_and(attname IN ('title','description','priority','updated_at','version')) FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')") -eq 't') 'Task effective UPDATE columns exactly match five approved columns'
        [void](Sql 'REVOKE UPDATE(version) ON flowdesk.tasks FROM flowdesk_app')
        [void](Run 'provision-task-update.ps1' @() $false)
        [void](Run 'provision-task-create.ps1' @() $false)
        [void](Run 'migrate-local.ps1' @('-Action','Validate') $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.tasks','version','UPDATE')") -eq 't') 'partial Task update rejected without repair'
        [void](Sql 'GRANT UPDATE(version) ON flowdesk.tasks TO flowdesk_app')
        [void](Sql 'GRANT UPDATE(status) ON flowdesk.tasks TO flowdesk_app')
        [void](Run 'provision-task-update.ps1' @() $false)
        Assert-Check ((Sql "SELECT has_column_privilege('flowdesk_app','flowdesk.tasks','status','UPDATE')") -eq 't') 'excess status grant rejected without revocation'
        [void](Sql 'REVOKE UPDATE(status) ON flowdesk.tasks FROM flowdesk_app')
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'Task update preserves application credential file'
    }
    if ($IncludeTaskStatusChange) {
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        $beforeStatus = Sql $snapshotSql
        Assert-Check ((Run 'provision-task-status-change.ps1') -match 'TASK_STATUS_CHANGE') 'status and activity column grants applied'
        Assert-Check ((Run 'provision-task-status-change.ps1') -match 'TASK_STATUS_CHANGE') 'status grant repeat preserves complete state'
        foreach ($legacy in @('provision-app-read.ps1','provision-workspace-create.ps1','provision-workspace-rename.ps1','provision-project-create.ps1','provision-project-update.ps1','provision-task-create.ps1','provision-task-update.ps1')) {
            Assert-Check ((Run $legacy) -match 'TASK_STATUS_CHANGE') "legacy $legacy preserves status policy"
        }
        $statusProbe = @'
BEGIN;
SET ROLE flowdesk_app;
INSERT INTO flowdesk.projects(workspace_id,name) SELECT id,'Status probe' FROM flowdesk.workspaces ORDER BY id LIMIT 1;
INSERT INTO flowdesk.tasks(project_id,title) SELECT id,'Status probe' FROM flowdesk.projects ORDER BY id LIMIT 1;
UPDATE flowdesk.tasks SET status='DONE',updated_at=CURRENT_TIMESTAMP,version=version+1;
INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at)
 SELECT t.id,u.id,'TODO',t.status,t.version,t.updated_at FROM flowdesk.tasks t CROSS JOIN LATERAL (SELECT id FROM flowdesk.users ORDER BY id LIMIT 1) u;
DO $check$ BEGIN
 BEGIN PERFORM id FROM flowdesk.task_status_activities; RAISE EXCEPTION 'Unexpected activity SELECT'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.task_status_activities SET to_status='TODO' WHERE false; RAISE EXCEPTION 'Unexpected activity UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.task_status_activities WHERE false; RAISE EXCEPTION 'Unexpected activity DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN TRUNCATE flowdesk.task_status_activities; RAISE EXCEPTION 'Unexpected activity TRUNCATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM nextval('flowdesk.task_status_activities_id_seq'); RAISE EXCEPTION 'Unexpected activity sequence'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
RESET ROLE;
DO $check$ BEGIN
 IF (SELECT count(*) FROM flowdesk.task_status_activities)<>1 THEN RAISE EXCEPTION 'Missing activity'; END IF;
END $check$;
ROLLBACK;
'@
        [void](Sql $statusProbe)
        Assert-Check ((Sql $snapshotSql) -ceq $beforeStatus -and (Sql 'SELECT count(*) FROM flowdesk.task_status_activities') -eq '0') 'status probe rollback preserves rows and migration history'
        Assert-Check ((Sql "SELECT count(*)=6 AND bool_and(attname IN ('task_id','actor_user_id','from_status','to_status','task_version','occurred_at')) FROM pg_attribute WHERE attrelid='flowdesk.task_status_activities'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'INSERT')") -eq 't') 'activity INSERT exactly six columns'
        [void](Sql 'REVOKE INSERT(occurred_at) ON flowdesk.task_status_activities FROM flowdesk_app')
        [void](Run 'provision-task-status-change.ps1' @() $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.task_status_activities','occurred_at','INSERT')") -eq 't') 'partial status policy refused without repair'
        [void](Sql 'GRANT INSERT(occurred_at) ON flowdesk.task_status_activities TO flowdesk_app')
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'status tool preserves application credential file'
    }
    if ($IncludeTaskStatusHistoryRead) {
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        $beforeHistory = Sql $snapshotSql
        Assert-Check ((Run 'provision-task-status-history-read.ps1') -match 'TASK_STATUS_HISTORY_READ') 'history read grants exactly seven columns'
        Assert-Check ((Run 'provision-task-status-history-read.ps1') -match 'TASK_STATUS_HISTORY_READ') 'history read repeats without password rotation'
        foreach ($legacy in @('provision-app-read.ps1','provision-workspace-create.ps1','provision-workspace-rename.ps1','provision-project-create.ps1','provision-project-update.ps1','provision-task-create.ps1','provision-task-update.ps1','provision-task-status-change.ps1')) {
            Assert-Check ((Run $legacy) -match 'TASK_STATUS_HISTORY_READ') "legacy $legacy preserves history read state"
        }
        Assert-Check ((Sql "SELECT count(*)=7 AND bool_and(attname IN ('id','task_id','actor_user_id','from_status','to_status','task_version','occurred_at')) FROM pg_attribute WHERE attrelid='flowdesk.task_status_activities'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'SELECT')") -eq 't') 'history SELECT has exactly seven effective columns'
        Assert-Check ((Sql "SELECT NOT has_table_privilege('flowdesk_app','flowdesk.task_status_activities','SELECT') AND NOT has_any_column_privilege('flowdesk_app','flowdesk.task_status_activities','UPDATE,REFERENCES') AND NOT has_sequence_privilege('flowdesk_app','flowdesk.task_status_activities_id_seq','USAGE,SELECT,UPDATE')") -eq 't') 'history read has no table-wide SELECT, audit mutation or sequence grants'
        $readProbe = @'
BEGIN;
SET ROLE flowdesk_app;
INSERT INTO flowdesk.projects(workspace_id,name) SELECT id,'History read probe' FROM flowdesk.workspaces ORDER BY id LIMIT 1;
INSERT INTO flowdesk.tasks(project_id,title) SELECT id,'History read probe' FROM flowdesk.projects ORDER BY id LIMIT 1;
UPDATE flowdesk.tasks SET status='DONE',updated_at=CURRENT_TIMESTAMP,version=version+1;
INSERT INTO flowdesk.task_status_activities(task_id,actor_user_id,from_status,to_status,task_version,occurred_at)
 SELECT t.id,u.id,'TODO',t.status,t.version,t.updated_at FROM flowdesk.tasks t CROSS JOIN LATERAL (SELECT id FROM flowdesk.users ORDER BY id LIMIT 1) u;
DO $check$ BEGIN
 IF (SELECT count(*) FROM flowdesk.task_status_activities a JOIN flowdesk.users u ON u.id=a.actor_user_id WHERE a.to_status='DONE' AND u.display_name IS NOT NULL)<>1 THEN RAISE EXCEPTION 'Missing readable history'; END IF;
 BEGIN UPDATE flowdesk.task_status_activities SET to_status='TODO' WHERE false; RAISE EXCEPTION 'Unexpected history UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.task_status_activities WHERE false; RAISE EXCEPTION 'Unexpected history DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM nextval('flowdesk.task_status_activities_id_seq'); RAISE EXCEPTION 'Unexpected history sequence'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
ROLLBACK;
'@
        [void](Sql $readProbe)
        Assert-Check ((Sql $snapshotSql) -ceq $beforeHistory -and (Sql 'SELECT count(*) FROM flowdesk.task_status_activities') -eq '0' -and (Sql 'SELECT count(*) FROM flowdesk.tasks') -eq '0' -and (Sql 'SELECT count(*) FROM flowdesk.projects') -eq '0') 'history read/status INSERT probe rolls back all business rows'
        [void](Sql 'REVOKE SELECT(occurred_at) ON flowdesk.task_status_activities FROM flowdesk_app')
        [void](Run 'provision-task-status-history-read.ps1' @() $false)
        [void](Run 'provision-task-status-change.ps1' @() $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.task_status_activities','occurred_at','SELECT')") -eq 't') 'partial history SELECT refused without repair'
        [void](Sql 'GRANT SELECT(occurred_at) ON flowdesk.task_status_activities TO flowdesk_app')
        [void](Sql 'GRANT SELECT(id) ON flowdesk.task_status_activities TO PUBLIC')
        [void](Run 'provision-task-status-history-read.ps1' @() $false)
        Assert-Check ((Sql "SELECT EXISTS(SELECT 1 FROM pg_attribute t,LATERAL aclexplode(t.attacl) a WHERE t.attrelid='flowdesk.task_status_activities'::regclass AND a.grantee=0)") -eq 't') 'PUBLIC column SELECT refused without automatic revocation'
        [void](Sql 'REVOKE SELECT(id) ON flowdesk.task_status_activities FROM PUBLIC')
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'history read preserves application credential file'
    }
    if ($IncludeTaskAssigneeDueDate) {
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        Assert-Check ((Run 'provision-task-assignee-due-date.ps1') -match 'TASK_ASSIGNEE_DUE_DATE') 'assignment/date access grants only approved Task columns'
        Assert-Check ((Run 'provision-task-assignee-due-date.ps1') -match 'TASK_ASSIGNEE_DUE_DATE') 'assignment/date access repeats idempotently'
        Assert-Check ((Sql "SELECT NOT has_table_privilege('flowdesk_app','flowdesk.tasks','SELECT,INSERT') AND (SELECT count(*)=11 FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'SELECT')) AND (SELECT count(*)=8 FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'INSERT')) AND (SELECT count(*)=8 FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE'))") -eq 't') 'Task table access remains column-scoped'
        $assignmentProbe = @'
BEGIN;
SET ROLE flowdesk_app;
INSERT INTO flowdesk.projects(workspace_id,name) SELECT id,'Assignment/date probe' FROM flowdesk.workspaces ORDER BY id LIMIT 1;
INSERT INTO flowdesk.tasks(project_id,title,assignee_id,due_date) SELECT p.id,'Assigned',u.id,DATE '2027-04-05' FROM flowdesk.projects p CROSS JOIN LATERAL (SELECT id FROM flowdesk.users ORDER BY id LIMIT 1) u ORDER BY p.id DESC LIMIT 1;
UPDATE flowdesk.tasks SET assignee_id=NULL,due_date=DATE '2027-05-06';
DO $check$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM flowdesk.tasks WHERE title='Assigned' AND assignee_id IS NULL AND due_date=DATE '2027-05-06') THEN RAISE EXCEPTION 'Task assignment/date UPDATE failed'; END IF;
 BEGIN UPDATE flowdesk.tasks SET project_id=project_id WHERE false; RAISE EXCEPTION 'Unexpected parent UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.tasks WHERE false; RAISE EXCEPTION 'Unexpected Task DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
RESET ROLE;
ROLLBACK;
'@
        [void](Sql $assignmentProbe)
        Assert-Check ((Sql 'SELECT count(*) FROM flowdesk.tasks') -eq '0') 'assignment/date privilege probe rolls back'
        [void](Sql 'REVOKE UPDATE(due_date) ON flowdesk.tasks FROM flowdesk_app')
        [void](Run 'provision-task-assignee-due-date.ps1' @() $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.tasks','due_date','UPDATE')") -eq 't') 'partial assignment update grant is not repaired'
        [void](Sql 'GRANT UPDATE(due_date) ON flowdesk.tasks TO flowdesk_app')
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'assignment/date tool preserves app credentials'
    }
    if ($IncludeWorkspaceMemberListRead) {
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        Assert-Check ((Run 'provision-workspace-member-list-read.ps1') -match 'workspace-member-list-read') 'member-list policy replaces broad reads with exact mapped-column SELECT'
        Assert-Check ((Run 'provision-workspace-member-list-read.ps1') -match 'workspace-member-list-read') 'member-list SELECT policy repeats idempotently'
        Assert-Check ((Sql "SELECT NOT has_table_privilege('flowdesk_app','flowdesk.users','SELECT') AND NOT has_table_privilege('flowdesk_app','flowdesk.workspaces','SELECT') AND NOT has_table_privilege('flowdesk_app','flowdesk.workspace_memberships','SELECT') AND (SELECT count(*)=16 FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid WHERE c.relname IN ('users','workspaces','workspace_memberships') AND a.attnum>0 AND NOT a.attisdropped AND has_column_privilege('flowdesk_app',c.oid,a.attnum,'SELECT'))") -eq 't') 'identity/workspace reads use 16 explicit columns and no table-wide SELECT'
        [void](Sql "SET ROLE flowdesk_app; SELECT u.id,u.display_name,m.role FROM flowdesk.workspace_memberships m JOIN flowdesk.users u ON u.id=m.user_id WHERE m.workspace_id=1 ORDER BY u.id; RESET ROLE")
        Assert-Check $true 'app can execute member projection using assigned column grants'
        [void](Sql 'REVOKE SELECT(display_name) ON flowdesk.users FROM flowdesk_app')
        [void](Run 'provision-workspace-member-list-read.ps1' @() $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.users','display_name','SELECT')") -eq 't') 'partial member-list SELECT is rejected without repair'
        [void](Sql 'GRANT SELECT(display_name) ON flowdesk.users TO flowdesk_app')
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'member-list tool preserves app credential file'
    }
    if ($IncludeWorkspaceMembershipManagement) {
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        Assert-Check ((Run 'provision-workspace-membership-management.ps1') -match 'workspace-membership-management') 'membership-management tool grants its exact policy'
        Assert-Check ((Run 'provision-workspace-membership-management.ps1') -match 'workspace-membership-management') 'membership-management grant repeats idempotently'
        $privileges = Sql "SELECT has_table_privilege('flowdesk_app','flowdesk.workspace_memberships','DELETE') AND NOT has_table_privilege('flowdesk_app','flowdesk.workspace_memberships','UPDATE') AND NOT has_table_privilege('flowdesk_app','flowdesk.workspace_invitations','SELECT,INSERT,UPDATE,DELETE') AND (SELECT count(*)=2 FROM pg_attribute WHERE attrelid='flowdesk.workspace_memberships'::regclass AND attname IN ('role','updated_at') AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE')) AND (SELECT count(*)=10 FROM pg_attribute WHERE attrelid='flowdesk.workspace_invitations'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'SELECT')) AND (SELECT count(*)=8 FROM pg_attribute WHERE attrelid='flowdesk.workspace_invitations'::regclass AND attname IN ('workspace_id','target_user_id','invited_by_user_id','role','token_digest','status','expires_at','accepted_at') AND has_column_privilege('flowdesk_app',attrelid,attnum,'INSERT')) AND (SELECT count(*)=2 FROM pg_attribute WHERE attrelid='flowdesk.workspace_invitations'::regclass AND attname IN ('status','accepted_at') AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE'))"
        Assert-Check ($privileges -eq 't') 'membership-management privileges are column/table scoped'
        $membershipProbe = @'
BEGIN;
SET ROLE flowdesk_app;
INSERT INTO flowdesk.workspace_invitations(workspace_id,target_user_id,invited_by_user_id,role,token_digest,status,expires_at,accepted_at)
SELECT w.id,u.id,o.id,'MEMBER',repeat('a',64),'PENDING',CURRENT_TIMESTAMP+INTERVAL '7 days',NULL
FROM flowdesk.workspaces w JOIN flowdesk.workspace_memberships o ON o.workspace_id=w.id AND o.role='OWNER'
JOIN flowdesk.users u ON u.id<>o.user_id ORDER BY w.id,u.id LIMIT 1;
UPDATE flowdesk.workspace_invitations SET status='REVOKED' WHERE token_digest=repeat('a',64);
UPDATE flowdesk.workspace_memberships SET role='VIEWER',updated_at=CURRENT_TIMESTAMP WHERE role='ADMIN';
DELETE FROM flowdesk.workspace_memberships WHERE role='VIEWER' AND user_id=-1;
DO $check$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM flowdesk.workspace_invitations WHERE token_digest=repeat('a',64) AND status='REVOKED') THEN RAISE EXCEPTION 'invite DML probe failed'; END IF;
 BEGIN UPDATE flowdesk.workspace_memberships SET user_id=user_id WHERE false; RAISE EXCEPTION 'unexpected membership-column UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.workspace_invitations WHERE false; RAISE EXCEPTION 'unexpected invitation DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN UPDATE flowdesk.workspace_invitations SET role=role WHERE false; RAISE EXCEPTION 'unexpected invitation role UPDATE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN DELETE FROM flowdesk.tasks WHERE false; RAISE EXCEPTION 'unexpected Task DELETE'; EXCEPTION WHEN insufficient_privilege THEN NULL; END;
END $check$;
RESET ROLE;
ROLLBACK;
'@
        [void](Sql $membershipProbe)
        Assert-Check ((Sql "SELECT count(*) FROM flowdesk.workspace_invitations WHERE token_digest=repeat('a',64)") -eq '0') 'membership privilege probe rolls back'
        [void](Sql 'REVOKE UPDATE(accepted_at) ON flowdesk.workspace_invitations FROM flowdesk_app')
        [void](Run 'provision-workspace-membership-management.ps1' @() $false)
        Assert-Check ((Sql "SELECT NOT has_column_privilege('flowdesk_app','flowdesk.workspace_invitations','accepted_at','UPDATE')") -eq 't') 'partial invitation UPDATE is rejected without repair'
        [void](Sql 'GRANT UPDATE(accepted_at) ON flowdesk.workspace_invitations TO flowdesk_app')
        Assert-Check ((Run 'provision-task-assignee-due-date.ps1') -match 'WORKSPACE_MEMBERSHIP_MANAGEMENT') 'earlier app permission tool preserves membership-management grants'
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'membership-management tool preserves app credential file'
    }
    if ($IncludeTaskArchive) {
        $appBefore = [IO.File]::ReadAllText($testAppEnv)
        Assert-Check ((Run 'provision-task-archive.ps1') -match 'TASK_ARCHIVE') 'Task archive grants only archived_at UPDATE'
        Assert-Check ((Run 'provision-task-archive.ps1') -match 'TASK_ARCHIVE') 'Task archive grant repeats idempotently'
        Assert-Check ((Sql "SELECT has_column_privilege('flowdesk_app','flowdesk.tasks','archived_at','SELECT') AND has_column_privilege('flowdesk_app','flowdesk.tasks','archived_at','UPDATE') AND NOT has_table_privilege('flowdesk_app','flowdesk.tasks','UPDATE') AND (SELECT count(*)=9 FROM pg_attribute WHERE attrelid='flowdesk.tasks'::regclass AND attnum>0 AND NOT attisdropped AND has_column_privilege('flowdesk_app',attrelid,attnum,'UPDATE'))") -eq 't') 'Task archive UPDATE remains column-scoped'
        Assert-Check ((Run 'provision-task-assignee-due-date.ps1') -match 'TASK_ARCHIVE') 'earlier Task permission tool preserves Task archive grant'
        [void](Run 'migrate-local.ps1' @('-Action','Validate'))
        [void](Run 'seed-local.ps1' @('-Action','Verify'))
        Assert-Check ([IO.File]::ReadAllText($testAppEnv) -ceq $appBefore) 'Task archive tool preserves app credential file'
    }
    $afterEnvironment = [Environment]::GetEnvironmentVariables('Process')
    $unchanged = $parentEnvironment.Count -eq $afterEnvironment.Count
    foreach ($key in $parentEnvironment.Keys) { if ($parentEnvironment[$key] -cne $afterEnvironment[$key]) { $unchanged = $false } }
    Assert-Check $unchanged 'parent process environment unchanged'
    $suite = if ($IncludeTaskArchive) { 'Migration, seed, all prior permissions and Task archive' } elseif ($IncludeWorkspaceMembershipManagement) { 'Migration, seed, all prior permissions and Workspace-membership-management' } elseif ($IncludeWorkspaceMemberListRead) { 'Migration, seed, prior permissions and Workspace-member-list-read' } elseif ($IncludeTaskAssigneeDueDate) { 'Migration, seed, prior permissions and Task-assignee/due-date' } elseif ($IncludeTaskStatusHistoryRead) { 'Migration, seed, all prior permission states and Task-status-history-read' } elseif ($IncludeTaskStatusChange) { 'Migration, seed, all prior permission states and Task-status-change' } elseif ($IncludeTaskUpdate) { 'Migration, seed, all prior permission states and Task-update' } elseif ($IncludeTaskCreate) { 'Migration, seed, all prior permission states and Task-create' } elseif ($IncludeProjectArchive) { 'Migration, seed, prior permissions and Project archive' } elseif ($IncludeProjectUpdate) { 'Migration, seed, read, create, rename, Project create/update' } elseif ($IncludeProjectCreate) { 'Migration, seed, read, create, rename and Project-create' } elseif ($IncludeWorkspaceRename) { 'Migration, seed, read, create and workspace-rename' } elseif ($IncludeWorkspaceCreate) { 'Migration, seed, read and workspace-create' } elseif ($IncludeReadAccess) { 'Migration, seed and read-access' } elseif ($IncludeSeed) { 'Migration and seed' } else { 'Migration' }
    Write-Output "$suite verification passed: $checks checks. All child outputs scanned for supplied passwords."
} finally {
    # Only the unique project created above is removed; no shared/local volume is addressed.
    & docker @composeArgs down --volumes --remove-orphans
    if ($LASTEXITCODE -ne 0) { Write-Warning "Could not clean isolated project $project" }
    foreach ($secretFile in @($envPath,$credentialPath,$testAppEnv)) {
        if (Test-Path -LiteralPath $secretFile) { [IO.File]::Delete($secretFile) }
    }
}
