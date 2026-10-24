param(
  [string]$BackupRoot = $env:APP_BACKUP_ROOT,
  [string]$StoragePath = $env:APP_FILE_STORAGE,
  [string]$ReportPath = $env:APP_REPORT_STORAGE,
  [string]$DefaultsFile = $env:MYSQL_DEFAULTS_FILE,
  [string]$MysqldumpPath = $env:MYSQLDUMP_PATH,
  [string]$Database = "indus_hostel"
)

$ErrorActionPreference = "Stop"
foreach ($item in @(@("APP_BACKUP_ROOT",$BackupRoot),@("APP_FILE_STORAGE",$StoragePath),@("APP_REPORT_STORAGE",$ReportPath),@("MYSQL_DEFAULTS_FILE",$DefaultsFile),@("MYSQLDUMP_PATH",$MysqldumpPath))) {
  if ([string]::IsNullOrWhiteSpace($item[1])) { throw "Set $($item[0]) before running the backup." }
}
if (!(Test-Path -LiteralPath $DefaultsFile -PathType Leaf)) { throw "MySQL option file not found." }
if (!(Test-Path -LiteralPath $MysqldumpPath -PathType Leaf)) { throw "mysqldump executable not found." }
if (!(Test-Path -LiteralPath $StoragePath -PathType Container)) { throw "Private file storage directory not found." }
if (!(Test-Path -LiteralPath $ReportPath -PathType Container)) { throw "Report directory not found." }

$root = [System.IO.Path]::GetFullPath($BackupRoot)
$null = New-Item -ItemType Directory -Path $root -Force
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$snapshot = [System.IO.Path]::GetFullPath((Join-Path $root "indus-hostel-$stamp"))
if (!$snapshot.StartsWith($root,[System.StringComparison]::OrdinalIgnoreCase)) { throw "Snapshot path escaped the backup destination." }
$null = New-Item -ItemType Directory -Path $snapshot

$dumpPath = Join-Path $snapshot "database.sql"
& $MysqldumpPath "--defaults-extra-file=$DefaultsFile" --single-transaction --routines --events --triggers --databases $Database "--result-file=$dumpPath"
if ($LASTEXITCODE -ne 0) { throw "mysqldump failed with exit code $LASTEXITCODE." }
Copy-Item -LiteralPath $StoragePath -Destination (Join-Path $snapshot "storage") -Recurse
Copy-Item -LiteralPath $ReportPath -Destination (Join-Path $snapshot "reports") -Recurse
Get-FileHash -LiteralPath $dumpPath -Algorithm SHA256 | Export-Csv -NoTypeInformation -Path (Join-Path $snapshot "database.sha256.csv")
Write-Output "Backup snapshot created: $snapshot"
