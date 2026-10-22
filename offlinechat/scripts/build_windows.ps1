#requires -Version 5.1
[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\dist'),
    [switch]$SmokeTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'Build Windows on Windows with Flutter and Visual Studio Desktop development with C++ installed.'
}

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$packageName = 'OfflineChat-Windows-x64'
$staging = Join-Path $OutputDirectory $packageName
$archive = Join-Path $OutputDirectory "$packageName.zip"

function Invoke-Flutter {
    param([string[]]$Arguments)
    & flutter @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "flutter $($Arguments -join ' ') failed with exit code $LASTEXITCODE."
    }
}

function Assert-File {
    param([string]$Path)
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing required Windows runtime file: $Path"
    }
}

Get-Command flutter -ErrorAction Stop | Out-Null
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null

Push-Location $projectRoot
try {
    Invoke-Flutter -Arguments @('pub', 'get', '--enforce-lockfile')
    Invoke-Flutter -Arguments @('analyze')
    Invoke-Flutter -Arguments @('test')
    Invoke-Flutter -Arguments @('build', 'windows', '--release')

    $releaseDirectory = Join-Path $projectRoot 'build\windows\x64\runner\Release'
    $required = @(
        'offlinechat.exe',
        'flutter_windows.dll',
        'flutter_ble_central_plugin.dll',
        'flutter_ble_peripheral_plugin.dll',
        'data\icudtl.dat',
        'data\app.so'
    )
    foreach ($file in $required) {
        Assert-File (Join-Path $releaseDirectory $file)
    }
    $executable = [IO.BinaryReader]::new([IO.File]::OpenRead((Join-Path $releaseDirectory 'offlinechat.exe')))
    try {
        if ($executable.ReadUInt16() -ne 0x5A4D) { throw 'Windows executable has no MZ header.' }
        $executable.BaseStream.Position = 0x3C
        $peOffset = $executable.ReadInt32()
        $executable.BaseStream.Position = $peOffset
        if ($executable.ReadUInt32() -ne 0x00004550 -or $executable.ReadUInt16() -ne 0x8664) {
            throw 'The Windows executable is not an x64 PE image.'
        }
    }
    finally {
        $executable.Dispose()
    }
    if (!(Test-Path (Join-Path $releaseDirectory 'data\flutter_assets') -PathType Container)) {
        throw 'The Flutter asset directory is missing.'
    }

    # Include the redistributable CRT alongside the app so a fresh Windows
    # installation does not need a separate Visual C++ runtime installation.
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    Assert-File $vswhere
    $visualStudio = (& $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath | Select-Object -First 1)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($visualStudio)) {
        throw 'Visual Studio C++ tools were not found. Install Desktop development with C++.'
    }
    $redistRoot = Join-Path $visualStudio 'VC\Redist\MSVC'
    $redist = Get-ChildItem -LiteralPath $redistRoot -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+(\.\d+)?$' } |
        Sort-Object { [version]$_.Name } -Descending |
        ForEach-Object { Join-Path $_.FullName 'x64\Microsoft.VC143.CRT' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Container } |
        Select-Object -First 1
    if (!$redist) {
        throw 'Visual Studio x64 VC143 redistributable DLLs were not found.'
    }

    if (Test-Path -LiteralPath $staging) {
        Remove-Item -LiteralPath $staging -Recurse -Force
    }
    New-Item -ItemType Directory -Path $staging | Out-Null
    Copy-Item -Path (Join-Path $releaseDirectory '*') -Destination $staging -Recurse -Force
    Copy-Item -Path (Join-Path $redist '*.dll') -Destination $staging -Force
    foreach ($file in @('msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll')) {
        Assert-File (Join-Path $staging $file)
    }
    Copy-Item -LiteralPath (Join-Path $projectRoot 'README.md') -Destination (Join-Path $staging 'README.md')

    if ($SmokeTest) {
        $process = $null
        try {
            $process = Start-Process -FilePath (Join-Path $staging 'offlinechat.exe') -WorkingDirectory $staging -PassThru
            Start-Sleep -Seconds 5
            $process.Refresh()
            if ($process.HasExited) {
                throw "The packaged Windows app exited during launch smoke test (exit $($process.ExitCode))."
            }
            Write-Host 'Packaged Windows app remained running during the launch smoke test.'
        }
        finally {
            if ($null -ne $process) {
                $process.Refresh()
                if (!$process.HasExited) {
                    Stop-Process -Id $process.Id -Force
                    $process.WaitForExit()
                }
                $process.Dispose()
            }
        }
    }

    if (Test-Path -LiteralPath $archive) {
        Remove-Item -LiteralPath $archive -Force
    }
    Compress-Archive -LiteralPath $staging -DestinationPath $archive -CompressionLevel Optimal
    # GitHub Git storage rejects files >= 100 MiB. Publish a failure rather than
    # claiming that an oversized archive was uploaded successfully.
    if ((Get-Item -LiteralPath $archive).Length -ge 100MB) {
        throw 'Windows ZIP exceeds the GitHub per-file limit; use the workflow artifact instead.'
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($archive)
    try {
        $entryNames = @($zip.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
        foreach ($file in $required + @('msvcp140.dll', 'vcruntime140.dll', 'vcruntime140_1.dll')) {
            $entryName = "$packageName/$($file.Replace('\', '/'))"
            if ($entryNames -notcontains $entryName) {
                throw "Archive is missing $entryName"
            }
        }
        foreach ($entry in $zip.Entries) {
            if ($entry.Name.Length -eq 0) { continue }
            $stream = $entry.Open()
            try { $stream.CopyTo([IO.Stream]::Null) } finally { $stream.Dispose() }
        }
    }
    finally {
        $zip.Dispose()
    }
    $hash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText("$archive.sha256", "$hash  $packageName.zip`n", [Text.UTF8Encoding]::new($false))
    Write-Host "Windows package: $archive"
    Write-Host "SHA256: $hash"
    Write-Host 'Extract the complete ZIP and run offlinechat.exe. BLE transport still needs physical-device validation.'
}
finally {
    Pop-Location
}
