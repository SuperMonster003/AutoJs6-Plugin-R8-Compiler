[CmdletBinding()]
param(
    [string]$ExpectedSourceFingerprint = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$utf8NoBom = [Text.UTF8Encoding]::new($false)
$invocationId = [Guid]::NewGuid().ToString()
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/reports/r42-g5/local-release-gate.json'))
$releaseRelative = 'releases/provider/0.1.0-provider-dev/local.4'
$releaseDirectory = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $releaseRelative))
$signingPropertiesPath = [IO.Path]::GetFullPath('D:\idea-projects\AutoJs6\sign.properties')
$keystorePath = [IO.Path]::GetFullPath('D:\idea-projects\AutoJs6\app\sm003.jks')
$hostAppDirectory = [IO.Path]::GetFullPath('D:\idea-projects\AutoJs6\app')
$buildToolsVersion = '37.0.0'
$temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ("autojs6-r8-g5-{0}" -f $invocationId.Replace('-', ''))
$failureStage = 'INITIALIZATION'

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

public static class R8G5AtomicFile {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool MoveFileEx(string existingPath, string newPath, int flags);
    public static void Replace(string existingPath, string newPath) {
        const int MOVEFILE_REPLACE_EXISTING = 0x1;
        const int MOVEFILE_WRITE_THROUGH = 0x8;
        if (!MoveFileEx(existingPath, newPath, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
            throw new Win32Exception(Marshal.GetLastWin32Error());
        }
    }
}
'@

function Stop-G5 {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$SafeMessage
    )
    $exception = [InvalidOperationException]::new($SafeMessage)
    $exception.Data['G5Code'] = $Code
    throw $exception
}

function Invoke-NativeCaptured {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )
    $previousPreference = $ErrorActionPreference
    try {
        # Windows PowerShell 5.1 wraps native stderr as a non-terminating ErrorRecord. Some valid
        # tools (notably java -version and Android SDK processing) use stderr on exit code zero.
        $ErrorActionPreference = 'Continue'
        $nativeOutput = @(& $FilePath @Arguments 2>&1)
        $nativeExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{
        exitCode = [int]$nativeExitCode
        output = [string[]]@($nativeOutput | ForEach-Object { [string]$_ })
    }
}

function Get-Sha256Bytes {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-Sha256File {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not [IO.File]::Exists($Path)) {
        Stop-G5 'REQUIRED_FILE_MISSING' 'A required file is missing'
    }
    $stream = [IO.File]::Open(
        $Path,
        [IO.FileMode]::Open,
        [IO.FileAccess]::Read,
        ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
    )
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try {
            return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant()
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function New-FileRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
        Stop-G5 'NON_REGULAR_FILE' 'A required evidence input is not a regular file'
    }
    return [ordered]@{
        path = $Label.Replace('\', '/')
        byteLength = [long]$item.Length
        sha256 = Get-Sha256File $Path
    }
}

function Write-AtomicJson {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value,
        [string[]]$ForbiddenValues = @()
    )
    $parent = [IO.Path]::GetDirectoryName($Path)
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $json = (($Value | ConvertTo-Json -Depth 100) -replace "`r`n", "`n") + "`n"
    if ($json -match '(?i)(?:[a-z]:[\\/]|\\\\)') {
        Stop-G5 'ABSOLUTE_PATH_IN_JSON' 'Evidence JSON attempted to persist an absolute path'
    }
    foreach ($forbidden in $ForbiddenValues) {
        if (-not [String]::IsNullOrEmpty($forbidden) -and
            $json.IndexOf($forbidden, [StringComparison]::Ordinal) -ge 0) {
            Stop-G5 'SIGNING_SECRET_IN_JSON' 'Evidence JSON attempted to persist signing material'
        }
    }
    $temporary = Join-Path $parent ('.{0}.{1}.tmp' -f [IO.Path]::GetFileName($Path), [Guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllText($temporary, $json, $utf8NoBom)
        [R8G5AtomicFile]::Replace($temporary, $Path)
    } finally {
        if ([IO.File]::Exists($temporary)) {
            [IO.File]::Delete($temporary)
        }
    }
}

function Get-FailureCode {
    param([Parameter(Mandatory = $true)][Exception]$Exception)
    if ($Exception.Data.Contains('G5Code')) {
        return [string]$Exception.Data['G5Code']
    }
    return 'UNEXPECTED_FAILURE'
}

function Assert-CanonicalRelativePath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    if ([String]::IsNullOrWhiteSpace($RelativePath) -or
        [IO.Path]::IsPathRooted($RelativePath) -or
        $RelativePath.Contains('\') -or
        $RelativePath.Split('/') -contains '..' -or
        $RelativePath.Split('/') -contains '.') {
        Stop-G5 'UNSAFE_REPOSITORY_PATH' 'Git returned a non-canonical repository path'
    }
    $full = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $RelativePath))
    $prefix = $repositoryRoot.TrimEnd('\') + '\'
    if (-not $full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Stop-G5 'REPOSITORY_PATH_ESCAPE' 'A repository file escaped the repository root'
    }
    return $full
}

function Get-RepositoryFiles {
    Push-Location $repositoryRoot
    try {
        $native = Invoke-NativeCaptured 'git.exe' @('-c', 'core.quotepath=false', 'ls-files', '--cached', '--others', '--exclude-standard')
        $lines = $native.output
        $exitCode = $native.exitCode
    } finally {
        Pop-Location
    }
    if ($exitCode -ne 0) {
        Stop-G5 'GIT_ENUMERATION_FAILED' 'Git could not enumerate the current worktree'
    }
    $files = [Collections.Generic.List[string]]::new()
    foreach ($line in $lines) {
        $relative = [string]$line
        $full = Assert-CanonicalRelativePath $relative
        if (-not [IO.File]::Exists($full)) {
            Stop-G5 'ENUMERATED_FILE_MISSING' 'An enumerated worktree file is missing'
        }
        $item = Get-Item -LiteralPath $full -Force
        if ($item.PSIsContainer -or (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0)) {
            Stop-G5 'REPOSITORY_LINK_REJECTED' 'The release snapshot cannot traverse a linked file'
        }
        if ($relative -match '(?i)(?:^|/)(?:sign\.properties|[^/]+\.(?:jks|keystore|p12|pfx|pem|key))$') {
            Stop-G5 'SIGNING_FILE_IN_REPOSITORY' 'Signing material must not enter the release snapshot'
        }
        $files.Add($relative)
    }
    $result = [string[]]$files.ToArray()
    [Array]::Sort($result, [StringComparer]::Ordinal)
    if ($result.Count -lt 50) {
        Stop-G5 'INCOMPLETE_SOURCE_SNAPSHOT' 'The repository snapshot contains too few files'
    }
    return $result
}

