[CmdletBinding()]
param(
    [string]$ExpectedSourceFingerprint = '',
    [string]$BuildToolsVersion = '37.0.0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$utf8NoBom = [Text.UTF8Encoding]::new($false)
$invocationId = [Guid]::NewGuid().ToString()
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/reports/r42-g7/local-release-gate.json'))
$releaseRelative = 'releases/provider/0.1.0-provider-dev/local.5'
$releaseDirectory = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $releaseRelative))
$releaseParent = [IO.Path]::GetDirectoryName($releaseDirectory)
$releaseStaging = Join-Path $releaseParent ('.local.5.{0}.tmp' -f $invocationId.Replace('-', ''))
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('autojs6-r8-g7-release-{0}' -f $invocationId.Replace('-', ''))))
$signingPropertiesPath = [IO.Path]::GetFullPath('D:\idea-projects\AutoJs6\sign.properties')
$keystorePath = [IO.Path]::GetFullPath('D:\idea-projects\AutoJs6\app\sm003.jks')
$expectedCertificateSha256 = '31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213'
$expectedPlatformLibrarySha256 = 'd9eb9da824d9e247a352f570f01e1169e725b2954bca9e283a71786c59b59f9a'
$expectedPlatformLibrarySize = 27768026L
$failureStage = 'INITIALIZATION'

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class R8G7ReleaseAtomicFile {
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

function Require {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) { throw $Message }
}

