# Windows PowerShell 5.1+; command-line mode also works with PowerShell 7.
[CmdletBinding()]
param(
    [string]$ProjectRoot = '',
    [string]$Timetable = '',
    [string]$Map = '',
    [string]$BusBundle = '',
    [switch]$NonInteractive
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Select-InputFile([string]$Title, [string]$Filter) {
    if ($NonInteractive) { throw "Missing input: $Title" }
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = New-Object System.Windows.Forms.OpenFileDialog
    $dialog.Title = $Title
    $dialog.Filter = $Filter
    try {
        if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { throw 'Cancelled; no files were changed.' }
        return $dialog.FileName
    } finally { $dialog.Dispose() }
}

function Test-ExactFile([string]$Path, [long]$Size, [string]$Hash) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    if ((Get-Item -LiteralPath $Path).Length -ne $Size) { return $false }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -eq $Hash
}

function Test-BusBundle([string]$Path, $Info) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $inputFile = $null; $gzip = $null; $sha = $null
    try {
        $inputFile = [System.IO.File]::OpenRead($Path)
        $gzip = New-Object System.IO.Compression.GZipStream($inputFile, [System.IO.Compression.CompressionMode]::Decompress)
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $buffer = New-Object byte[] 65536
        [long]$size = 0
        while (($read = $gzip.Read($buffer, 0, $buffer.Length)) -gt 0) {
            $size += $read
            if ($size -gt [long]$Info.size) { return $false }
            [void]$sha.TransformBlock($buffer, 0, $read, $buffer, 0)
        }
        [void]$sha.TransformFinalBlock((New-Object byte[] 0), 0, 0)
        $hash = [BitConverter]::ToString($sha.Hash).Replace('-', '')
        return $size -eq [long]$Info.size -and $hash -eq [string]$Info.sha256
    } catch { return $false }
    finally {
        if ($sha) { $sha.Dispose() }
        if ($gzip) { $gzip.Dispose() }
        if ($inputFile) { $inputFile.Dispose() }
    }
}

if (-not $ProjectRoot) {
    $parent = Split-Path -Parent $PSScriptRoot
    if (Test-Path -LiteralPath (Join-Path $parent 'app/src/main/assets/bootstrap/data-files.json')) { $ProjectRoot = $parent }
    elseif ($NonInteractive) { throw 'Specify -ProjectRoot.' }
    else {
        Add-Type -AssemblyName System.Windows.Forms
        $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
        $dialog.Description = 'Select the OfflineTransitMap project folder (contains app and gradlew.bat).'
        try {
            if ($dialog.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { throw 'Cancelled; no files were changed.' }
            $ProjectRoot = $dialog.SelectedPath
        } finally { $dialog.Dispose() }
    }
}
$ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
$assets = Join-Path $ProjectRoot 'app/src/main/assets'
$config = Get-Content -LiteralPath (Join-Path $assets 'bootstrap/data-files.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$busInfo = Get-Content -LiteralPath (Join-Path $assets 'bootstrap/keio-bus-info.json') -Raw -Encoding UTF8 | ConvertFrom-Json
$items = New-Object 'System.Collections.Generic.List[object]'
$seen = @{}
# Validate every source before copying anything. Keep the project's distribution profile intact.
foreach ($entry in $config.files) {
    if (-not $entry.PSObject.Properties['asset']) { continue }
    if ($seen.ContainsKey([string]$entry.id)) { throw 'Duplicate file in data-files.json.' }
    $seen[[string]$entry.id] = $true
    $name = switch ($entry.id) { 'timetable' { 'timetable.db' }; 'map' { 'tokyo.pmtiles' }; default { throw 'Unknown deployment file.' } }
    if ($entry.asset -ne "bootstrap/$name") { throw 'Unexpected asset path in data-files.json.' }
    $destination = Join-Path $assets $entry.asset
    if (Test-ExactFile $destination $entry.size $entry.sha256) { Write-Host "Already ready: $name"; continue }
    if ($entry.id -eq 'timetable') {
        $source = $Timetable
        if (-not $source) { $source = Select-InputFile 'Select the original timetable.db (JR / Nishi Tokyo Bus)' 'SQLite database (*.db)|*.db|All files (*.*)|*.*' }
        if ((Test-Path -LiteralPath "$source-wal") -and (Get-Item -LiteralPath "$source-wal").Length -gt 0) { throw 'The database has pending WAL writes. Close/checkpoint it before distributing.' }
    } else {
        $source = $Map
        if (-not $source) { $source = Select-InputFile 'Select the original PMTiles map' 'PMTiles (*.pmtiles)|*.pmtiles|All files (*.*)|*.*' }
    }
    if (-not (Test-ExactFile $source $entry.size $entry.sha256)) {
        throw "Size or SHA-256 mismatch: $name. Select the original shared file. To intentionally distribute another version, use tools/configure_data_files.py."
    }
    $items.Add([pscustomobject]@{ Source = (Resolve-Path -LiteralPath $source).Path; Destination = $destination; Name = $name; Hash = [string]$entry.sha256 })
}
$busTarget = Join-Path $assets 'bootstrap/keio-bus.bundle'
if (Test-BusBundle $busTarget $busInfo) { Write-Host 'Already ready: keio-bus.bundle' }
else {
    if (-not $BusBundle) {
        $packaged = Join-Path $PSScriptRoot 'keio-bus.bundle'
        if (Test-Path -LiteralPath $packaged -PathType Leaf) { $BusBundle = $packaged }
        else { $BusBundle = Select-InputFile 'Select the generated keio-bus.bundle from the setup ZIP' 'Keio bus bundle (*.bundle)|*.bundle' }
    }
    if (-not (Test-BusBundle $BusBundle $busInfo)) { throw 'Keio bus bundle does not match this project version. Use the matching setup ZIP or regenerate it with tools/build_keio_bus_data.py.' }
    $items.Add([pscustomobject]@{ Source = (Resolve-Path -LiteralPath $BusBundle).Path; Destination = $busTarget; Name = 'keio-bus.bundle'; Hash = (Get-FileHash -LiteralPath $BusBundle -Algorithm SHA256).Hash })
}
$backupRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) ('OfflineTransitMap/data-backups/' + [Guid]::NewGuid().ToString('N'))
foreach ($item in $items) {
    $temporary = $item.Destination + '.prepare-' + [Guid]::NewGuid().ToString('N')
    try {
        Copy-Item -LiteralPath $item.Source -Destination $temporary
        if ((Get-FileHash -LiteralPath $temporary -Algorithm SHA256).Hash -ne  $item.Hash) { throw "Copy verification failed: $($item.Name)" }
        if (Test-Path -LiteralPath $item.Destination) {
            [void](New-Item -ItemType Directory -Path $backupRoot -Force)
            $backup = Join-Path $backupRoot $item.Name
            Copy-Item -LiteralPath $item.Destination -Destination $backup
            Write-Host "Previous asset backed up: $backup"
        }
        Move-Item -LiteralPath $temporary -Destination $item.Destination -Force
        Write-Host "Prepared: $($item.Name)"
    } finally { if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Force } }
}
Write-Host 'Ready. Return to Android Studio and build again. No per-device data copy is required.'
