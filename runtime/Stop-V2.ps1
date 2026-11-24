param([switch]$Database,[ValidateSet('all','application','mcp','embedding')][string]$Component='all')
$ErrorActionPreference='Stop'
foreach($part in @('application','mcp','embedding')){
 if($Component -ne 'all' -and $Component -ne $part){continue}
 $file=Join-Path $PSScriptRoot ($part+'.pid');if(-not(Test-Path $file)){continue}
 $processId=[int](Get-Content $file)
 $process=Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
 if($process -and $process.CommandLine -like '*.java-ai-runtime-v2*'){Stop-Process -Id $processId -ErrorAction SilentlyContinue}
 Remove-Item -LiteralPath $file
}
if($Database){& wsl -d Ubuntu -- sh -lc 'cd /mnt/d/.work/jianli/java-ai-playground-v2 && docker compose --env-file ../.java-ai-runtime-v2/local.env stop'}
Write-Host 'Stopped requested V2 processes only; volumes and shared V1 resources preserved.'
