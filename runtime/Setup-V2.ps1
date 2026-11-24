param([string]$Workspace='D:\.work\jianli')
$ErrorActionPreference='Stop'
$source=Join-Path $Workspace 'java-ai-playground-v2\runtime'
$destination=Join-Path $Workspace '.java-ai-runtime-v2'
New-Item -ItemType Directory -Path $destination -Force|Out-Null
foreach($name in @('Environment.ps1','Start-V2.ps1','Stop-V2.ps1','Test-V2.ps1','Show-DemoAccounts.ps1','prepare_test_db.py','embedding_server.py','maven-settings.xml','maven-global-settings.xml')){Copy-Item -LiteralPath (Join-Path $source $name) -Destination $destination -Force}
if(-not(Test-Path (Join-Path $destination 'local.env'))){
 $bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
 (Get-Content (Join-Path $source 'config.example.env')).Replace('replace-with-random-local-password',[Convert]::ToBase64String($bytes))|Set-Content (Join-Path $destination 'local.env')
}
if(-not(Test-Path (Join-Path $destination 'mcp-secrets.dpapi'))){
 $bytes=New-Object byte[] 32;[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes);$serviceSecret=[Convert]::ToBase64String($bytes);[Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
 @{serviceToken=$serviceSecret;signingKey=[Convert]::ToBase64String($bytes)}|ConvertTo-Json -Compress|ConvertTo-SecureString -AsPlainText -Force|ConvertFrom-SecureString|Set-Content (Join-Path $destination 'mcp-secrets.dpapi')
}
Write-Host 'V2 runtime prepared. Existing configuration, credentials and data were preserved.'
