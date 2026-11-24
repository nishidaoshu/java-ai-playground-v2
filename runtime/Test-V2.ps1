param([ValidateSet('backend','sample','full')][string]$Mode='backend',[string]$Ids='',[string]$Report='')
. (Join-Path $PSScriptRoot 'Environment.ps1')
Push-Location $projectRoot
try{
 if($Mode -eq 'backend'){
  & $python (Join-Path $runtimeRoot 'prepare_test_db.py');if($LASTEXITCODE -ne 0){throw 'Cannot prepare V2 test DB'}
  & mvn -B -ntp -gs (Join-Path $runtimeRoot 'maven-global-settings.xml') -s (Join-Path $runtimeRoot 'maven-settings.xml') test
  if($LASTEXITCODE -ne 0){throw 'V2 deterministic tests failed'}
 }else{
  if(-not $Report){$Report=Join-Path $projectRoot ('evaluation\results\'+$Mode+'-'+(Get-Date -Format 'yyyyMMdd-HHmmss')+'.json')}
  $evalArguments=@('evaluation/run_live.py','--mode',$Mode,'--output',$Report);if($Ids){$evalArguments+=@('--ids',$Ids)}
  & $python @evalArguments;$evaluationExit=$LASTEXITCODE
  if(Test-Path -LiteralPath $Report){& $python evaluation/cleanup_fixtures.py $Report;if($LASTEXITCODE -ne 0){throw 'Exact-manifest fixture cleanup failed'}}
  if($evaluationExit -ne 0){throw 'Evaluation contains failures; inspect the actual JSON report'}
 }
}finally{Pop-Location}
