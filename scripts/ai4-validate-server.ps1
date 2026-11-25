param(
    [ValidateSet('dedicated-enabled', 'dedicated-disabled', 'dedicated-missing', 'dedicated-construction', 'dedicated-equipment', 'dedicated-special', 'dedicated-advanced', 'dedicated-entities', 'dedicated-fluids', 'dedicated-environment', 'dedicated-permissions', 'dedicated-persistence', 'dedicated-shutdown', 'dedicated-fluid-storage', 'dedicated-fluid-processing', 'dedicated-processing-callbacks', 'dedicated-performance', 'dedicated-maid-tasks', 'dedicated-maid-transport', 'dedicated-maid-combat', 'dedicated-tree-harvest', 'dedicated-waystones-travel', 'dedicated-exploration', 'dedicated-storage-dynamic', 'dedicated-craftable')]
    [string]$Scenario = 'dedicated-enabled',
    [string]$ModProfile = '',
    [ValidateSet('', 'prepare', 'verify')][string]$RestartPhase = '',
    [string]$RestartRun = '',
    [switch]$PrepareOnly,
    [switch]$ConstructionOwnershipOnly,
    [switch]$ConstructionClosureOnly,
    [switch]$ExplorationPreparedOnly
)
$ErrorActionPreference = 'Stop'
if ($ConstructionOwnershipOnly -and $Scenario -ne 'dedicated-construction') {
    throw 'Ownership-only validation applies only to the construction scenario'
}
if ($ConstructionClosureOnly -and ($Scenario -ne 'dedicated-construction' -or $ConstructionOwnershipOnly)) {
    throw 'Construction closure needs its own construction scenario run'
}
if ($ExplorationPreparedOnly -and $Scenario -ne 'dedicated-exploration') {
    throw 'Prepared exploration validation applies only to its exploration scenario'
}
if ($Scenario -eq 'dedicated-shutdown') {
    if ($RestartPhase -notin @('prepare','verify')) { throw 'Shutdown validation needs an explicit phase' }
    $RestartRun = ([Guid]::Parse($RestartRun)).ToString()
} elseif ($RestartPhase -or $RestartRun) { throw 'Restart parameters apply only to the shutdown scenario' }
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$validationRoot = [System.IO.Path]::GetFullPath((Join-Path $workspaceRoot 'build/ai4-validation'))
if ($ModProfile -notmatch '^[a-z_]*$') { throw 'Invalid isolated mod profile' }
$caseName = if ($ModProfile) { "$Scenario-$ModProfile" } else { $Scenario }
$gameDirectory = [System.IO.Path]::GetFullPath((Join-Path $validationRoot "game/$caseName"))
if (-not $gameDirectory.StartsWith($validationRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Validation path escaped the workspace'
}
$ancestor = $gameDirectory
while ($ancestor.StartsWith($workspaceRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    $entry = Get-Item -LiteralPath $ancestor -Force -ErrorAction SilentlyContinue
    if ($entry -and ($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        throw 'Validation paths must not contain symbolic links or junctions'
    }
    if ($ancestor -eq $workspaceRoot) { break }
    $ancestor = [System.IO.Path]::GetDirectoryName($ancestor)
}
function Write-NewFixture([string]$Path, [string]$Text) {
    $entry = Get-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
    if ($entry -and ($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint)) { throw 'Unsafe fixture link' }
    if (-not (Test-Path -LiteralPath $Path)) {
        [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
    }
}
New-Item -ItemType Directory -Path (Join-Path $gameDirectory 'config') -Force | Out-Null
Write-NewFixture (Join-Path $gameDirectory 'eula.txt') "eula=true`n"
Write-NewFixture (Join-Path $gameDirectory 'server.properties') @'
server-ip=127.0.0.1
server-port=25791
online-mode=false
level-name=validation-world
level-type=minecraft:flat
level-seed=481920
view-distance=3
simulation-distance=3
spawn-protection=0
max-players=2
enable-rcon=false
enable-query=false
enable-status=false
motd=FireflyMC isolated validation
'@
$aiConfiguration = Join-Path $gameDirectory 'config/fireflymc-ai4.json'
if ($Scenario -eq 'dedicated-missing') {
    if (Test-Path -LiteralPath $aiConfiguration) { throw 'Missing-config scenario already contains a configuration; use a fresh isolated directory' }
} elseif ($Scenario -in @('dedicated-disabled', 'dedicated-construction', 'dedicated-equipment', 'dedicated-special', 'dedicated-advanced', 'dedicated-entities', 'dedicated-fluids', 'dedicated-environment', 'dedicated-permissions', 'dedicated-persistence', 'dedicated-fluid-storage', 'dedicated-fluid-processing', 'dedicated-processing-callbacks', 'dedicated-performance', 'dedicated-maid-tasks', 'dedicated-maid-combat', 'dedicated-tree-harvest', 'dedicated-waystones-travel', 'dedicated-exploration', 'dedicated-storage-dynamic', 'dedicated-craftable')) {
    Write-NewFixture $aiConfiguration '{"schemaVersion":1,"enabled":false}'
} else {
    # This initialization-only fixture never submits a model request. It does not read any real key.
    Write-NewFixture $aiConfiguration @'
{"schemaVersion":1,"enabled":true,"model":{"endpoint":"https://validation.invalid","apiKey":"isolated-no-model-requests","name":"initialization-only","protocol":"chat_completions"},"sharing":{"crossChatEnabled":false}}
'@
}
if ($PrepareOnly) {
    Write-Output "Prepared isolated scenario: $Scenario"
    return
}
$memory = Get-CimInstance Win32_OperatingSystem
if ($memory.FreePhysicalMemory -lt 1572864) { throw 'Less than 1.5 GiB free physical memory; isolated server not started' }
Push-Location $workspaceRoot
try {
    $started = [DateTime]::UtcNow
    & .\gradlew.bat '-Pai4Validation=true' "-Pai4Scenario=$Scenario" "-Pai4TestMods=$ModProfile" "-Pai4RestartPhase=$RestartPhase" "-Pai4RestartRun=$RestartRun" "-Pai4ConstructionOwnershipOnly=$($ConstructionOwnershipOnly.IsPresent.ToString().ToLowerInvariant())" "-Pai4ConstructionClosureOnly=$($ConstructionClosureOnly.IsPresent.ToString().ToLowerInvariant())" "-Pai4ExplorationPreparedOnly=$($ExplorationPreparedOnly.IsPresent.ToString().ToLowerInvariant())" runAi4ValidationServer
    if ($LASTEXITCODE -ne 0) { throw "Validation process failed with exit code $LASTEXITCODE" }
    $reportPath = Join-Path $validationRoot "reports/$caseName.json"
    if ((Get-Item -LiteralPath $reportPath).LastWriteTimeUtc -lt $started) { throw 'Validation report is stale' }
    $report = Get-Content -LiteralPath $reportPath -Raw | ConvertFrom-Json
    if ($report.status -ne 'PASSED') { throw "Validation reported $($report.status)" }
    Write-Output "Validated isolated scenario: $Scenario"
} finally { Pop-Location }
