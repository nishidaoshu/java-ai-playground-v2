param([switch]$Build,[switch]$SkipDatabase)
. (Join-Path $PSScriptRoot 'Environment.ps1')
if($Build -and ((Get-NetTCPConnection -LocalPort $env:APP_PORT -State Listen -ErrorAction SilentlyContinue) -or (Get-NetTCPConnection -LocalPort $env:MCP_PORT -State Listen -ErrorAction SilentlyContinue))){throw 'Stop V2 application and MCP before -Build so the running JAR cannot silently remain old.'}
if(-not(Test-Path (Join-Path $runtimeRoot 'wsl-keepalive.pid')) -or -not(Get-Process -Id ([int](Get-Content (Join-Path $runtimeRoot 'wsl-keepalive.pid'))) -ErrorAction SilentlyContinue)){
 $keep=Start-Process wsl.exe -ArgumentList @('-d','Ubuntu','--exec','tail','-f','/dev/null') -WindowStyle Hidden -PassThru -RedirectStandardError (Join-Path $runtimeRoot 'wsl-keepalive.log');$keep.Id|Set-Content (Join-Path $runtimeRoot 'wsl-keepalive.pid')
}
if(-not $SkipDatabase){& wsl -d Ubuntu -- sh -lc 'cd /mnt/d/.work/jianli/java-ai-playground-v2 && docker compose --env-file ../.java-ai-runtime-v2/local.env up -d --wait';if($LASTEXITCODE -ne 0){throw 'V2 database not healthy'}}
& $python (Join-Path $runtimeRoot 'prepare_test_db.py');if($LASTEXITCODE -ne 0){throw 'V2 test database preparation failed'}
$jar=Join-Path $projectRoot 'target\java-ai-playground-v2-2.0-SNAPSHOT.jar'
if($Build -or -not(Test-Path $jar)){
 Push-Location $projectRoot
 try{& mvn -B -ntp -gs (Join-Path $runtimeRoot 'maven-global-settings.xml') -s (Join-Path $runtimeRoot 'maven-settings.xml') -Pproduction -DskipTests package;if($LASTEXITCODE -ne 0){throw 'V2 production build failed'}}finally{Pop-Location}
}
function Start-Component($component,$command,$arguments,$port,$health,$expected){
 if(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue){
  if((Invoke-RestMethod $health -TimeoutSec 4).application -ne $expected){throw "V2 port $port occupied; change only V2 configuration"};return
 }
 $process=Start-Process $command -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeRoot ($component+'.log')) -RedirectStandardError (Join-Path $runtimeRoot ($component+'-error.log'))
 $process.Id|Set-Content (Join-Path $runtimeRoot ($component+'.pid'))
 for($attempt=0;$attempt -lt 120;$attempt++){
  if($process.HasExited){throw "$component exited; inspect V2 logs"}
  try{if((Invoke-RestMethod $health -TimeoutSec 2).application -eq $expected){return}}catch{}
  Start-Sleep -Milliseconds 500
 }
 throw "$component readiness timeout"
}
Start-Component 'embedding' $python ('"'+(Join-Path $runtimeRoot 'embedding_server.py')+'" --port '+$env:EMBEDDING_PORT+' --model-dir "'+$modelPath+'"') ([int]$env:EMBEDDING_PORT) ('http://127.0.0.1:'+$env:EMBEDDING_PORT+'/health') 'java-ai-v2-embedding'
$runtimeJar=Join-Path $runtimeRoot 'application.jar'
if(-not(Get-NetTCPConnection -LocalPort $env:APP_PORT -State Listen -ErrorAction SilentlyContinue) -and -not(Get-NetTCPConnection -LocalPort $env:MCP_PORT -State Listen -ErrorAction SilentlyContinue)){Copy-Item -LiteralPath $jar -Destination $runtimeJar -Force}
$java=Join-Path $env:JAVA_HOME 'bin\java.exe'
Start-Component 'mcp' $java ('-Dfile.encoding=UTF-8 -jar "'+$runtimeJar+'" --spring.profiles.active=mcp --server.port='+$env:MCP_PORT+' --app.demo.enabled=false') ([int]$env:MCP_PORT) ('http://127.0.0.1:'+$env:MCP_PORT+'/mcp-health') 'java-ai-v2-mcp'
Start-Component 'application' $java ('-Dfile.encoding=UTF-8 -jar "'+$runtimeJar+'"') ([int]$env:APP_PORT) ('http://127.0.0.1:'+$env:APP_PORT+'/api/health') 'java-ai-playground-v2'
Write-Host ('V2 running: http://127.0.0.1:'+$env:APP_PORT+'; MCP: '+$env:MCP_BASE_URL+'/mcp')
