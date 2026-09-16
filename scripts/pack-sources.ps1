[CmdletBinding()]
param(
    # Where the single archive is written. Relative paths resolve against the caller's directory.
    [string] $OutputPath,

    # Repository root that owns common/, targets/ and the sibling project files. Defaults to the
    # parent of this script's directory.
    [string] $RepositoryRoot,

    # Project directory holding the shared sources, packed in full. Defaults to <RepositoryRoot>/common.
    [string] $CommonRoot,

    # Directory holding one sub-directory per loader/version target, each packed in full.
    [string] $TargetsRoot,

    # Sibling project files taken from $RepositoryRoot and placed at the archive root, next to
    # common/ and targets/. Names that do not exist are reported and skipped.
    [string[]] $RootFile = @('build.gradle', 'gradle.properties', 'settings.gradle', 'AGENTS.md', 'AGENT.md'),

    # Directory names skipped at any depth inside a packed directory. They hold build output, caches
    # or editor/VCS state that does not belong in a source archive.
    [string[]] $SkipDirectoryName = @('.git', '.gradle', '.idea', 'build', 'run', 'out'),

    [ValidateSet('Optimal', 'Fastest', 'NoCompression')]
    [string] $CompressionLevel = 'Optimal'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

# $PSScriptRoot is not reliable while parameter defaults are bound, so resolve the repository from
# this file's own location here instead.
$scriptsDirectory = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$scriptRepositoryRoot = Split-Path -Parent $scriptsDirectory

if ([string]::IsNullOrWhiteSpace($RepositoryRoot)) {
    $RepositoryRoot = $scriptRepositoryRoot
}
$RepositoryRoot = [System.IO.Path]::GetFullPath($RepositoryRoot)

if (-not (Test-Path -LiteralPath $RepositoryRoot -PathType Container)) {
    throw "Repository root not found: $RepositoryRoot"
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $RepositoryRoot 'build\GravityEngine-sources.zip'
}
if ([string]::IsNullOrWhiteSpace($CommonRoot)) {
    $CommonRoot = Join-Path $RepositoryRoot 'common'
}
if ([string]::IsNullOrWhiteSpace($TargetsRoot)) {
    $TargetsRoot = Join-Path $RepositoryRoot 'targets'
}

# Each packed directory becomes a self-describing prefix such as common or targets/fabric-1.20.1, so
# the archive mirrors the repository layout and can be unpacked anywhere without collisions.
$packRoots = [System.Collections.Generic.List[object]]::new()

if (-not (Test-Path -LiteralPath $CommonRoot -PathType Container)) {
    throw "Common project directory not found: $CommonRoot"
}
$packRoots.Add([pscustomobject]@{ Path = (Get-Item -LiteralPath $CommonRoot).FullName; Prefix = 'common' })

if (-not (Test-Path -LiteralPath $TargetsRoot -PathType Container)) {
    throw "Targets directory not found: $TargetsRoot"
}

foreach ($targetDirectory in Get-ChildItem -LiteralPath $TargetsRoot -Directory | Sort-Object Name) {
    if (-not (Test-Path -LiteralPath (Join-Path $targetDirectory.FullName 'src') -PathType Container)) {
        Write-Warning "Target '$($targetDirectory.Name)' has no src directory."
    }

    $packRoots.Add([pscustomobject]@{
            Path   = $targetDirectory.FullName
            Prefix = "targets/$($targetDirectory.Name)"
        })
}

# Sibling project files collapse to the archive root, so an unpacked tree keeps build.gradle,
# gradle.properties and settings.gradle beside common/ and targets/.
$rootFiles = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in $RootFile) {
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        continue
    }

    $candidatePath = Join-Path $RepositoryRoot $candidate
    if (Test-Path -LiteralPath $candidatePath -PathType Leaf) {
        $rootFiles.Add([pscustomobject]@{
                Name = Split-Path -Leaf $candidatePath
                Path = (Get-Item -LiteralPath $candidatePath).FullName
            })
        continue
    }

    Write-Warning "Sibling project file not found, skipping it: $candidatePath"
}

$outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)

foreach ($packRoot in $packRoots) {
    # Packing the archive into a directory being packed would read a half-written file.
    $rootPrefix = $packRoot.Path.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
        [System.IO.Path]::DirectorySeparatorChar
    if ($outputFullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Output archive must not live inside a packed directory: $outputFullPath"
    }
}

$entries = [System.Collections.Generic.List[object]]::new()
$entryNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)

$skippedDirectoryNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
foreach ($name in $SkipDirectoryName) {
    if (-not [string]::IsNullOrWhiteSpace($name)) {
        [void] $skippedDirectoryNames.Add($name.Trim())
    }
}

