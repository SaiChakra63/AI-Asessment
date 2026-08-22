[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath,

    [Parameter(Mandatory = $true)]
    [string]$TargetDatabase,

    [string]$DatabaseUser,
    [string]$ComposeFile,
    [string]$ComposeService = "postgres"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot

if ([string]::IsNullOrWhiteSpace($ComposeFile)) {
    $ComposeFile = Join-Path $projectRoot "docker-compose.yml"
}

$ComposeFile = (Resolve-Path -LiteralPath $ComposeFile).Path
$BackupPath = (Resolve-Path -LiteralPath $BackupPath).Path

if (-not (Test-Path -LiteralPath $BackupPath -PathType Leaf)) {
    throw "BackupPath must identify a backup file."
}

if ($ComposeService -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]*$') {
    throw "ComposeService contains unsupported characters."
}

if ($TargetDatabase -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
    throw "TargetDatabase must be a simple PostgreSQL identifier (letters, digits, and underscores)."
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "The docker command was not found. Start Docker Desktop and ensure docker.exe is on PATH."
}

function Invoke-Compose {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments,
        [switch]$Quiet
    )

    $output = & docker compose -f $ComposeFile @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine).Trim()

    if ($exitCode -ne 0) {
        if ([string]::IsNullOrWhiteSpace($text)) {
            throw "docker compose failed with exit code $exitCode."
        }
        throw "docker compose failed with exit code $exitCode`: $text"
    }

    if (-not $Quiet -and -not [string]::IsNullOrWhiteSpace($text)) {
        Write-Host $text
    }

    return $text
}

function Get-ContainerEnvironmentValue {
    param([Parameter(Mandatory = $true)][string]$Name)

    $value = Invoke-Compose -Quiet -Arguments @(
        "exec", "-T", $ComposeService, "printenv", $Name
    )

    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "The $Name environment variable is not set in the $ComposeService container."
    }

    return $value.Trim()
}

$checksumPath = "$BackupPath.sha256"
$manifestPath = "$BackupPath.manifest.json"

if (-not (Test-Path -LiteralPath $checksumPath -PathType Leaf)) {
    throw "Checksum sidecar is missing: $checksumPath"
}

if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Backup manifest is missing: $manifestPath"
}

try {
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
} catch {
    throw "Backup manifest is not valid JSON: $manifestPath"
}

if ($manifest.manifestVersion -ne 1) {
    throw "Unsupported backup manifest version: $($manifest.manifestVersion)"
}

$backupFile = Get-Item -LiteralPath $BackupPath
$sourceDatabase = [string]$manifest.source.database
$manifestFileName = [string]$manifest.artifact.file
$manifestHash = ([string]$manifest.artifact.sha256).ToLowerInvariant()
$manifestSize = [Int64]$manifest.artifact.sizeBytes

if ([string]::IsNullOrWhiteSpace($sourceDatabase)) {
    throw "The manifest does not identify its source database."
}

if ($manifestFileName -ne $backupFile.Name) {
    throw "Manifest filename '$manifestFileName' does not match '$($backupFile.Name)'."
}

if ($manifestSize -ne $backupFile.Length) {
    throw "Backup size does not match the recorded manifest."
}

$sidecarText = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
$sidecarMatch = [regex]::Match($sidecarText, '^(?<hash>[A-Fa-f0-9]{64})\s+')
if (-not $sidecarMatch.Success) {
    throw "Checksum sidecar has an invalid format."
}

$sidecarHash = $sidecarMatch.Groups["hash"].Value.ToLowerInvariant()
$actualHash = (Get-FileHash -LiteralPath $BackupPath -Algorithm SHA256).Hash.ToLowerInvariant()

if ($actualHash -ne $sidecarHash -or $actualHash -ne $manifestHash) {
    throw "SHA-256 verification failed. The backup may be incomplete or modified."
}

if ($TargetDatabase.Equals($sourceDatabase, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to restore into the source database '$sourceDatabase'. Choose a new database name."
}

# Confirm the container is running before reading its non-secret user setting.
[void](Invoke-Compose -Quiet -Arguments @(
    "exec", "-T", $ComposeService, "pg_restore", "--version"
))

if ([string]::IsNullOrWhiteSpace($DatabaseUser)) {
    $DatabaseUser = Get-ContainerEnvironmentValue -Name "POSTGRES_USER"
}

if ($DatabaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]{0,62}$') {
    throw "DatabaseUser contains unsupported characters."
}

$databaseExistsQuery = "SELECT 1 FROM pg_database WHERE datname = '$TargetDatabase';"
$databaseExists = Invoke-Compose -Quiet -Arguments @(
    "exec", "-T", $ComposeService,
    "psql",
    "--username=$DatabaseUser",
    "--dbname=postgres",
    "--tuples-only",
    "--no-align",
    "--command=$databaseExistsQuery"
)

if (-not [string]::IsNullOrWhiteSpace($databaseExists) -and $databaseExists.Trim() -eq "1") {
    throw "Refusing to overwrite existing database '$TargetDatabase'. Choose a new target name."
}

$restoreId = [Guid]::NewGuid().ToString("N")
$containerBackup = "/tmp/url-shortener-restore-$restoreId.dump"
$databaseCreated = $false

try {
    # Copy and validate the exact local file. Binary backup bytes never pass through PowerShell's pipeline.
    [void](Invoke-Compose -Arguments @(
        "cp", $BackupPath, "${ComposeService}:$containerBackup"
    ))
    [void](Invoke-Compose -Quiet -Arguments @(
        "exec", "-T", $ComposeService, "pg_restore", "--list", $containerBackup
    ))

    Write-Host "Checksum and PostgreSQL archive validation passed."
    Write-Host "Creating new restore target '$TargetDatabase'..."
    [void](Invoke-Compose -Arguments @(
        "exec", "-T", $ComposeService,
        "createdb", "--username=$DatabaseUser", $TargetDatabase
    ))
    $databaseCreated = $true

    [void](Invoke-Compose -Arguments @(
        "exec", "-T", $ComposeService,
        "pg_restore",
        "--username=$DatabaseUser",
        "--dbname=$TargetDatabase",
        "--exit-on-error",
        "--single-transaction",
        "--no-owner",
        "--no-privileges",
        $containerBackup
    ))

    $publicTableCount = Invoke-Compose -Quiet -Arguments @(
        "exec", "-T", $ComposeService,
        "psql",
        "--username=$DatabaseUser",
        "--dbname=$TargetDatabase",
        "--tuples-only",
        "--no-align",
        "--command=SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public';"
    )

    Write-Host "Restore completed into the new database '$TargetDatabase'."
    Write-Host "Public tables discovered: $($publicTableCount.Trim())"
    Write-Host "No live-database cutover was attempted. Validate this database before any manual promotion."
}
catch {
    if ($databaseCreated) {
        Write-Warning "Restore did not complete. The new database '$TargetDatabase' was intentionally left in place for investigation; it was not deleted automatically."
    }
    throw
}
finally {
    try {
        [void](Invoke-Compose -Quiet -Arguments @(
            "exec", "-T", $ComposeService, "rm", "-f", "--", $containerBackup
        ))
    } catch {
        Write-Warning "Could not remove temporary container artifact $containerBackup."
    }
}
