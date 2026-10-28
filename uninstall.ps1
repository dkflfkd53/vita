#Requires -Version 5.1
# 자동 시작 등록을 내리고 서버를 멈춘다. 쌓인 기록(%USERPROFILE%\.claude-worklog\worklog.db)은 지우지 않는다
#   powershell -ExecutionPolicy Bypass -File .\uninstall.ps1
$ErrorActionPreference = 'Continue'
Unregister-ScheduledTask -TaskName 'Vita' -Confirm:$false -ErrorAction SilentlyContinue
Remove-Item (Join-Path ([Environment]::GetFolderPath('Startup')) 'Vita.lnk') -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*worklog.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Write-Host "중지·등록 해제 완료. 기록은 $env:USERPROFILE\.claude-worklog 에 남아 있습니다"
