#Requires -Version 5.1
# Vita 설치(Windows) — 빌드 → %USERPROFILE%\.claude-worklog\app 에 복사 → 로그인하면 자동 시작(작업 스케줄러) → 지금 실행
#   powershell -ExecutionPolicy Bypass -File .\install.ps1
# 다시 실행하면 새 jar 로 교체·재시작. 지우려면 .\uninstall.ps1 (기록 DB 는 남긴다)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$TaskName = 'Vita'
$DataDir = Join-Path $env:USERPROFILE '.claude-worklog'
$AppDir = Join-Path $DataDir 'app'
$Jar = Join-Path $AppDir 'worklog.jar'

function Need($cmd, $hint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) { throw "$cmd 를 찾지 못했습니다 — $hint" }
}
Need java 'Java 21 을 설치하세요 (예: winget install EclipseAdoptium.Temurin.21.JDK) 후 새 터미널에서 다시 실행'
Need npm 'Node.js 20 이상을 설치하세요 (예: winget install OpenJS.NodeJS.LTS)'
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Write-Warning 'git 이 없어 커밋은 모으지 않습니다 (설치: winget install Git.Git)' }
# java -version 은 stderr 로 찍는다 — PowerShell 5.1 에서 2>&1 을 쓰면 오류로 멈추므로 cmd 로 받는다
$javaVersion = (cmd /c 'java -version 2>&1' | Select-Object -First 1)
if ($javaVersion -match 'version "(\d+)') { $major = [int]$Matches[1] } else { $major = 0 }
if ($major -lt 21) { throw "Java 21 이상이 필요합니다 (지금: $javaVersion)" }
$claude = (Get-Command claude -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if (-not $claude) { Write-Warning 'claude 를 찾지 못했습니다 — 수집·화면은 되지만 AI 요약·리포트는 Claude Code 설치·로그인 뒤에 동작합니다' }

Write-Host '1/4 프론트 빌드'
Push-Location front
npm install --no-audit --no-fund
if ($LASTEXITCODE -ne 0) { throw 'npm install 실패' }
npm run build
if ($LASTEXITCODE -ne 0) { throw '프론트 빌드 실패' }
Pop-Location

Write-Host '2/4 서버 빌드'
Push-Location back
.\gradlew.bat bootJar -q
if ($LASTEXITCODE -ne 0) { throw '서버 빌드 실패' }
Pop-Location

Write-Host '3/4 설치'
New-Item -ItemType Directory -Force -Path $AppDir, (Join-Path $DataDir 'logs') | Out-Null
# 돌고 있으면 내린다(jar 를 덮어쓰려면)
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*worklog.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1
$built = Get-ChildItem 'back\build\libs\worklog-back-*.jar' | Where-Object { $_.Name -notlike '*-plain.jar' } | Select-Object -First 1
Copy-Item $built.FullName $Jar -Force

Write-Host '4/4 자동 시작 등록'
$javaw = Join-Path (Split-Path (Get-Command java).Source) 'javaw.exe'
if (-not (Test-Path $javaw)) { $javaw = (Get-Command java).Source }
$argList = @('-Xmx512m', '-jar', "`"$Jar`"")
if ($claude) { $argList += "--worklog.claude-bin=`"$claude`"" }
$arguments = $argList -join ' '
try {
    $action = New-ScheduledTaskAction -Execute $javaw -Argument $arguments -WorkingDirectory $DataDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $principal = New-ScheduledTaskPrincipal -UserId $env:USERNAME -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings `
        -Description 'Vita — Claude Code 작업 기록 (http://127.0.0.1:4100)' -Force | Out-Null
    Start-ScheduledTask -TaskName $TaskName
} catch {
    # 작업 스케줄러 등록이 막힌 환경 — 시작 프로그램 폴더 바로가기로 대신하고 지금 바로 띄운다
    Write-Warning "작업 스케줄러 등록 실패($($_.Exception.Message)) — 시작 프로그램 바로가기로 대신합니다"
    $lnk = Join-Path ([Environment]::GetFolderPath('Startup')) 'Vita.lnk'
    $shell = New-Object -ComObject WScript.Shell
    $s = $shell.CreateShortcut($lnk)
    $s.TargetPath = $javaw
    $s.Arguments = $arguments
    $s.WorkingDirectory = $DataDir
    $s.Save()
    Start-Process -FilePath $javaw -ArgumentList $arguments -WorkingDirectory $DataDir -WindowStyle Hidden
}

for ($i = 0; $i -lt 60; $i++) {
    try {
        Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 'http://127.0.0.1:4100/api/jobs' | Out-Null
        Write-Host '실행 중: http://127.0.0.1:4100'
        exit 0
    } catch { Start-Sleep -Seconds 1 }
}
throw "서버가 60초 안에 뜨지 않았습니다 — $DataDir\logs\worklog.log 를 확인하세요"
