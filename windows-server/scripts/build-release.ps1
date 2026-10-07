$ErrorActionPreference = 'Stop'

$serverDirectory = Split-Path -Parent $PSScriptRoot
$projectDirectory = Split-Path -Parent $serverDirectory
$previousEncodedFlags = $env:CARGO_ENCODED_RUSTFLAGS
$temporaryConfig = Join-Path ([System.IO.Path]::GetTempPath()) ('phone-mouse-rustflags-' + [guid]::NewGuid().ToString('N') + '.toml')

Push-Location -LiteralPath $serverDirectory
try {
    $toolchainDirectory = (& rustc --print sysroot | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot locate the Rust toolchain.' }
    $cargoDirectory = if ($env:CARGO_HOME) { $env:CARGO_HOME } else { Join-Path $env:USERPROFILE '.cargo' }
    $mappings = @(
        @{ Directory = $env:USERPROFILE; Replacement = '/user' },
        @{ Directory = $projectDirectory; Replacement = '/phone-mouse' },
        @{ Directory = $cargoDirectory; Replacement = '/cargo' },
        @{ Directory = $toolchainDirectory; Replacement = '/rust' }
    )

    # Rust compares prefixes literally; handle both Windows separator styles.
    $remapFlags = @()
    $privatePrefixes = @()
    foreach ($mapping in $mappings) {
        $directory = [System.IO.Path]::GetFullPath($mapping.Directory).TrimEnd('\', '/')
        foreach ($prefix in @($directory, $directory.Replace('\', '/')) | Select-Object -Unique) {
            $privatePrefixes += $prefix
            $remapFlags += '--remap-path-prefix=' + $prefix + '=' + $mapping.Replacement
        }
    }

    # An extra config merges these flags with the existing static CRT settings.
    $configText = '[target.x86_64-pc-windows-msvc]' + [Environment]::NewLine + 'rustflags = ' + (ConvertTo-Json -InputObject $remapFlags -Compress)
    [System.IO.File]::WriteAllText($temporaryConfig, $configText, [System.Text.UTF8Encoding]::new($false))

    # Environment flags take precedence over Cargo config, so append the remaps
    # there as well when the caller supplies custom flags.
    if ($previousEncodedFlags) {
        $env:CARGO_ENCODED_RUSTFLAGS = $previousEncodedFlags + [char]31 + ($remapFlags -join [char]31)
    } elseif ($env:RUSTFLAGS) {
        $callerFlags = @($env:RUSTFLAGS.Trim() -split '\s+')
        $env:CARGO_ENCODED_RUSTFLAGS = ($callerFlags + $remapFlags) -join [char]31
    }

    & cargo build --locked --release --config $temporaryConfig
    if ($LASTEXITCODE -ne 0) { throw 'Windows release build failed.' }

    # Fail before packaging if any of the mapped local paths survive in the EXE.
    $executable = Join-Path $serverDirectory 'target/release/phone-mouse-server.exe'
    $bytes = [System.IO.File]::ReadAllBytes($executable)
    $utf8Text = [System.Text.Encoding]::UTF8.GetString($bytes)
    $utf16Text = [System.Text.Encoding]::Unicode.GetString($bytes)
    $utf16ShiftedText = [System.Text.Encoding]::Unicode.GetString($bytes, 1, $bytes.Length - 1)
    foreach ($prefix in $privatePrefixes) {
        if ($utf8Text.IndexOf($prefix, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $utf16Text.IndexOf($prefix, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $utf16ShiftedText.IndexOf($prefix, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'A local build path remains in the release executable; packaging was stopped.'
        }
    }
    Write-Output 'Release executable passed the local build path check.'
} finally {
    $env:CARGO_ENCODED_RUSTFLAGS = $previousEncodedFlags
    if (Test-Path -LiteralPath $temporaryConfig) {
        Remove-Item -LiteralPath $temporaryConfig -Force
    }
    Pop-Location
}