function Get-Sha256File {
    param([Parameter(Mandatory = $true)][string]$Path)
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $digest = [Security.Cryptography.SHA256]::Create()
    try {
        return (($digest.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $digest.Dispose()
        $stream.Dispose()
    }
}

function Get-Sha256Bytes {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)
    $digest = [Security.Cryptography.SHA256]::Create()
    try {
        return (($digest.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $digest.Dispose()
    }
}

function Write-AtomicJson {
    param([Parameter(Mandatory = $true)][object]$Value)
    $directory = [IO.Path]::GetDirectoryName($reportPath)
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = Join-Path $directory ('.{0}.{1}.tmp' -f [IO.Path]::GetFileName($reportPath), [Guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 64 -Compress), $utf8NoBom)
        if ([IO.File]::Exists($reportPath)) {
            [R8G7ReleaseAtomicFile]::Replace($temporary, $reportPath)
        } else {
            [IO.File]::Move($temporary, $reportPath)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Write-FailedGate {
    param([Parameter(Mandatory = $true)][string]$Stage)
    Write-AtomicJson ([ordered]@{
        schemaVersion = 'autojs6.r8.g7.local-release-gate/v1'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'APPEND_ONLY_LOCAL5_PLATFORM_LIBRARY_FIX_RELEASE'
        failureStage = $Stage
        summary = 'G7 local.5 publication failed closed; no new release claim is valid'
        claims = [ordered]@{
            localPublished = $false
            remotePublished = $false
            signedApkVerified = $false
            sameEnvironmentReproducible = $false
        }
    })
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    $previousPreference = $ErrorActionPreference
    $nativeOutput = @()
    try {
        $ErrorActionPreference = 'Continue'
        $nativeOutput = @(& $FilePath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    $lines = @($nativeOutput | ForEach-Object {
        if ($_ -is [Management.Automation.ErrorRecord]) { [string]$_.Exception.Message } else { [string]$_ }
    })
    if ($exitCode -ne 0) { throw "$FailureMessage (exit $exitCode)" }
    return [pscustomobject]@{ Lines = [string[]]$lines; Text = ($lines -join "`n") }
}

function New-FileRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    Require ([IO.File]::Exists($Path)) "Evidence file is missing: $RelativePath"
    $item = Get-Item -LiteralPath $Path -Force
    Require (-not $item.PSIsContainer) "Evidence path is not a file: $RelativePath"
    Require (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0) "Evidence file is a link: $RelativePath"
    return [ordered]@{
        path = $RelativePath.Replace('\', '/')
        byteLength = [long]$item.Length
        sha256 = Get-Sha256File $Path
    }
}

function Get-RepositoryFiles {
    $result = Invoke-Native 'git.exe' @(
        '-C', $repositoryRoot, '-c', 'core.quotepath=false', 'ls-files', '--cached', '--others', '--exclude-standard'
    ) 'Git could not enumerate the current worktree'
    $files = [Collections.Generic.List[string]]::new()
    foreach ($line in $result.Lines) {
        $relative = [string]$line
        Require (-not [String]::IsNullOrWhiteSpace($relative)) 'Git returned an empty repository path'
        Require (-not [IO.Path]::IsPathRooted($relative) -and -not $relative.Contains('\')) 'Git returned a non-canonical repository path'
        Require (-not ($relative.Split('/') -contains '..')) 'A repository path escapes its root'
        $full = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $relative))
        Require ($full.StartsWith($repositoryRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) 'A repository file escaped its root'
        Require ([IO.File]::Exists($full)) 'An enumerated repository file is missing'
        $item = Get-Item -LiteralPath $full -Force
        Require (-not $item.PSIsContainer -and (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)) 'Release snapshots reject links and directories'
        Require ($relative -notmatch '(?i)(?:^|/)(?:sign\.properties|[^/]+\.(?:jks|keystore|p12|pfx|pem|key))$') 'Signing material entered the repository snapshot'
        $files.Add($relative)
    }
    $array = [string[]]$files.ToArray()
    [Array]::Sort($array, [StringComparer]::Ordinal)
    Require ($array.Count -ge 50) 'The repository snapshot contains too few files'
    return $array
}

function Test-IsReleaseInput {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    if ($RelativePath -in @(
        'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties', 'gradlew',
        'gradlew.bat', 'app/build.gradle.kts'
    )) { return $true }
    if ($RelativePath.StartsWith('app/src/main/', [StringComparison]::Ordinal)) { return $true }
    if ($RelativePath -match '^plugin-api/(?:protocol-wire-api|r8-compiler-api)/(?:build\.gradle\.kts|consumer-rules\.pro)$') { return $true }
    if ($RelativePath -match '^plugin-api/(?:protocol-wire-api|r8-compiler-api)/src/main/') { return $true }
    if ($RelativePath -match '^plugin-api/r8-compiler-api/releases/0\.1\.0/(?:contract-distribution-manifest\.json|protocol-wire-api-0\.1\.0\.aar|r8-compiler-api-0\.1\.0\.aar)$') { return $true }
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
        'build.gradle.kts', 'settings.gradle.kts', 'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.jar', 'app/build.gradle.kts', 'app/src/main/AndroidManifest.xml',
        'app/src/main/java/io/github/supermonster003/autojs6/plugin/r8compiler/BundledPlatformLibrary.kt',
        'app/src/main/java/io/github/supermonster003/autojs6/plugin/r8compiler/R8DiagnosticCollector.kt',
        'plugin-api/r8-compiler-api/releases/0.1.0/protocol-wire-api-0.1.0.aar',
        'plugin-api/r8-compiler-api/releases/0.1.0/r8-compiler-api-0.1.0.aar'
    )) {
        Require (@($records | Where-Object { $_.path -ceq $required }).Count -eq 1) 'The release input closure is incomplete'
    }
    Require ($records.Count -ge 35) 'The release input closure contains too few files'
    return @($records)
}

function Get-SourceFingerprint {
    param([Parameter(Mandatory = $true)][object[]]$Records)
    $builder = [Text.StringBuilder]::new()
    foreach ($record in $Records) {
        [void]$builder.Append([string]$record.path).Append([char]0)
        [void]$builder.Append(([long]$record.byteLength).ToString([Globalization.CultureInfo]::InvariantCulture)).Append([char]0)
        [void]$builder.Append([string]$record.sha256).Append("`n")
    }
    return Get-Sha256Bytes $utf8NoBom.GetBytes($builder.ToString())
}

function Assert-RecordsUnchanged {
    param([Parameter(Mandatory = $true)][object[]]$Records)
    foreach ($record in $Records) {
        $path = Join-Path $repositoryRoot ([string]$record.path)
        Require ([IO.File]::Exists($path)) 'A release input disappeared during publication'
        Require ((Get-Item -LiteralPath $path).Length -eq [long]$record.byteLength) 'A release input length changed during publication'
        Require ((Get-Sha256File $path) -ceq [string]$record.sha256) 'A release input digest changed during publication'
    }
}

function Copy-RepositorySnapshot {
    param(
        [Parameter(Mandatory = $true)][string[]]$RepositoryFiles,
        [Parameter(Mandatory = $true)][string]$Destination
    )
    [IO.Directory]::CreateDirectory($Destination) | Out-Null
    foreach ($relative in $RepositoryFiles) {
        $target = [IO.Path]::GetFullPath((Join-Path $Destination $relative))
        Require ($target.StartsWith($Destination.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) 'A snapshot path escaped its root'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
        [IO.File]::Copy((Join-Path $repositoryRoot $relative), $target, $false)
    }
}

function Invoke-IsolatedBuild {
    param(
        [Parameter(Mandatory = $true)][string]$Snapshot,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $gradle = Join-Path $Snapshot 'gradlew.bat'
    Write-Host "G7 isolated offline build $Label started"
    Push-Location $Snapshot
    try {
        $result = Invoke-Native $gradle @(
            '--offline', '--no-daemon', '--no-build-cache', '--no-configuration-cache', '--rerun-tasks', ':app:assembleRelease'
        ) "G7 isolated build $Label failed"
    } finally {
        Pop-Location
    }
    foreach ($line in $result.Lines) { Write-Host $line }
    $apk = Join-Path $Snapshot 'app/build/outputs/apk/release/app-release-unsigned.apk'
    Require ([IO.File]::Exists($apk)) "G7 isolated build $Label produced no APK"
    return [ordered]@{
        label = $Label
        path = $apk
        byteLength = [long](Get-Item -LiteralPath $apk).Length
        sha256 = Get-Sha256File $apk
    }
}

function Read-SigningProperties {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $signingPropertiesPath) {
        if ([String]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
        $match = [regex]::Match($line, '^\s*([^:=\s]+)\s*[:=]\s*(.*)$')
        if ($match.Success) { $values[$match.Groups[1].Value] = $match.Groups[2].Value.Trim() }
    }
    foreach ($key in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
        Require ($values.ContainsKey($key) -and -not [String]::IsNullOrEmpty([string]$values[$key])) 'The authorized signing configuration is incomplete'
    }
    return $values
}

function Sign-Apk {
    param(
        [Parameter(Mandatory = $true)][string]$UnsignedApk,
        [Parameter(Mandatory = $true)][string]$SignedApk,
        [Parameter(Mandatory = $true)][string]$Alias
    )
    [void](Invoke-Native $script:apksignerPath @(
        'sign', '--ks', $keystorePath, '--ks-key-alias', $Alias,
        '--ks-pass', 'env:AUTOJS6_R8_G7_STORE_PASSWORD',
        '--key-pass', 'env:AUTOJS6_R8_G7_KEY_PASSWORD',
        '--min-sdk-version', '24', '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true',
        '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false', '--out', $SignedApk, $UnsignedApk
    ) 'G7 APK signing failed')
    Require ([IO.File]::Exists($SignedApk)) 'G7 signed APK is missing'
    return [ordered]@{ path = $SignedApk; byteLength = [long](Get-Item -LiteralPath $SignedApk).Length; sha256 = Get-Sha256File $SignedApk }
}

function Verify-SignedApk {
    param([Parameter(Mandatory = $true)][string]$SignedApk)
    $verification = Invoke-Native $script:apksignerPath @(
        'verify', '--verbose', '--print-certs', '--min-sdk-version', '24', '--max-sdk-version', '36', $SignedApk
    ) 'G7 signed APK verification failed'
    Require ($verification.Text.Contains('Verified using v2 scheme (APK Signature Scheme v2): true')) 'G7 APK is not v2 signed'
    Require ($verification.Text.Contains('Verified using v3 scheme (APK Signature Scheme v3): true')) 'G7 APK is not v3 signed'
    Require ($verification.Text.Contains('Number of signers: 1')) 'G7 APK does not have exactly one signer'
    $matches = [regex]::Matches($verification.Text, '(?im)certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$')
    Require ($matches.Count -eq 1 -and $matches[0].Groups[1].Value -ceq $expectedCertificateSha256) 'G7 APK signer differs'

    $badging = Invoke-Native $script:aapt2Path @('dump', 'badging', $SignedApk) 'aapt2 rejected the G7 APK'
    Require ($badging.Text -match "(?m)^package: name='io\.github\.supermonster003\.autojs6\.plugin\.r8compiler' versionCode='1' versionName='0\.1\.0-provider-dev'") 'G7 APK identity differs'

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($SignedApk)
    try {
        $entry = $archive.GetEntry('assets/r8-library/android-36.jar')
        Require ($null -ne $entry) 'G7 APK lacks the pinned platform library asset'
        Require ([long]$entry.Length -eq $expectedPlatformLibrarySize) 'G7 platform library asset length differs'
        $stream = $entry.Open()
        $digest = [Security.Cryptography.SHA256]::Create()
        try {
            $assetSha256 = (($digest.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '')
        } finally {
            $digest.Dispose()
            $stream.Dispose()
        }
        Require ($assetSha256 -ceq $expectedPlatformLibrarySha256) 'G7 platform library asset digest differs'
    } finally {
        $archive.Dispose()
    }
}

function Publish-AppendOnlyRelease {
    param(
        [Parameter(Mandatory = $true)][string]$SignedApk,
        [Parameter(Mandatory = $true)][string]$SourceFingerprint
    )
    $apiDirectory = Join-Path $repositoryRoot 'plugin-api/r8-compiler-api/releases/0.1.0'
    $protocolAar = Join-Path $apiDirectory 'protocol-wire-api-0.1.0.aar'
    $compilerAar = Join-Path $apiDirectory 'r8-compiler-api-0.1.0.aar'
    Require ((Get-Sha256File $protocolAar) -ceq '1d97a5b44b2c20e85aa12b263fca604a32d6d89275d47a19076861cd20c29a36') 'Frozen protocol AAR changed'
    Require ((Get-Sha256File $compilerAar) -ceq 'e9df49b7e49992615a15bc0af2372a4525f02b4a2a915a560ddab3128bb2f066') 'Frozen R8 API AAR changed'

    [IO.Directory]::CreateDirectory($releaseParent) | Out-Null
    Require (-not [IO.Directory]::Exists($releaseStaging) -and -not [IO.File]::Exists($releaseStaging)) 'G7 release staging path already exists'
    [IO.Directory]::CreateDirectory($releaseStaging) | Out-Null
    $apkName = 'autojs6-r8-compiler-provider-0.1.0-provider-dev-signed.apk'
    [IO.File]::Copy($SignedApk, (Join-Path $releaseStaging $apkName), $false)
    [IO.File]::Copy($protocolAar, (Join-Path $releaseStaging 'protocol-wire-api-0.1.0.aar'), $false)
    [IO.File]::Copy($compilerAar, (Join-Path $releaseStaging 'r8-compiler-api-0.1.0.aar'), $false)
    $payloadRecords = @(
        New-FileRecord (Join-Path $releaseStaging $apkName) $apkName
        New-FileRecord (Join-Path $releaseStaging 'protocol-wire-api-0.1.0.aar') 'protocol-wire-api-0.1.0.aar'
        New-FileRecord (Join-Path $releaseStaging 'r8-compiler-api-0.1.0.aar') 'r8-compiler-api-0.1.0.aar'
    )
    $manifest = [ordered]@{
        schemaVersion = 'autojs6.r8.local-release/v2'
        releaseId = '0.1.0-provider-dev-local.5'
        channel = 'LOCAL_ONLY'
        sourceFingerprint = $SourceFingerprint
        compilerVersion = '8.13.17'
        platformLibrary = [ordered]@{ api = 36; byteLength = $expectedPlatformLibrarySize; sha256 = $expectedPlatformLibrarySha256 }
        signing = [ordered]@{ signerCount = 1; certificateSha256 = $expectedCertificateSha256; v2 = $true; v3 = $true }
        files = $payloadRecords
        claims = [ordered]@{ localPublished = $true; remotePublished = $false }
    }
    [IO.File]::WriteAllText(
        (Join-Path $releaseStaging 'release-manifest.json'),
        ($manifest | ConvertTo-Json -Depth 32 -Compress),
        $utf8NoBom
    )

    $status = 'NEW'
    if ([IO.Directory]::Exists($releaseDirectory)) {
        $status = 'IDENTICAL'
        $expectedNames = @($apkName, 'protocol-wire-api-0.1.0.aar', 'r8-compiler-api-0.1.0.aar', 'release-manifest.json')
        $actualNames = @(Get-ChildItem -LiteralPath $releaseDirectory -Force | ForEach-Object { $_.Name } | Sort-Object)
        Require (($actualNames -join "`n") -ceq (($expectedNames | Sort-Object) -join "`n")) 'Existing local.5 file set differs'
        foreach ($name in $expectedNames) {
            Require ((Get-Sha256File (Join-Path $releaseDirectory $name)) -ceq (Get-Sha256File (Join-Path $releaseStaging $name))) 'Existing local.5 bytes differ'
        }
    } else {
        Require (-not [IO.File]::Exists($releaseDirectory)) 'The local.5 release path is not a directory'
        [IO.Directory]::Move($releaseStaging, $releaseDirectory)
    }
    return $status
}

Write-FailedGate $failureStage

try {
    $failureStage = 'TOOLCHAIN_RESOLUTION'
    Require ([IO.File]::Exists($signingPropertiesPath) -and [IO.File]::Exists($keystorePath)) 'Authorized signing inputs are missing'
    $sdkRoot = if (-not [String]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        [IO.Path]::GetFullPath($env:ANDROID_HOME)
    } elseif (-not [String]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        [IO.Path]::GetFullPath($env:ANDROID_SDK_ROOT)
    } else {
        throw 'ANDROID_HOME or ANDROID_SDK_ROOT is required'
    }
    $script:apksignerPath = Join-Path $sdkRoot "build-tools/$BuildToolsVersion/apksigner.bat"
    $script:aapt2Path = Join-Path $sdkRoot "build-tools/$BuildToolsVersion/aapt2.exe"
    $platformLibraryPath = Join-Path $sdkRoot 'platforms/android-36/android.jar'
    foreach ($requiredTool in @($script:apksignerPath, $script:aapt2Path, $platformLibraryPath)) {
        Require ([IO.File]::Exists($requiredTool)) 'A pinned Android toolchain file is missing'
    }
    Require ((Get-Item -LiteralPath $platformLibraryPath).Length -eq $expectedPlatformLibrarySize) 'Installed Android 36 platform library size differs'
    Require ((Get-Sha256File $platformLibraryPath) -ceq $expectedPlatformLibrarySha256) 'Installed Android 36 platform library digest differs'

    $failureStage = 'SOURCE_SNAPSHOT'
    $repositoryFiles = Get-RepositoryFiles
    $releaseInputs = Get-ReleaseInputRecords $repositoryFiles
    $sourceFingerprint = Get-SourceFingerprint $releaseInputs
    if (-not [String]::IsNullOrWhiteSpace($ExpectedSourceFingerprint)) {
        Require ($sourceFingerprint -ceq $ExpectedSourceFingerprint.Trim().ToLowerInvariant()) 'Expected source fingerprint differs'
    }
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $snapshotA = Join-Path $temporaryRoot 'snapshot-a'
    $snapshotB = Join-Path $temporaryRoot 'snapshot-b'
    Copy-RepositorySnapshot $repositoryFiles $snapshotA
    Copy-RepositorySnapshot $repositoryFiles $snapshotB

    $failureStage = 'ISOLATED_BUILDS'
    $buildA = Invoke-IsolatedBuild $snapshotA 'A'
    $buildB = Invoke-IsolatedBuild $snapshotB 'B'
    Require ([string]$buildA.sha256 -ceq [string]$buildB.sha256 -and [long]$buildA.byteLength -eq [long]$buildB.byteLength) 'G7 unsigned APK builds are not byte-identical'

    $failureStage = 'SIGNING'
    $signingPropertiesSha256 = Get-Sha256File $signingPropertiesPath
    $keystoreSha256 = Get-Sha256File $keystorePath
    $signing = Read-SigningProperties
    $configuredStore = [string]$signing['storeFile']
    $configuredStorePath = if ([IO.Path]::IsPathRooted($configuredStore)) {
        [IO.Path]::GetFullPath($configuredStore)
    } else {
        [IO.Path]::GetFullPath((Join-Path 'D:\idea-projects\AutoJs6\app' $configuredStore))
    }
    Require ($configuredStorePath.Equals($keystorePath, [StringComparison]::OrdinalIgnoreCase)) 'Signing properties do not select the authorized keystore'
    $env:AUTOJS6_R8_G7_STORE_PASSWORD = [string]$signing['storePassword']
    $env:AUTOJS6_R8_G7_KEY_PASSWORD = [string]$signing['keyPassword']
    try {
        $signedA = Sign-Apk ([string]$buildA.path) (Join-Path $temporaryRoot 'provider-signed-a.apk') ([string]$signing['keyAlias'])
        $signedB = Sign-Apk ([string]$buildB.path) (Join-Path $temporaryRoot 'provider-signed-b.apk') ([string]$signing['keyAlias'])
    } finally {
        Remove-Item Env:AUTOJS6_R8_G7_STORE_PASSWORD -ErrorAction SilentlyContinue
        Remove-Item Env:AUTOJS6_R8_G7_KEY_PASSWORD -ErrorAction SilentlyContinue
    }
    Require ([string]$signedA.sha256 -ceq [string]$signedB.sha256 -and [long]$signedA.byteLength -eq [long]$signedB.byteLength) 'G7 signed APK passes are not byte-identical'
    Verify-SignedApk ([string]$signedA.path)
    Require ((Get-Sha256File $signingPropertiesPath) -ceq $signingPropertiesSha256) 'Signing properties changed during publication'
    Require ((Get-Sha256File $keystorePath) -ceq $keystoreSha256) 'Signing keystore changed during publication'
    Assert-RecordsUnchanged $releaseInputs

    $failureStage = 'APPEND_ONLY_PUBLICATION'
    $releaseStatus = Publish-AppendOnlyRelease ([string]$signedA.path) $sourceFingerprint
    $releaseFiles = @(Get-ChildItem -LiteralPath $releaseDirectory -File | Sort-Object Name | ForEach-Object {
        New-FileRecord $_.FullName ((Join-Path $releaseRelative $_.Name).Replace('\', '/'))
    })

    $failureStage = 'FINAL_REPORT'
    $g6Path = Join-Path $repositoryRoot 'build/reports/r42-g6/device-acceptance-gate.json'
    Require ([IO.File]::Exists($g6Path)) 'The positive G6 Gate is missing'
    $g6 = Get-Content -Raw -LiteralPath $g6Path | ConvertFrom-Json
    Require ([string]$g6.schemaVersion -ceq 'autojs6.r8.g6.device-acceptance-gate/v1' -and [bool]$g6.passed) 'The G6 prerequisite Gate is not positive'
    $finalReport = [ordered]@{
        schemaVersion = 'autojs6.r8.g7.local-release-gate/v1'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'APPEND_ONLY_LOCAL5_PLATFORM_LIBRARY_FIX_RELEASE'
        release = [ordered]@{
            releaseId = '0.1.0-provider-dev-local.5'
            channel = 'LOCAL_ONLY'
            status = $releaseStatus
            files = $releaseFiles
            remotePublished = $false
        }
        source = [ordered]@{
            fingerprint = $sourceFingerprint
            inputFiles = $releaseInputs.Count
        }
        builds = [ordered]@{
            isolatedSnapshots = 2
            offline = $true
            rerunTasks = $true
            unsignedApkByteLength = [long]$buildA.byteLength
            unsignedApkSha256 = [string]$buildA.sha256
            signedApkByteLength = [long]$signedA.byteLength
            signedApkSha256 = [string]$signedA.sha256
        }
        platformLibrary = [ordered]@{
            api = 36
            byteLength = $expectedPlatformLibrarySize
            sha256 = $expectedPlatformLibrarySha256
            embeddedAssetVerified = $true
        }
        signing = [ordered]@{
            signerCount = 1
            certificateSha256 = $expectedCertificateSha256
            v2 = $true
            v3 = $true
            secretsPersisted = $false
        }
        priorEvidence = [ordered]@{
            schemaVersion = [string]$g6.schemaVersion
            invocationId = [string]$g6.invocationId
            path = 'build/reports/r42-g6/device-acceptance-gate.json'
            byteLength = [long](Get-Item -LiteralPath $g6Path).Length
            sha256 = Get-Sha256File $g6Path
        }
        publisher = New-FileRecord $PSCommandPath 'scripts/publish-g7-local-release.ps1'
        operations = [ordered]@{
            gitPushPerformed = $false
            remotePublicationPerformed = $false
            priorReleaseModified = $false
        }
        claims = [ordered]@{
            localPublished = $true
            remotePublished = $false
            signedApkVerified = $true
            sameEnvironmentReproducible = $true
            platformLibraryFixPackaged = $true
            deviceVerified = $false
            retraceExecuted = $false
            jniLinked = $false
        }
        summary = 'Append-only local.5 packages the byte-pinned Android 36 compiler library after two identical isolated offline builds and signatures; remote publication remains false'
    }
    Write-AtomicJson $finalReport
    Write-Output "G7 local.5 release Gate passed: $reportPath"
    Write-Output "Invocation ID: $invocationId"
    Write-Output "Source fingerprint: $sourceFingerprint"
} catch {
    try { Write-FailedGate $failureStage } catch { Write-Error 'G7 release also failed to record its negative Gate' }
    throw
} finally {
    Remove-Item Env:AUTOJS6_R8_G7_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:AUTOJS6_R8_G7_KEY_PASSWORD -ErrorAction SilentlyContinue
    if ([IO.Directory]::Exists($temporaryRoot)) {
        $systemTempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $candidate = [IO.Path]::GetFullPath($temporaryRoot)
        if ($candidate.StartsWith($systemTempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($candidate).StartsWith('autojs6-r8-g7-release-', [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $candidate -Recurse -Force
        }
    }
    if ([IO.Directory]::Exists($releaseStaging)) {
        $parentPrefix = [IO.Path]::GetFullPath($releaseParent).TrimEnd('\') + '\'
        $candidate = [IO.Path]::GetFullPath($releaseStaging)
        if ($candidate.StartsWith($parentPrefix, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($candidate).StartsWith('.local.5.', [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $candidate -Recurse -Force
        }
    }
}
