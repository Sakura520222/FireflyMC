param(
    [ValidateSet('base','chipped','fd','fd_mnd','cfb','backpacks','curios','equipment','apoth','psi','aether','deep_aether','twilight','cataclysm','maid','maid_tasks','irons','irons_backpacks','bumble','environment','travel_permissions','permissions','interaction','slabs','construction','trees','storage_dynamic','building_dynamic')]
    [string]$Profile = 'base'
)
$ErrorActionPreference = 'Stop'
$workspaceRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportRoot = Join-Path $workspaceRoot 'build/ai4-validation/adapter-runs'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$stamp = [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')
$logPath = Join-Path $reportRoot "$Profile-$stamp.log"
$jsonPath = Join-Path $reportRoot "$Profile-$stamp.json"
$modProfile = if ($Profile -eq 'base') { '' } else { $Profile }
Push-Location $workspaceRoot
try {
    $started = [DateTime]::UtcNow
    & .\gradlew.bat "-Pai4TestMods=$modProfile" runGameTestServer *> $logPath
    $code = $LASTEXITCODE
    $log = Get-Content -LiteralPath $logPath -Raw
    $completed = [regex]::Match($log, '(\d+) GAME TESTS COMPLETE IN ([0-9.]+) s')
    $passed = [regex]::Match($log, 'All (\d+) required tests passed')
    $state = if ($code -eq 0 -and $completed.Success -and $passed.Success) { 'PASSED' } else { 'FAILED' }
    $report = [ordered]@{profile=$Profile;status=$state;exitCode=$code;startedUtc=$started.ToString('o');
        elapsedSeconds=([DateTime]::UtcNow-$started).TotalSeconds;log=$logPath;
        totalTests=$(if ($completed.Success) { [int]$completed.Groups[1].Value } else { 0 });
        requiredPassed=$(if ($passed.Success) { [int]$passed.Groups[1].Value } else { 0 });
        testSeconds=$(if ($completed.Success) { [double]::Parse($completed.Groups[2].Value,[Globalization.CultureInfo]::InvariantCulture) } else { 0 });
        scope='Isolated headless GameTest with explicitly selected local JARs; no graphical client or full-pack claim.'}
    [System.IO.File]::WriteAllText($jsonPath, ($report | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $report | ConvertTo-Json -Compress | Write-Output
    if ($state -ne 'PASSED') { Get-Content -LiteralPath $logPath -Tail 24; throw "Adapter validation failed: $Profile; report: $jsonPath" }
} finally { Pop-Location }
