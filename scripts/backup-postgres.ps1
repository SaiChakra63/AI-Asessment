[CmdletBinding()]
param(
    [string]$Database,
    [string]$DatabaseUser,
    [string]$OutputDirectory,
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

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot "backups\postgres"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputDirectory)) {
    $OutputDirectory = Join-Path $projectRoot $OutputDirectory
}

$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)

if ($ComposeService -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]*$') {
    throw "ComposeService contains unsupported characters."
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

# Confirm the database container is running before discovering its non-secret settings.
[void](Invoke-Compose -Quiet -Arguments @(
    "exec", "-T", $ComposeService, "pg_dump", "--version"
))

if ([string]::IsNullOrWhiteSpace($Database)) {
    $Database = Get-ContainerEnvironmentValue -Name "POSTGRES_DB"
}

if ([string]::IsNullOrWhiteSpace($DatabaseUser)) {
    $DatabaseUser = Get-ContainerEnvironmentValue -Name "POSTGRES_USER"
}

if ($Database -notmatch '^[A-Za-z_][A-Za-z0-9_-]{0,62}$') {
    throw "Database contains unsupported characters. Pass a simple PostgreSQL database name."
}

if ($DatabaseUser -notmatch '^[A-Za-z_][A-Za-z0-9_-]{0,62}$') {
    throw "DatabaseUser contains unsupported characters."
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

$createdAtUtc = [DateTime]::UtcNow
$backupId = "{0}-{1}" -f $createdAtUtc.ToString("yyyyMMddTHHmmssfffZ"), ([Guid]::NewGuid().ToString("N").Substring(0, 8))
$baseName = "{0}-{1}" -f $Database, $backupId
$containerDump = "/tmp/$baseName.dump.partial"
$containerVerificationCopy = "/tmp/$baseName.verify.dump"
$hostPartialPath = Join-Path $OutputDirectory "$baseName.dump.partial"
$finalPath = Join-Path $OutputDirectory "$baseName.dump"
$checksumPath = "$finalPath.sha256"
$manifestPath = "$finalPath.manifest.json"

if (Test-Path -LiteralPath $finalPath) {
    throw "Refusing to overwrite existing backup: $finalPath"
}

try {
    Write-Host "Creating PostgreSQL custom-format backup for database '$Database'..."
    [void](Invoke-Compose -Arguments @(
        "exec", "-T", $ComposeService,
        "pg_dump",
        "--username=$DatabaseUser",
        "--dbname=$Database",
        "--format=custom",
        "--compress=6",
        "--no-owner",
        "--no-privileges",
        "--file=$containerDump"
    ))

    # Copy the binary artifact as a file. Do not pipe a custom-format dump through PowerShell.
    [void](Invoke-Compose -Arguments @(
        "cp", "${ComposeService}:$containerDump", $hostPartialPath
    ))

    if (-not (Test-Path -LiteralPath $hostPartialPath -PathType Leaf)) {
        throw "Docker reported success, but the local partial backup was not created."
    }

    if ((Get-Item -LiteralPath $hostPartialPath).Length -le 0) {
        throw "The local partial backup is empty."
    }

    # Validate the exact host copy by copying it back under a different temporary name.
    [void](Invoke-Compose -Arguments @(
        "cp", $hostPartialPath, "${ComposeService}:$containerVerificationCopy"
    ))
    [void](Invoke-Compose -Quiet -Arguments @(
        "exec", "-T", $ComposeService, "pg_restore", "--list", $containerVerificationCopy
    ))

    Move-Item -LiteralPath $hostPartialPath -Destination $finalPath

    $file = Get-Item -LiteralPath $finalPath
    $sha256 = (Get-FileHash -LiteralPath $finalPath -Algorithm SHA256).Hash.ToLowerInvariant()
    "$sha256  $($file.Name)" | Set-Content -LiteralPath $checksumPath -Encoding UTF8

    $pgDumpVersion = Invoke-Compose -Quiet -Arguments @(
        "exec", "-T", $ComposeService, "pg_dump", "--version"
    )

    $manifest = [ordered]@{
        manifestVersion = 1
        backupId = $backupId
        createdAtUtc = $createdAtUtc.ToString("o")
        source = [ordered]@{
            database = $Database
            composeService = $ComposeService
        }
        artifact = [ordered]@{
            file = $file.Name
            format = "postgresql-custom"
            sizeBytes = $file.Length
            sha256 = $sha256
        }
        tooling = [ordered]@{
            pgDumpVersion = $pgDumpVersion
            validation = "pg_restore --list archive TOC readability; full restore drill required"
        }
        restorePolicy = [ordered]@{
            newDatabaseOnly = $true
            automaticCutover = $false
        }
    }

    $manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    Write-Host "Backup created with archive-TOC, size, and SHA-256 checks."
    Write-Host "  Artifact: $finalPath"
    Write-Host "  Checksum: $checksumPath"
    Write-Host "  Manifest: $manifestPath"
    Write-Host "A full isolated restore drill is still required to prove recoverability."
    Write-Host "No previous backups were deleted. Retention is an explicit operator decision."
}
finally {
    foreach ($containerPath in @($containerDump, $containerVerificationCopy)) {
        try {
            [void](Invoke-Compose -Quiet -Arguments @(
                "exec", "-T", $ComposeService, "rm", "-f", "--", $containerPath
            ))
        } catch {
            Write-Warning "Could not remove temporary container artifact $containerPath."
        }
    }

    if (Test-Path -LiteralPath $hostPartialPath -PathType Leaf) {
        Write-Warning "The incomplete .partial artifact was retained for investigation. It must not be used as a backup."
    }
}
