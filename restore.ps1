#Requires -Version 5.1
# DB 를 백업(%USERPROFILE%\.claude-worklog\backups\worklog-YYYY-MM-DD.db.gz)으로 되돌린다
#   powershell -ExecutionPolicy Bypass -File .\restore.ps1            → 목록에서 번호로 고름
#   powershell -ExecutionPolicy Bypass -File .\restore.ps1 <파일>     → 그 파일로
# 되돌리기 전 현재 DB 는 worklog.db.before-restore-<시각> 으로 남긴다. (macOS 의 암호화 사본 .enc 는 restore.sh 로)
param([string]$File)
$ErrorActionPreference = 'Stop'
$DataDir = Join-Path $env:USERPROFILE '.claude-worklog'
$Db = Join-Path $DataDir 'worklog.db'

if (-not $File) {
    $list = @(Get-ChildItem (Join-Path $DataDir 'backups') -Filter 'worklog-*.db.gz' -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
    if ($list.Count -eq 0) { throw '백업이 없습니다' }
    for ($i = 0; $i -lt $list.Count; $i++) { Write-Host ("{0,3}) {1}  {2:N0}KB" -f ($i + 1), $list[$i].Name, ($list[$i].Length / 1KB)) }
    $n = [int](Read-Host '번호')
    $File = $list[$n - 1].FullName
}
if (-not (Test-Path $File)) { throw "파일이 없습니다: $File" }

Stop-ScheduledTask -TaskName 'Vita' -ErrorAction SilentlyContinue
Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*worklog.jar*' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1

if (Test-Path $Db) { Copy-Item $Db "$Db.before-restore-$(Get-Date -Format yyyyMMdd-HHmmss)" }
Remove-Item "$Db-wal", "$Db-shm" -ErrorAction SilentlyContinue
$in = [IO.File]::OpenRead($File)
$gz = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
$out = [IO.File]::Create($Db)
$gz.CopyTo($out)
$out.Close(); $gz.Close(); $in.Close()
Write-Host "되돌림: $File"

Start-ScheduledTask -TaskName 'Vita' -ErrorAction SilentlyContinue
Write-Host '서버를 다시 띄웠습니다(작업 스케줄러에 없으면 .\install.ps1 을 다시 실행하세요)'
