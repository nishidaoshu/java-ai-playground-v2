# Interactive local display only. Never redirect this output to logs or reports.
$file=Join-Path (Split-Path -Parent $PSScriptRoot) '.java-ai-runtime-v1\demo-accounts.dpapi'
$accounts=[Net.NetworkCredential]::new('',(ConvertTo-SecureString ([IO.File]::ReadAllText($file).Trim()))).Password|ConvertFrom-Json
Write-Host 'alice:' $accounts.alice
Write-Host 'bob:  ' $accounts.bob
