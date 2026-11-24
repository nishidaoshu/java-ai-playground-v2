$ErrorActionPreference='Stop'
$runtimeRoot=$PSScriptRoot
$workspaceRoot=Split-Path -Parent $runtimeRoot
$projectRoot=Join-Path $workspaceRoot 'java-ai-playground-v2'
$env:JAVA_HOME=Join-Path $workspaceRoot '.java-ai-runtime\jdk-21.0.13.1+2'
$env:Path=(Join-Path $env:JAVA_HOME 'bin')+';D:\maven3.8.9\apache-maven-3.8.9\bin;'+$env:Path
$env:MAVEN_OPTS='-Dfile.encoding=UTF-8 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897'
$env:MAVEN_CONFIG='-gs "'+(Join-Path $runtimeRoot 'maven-global-settings.xml')+'" -s "'+(Join-Path $runtimeRoot 'maven-settings.xml')+'"'
$env:npm_config_proxy='http://127.0.0.1:7897'
$env:npm_config_https_proxy='http://127.0.0.1:7897'
$env:PYTHONUTF8='1'
$env:PYTHONIOENCODING='utf-8'
Remove-Item Env:\GIT_CONFIG_COUNT -ErrorAction SilentlyContinue
if(Test-Path (Join-Path $runtimeRoot 'local.env')){Get-Content (Join-Path $runtimeRoot 'local.env') | ForEach-Object {if($_ -match '^([A-Z_0-9]+)=(.*)$'){[Environment]::SetEnvironmentVariable($Matches[1],$Matches[2],'Process')}}}
$keyFile=Join-Path $workspaceRoot '.java-ai-runtime\deepseek-key.dpapi'
$env:DEEPSEEK_API_KEY=[Net.NetworkCredential]::new('',(ConvertTo-SecureString ([IO.File]::ReadAllText($keyFile).Trim()))).Password
$accountFile=Join-Path $workspaceRoot '.java-ai-runtime-v1\demo-accounts.dpapi'
$accountValues=([Net.NetworkCredential]::new('',(ConvertTo-SecureString ([IO.File]::ReadAllText($accountFile).Trim()))).Password | ConvertFrom-Json)
$env:DEMO_ALICE_PASSWORD=$accountValues.alice
$env:DEMO_BOB_PASSWORD=$accountValues.bob
$mcpValues=([Net.NetworkCredential]::new('',(ConvertTo-SecureString ([IO.File]::ReadAllText((Join-Path $runtimeRoot 'mcp-secrets.dpapi')).Trim()))).Password | ConvertFrom-Json)
$env:MCP_SERVICE_TOKEN=$mcpValues.serviceToken
$env:MCP_SIGNING_KEY=$mcpValues.signingKey
# Reuse Python packages and model files read-only, but own the V2 embedding process and port.
$python=Join-Path $workspaceRoot '.java-ai-runtime-v1\py310\Scripts\python.exe'
$modelPath=Join-Path $workspaceRoot '.java-ai-runtime-v1\models\bge-small-zh-v1.5'
$wslAddress=(((& wsl -d Ubuntu -- hostname -I 2>$null) -join ' ').Trim() -split '\s+')[0]
if($wslAddress -notmatch '^\d+\.\d+\.\d+\.\d+$'){throw 'Cannot resolve WSL IPv4'}
$env:DB_URL='jdbc:postgresql://'+$wslAddress+':'+$env:DB_PORT+'/java_ai_v2'
$env:TEST_DB_URL='jdbc:postgresql://'+$wslAddress+':'+$env:DB_PORT+'/java_ai_v2_test'
$env:EMBEDDING_BASE_URL='http://127.0.0.1:'+$env:EMBEDDING_PORT+'/v1'
$env:MCP_BASE_URL='http://127.0.0.1:'+$env:MCP_PORT
if($env:DB_USER -ne 'java_ai_v2' -or $env:DB_PORT -eq '15432'){throw 'Refusing unsafe V2 database configuration'}