function Test-IsReleaseInput {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    if ($RelativePath -in @(
        'build.gradle.kts',
        'settings.gradle.kts',
        'gradle.properties',
        'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.jar',
        'gradle/wrapper/gradle-wrapper.properties',
        'gradlew',
        'gradlew.bat',
        'app/build.gradle.kts'
    )) {
        return $true
    }
    if ($RelativePath.StartsWith('app/src/main/', [StringComparison]::Ordinal)) {
        return $true
    }
    if ($RelativePath -match '^plugin-api/(?:protocol-wire-api|r8-compiler-api)/(?:build\.gradle\.kts|consumer-rules\.pro)$') {
        return $true
    }
    if ($RelativePath -match '^plugin-api/(?:protocol-wire-api|r8-compiler-api)/src/main/') {
        return $true
    }
    if ($RelativePath -in @(
        'plugin-api/r8-compiler-api/releases/0.1.0/contract-distribution-manifest.json',
        'plugin-api/r8-compiler-api/releases/0.1.0/protocol-wire-api-0.1.0.aar',
        'plugin-api/r8-compiler-api/releases/0.1.0/r8-compiler-api-0.1.0.aar'
    )) {
        return $true
    }
    return $false
}

function Get-ReleaseInputRecords {
    param([Parameter(Mandatory = $true)][string[]]$RepositoryFiles)
    $records = [Collections.Generic.List[object]]::new()
    foreach ($relative in $RepositoryFiles) {
        if (Test-IsReleaseInput $relative) {
            $records.Add((New-FileRecord (Join-Path $repositoryRoot $relative) $relative))
        }
    }
    foreach ($required in @(
        'build.gradle.kts',
        'settings.gradle.kts',
        'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.jar',
        'app/build.gradle.kts',
        'app/src/main/AndroidManifest.xml',
        'plugin-api/r8-compiler-api/releases/0.1.0/protocol-wire-api-0.1.0.aar',
        'plugin-api/r8-compiler-api/releases/0.1.0/r8-compiler-api-0.1.0.aar'
    )) {
        if (@($records | Where-Object { $_.path -ceq $required }).Count -ne 1) {
            Stop-G5 'RELEASE_INPUT_MISSING' 'The release input closure is incomplete'
        }
    }
    if ($records.Count -lt 35) {
        Stop-G5 'RELEASE_INPUT_SET_TOO_SMALL' 'The release input closure contains too few files'
    }
    return @($records)
}

function Get-SourceFingerprint {
    param([Parameter(Mandatory = $true)][object[]]$Records)
    $builder = [Text.StringBuilder]::new()
    foreach ($record in $Records) {
        [void]$builder.Append([string]$record.path)
        [void]$builder.Append([char]0)
        [void]$builder.Append(([long]$record.byteLength).ToString([Globalization.CultureInfo]::InvariantCulture))
        [void]$builder.Append([char]0)
        [void]$builder.Append([string]$record.sha256)
        [void]$builder.Append("`n")
    }
    return Get-Sha256Bytes $utf8NoBom.GetBytes($builder.ToString())
}

function Assert-RecordsUnchanged {
    param([Parameter(Mandatory = $true)][object[]]$Records)
    foreach ($record in $Records) {
        $path = Join-Path $repositoryRoot ([string]$record.path)
        if (-not [IO.File]::Exists($path)) {
            Stop-G5 'RELEASE_INPUT_CHANGED' 'A release input disappeared during verification'
        }
        $item = Get-Item -LiteralPath $path -Force
        if ([long]$item.Length -ne [long]$record.byteLength -or
            (Get-Sha256File $path) -cne [string]$record.sha256) {
            Stop-G5 'RELEASE_INPUT_CHANGED' 'A release input changed during verification'
        }
    }
}

