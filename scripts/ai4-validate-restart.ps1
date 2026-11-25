param([string]$ModProfile = '')
$ErrorActionPreference = 'Stop'
if ($ModProfile -notmatch '^[a-z_]*$') { throw 'Invalid isolated mod profile' }
$workspaceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportRoot = Join-Path $workspaceRoot 'build/ai4-validation/restart-runs'
New-Item -ItemType Directory -Path $reportRoot -Force | Out-Null
$runId = [Guid]::NewGuid().ToString()
$result = [ordered]@{runId=$runId;profile=$ModProfile;status='RUNNING';startedUtc=[DateTime]::UtcNow.ToString('o');phases=@()}
$resultFile = Join-Path $reportRoot ($runId + '.json')
Push-Location $workspaceRoot
try {
    foreach ($phase in @('prepare','verify')) {
        $log = Join-Path $reportRoot ($runId + '-' + $phase + '.log')
        & (Join-Path $PSScriptRoot 'ai4-validate-server.ps1') -Scenario dedicated-shutdown -ModProfile $ModProfile -RestartPhase $phase -RestartRun $runId *> $log
        $name = 'dedicated-shutdown' + $(if ($ModProfile) { '-' + $ModProfile } else { '' })
        $report = Get-Content -LiteralPath (Join-Path $workspaceRoot "build/ai4-validation/reports/$name.json") -Raw | ConvertFrom-Json
        if ($report.restartRun -ne $runId -or $report.phase -ne $phase -or $report.status -ne 'PASSED') { throw 'Restart phase report did not match this run' }
        $result.phases += [ordered]@{phase=$phase;status=$report.status;archive=$report.archive;processId=$report.processId;vmStartedAt=$report.vmStartedAt;checks=$report.passedChecks.Count;log=$log}
    }
    if ($result.phases[0].processId -eq $result.phases[1].processId -and $result.phases[0].vmStartedAt -eq $result.phases[1].vmStartedAt) { throw 'The second phase did not use another JVM' }
    $result.status = 'PASSED'
} catch {
    $result.status = 'FAILED'; $result.error = $_.Exception.Message; throw
} finally {
    $result.finishedUtc = [DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText($resultFile, ($result | ConvertTo-Json -Depth 8), [Text.UTF8Encoding]::new($false))
    $result | ConvertTo-Json -Depth 8 -Compress | Write-Output
    Pop-Location
}