function Add-ArchiveEntry {
    param(
        [Parameter(Mandatory = $true)][string] $EntryName,
        [string] $SourcePath,
        [switch] $IsDirectory
    )

    if (-not $entryNames.Add($EntryName)) {
        throw "Duplicate archive entry '$EntryName' from $SourcePath"
    }

    $entries.Add([pscustomobject]@{
            Name        = $EntryName
            Path        = $SourcePath
            IsDirectory = [bool] $IsDirectory
        })
}

# Counts the files a directory would contribute while ignoring skipped directories, so a directory
# that only holds build output or caches is reported instead of silently contributing nothing.
function Get-PackableFileCount {
    param([Parameter(Mandatory = $true)][string] $DirectoryPath)

    $count = 0
    foreach ($child in Get-ChildItem -LiteralPath $DirectoryPath -Force) {
        if ($child.PSIsContainer) {
            if ($skippedDirectoryNames.Contains($child.Name)) {
                continue
            }

            $count += Get-PackableFileCount -DirectoryPath $child.FullName
            continue
        }

        $count++
    }

    return $count
}

function Add-PackedDirectory {
    param(
        [Parameter(Mandatory = $true)][string] $DirectoryPath,
        [Parameter(Mandatory = $true)][string] $EntryPrefix
    )

    $added = 0
    foreach ($child in Get-ChildItem -LiteralPath $DirectoryPath -Force | Sort-Object Name) {
        $entryName = "$EntryPrefix/$($child.Name)"

        if ($child.PSIsContainer) {
            if ($skippedDirectoryNames.Contains($child.Name)) {
                continue
            }

            # Keep empty directories such as an unused resources/ folder visible in the archive.
            if ((Get-PackableFileCount -DirectoryPath $child.FullName) -eq 0) {
                Add-ArchiveEntry -EntryName "$entryName/" -IsDirectory
                $added++
                continue
            }

            $added += Add-PackedDirectory -DirectoryPath $child.FullName -EntryPrefix $entryName
            continue
        }

        Add-ArchiveEntry -EntryName $entryName -SourcePath $child.FullName
        $added++
    }

    return $added
}

foreach ($packRoot in $packRoots) {
    if ((Get-PackableFileCount -DirectoryPath $packRoot.Path) -eq 0) {
        Write-Warning "No packable files under $($packRoot.Prefix); skipping it."
        continue
    }

    [void] (Add-PackedDirectory -DirectoryPath $packRoot.Path -EntryPrefix $packRoot.Prefix)
}

foreach ($rootFileEntry in $rootFiles) {
    Add-ArchiveEntry -EntryName $rootFileEntry.Name -SourcePath $rootFileEntry.Path
}

if ($entries.Count -eq 0) {
    throw 'No source files found to pack.'
}

$sortedEntries = @($entries | Sort-Object -Property Name)

$outputDirectory = Split-Path -Parent $outputFullPath
if ($outputDirectory -and -not (Test-Path -LiteralPath $outputDirectory -PathType Container)) {
    New-Item -Path $outputDirectory -ItemType Directory -Force | Out-Null
}

if (Test-Path -LiteralPath $outputFullPath -PathType Leaf) {
    Remove-Item -LiteralPath $outputFullPath -Force
}

$level = [System.IO.Compression.CompressionLevel]::$CompressionLevel
$archive = [System.IO.Compression.ZipFile]::Open($outputFullPath, [System.IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($entry in $sortedEntries) {
        if ($entry.IsDirectory) {
            [void] $archive.CreateEntry($entry.Name)
            continue
        }

        [void] [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive,
            $entry.Path,
            $entry.Name,
            $level)
    }
}
finally {
    $archive.Dispose()
}

$archiveBytes = (Get-Item -LiteralPath $outputFullPath).Length

foreach ($packRoot in $packRoots) {
    $packedFiles = @($sortedEntries | Where-Object {
            -not $_.IsDirectory -and $_.Name.StartsWith(
                "$($packRoot.Prefix)/",
                [System.StringComparison]::Ordinal)
        }).Count
    Write-Host ("Packed {0,5} files from {1}" -f $packedFiles, $packRoot.Prefix)
}

$siblingNames = ($rootFiles | ForEach-Object { $_.Name }) -join ', '
Write-Host ("Packed {0,5} sibling files at the archive root: {1}" -f $rootFiles.Count, $siblingNames)

Write-Host ("Wrote {0} entries ({1:N1} KB) to {2}" -f $sortedEntries.Count, ($archiveBytes / 1KB), $outputFullPath)