function Copy-RepositorySnapshot {
    param(
        [Parameter(Mandatory = $true)][string[]]$RepositoryFiles,
        [Parameter(Mandatory = $true)][string]$Destination,
        [Parameter(Mandatory = $true)][object[]]$ReleaseInputRecords
    )
    [IO.Directory]::CreateDirectory($Destination) | Out-Null
    foreach ($relative in $RepositoryFiles) {
        $source = Join-Path $repositoryRoot $relative
        $target = [IO.Path]::GetFullPath((Join-Path $Destination $relative))
        $prefix = $Destination.TrimEnd('\') + '\'
        if (-not $target.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            Stop-G5 'SNAPSHOT_PATH_ESCAPE' 'A source file escaped its isolated snapshot'
        }
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
        [IO.File]::Copy($source, $target, $false)
    }
    foreach ($record in $ReleaseInputRecords) {
        $target = Join-Path $Destination ([string]$record.path)
        if ((Get-Sha256File $target) -cne [string]$record.sha256 -or
            (Get-Item -LiteralPath $target).Length -ne [long]$record.byteLength) {
            Stop-G5 'SNAPSHOT_COPY_MISMATCH' 'An isolated snapshot does not match its release inputs'
        }
    }
}

function Invoke-IsolatedReleaseBuild {
    param(
        [Parameter(Mandatory = $true)][string]$Snapshot,
        [Parameter(Mandatory = $true)][ValidateSet('A', 'B')][string]$Label
    )
    $gradle = Join-Path $Snapshot 'gradlew.bat'
    Write-Host "G5 isolated offline build $Label started."
    Push-Location $Snapshot
    try {
        $native = Invoke-NativeCaptured $gradle @(
            '--offline',
            '--no-daemon',
            '--no-build-cache',
            '--no-configuration-cache',
            '--rerun-tasks',
            ':app:assembleRelease'
        )
        $buildLog = $native.output
        $exitCode = $native.exitCode
    } finally {
        Pop-Location
    }
    foreach ($line in $buildLog) { Write-Host ([string]$line) }
    if ($exitCode -ne 0) {
        Stop-G5 ("ISOLATED_BUILD_{0}_FAILED" -f $Label) ("Isolated release build {0} failed" -f $Label)
    }
    $apk = Join-Path $Snapshot 'app/build/outputs/apk/release/app-release-unsigned.apk'
    if (-not [IO.File]::Exists($apk)) {
        Stop-G5 ("ISOLATED_BUILD_{0}_APK_MISSING" -f $Label) ("Isolated release build {0} did not produce the unsigned APK" -f $Label)
    }
    $item = Get-Item -LiteralPath $apk
    Write-Host ("G5 isolated offline build {0} completed: {1} bytes." -f $Label, $item.Length)
    return [ordered]@{
        label = $Label
        apkPath = $apk
        byteLength = [long]$item.Length
        sha256 = Get-Sha256File $apk
    }
}

function ConvertFrom-JavaPropertyEscapes {
    param([Parameter(Mandatory = $true)][string]$Text)
    $builder = [Text.StringBuilder]::new()
    for ($index = 0; $index -lt $Text.Length; $index++) {
        $character = $Text[$index]
        if ($character -ne '\') {
            [void]$builder.Append($character)
            continue
        }
        $index++
        if ($index -ge $Text.Length) {
            [void]$builder.Append('\')
            break
        }
        $escaped = $Text[$index]
        switch ($escaped) {
            't' { [void]$builder.Append("`t") }
            'n' { [void]$builder.Append("`n") }
            'r' { [void]$builder.Append("`r") }
            'f' { [void]$builder.Append("`f") }
            'u' {
                if ($index + 4 -ge $Text.Length) {
                    Stop-G5 'SIGNING_PROPERTIES_SYNTAX' 'The signing properties contain an invalid Unicode escape'
                }
                $hex = $Text.Substring($index + 1, 4)
                if ($hex -cnotmatch '^[0-9a-fA-F]{4}$') {
                    Stop-G5 'SIGNING_PROPERTIES_SYNTAX' 'The signing properties contain an invalid Unicode escape'
                }
                [void]$builder.Append([char][Convert]::ToInt32($hex, 16))
                $index += 4
            }
            default { [void]$builder.Append($escaped) }
        }
    }
    return $builder.ToString()
}

function Read-JavaProperties {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not [IO.File]::Exists($Path)) {
        Stop-G5 'SIGNING_PROPERTIES_MISSING' 'The authorized signing properties are missing'
    }
    $logicalLines = [Collections.Generic.List[string]]::new()
    $buffer = ''
    $continuing = $false
    foreach ($physicalLine in [IO.File]::ReadAllLines($Path)) {
        $part = if ($continuing) { $physicalLine.TrimStart([char[]]@(' ', "`t", "`f")) } else { $physicalLine }
        $buffer += $part
        $slashCount = 0
        for ($i = $buffer.Length - 1; $i -ge 0 -and $buffer[$i] -eq '\'; $i--) { $slashCount++ }
        if (($slashCount % 2) -eq 1) {
            $buffer = $buffer.Substring(0, $buffer.Length - 1)
            $continuing = $true
            continue
        }
        $logicalLines.Add($buffer)
        $buffer = ''
        $continuing = $false
    }
    if ($continuing) { $logicalLines.Add($buffer) }

    $properties = @{}
    foreach ($logicalLine in $logicalLines) {
        $line = $logicalLine.TrimStart([char[]]@(' ', "`t", "`f"))
        if ($line.Length -eq 0 -or $line[0] -eq '#' -or $line[0] -eq '!') { continue }
        $escaped = $false
        $keyEnd = $line.Length
        $separatorKind = ''
        for ($i = 0; $i -lt $line.Length; $i++) {
            $character = $line[$i]
            if (-not $escaped -and ($character -eq '=' -or $character -eq ':' -or
                $character -eq ' ' -or $character -eq "`t" -or $character -eq "`f")) {
                $keyEnd = $i
                $separatorKind = [string]$character
                break
            }
            if ($character -eq '\') { $escaped = -not $escaped } else { $escaped = $false }
        }
        $valueStart = $keyEnd
        while ($valueStart -lt $line.Length -and
            ($line[$valueStart] -eq ' ' -or $line[$valueStart] -eq "`t" -or $line[$valueStart] -eq "`f")) {
            $valueStart++
        }
        if ($valueStart -lt $line.Length -and ($line[$valueStart] -eq '=' -or $line[$valueStart] -eq ':')) {
            $valueStart++
        } elseif ($separatorKind -eq '=' -or $separatorKind -eq ':') {
            $valueStart = $keyEnd + 1
        }
        while ($valueStart -lt $line.Length -and
            ($line[$valueStart] -eq ' ' -or $line[$valueStart] -eq "`t" -or $line[$valueStart] -eq "`f")) {
            $valueStart++
        }
        $rawKey = $line.Substring(0, $keyEnd)
        $rawValue = if ($valueStart -lt $line.Length) { $line.Substring($valueStart) } else { '' }
        $properties[(ConvertFrom-JavaPropertyEscapes $rawKey)] = ConvertFrom-JavaPropertyEscapes $rawValue
    }
    return $properties
}

function Resolve-BuildTools {
    $sdkRoot = if (-not [String]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $env:ANDROID_HOME
    } elseif (-not [String]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $env:ANDROID_SDK_ROOT
    } else {
        Stop-G5 'ANDROID_SDK_NOT_CONFIGURED' 'ANDROID_HOME or ANDROID_SDK_ROOT is required'
    }
    $sdkRoot = [IO.Path]::GetFullPath($sdkRoot)
    $directory = Join-Path $sdkRoot ("build-tools/{0}" -f $buildToolsVersion)
    $apkSigner = Join-Path $directory 'apksigner.bat'
    $apkSignerJar = Join-Path $directory 'lib/apksigner.jar'
    $aapt2 = Join-Path $directory 'aapt2.exe'
    $androidJar = Join-Path $sdkRoot 'platforms/android-36/android.jar'
    foreach ($path in @($apkSigner, $apkSignerJar, $aapt2, $androidJar)) {
        if (-not [IO.File]::Exists($path)) {
            Stop-G5 'PINNED_ANDROID_TOOL_MISSING' 'A pinned Android build tool is missing'
        }
    }
    return [ordered]@{
        apkSigner = $apkSigner
        apkSignerJar = $apkSignerJar
        aapt2 = $aapt2
        androidJar = $androidJar
    }
}

function Invoke-ApkSign {
    param(
        [Parameter(Mandatory = $true)][string]$ApkSigner,
        [Parameter(Mandatory = $true)][string]$Alias,
        [Parameter(Mandatory = $true)][string]$UnsignedApk,
        [Parameter(Mandatory = $true)][string]$SignedApk,
        [Parameter(Mandatory = $true)][ValidateSet('A', 'B')][string]$Label
    )
    $native = Invoke-NativeCaptured $ApkSigner @(
        'sign',
        '--ks', $keystorePath,
        '--ks-key-alias', $Alias,
        '--ks-pass', 'env:AUTOJS6_R8_G5_STORE_PASSWORD',
        '--key-pass', 'env:AUTOJS6_R8_G5_KEY_PASSWORD',
        '--min-sdk-version', '24',
        '--v1-signing-enabled', 'false',
        '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true',
        '--v4-signing-enabled', 'false',
        '--out', $SignedApk,
        $UnsignedApk
    )
    if ($native.exitCode -ne 0 -or -not [IO.File]::Exists($SignedApk)) {
        Stop-G5 ("APK_SIGN_{0}_FAILED" -f $Label) ("APK signing pass {0} failed" -f $Label)
    }
    return [ordered]@{
        label = $Label
        path = $SignedApk
        byteLength = [long](Get-Item -LiteralPath $SignedApk).Length
        sha256 = Get-Sha256File $SignedApk
    }
}

function Get-ApkSignatureInfo {
    param(
        [Parameter(Mandatory = $true)][string]$ApkSigner,
        [Parameter(Mandatory = $true)][string]$SignedApk
    )
    $native = Invoke-NativeCaptured $ApkSigner @(
        'verify', '--verbose', '--print-certs',
        '--min-sdk-version', '24', '--max-sdk-version', '36',
        $SignedApk
    )
    $lines = $native.output
    if ($native.exitCode -ne 0) {
        Stop-G5 'APK_SIGNATURE_VERIFY_FAILED' 'The signed APK did not verify for API 24-36'
    }
    $textLines = @($lines | ForEach-Object { [string]$_ })
    $digestValues = [Collections.Generic.List[string]]::new()
    foreach ($line in $textLines) {
        if ($line -match '(?i)certificate SHA-256 digest:\s*([0-9a-f]{64})(?:\s|$)') {
            $digestValues.Add($Matches[1].ToLowerInvariant())
        }
    }
    $uniqueDigests = @($digestValues | Sort-Object -Unique)
    $signerCountLines = @($textLines | Where-Object { $_ -match '^Number of signers:\s*([0-9]+)\s*$' })
    if ($signerCountLines.Count -ne 1) {
        Stop-G5 'APK_SIGNER_CARDINALITY' 'The signed APK does not report exactly one signer-count record'
    }
    [void]($signerCountLines[0] -match '^Number of signers:\s*([0-9]+)\s*$')
    $reportedSignerCount = [int]$Matches[1]
    if ($uniqueDigests.Count -ne 1 -or $reportedSignerCount -ne 1) {
        Stop-G5 'APK_SIGNER_CARDINALITY' 'The signed APK does not have exactly one unique signer certificate'
    }
    $certificateDigest = [string]$uniqueDigests[0]
    $flag = @{}
    foreach ($scheme in @('v1 scheme \(JAR signing\)', 'v2 scheme \(APK Signature Scheme v2\)', 'v3 scheme \(APK Signature Scheme v3\)', 'v3\.1 scheme \(APK Signature Scheme v3\.1\)', 'v3\.2 scheme \(APK Signature Scheme v3\.2\)', 'v4 scheme \(APK Signature Scheme v4\)')) {
        $match = @($textLines | Where-Object { $_ -match ("^Verified using {0}: (true|false)$" -f $scheme) })
        if ($match.Count -eq 1) {
            [void]($match[0] -match ': (true|false)$')
            $flag[$scheme] = $Matches[1] -ceq 'true'
        } else {
            $flag[$scheme] = $false
        }
    }
    if ($flag['v1 scheme \(JAR signing\)'] -or
        -not $flag['v2 scheme \(APK Signature Scheme v2\)'] -or
        -not $flag['v3 scheme \(APK Signature Scheme v3\)'] -or
        $flag['v3\.1 scheme \(APK Signature Scheme v3\.1\)'] -or
        $flag['v3\.2 scheme \(APK Signature Scheme v3\.2\)'] -or
        $flag['v4 scheme \(APK Signature Scheme v4\)']) {
        Stop-G5 'APK_SIGNATURE_SCHEME_MISMATCH' 'The signed APK does not match the pinned v2/v3 signature boundary'
    }
    return [ordered]@{
        certificateSha256 = $certificateDigest
        signerCount = 1
        verifiedApiRange = '24-36'
        v1 = $false
        v2 = $true
        v3 = $true
        v31 = $false
        v32 = $false
        v4 = $false
    }
}

function Get-ApkManifestInfo {
    param(
        [Parameter(Mandatory = $true)][string]$Aapt2,
        [Parameter(Mandatory = $true)][string]$SignedApk
    )
    $badgingNative = Invoke-NativeCaptured $Aapt2 @('dump', 'badging', $SignedApk)
    $badgingLines = $badgingNative.output
    if ($badgingNative.exitCode -ne 0) {
        Stop-G5 'APK_BADGING_FAILED' 'The signed APK badging could not be inspected'
    }
    $packageLine = @($badgingLines | ForEach-Object { [string]$_ } | Where-Object { $_ -match '^package:' })
    if ($packageLine.Count -ne 1 -or
        $packageLine[0] -notmatch "^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'") {
        Stop-G5 'APK_IDENTITY_PARSE_FAILED' 'The signed APK package identity could not be parsed'
    }
    $applicationId = $Matches[1]
    $versionCode = $Matches[2]
    $versionName = $Matches[3]
    $xmlNative = Invoke-NativeCaptured $Aapt2 @('dump', 'xmltree', '--file', 'AndroidManifest.xml', $SignedApk)
    $xmlLines = $xmlNative.output
    if ($xmlNative.exitCode -ne 0) {
        Stop-G5 'APK_MANIFEST_DUMP_FAILED' 'The signed APK manifest could not be inspected'
    }
    $xml = ($xmlLines | ForEach-Object { [string]$_ }) -join "`n"
    $required = @(
        'minSdkVersion(0x0101020c)=24',
        'targetSdkVersion(0x01010270)=36',
        '"org.autojs.permission.PLUGIN"',
        '"io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService"',
        'permission(0x01010006)="org.autojs.permission.PLUGIN"',
        'exported(0x01010010)=true',
        'process(0x01010011)=":r8"',
        '"org.autojs.plugin.R8_COMPILER"'
    )
    foreach ($needle in $required) {
        if ($xml.IndexOf($needle, [StringComparison]::Ordinal) -lt 0) {
            Stop-G5 'APK_MANIFEST_IDENTITY_MISMATCH' 'The signed APK manifest does not match the reserved provider identity'
        }
    }
    if ($applicationId -cne 'io.github.supermonster003.autojs6.plugin.r8compiler' -or
        $versionCode -cne '1' -or $versionName -cne '0.1.0-provider-dev') {
        Stop-G5 'APK_PACKAGE_IDENTITY_MISMATCH' 'The signed APK package or version identity is unexpected'
    }
    return [ordered]@{
        applicationId = $applicationId
        versionCode = [int]$versionCode
        versionName = $versionName
        minSdk = 24
        targetSdk = 36
        permission = 'org.autojs.permission.PLUGIN'
        service = 'io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService'
        serviceProcess = ':r8'
        serviceExported = $true
        serviceAction = 'org.autojs.plugin.R8_COMPILER'
    }
}

function Assert-ExactReleaseDirectory {
    param(
        [Parameter(Mandatory = $true)][string]$Directory,
        [Parameter(Mandatory = $true)][object[]]$ExpectedFiles
    )
    if (-not [IO.Directory]::Exists($Directory)) {
        Stop-G5 'LOCAL_RELEASE_MISSING' 'The local release directory is missing'
    }
    $directoryItem = Get-Item -LiteralPath $Directory -Force
    if (($directoryItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        Stop-G5 'LOCAL_RELEASE_LINK_REJECTED' 'The local release directory cannot be a link'
    }
    $children = @(Get-ChildItem -LiteralPath $Directory -Force)
    if (@($children | Where-Object { $_.PSIsContainer -or (($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) }).Count -ne 0) {
        Stop-G5 'LOCAL_RELEASE_NON_REGULAR_CHILD' 'The local release contains a non-regular child'
    }
    $expectedNames = [string[]]@($ExpectedFiles | ForEach-Object { [string]$_.name })
    $actualNames = [string[]]@($children | ForEach-Object { $_.Name })
    [Array]::Sort($expectedNames, [StringComparer]::Ordinal)
    [Array]::Sort($actualNames, [StringComparer]::Ordinal)
    if (@(Compare-Object -ReferenceObject $expectedNames -DifferenceObject $actualNames -CaseSensitive).Count -ne 0) {
        Stop-G5 'LOCAL_RELEASE_SHAPE_MISMATCH' 'The local release does not have the exact append-only shape'
    }
    foreach ($expected in $ExpectedFiles) {
        $path = Join-Path $Directory ([string]$expected.name)
        if ((Get-Item -LiteralPath $path).Length -ne [long]$expected.byteLength -or
            (Get-Sha256File $path) -cne [string]$expected.sha256) {
            Stop-G5 'LOCAL_RELEASE_BYTE_MISMATCH' 'An existing local release byte differs from the append-only candidate'
        }
    }
}

function Publish-AppendOnlyRelease {
    param(
        [Parameter(Mandatory = $true)][object[]]$Payloads,
        [Parameter(Mandatory = $true)][byte[]]$ManifestBytes,
        [Parameter(Mandatory = $true)][string]$ManifestName
    )
    $expected = [Collections.Generic.List[object]]::new()
    foreach ($payload in $Payloads) {
        $expected.Add([ordered]@{
            name = [string]$payload.name
            byteLength = [long](Get-Item -LiteralPath ([string]$payload.source)).Length
            sha256 = Get-Sha256File ([string]$payload.source)
        })
    }
    $expected.Add([ordered]@{
        name = $ManifestName
        byteLength = [long]$ManifestBytes.Length
        sha256 = Get-Sha256Bytes $ManifestBytes
    })
    $expectedFiles = @($expected)

    if ([IO.Directory]::Exists($releaseDirectory)) {
        Assert-ExactReleaseDirectory $releaseDirectory $expectedFiles
        return [ordered]@{ status = 'IDENTICAL'; files = $expectedFiles }
    }

    $parent = [IO.Path]::GetDirectoryName($releaseDirectory)
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $parentItem = Get-Item -LiteralPath $parent -Force
    if (($parentItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        Stop-G5 'LOCAL_RELEASE_PARENT_LINK_REJECTED' 'The local release parent cannot be a link'
    }
    $partial = Join-Path $parent ('.partial-{0}' -f [Guid]::NewGuid().ToString('N'))
    try {
        [IO.Directory]::CreateDirectory($partial) | Out-Null
        foreach ($payload in $Payloads) {
            [IO.File]::Copy([string]$payload.source, (Join-Path $partial ([string]$payload.name)), $false)
        }
        [IO.File]::WriteAllBytes((Join-Path $partial $ManifestName), $ManifestBytes)
        Assert-ExactReleaseDirectory $partial $expectedFiles
        try {
            [IO.Directory]::Move($partial, $releaseDirectory)
        } catch {
            if ([IO.Directory]::Exists($releaseDirectory)) {
                Assert-ExactReleaseDirectory $releaseDirectory $expectedFiles
                return [ordered]@{ status = 'IDENTICAL'; files = $expectedFiles }
            }
            throw
        }
        Assert-ExactReleaseDirectory $releaseDirectory $expectedFiles
        return [ordered]@{ status = 'CREATED'; files = $expectedFiles }
    } finally {
        if ([IO.Directory]::Exists($partial)) {
            $resolvedParent = [IO.Path]::GetFullPath([IO.Path]::GetDirectoryName($partial))
            if ($resolvedParent -ceq [IO.Path]::GetFullPath($parent) -and
                [IO.Path]::GetFileName($partial).StartsWith('.partial-', [StringComparison]::Ordinal)) {
                Remove-Item -LiteralPath $partial -Recurse -Force
            }
        }
    }
}

function Get-PriorEvidence {
    $specifications = @(
        [ordered]@{ name = 'g2'; path = 'build/reports/r42-g2/provider-gate.json'; schema = 'autojs6.r8.g2.provider-gate/v2' },
        [ordered]@{ name = 'g3'; path = 'build/reports/r42-g3/host-control-plane-gate.json'; schema = 'autojs6.r8.g3.host-transaction-gate/v4' },
        [ordered]@{ name = 'g4'; path = 'build/reports/r42-g4/compatibility-corpus-gate.json'; schema = 'autojs6.r8.g4.compatibility-corpus-gate/v1' }
    )
    $records = [Collections.Generic.List[object]]::new()
    foreach ($specification in $specifications) {
        $full = Join-Path $repositoryRoot ([string]$specification.path)
        if (-not [IO.File]::Exists($full)) {
            Stop-G5 'PRIOR_GATE_MISSING' 'A required prior Gate report is missing'
        }
        $report = [IO.File]::ReadAllText($full, $utf8NoBom) | ConvertFrom-Json
        if ($report.schemaVersion -cne [string]$specification.schema -or -not [bool]$report.passed) {
            Stop-G5 'PRIOR_GATE_NOT_POSITIVE' 'A required prior Gate report is not positive'
        }
        $records.Add([ordered]@{
            gate = [string]$specification.name
            schemaVersion = [string]$report.schemaVersion
            invocationId = [string]$report.invocationId
            path = [string]$specification.path
            byteLength = [long](Get-Item -LiteralPath $full).Length
            sha256 = Get-Sha256File $full
        })
    }
    return @($records)
}

function Assert-PriorEvidenceUnchanged {
    param([Parameter(Mandatory = $true)][object[]]$PriorEvidence)
    foreach ($record in $PriorEvidence) {
        $full = Join-Path $repositoryRoot ([string]$record.path)
        if (-not [IO.File]::Exists($full) -or
            (Get-Item -LiteralPath $full).Length -ne [long]$record.byteLength -or
            (Get-Sha256File $full) -cne [string]$record.sha256) {
            Stop-G5 'PRIOR_GATE_CHANGED' 'A prior Gate report changed during G5 verification'
        }
    }
}

$invalid = [ordered]@{
    schemaVersion = 'autojs6.r8.g5.local-release-gate/v1'
    invocationId = $invocationId
    passed = $false
    evidenceBoundary = 'LOCAL_SIGNED_PROVIDER_RELEASE_SAME_ENVIRONMENT_REPRODUCIBILITY'
    summary = 'G5 local release verification started and has not completed'
    claims = [ordered]@{
        localPublished = $false
        remotePublished = $false
        signedApkVerified = $false
        sameEnvironmentReproducible = $false
        deviceVerified = $false
    }
}
Write-AtomicJson -Path $reportPath -Value $invalid

try {
    $failureStage = 'REPOSITORY_VALIDATION'
    if (-not [IO.File]::Exists((Join-Path $repositoryRoot 'ROADMAP.md')) -or
        -not [IO.Directory]::Exists((Join-Path $repositoryRoot '.git'))) {
        Stop-G5 'REPOSITORY_ROOT_INVALID' 'The G5 repository root is invalid'
    }

    $publisherPath = Join-Path $repositoryRoot 'scripts/publish-g5-local-release.ps1'
    $designPath = Join-Path $repositoryRoot 'docs/local-release-v1.md'
    $identityPath = Join-Path $repositoryRoot 'docs/identity-reservation.json'
    foreach ($required in @($publisherPath, $designPath, $identityPath)) {
        if (-not [IO.File]::Exists($required)) {
            Stop-G5 'G5_EVIDENCE_SOURCE_MISSING' 'A G5 evidence source is missing'
        }
    }
    $failureStage = 'PUBLISHER_FILE_RECORD'
    $publisherRecord = New-FileRecord $publisherPath 'scripts/publish-g5-local-release.ps1'
    $failureStage = 'DESIGN_FILE_RECORD'
    $designRecord = New-FileRecord $designPath 'docs/local-release-v1.md'

    $failureStage = 'SOURCE_SNAPSHOT_ENUMERATION'
    $repositoryFiles = Get-RepositoryFiles
    $releaseInputRecords = Get-ReleaseInputRecords $repositoryFiles
    $sourceFingerprint = Get-SourceFingerprint $releaseInputRecords
    if (-not [String]::IsNullOrWhiteSpace($ExpectedSourceFingerprint)) {
        $expected = $ExpectedSourceFingerprint.ToLowerInvariant()
        if ($expected -cnotmatch '^[0-9a-f]{64}$' -or $expected -cne $sourceFingerprint) {
            Stop-G5 'EXPECTED_SOURCE_FINGERPRINT_MISMATCH' 'The expected release source fingerprint does not match'
        }
    }

    $failureStage = 'IDENTITY_AND_PRIOR_EVIDENCE'
    $identity = [IO.File]::ReadAllText($identityPath, $utf8NoBom) | ConvertFrom-Json
    if ($identity.status -cne 'LOCAL_PROVIDER_AND_HOST_INTEGRATED_NOT_PUBLISHED' -or
        -not [bool]$identity.claims.providerImplemented -or
        -not [bool]$identity.claims.hostIntegrated -or
        -not [bool]$identity.claims.r8Executed -or
        [bool]$identity.claims.binderVerified -or
        [bool]$identity.claims.deviceVerified -or
        [bool]$identity.claims.published) {
        Stop-G5 'IDENTITY_BOUNDARY_MISMATCH' 'The mutable identity does not match the local pre-device boundary'
    }
    $identityRecord = New-FileRecord $identityPath 'docs/identity-reservation.json'
    $priorEvidence = Get-PriorEvidence

    $failureStage = 'FROZEN_API_VALIDATION'
    $contractDirectory = Join-Path $repositoryRoot 'plugin-api/r8-compiler-api/releases/0.1.0'
    $contractManifestPath = Join-Path $contractDirectory 'contract-distribution-manifest.json'
    $protocolAarPath = Join-Path $contractDirectory 'protocol-wire-api-0.1.0.aar'
    $r8ApiAarPath = Join-Path $contractDirectory 'r8-compiler-api-0.1.0.aar'
    $contractManifest = [IO.File]::ReadAllText($contractManifestPath, $utf8NoBom) | ConvertFrom-Json
    if ([string]$contractManifest.evidenceBoundary -cne 'CONTRACT_AAR_ONLY' -or
        [string]$contractManifest.distributionVersion -cne '0.1.0' -or
        [bool]$contractManifest.claims.published) {
        Stop-G5 'FROZEN_API_BOUNDARY_MISMATCH' 'The frozen G1 API distribution boundary is invalid'
    }
    foreach ($candidate in @(
        [ordered]@{ name = 'protocol-wire-api-0.1.0.aar'; path = $protocolAarPath },
        [ordered]@{ name = 'r8-compiler-api-0.1.0.aar'; path = $r8ApiAarPath }
    )) {
        $manifestRecord = @($contractManifest.artifacts | Where-Object { $_.path -ceq [string]$candidate.name })
        if ($manifestRecord.Count -ne 1 -or
            (Get-Item -LiteralPath ([string]$candidate.path)).Length -ne [long]$manifestRecord[0].byteLength -or
            (Get-Sha256File ([string]$candidate.path)) -cne [string]$manifestRecord[0].sha256) {
            Stop-G5 'FROZEN_API_ARTIFACT_MISMATCH' 'A frozen G1 API artifact does not match its immutable manifest'
        }
    }
    $contractManifestRecord = New-FileRecord $contractManifestPath 'plugin-api/r8-compiler-api/releases/0.1.0/contract-distribution-manifest.json'

    $failureStage = 'ANDROID_TOOLCHAIN_VALIDATION'
    $androidTools = Resolve-BuildTools
    $failureStage = 'ISOLATED_SNAPSHOT_COPY'
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $snapshotA = Join-Path $temporaryRoot 'snapshot-a'
    $snapshotB = Join-Path $temporaryRoot 'snapshot-b'
    Copy-RepositorySnapshot $repositoryFiles $snapshotA $releaseInputRecords
    Copy-RepositorySnapshot $repositoryFiles $snapshotB $releaseInputRecords

    $failureStage = 'ISOLATED_BUILD_A'
    $buildA = Invoke-IsolatedReleaseBuild $snapshotA 'A'
    $failureStage = 'ISOLATED_BUILD_B'
    $buildB = Invoke-IsolatedReleaseBuild $snapshotB 'B'
    if ([string]$buildA.sha256 -cne [string]$buildB.sha256 -or
        [long]$buildA.byteLength -ne [long]$buildB.byteLength) {
        Stop-G5 'UNSIGNED_APK_NOT_REPRODUCIBLE' 'The two isolated unsigned APK builds are not byte-identical'
    }

    $failureStage = 'SIGNING_CONFIGURATION_VALIDATION'
    $signingPropertiesShaAtStart = Get-Sha256File $signingPropertiesPath
    $keystoreShaAtStart = Get-Sha256File $keystorePath
    $signingProperties = Read-JavaProperties $signingPropertiesPath
    foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
        if (-not $signingProperties.ContainsKey($key) -or [String]::IsNullOrEmpty([string]$signingProperties[$key])) {
            Stop-G5 'SIGNING_PROPERTY_MISSING' 'The authorized signing configuration is incomplete'
        }
    }
    $configuredStore = [string]$signingProperties['storeFile']
    $configuredStoreFull = if ([IO.Path]::IsPathRooted($configuredStore)) {
        [IO.Path]::GetFullPath($configuredStore)
    } else {
        [IO.Path]::GetFullPath((Join-Path $hostAppDirectory $configuredStore))
    }
    if (-not $configuredStoreFull.Equals($keystorePath, [StringComparison]::OrdinalIgnoreCase)) {
        Stop-G5 'SIGNING_STORE_MISMATCH' 'The host signing properties do not select the authorized keystore'
    }

    $signedAPath = Join-Path $temporaryRoot 'provider-signed-a.apk'
    $signedBPath = Join-Path $temporaryRoot 'provider-signed-b.apk'
    $failureStage = 'APK_SIGNING'
    try {
        $env:AUTOJS6_R8_G5_STORE_PASSWORD = [string]$signingProperties['storePassword']
        $env:AUTOJS6_R8_G5_KEY_PASSWORD = [string]$signingProperties['keyPassword']
        $signedA = Invoke-ApkSign ([string]$androidTools.apkSigner) ([string]$signingProperties['keyAlias']) ([string]$buildA.apkPath) $signedAPath 'A'
        $signedB = Invoke-ApkSign ([string]$androidTools.apkSigner) ([string]$signingProperties['keyAlias']) ([string]$buildB.apkPath) $signedBPath 'B'
    } finally {
        $env:AUTOJS6_R8_G5_STORE_PASSWORD = $null
        $env:AUTOJS6_R8_G5_KEY_PASSWORD = $null
    }
    $failureStage = 'APK_SIGNATURE_AND_MANIFEST_VALIDATION'
    if ([string]$signedA.sha256 -cne [string]$signedB.sha256 -or
        [long]$signedA.byteLength -ne [long]$signedB.byteLength) {
        Stop-G5 'SIGNED_APK_NOT_REPRODUCIBLE' 'The two independently signed APKs are not byte-identical'
    }
    $signatureA = Get-ApkSignatureInfo ([string]$androidTools.apkSigner) $signedAPath
    $signatureB = Get-ApkSignatureInfo ([string]$androidTools.apkSigner) $signedBPath
    if ([string]$signatureA.certificateSha256 -cne [string]$signatureB.certificateSha256) {
        Stop-G5 'APK_SIGNER_MISMATCH' 'The two signed APKs do not have the same signer certificate'
    }
    $apkIdentity = Get-ApkManifestInfo ([string]$androidTools.aapt2) $signedAPath

    $failureStage = 'POST_BUILD_INPUT_REVALIDATION'
    if ((Get-Sha256File $signingPropertiesPath) -cne $signingPropertiesShaAtStart -or
        (Get-Sha256File $keystorePath) -cne $keystoreShaAtStart) {
        Stop-G5 'SIGNING_INPUT_CHANGED' 'The external signing inputs changed during G5 verification'
    }
    Assert-RecordsUnchanged $releaseInputRecords
    Assert-PriorEvidenceUnchanged $priorEvidence
    if ((Get-Sha256File $publisherPath) -cne [string]$publisherRecord.sha256 -or
        (Get-Sha256File $designPath) -cne [string]$designRecord.sha256 -or
        (Get-Sha256File $identityPath) -cne [string]$identityRecord.sha256) {
        Stop-G5 'G5_EVIDENCE_SOURCE_CHANGED' 'A G5 evidence source changed during verification'
    }

    $failureStage = 'TOOLCHAIN_IDENTITY_RECORDING'
    $javaCommand = Get-Command java.exe -ErrorAction Stop
    $javaNative = Invoke-NativeCaptured $javaCommand.Source @('-version')
    $javaVersionLines = $javaNative.output
    if ($javaNative.exitCode -ne 0 -or $javaVersionLines.Count -lt 1) {
        Stop-G5 'JAVA_IDENTITY_FAILED' 'The Java runtime identity could not be inspected'
    }
    $wrapperProperties = [IO.File]::ReadAllText((Join-Path $repositoryRoot 'gradle/wrapper/gradle-wrapper.properties'), $utf8NoBom)
    if ($wrapperProperties -notmatch 'gradle-([0-9.]+)-bin\.zip') {
        Stop-G5 'GRADLE_VERSION_PARSE_FAILED' 'The Gradle wrapper version could not be parsed'
    }
    $gradleVersion = $Matches[1]
    $rootBuildText = [IO.File]::ReadAllText((Join-Path $repositoryRoot 'build.gradle.kts'), $utf8NoBom)
    $versionCatalogText = [IO.File]::ReadAllText((Join-Path $repositoryRoot 'gradle/libs.versions.toml'), $utf8NoBom)
    if ($rootBuildText -notmatch 'id\("com\.android\.application"\) version "([^"]+)"' -or
        $versionCatalogText -notmatch '(?m)^r8\s*=\s*"([^"]+)"\s*$') {
        Stop-G5 'TOOLCHAIN_VERSION_PARSE_FAILED' 'The pinned Android or R8 tool version could not be parsed'
    }
    [void]($rootBuildText -match 'id\("com\.android\.application"\) version "([^"]+)"')
    $agpVersion = $Matches[1]
    [void]($versionCatalogText -match '(?m)^r8\s*=\s*"([^"]+)"\s*$')
    $r8Version = $Matches[1]

    $failureStage = 'LOCAL_RELEASE_MANIFEST_CREATION'
    $releaseApkName = 'autojs6-r8-compiler-provider-0.1.0-provider-dev-signed.apk'
    $manifestName = 'local-release-manifest.json'
    $payloads = @(
        [ordered]@{ name = $releaseApkName; source = $signedAPath },
        [ordered]@{ name = 'protocol-wire-api-0.1.0.aar'; source = $protocolAarPath },
        [ordered]@{ name = 'r8-compiler-api-0.1.0.aar'; source = $r8ApiAarPath }
    )
    $artifactRecords = @($payloads | ForEach-Object {
        [ordered]@{
            path = [string]$_.name
            byteLength = [long](Get-Item -LiteralPath ([string]$_.source)).Length
            sha256 = Get-Sha256File ([string]$_.source)
            type = if ([string]$_.name -ceq $releaseApkName) { 'SIGNED_PROVIDER_APK' } else { 'FROZEN_API_AAR' }
        }
    })

    $manifest = [ordered]@{
        schemaVersion = 'autojs6.r8.local-release/v1'
        releaseId = '0.1.0-provider-dev-local.4'
        channel = 'LOCAL_ONLY'
        evidenceBoundary = 'LOCAL_SIGNED_PROVIDER_RELEASE_SAME_ENVIRONMENT_REPRODUCIBILITY'
        apkIdentity = $apkIdentity
        sourceSnapshot = [ordered]@{
            scope = 'provider-release-inputs-v1'
            canonicalization = 'path-nul-byteLength-nul-sha256-lf-v1'
            fileCount = $releaseInputRecords.Count
            sha256 = $sourceFingerprint
            files = $releaseInputRecords
        }
        builds = [ordered]@{
            isolatedSnapshotCount = 2
            network = 'OFFLINE'
            daemon = $false
            buildCache = $false
            configurationCache = $false
            rerunTasks = $true
            task = ':app:assembleRelease'
            unsignedApk = [ordered]@{
                byteLength = [long]$buildA.byteLength
                sha256 = [string]$buildA.sha256
                buildAEqualsBuildB = $true
            }
            signedApk = [ordered]@{
                byteLength = [long]$signedA.byteLength
                sha256 = [string]$signedA.sha256
                signingAEqualsSigningB = $true
            }
        }
        signing = [ordered]@{
            source = 'AUTHORIZED_EXTERNAL_HOST_SIGNING_CONFIGURATION'
            certificateSha256 = [string]$signatureA.certificateSha256
            signerCount = 1
            verifiedApiRange = '24-36'
            schemes = [ordered]@{ v1 = $false; v2 = $true; v3 = $true; v31 = $false; v32 = $false; v4 = $false }
            passwordsPassedByProcessEnvironment = $true
            passwordsPersisted = $false
            keyAliasPersisted = $false
            externalPathsPersisted = $false
        }
        frozenApiDistribution = [ordered]@{
            version = '0.1.0'
            manifest = $contractManifestRecord
            artifactsMatchManifest = $true
        }
        priorEvidence = $priorEvidence
        toolchain = [ordered]@{
            gradle = $gradleVersion
            androidGradlePlugin = $agpVersion
            r8 = $r8Version
            javaVersion = $javaVersionLines[0]
            javaExecutableSha256 = Get-Sha256File $javaCommand.Source
            androidBuildTools = $buildToolsVersion
            apksignerJarSha256 = Get-Sha256File ([string]$androidTools.apkSignerJar)
            aapt2Sha256 = Get-Sha256File ([string]$androidTools.aapt2)
            android36JarSha256 = Get-Sha256File ([string]$androidTools.androidJar)
        }
        design = $designRecord
        publisher = $publisherRecord
        artifacts = $artifactRecords
        claims = [ordered]@{
            localPublished = $true
            appendOnly = $true
            signedApkVerified = $true
            sameEnvironmentUnsignedApkByteReproducible = $true
            sameEnvironmentSignedApkByteReproducible = $true
            hermeticCrossEnvironmentReproducibilityClaimed = $false
            frozenApiHistoryIncluded = $true
            remotePublished = $false
            gitPushPerformed = $false
            remoteReleaseCreated = $false
            remoteMavenPublished = $false
            adbInvoked = $false
            installed = $false
            binderVerified = $false
            deviceVerified = $false
        }
    }
    $manifestJson = (($manifest | ConvertTo-Json -Depth 100) -replace "`r`n", "`n") + "`n"
    foreach ($forbidden in @(
        [string]$signingProperties['storePassword'],
        [string]$signingProperties['keyPassword'],
        $signingPropertiesPath,
        $keystorePath
    )) {
        if (-not [String]::IsNullOrEmpty($forbidden) -and
            $manifestJson.IndexOf($forbidden, [StringComparison]::Ordinal) -ge 0) {
            Stop-G5 'SIGNING_SECRET_IN_MANIFEST' 'The local release manifest attempted to persist signing material'
        }
    }
    $encodedKeyAlias = ([string]$signingProperties['keyAlias'] | ConvertTo-Json -Compress)
    if ($manifestJson.IndexOf($encodedKeyAlias, [StringComparison]::Ordinal) -ge 0) {
        Stop-G5 'SIGNING_ALIAS_IN_MANIFEST' 'The local release manifest attempted to persist the signing alias'
    }
    if ($manifestJson -match '(?i)(?:[a-z]:[\\/]|\\\\)') {
        Stop-G5 'ABSOLUTE_PATH_IN_MANIFEST' 'The local release manifest attempted to persist an absolute path'
    }
    $manifestBytes = $utf8NoBom.GetBytes($manifestJson)
    $failureStage = 'APPEND_ONLY_LOCAL_PUBLICATION'
    $publication = Publish-AppendOnlyRelease $payloads $manifestBytes $manifestName

    $failureStage = 'FINAL_GATE_PERSISTENCE'
    $releaseRecords = @($publication.files | ForEach-Object {
        [ordered]@{
            path = ("{0}/{1}" -f $releaseRelative, [string]$_.name)
            byteLength = [long]$_.byteLength
            sha256 = [string]$_.sha256
        }
    })
    $manifestReleaseRecord = @($releaseRecords | Where-Object { $_.path -ceq ("{0}/{1}" -f $releaseRelative, $manifestName) })
    if ($manifestReleaseRecord.Count -ne 1) {
        Stop-G5 'LOCAL_MANIFEST_RECORD_MISSING' 'The local release manifest record is missing'
    }

    $report = [ordered]@{
        schemaVersion = 'autojs6.r8.g5.local-release-gate/v1'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'LOCAL_SIGNED_PROVIDER_RELEASE_SAME_ENVIRONMENT_REPRODUCIBILITY'
        release = [ordered]@{
            releaseId = '0.1.0-provider-dev-local.4'
            channel = 'LOCAL_ONLY'
            path = $releaseRelative
            status = [string]$publication.status
            manifestSha256 = [string]$manifestReleaseRecord[0].sha256
            files = $releaseRecords
        }
        sourceFingerprint = [ordered]@{
            scope = 'provider-release-inputs-v1'
            fileCount = $releaseInputRecords.Count
            sha256 = $sourceFingerprint
        }
        builds = [ordered]@{
            isolatedSnapshots = 2
            offline = $true
            unsignedApkSha256 = [string]$buildA.sha256
            signedApkSha256 = [string]$signedA.sha256
            unsignedByteIdentical = $true
            signedByteIdentical = $true
        }
        signing = [ordered]@{
            certificateSha256 = [string]$signatureA.certificateSha256
            signerCount = 1
            verifiedApiRange = '24-36'
            v1 = $false
            v2 = $true
            v3 = $true
            v31 = $false
            v32 = $false
            v4 = $false
            secretValuesPersisted = $false
            externalPathsPersisted = $false
        }
        apkIdentity = $apkIdentity
        priorEvidence = $priorEvidence
        frozenApiDistributionManifestSha256 = [string]$contractManifestRecord.sha256
        design = $designRecord
        publisher = $publisherRecord
        claims = [ordered]@{
            localPublished = $true
            appendOnlyReleaseVerified = $true
            remotePublished = $false
            gitPushPerformed = $false
            remoteReleaseCreated = $false
            remoteMavenPublished = $false
            signedApkVerified = $true
            sameEnvironmentReproducible = $true
            hermeticCrossEnvironmentReproducibilityClaimed = $false
            adbInvoked = $false
            installed = $false
            binderVerified = $false
            deviceVerified = $false
        }
        summary = 'Append-only local signed provider APK and frozen API history passed two isolated offline same-environment builds; remote publication, installation, Binder, and device acceptance remain open'
    }
    $forbiddenValues = @(
        [string]$signingProperties['storePassword'],
        [string]$signingProperties['keyPassword'],
        $signingPropertiesPath,
        $keystorePath
    )
    $reportJsonPreview = (($report | ConvertTo-Json -Depth 100) -replace "`r`n", "`n") + "`n"
    if ($reportJsonPreview.IndexOf($encodedKeyAlias, [StringComparison]::Ordinal) -ge 0) {
        Stop-G5 'SIGNING_ALIAS_IN_REPORT' 'The G5 report attempted to persist the signing alias'
    }
    Write-AtomicJson -Path $reportPath -Value $report -ForbiddenValues $forbiddenValues
    Write-Output ("G5 local release gate passed: {0}" -f $reportPath)
    Write-Output ("Invocation ID: {0}" -f $invocationId)
    Write-Output ("Append-only status: {0}" -f [string]$publication.status)
} catch {
    $failureCode = Get-FailureCode $_.Exception
    $failure = [ordered]@{
        schemaVersion = 'autojs6.r8.g5.local-release-gate/v1'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'LOCAL_SIGNED_PROVIDER_RELEASE_SAME_ENVIRONMENT_REPRODUCIBILITY'
        failureCode = $failureCode
        failureStage = $failureStage
        summary = 'G5 local release verification failed closed; no positive local-release Gate claim is valid'
        claims = [ordered]@{
            localPublished = $false
            remotePublished = $false
            signedApkVerified = $false
            sameEnvironmentReproducible = $false
            deviceVerified = $false
        }
    }
    try {
        Write-AtomicJson -Path $reportPath -Value $failure
    } catch {
        # Preserve the original safe failure code on the console if even fail-closed persistence fails.
    }
    Write-Error ("G5 local release gate failed closed: {0} at {1}" -f $failureCode, $failureStage)
    exit 1
} finally {
    $env:AUTOJS6_R8_G5_STORE_PASSWORD = $null
    $env:AUTOJS6_R8_G5_KEY_PASSWORD = $null
    if ([IO.Directory]::Exists($temporaryRoot)) {
        $temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetDirectoryName($temporaryRoot))
        $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
        $temporaryName = [IO.Path]::GetFileName($temporaryRoot)
        if ($temporaryParent.TrimEnd('\') -ceq $expectedParent -and
            $temporaryName.StartsWith('autojs6-r8-g5-', [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
        }
    }
}
