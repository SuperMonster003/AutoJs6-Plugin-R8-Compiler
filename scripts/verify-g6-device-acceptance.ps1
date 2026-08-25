[CmdletBinding()]
param(
    [switch]$AuthorizeDeviceAcceptance,
    [Parameter(Mandatory = $true)][string]$PhysicalSerial,
    [Parameter(Mandatory = $true)][string]$Api28AvdSerial,
    [Parameter(Mandatory = $true)][string]$Api25AvdSerial,
    [string]$AdbPath = '',
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
$hostRepositoryRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '..\AutoJs6'))
$reportPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/reports/r42-g6/device-acceptance-gate.json'))
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ("autojs6-r8-g6-{0}" -f $invocationId.Replace('-', ''))))
$failureStage = 'INITIALIZATION'
$expectedCertificateSha256 = '31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213'
$hostPackage = 'org.autojs.autojs6'
$testPackage = 'org.autojs.autojs6.test'
$providerPackage = 'io.github.supermonster003.autojs6.plugin.r8compiler'
$providerComponent = "$providerPackage/$providerPackage.R8CompilerService"
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$protectedSerials = @('QV710AF65F', '968e9f18')

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class R8G6AtomicFile {
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

function Get-Sha256File {
    param([Parameter(Mandatory = $true)][string]$Path)
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return (($sha256.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $sha256.Dispose()
        $stream.Dispose()
    }
}

function Write-AtomicJson {
    param([Parameter(Mandatory = $true)][object]$Value)
    $directory = [IO.Path]::GetDirectoryName($reportPath)
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = Join-Path $directory (".{0}.{1}.tmp" -f [IO.Path]::GetFileName($reportPath), [Guid]::NewGuid().ToString('N'))
    try {
        $json = $Value | ConvertTo-Json -Depth 64 -Compress
        [IO.File]::WriteAllText($temporary, $json, $utf8NoBom)
        if ([IO.File]::Exists($reportPath)) {
            [R8G6AtomicFile]::Replace($temporary, $reportPath)
        } else {
            [IO.File]::Move($temporary, $reportPath)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) {
            [IO.File]::Delete($temporary)
        }
    }
}

function Write-FailedGate {
    param(
        [Parameter(Mandatory = $true)][string]$Code,
        [Parameter(Mandatory = $true)][string]$Stage
    )
    Write-AtomicJson ([ordered]@{
        schemaVersion = 'autojs6.r8.g6.device-acceptance-gate/v1'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'AUTHORIZED_CROSS_APK_BINDER_PFD_DEVICE_ACCEPTANCE'
        failureCode = $Code
        failureStage = $Stage
        summary = 'G6 device acceptance failed closed; no positive device claim is valid'
        claims = [ordered]@{
            localPublished = $false
            remotePublished = $false
            installedProviderBytesVerified = $false
            crossApkBinderVerified = $false
            pfdLifecycleVerified = $false
            processDeathVerified = $false
            physicalDeviceVerified = $false
            deviceVerified = $false
        }
    })
}

function Require {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) { throw $Message }
}

function Assert-Sha256 {
    param(
        [AllowNull()][object]$Value,
        [Parameter(Mandatory = $true)][string]$Label
    )
    Require (([string]$Value) -cmatch '^[0-9a-f]{64}$') "$Label is not a lowercase SHA-256"
}

function Assert-SafeSerial {
    param([Parameter(Mandatory = $true)][string]$Serial)
    Require ($Serial -cmatch '^[A-Za-z0-9._:-]+$') 'A device serial contains unsupported characters'
    Require ($Serial -notin $protectedSerials) 'A protected device serial was explicitly excluded from G6'
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

function Assert-FileRecord {
    param(
        [Parameter(Mandatory = $true)][object]$Record,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $relative = [string]$Record.path
    Require (-not [IO.Path]::IsPathRooted($relative)) 'An evidence record contains a rooted path'
    $full = [IO.Path]::GetFullPath((Join-Path $Root $relative))
    $prefix = $Root.TrimEnd('\') + '\'
    Require ($full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) 'An evidence record escapes its root'
    Require ([IO.File]::Exists($full)) "An evidence file is missing: $relative"
    Require ((Get-Item -LiteralPath $full).Length -eq [long]$Record.byteLength) "Evidence length changed: $relative"
    Require ((Get-Sha256File $full) -ceq [string]$Record.sha256) "Evidence digest changed: $relative"
}

function Read-PositiveGate {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Schema
    )
    Require ([IO.File]::Exists($Path)) 'A prerequisite Gate report is missing'
    $gate = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    Require ([string]$gate.schemaVersion -ceq $Schema) 'A prerequisite Gate schema differs'
    Require ([bool]$gate.passed) 'A prerequisite Gate is not positive'
    Require ([string]$gate.invocationId -cmatch '^[0-9a-f-]{36}$') 'A prerequisite invocation ID is invalid'
    return $gate
}

function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    $previousErrorActionPreference = $ErrorActionPreference
    $nativeOutput = @()
    try {
        # Windows PowerShell 5.1 wraps any native stderr line in NativeCommandError. adb pull writes
        # successful progress to stderr, so capture both channels and decide only from exit code.
        $ErrorActionPreference = 'Continue'
        $nativeOutput = @(& $FilePath @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $lines = @($nativeOutput | ForEach-Object {
        if ($_ -is [Management.Automation.ErrorRecord]) {
            [string]$_.Exception.Message
        } else {
            [string]$_
        }
    })
    if ($exitCode -ne 0) {
        throw "$FailureMessage (exit $exitCode)"
    }
    return [pscustomobject]@{
        Lines = [string[]]$lines
        Text = ($lines -join "`n")
    }
}

function Invoke-Adb {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    return Invoke-Native $script:resolvedAdbPath (@('-s', $Serial) + $Arguments) $FailureMessage
}

function Get-DeviceProperty {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $result = Invoke-Adb $Serial @('shell', 'getprop', $Name) "Unable to read device property $Name"
    return $result.Text.Trim()
}

function Get-InstalledBasePath {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )
    $result = Invoke-Adb $Serial @('shell', 'pm', 'path', $PackageName) "Unable to resolve installed package $PackageName"
    $lines = @($result.Lines | Where-Object { -not [String]::IsNullOrWhiteSpace($_) })
    Require ($lines.Count -eq 1) "Installed package $PackageName does not have exactly one base APK"
    Require ($lines[0].StartsWith('package:', [StringComparison]::Ordinal)) "Installed package $PackageName returned a malformed path"
    $path = $lines[0].Substring('package:'.Length).Trim()
    Require ($path.StartsWith('/data/app/', [StringComparison]::Ordinal)) "Installed package $PackageName is outside /data/app"
    Require ($path.EndsWith('/base.apk', [StringComparison]::Ordinal)) "Installed package $PackageName is not a base APK"
    Require ($path -cnotmatch '[\r\n\x00]') "Installed package $PackageName returned unsafe path bytes"
    return $path
}

function Get-ApkSignerSha256 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][int]$Sdk
    )
    $result = Invoke-Native $script:apksignerPath @(
        'verify', '--print-certs', '--min-sdk-version', $Sdk.ToString(), '--max-sdk-version', $Sdk.ToString(), $Path
    ) 'apksigner rejected an installed APK'
    $matches = [regex]::Matches(
        $result.Text,
        '(?im)^(?:V\d+(?:\.\d+)? Signer|Signer #\d+): certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$'
    )
    Require ($matches.Count -eq 1) 'An installed APK does not have exactly one signer'
    return $matches[0].Groups[1].Value.ToLowerInvariant()
}

function Get-InstalledPackageRecord {
    param(
        [Parameter(Mandatory = $true)][object]$Target,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $remotePath = Get-InstalledBasePath $Target.serial $PackageName
    $localPath = Join-Path $Target.tempDirectory "$Label-base.apk"
    [void](Invoke-Adb $Target.serial @('pull', $remotePath, $localPath) "Unable to pull installed $Label APK")
    Require ([IO.File]::Exists($localPath)) "Pulled $Label APK is missing"
    $record = [ordered]@{
        packageName = $PackageName
        byteLength = [long](Get-Item -LiteralPath $localPath).Length
        sha256 = Get-Sha256File $localPath
        signerSha256 = Get-ApkSignerSha256 $localPath $Target.sdkInt
    }
    Assert-Sha256 $record.sha256 "$Label APK digest"
    Require ($record.signerSha256 -ceq $expectedCertificateSha256) "$Label APK signer differs from the authorized host signer"
    return [pscustomobject]@{
        PublicRecord = $record
        RemotePath = $remotePath
    }
}

function Assert-InstrumentationSuccess {
    param(
        [Parameter(Mandatory = $true)][string]$Output,
        [Parameter(Mandatory = $true)][int]$ExpectedTests
    )
    Require ($Output.Contains('INSTRUMENTATION_CODE: -1')) 'Instrumentation did not report AndroidJUnitRunner success'
    $suffix = if ($ExpectedTests -eq 1) { 'test' } else { 'tests' }
    Require ($Output.Contains("OK ($ExpectedTests $suffix)")) 'Instrumentation did not report the exact passing test count'
    Require (-not $Output.Contains('FAILURES!!!')) 'Instrumentation reported a test failure'
    Require (-not $Output.Contains('INSTRUMENTATION_FAILED')) 'Instrumentation failed to start'
}

function Get-InstrumentationReceipts {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines,
        [Parameter(Mandatory = $true)][string]$Key
    )
    $prefix = "INSTRUMENTATION_STATUS: $Key="
    $receipts = [Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        if ($line.StartsWith($prefix, [StringComparison]::Ordinal)) {
            $encoded = $line.Substring($prefix.Length)
            try {
                $receipts.Add(($encoded | ConvertFrom-Json))
            } catch {
                throw "Instrumentation receipt $Key is not valid JSON"
            }
        }
    }
    return @($receipts)
}

function Assert-BooleanFields {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][string[]]$Names
    )
    foreach ($name in $Names) {
        $property = $Receipt.PSObject.Properties[$name]
        Require ($null -ne $property -and $property.Value -is [bool] -and [bool]$property.Value) "Receipt field $name is not exactly true"
    }
}

function Assert-CommonReceipt {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][object]$Target
    )
    Require ([int]$Receipt.schemaVersion -eq 1) 'Instrumentation receipt schema differs'
    Require ([string]$Receipt.status -ceq 'PASS') 'Instrumentation receipt is not PASS'
    Require ([string]$Receipt.deviceIdentity -ceq $Target.deviceIdentity) 'Instrumentation receipt device identity differs'
    Require ([int]$Receipt.sdkInt -eq $Target.sdkInt) 'Instrumentation receipt SDK differs'
    Require ([string]$Receipt.hostPackage -ceq $hostPackage) 'Instrumentation receipt host package differs'
    Require ([int]$Receipt.hostVersionCode -eq 5276) 'Instrumentation receipt host version differs'
    Require ([string]$Receipt.providerPackage -ceq $providerPackage) 'Instrumentation receipt provider package differs'
    Require ([string]$Receipt.providerComponent -ceq $providerComponent) 'Instrumentation receipt provider component differs'
    Require ([int]$Receipt.providerVersionCode -eq 1) 'Instrumentation receipt provider version differs'
}

function Assert-HappyReceipt {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][object]$Target
    )
    Assert-CommonReceipt $Receipt $Target
    Require ([string]$Receipt.roadmapStage -ceq 'G6-REAL-R8-HAPPY') 'Happy receipt stage differs'
    Require ([string]$Receipt.compilerVersion -ceq '8.13.17') 'Happy receipt compiler version differs'
    Require ([int]$Receipt.minApi -eq $Target.sdkInt) 'Happy receipt compiler minApi differs'
    Require ([int]$Receipt.fixtureAnswer -eq 42) 'Happy receipt did not execute answer42'
    Require ([int]$Receipt.hostUid -ne [int]$Receipt.providerUid) 'Provider unexpectedly shares the host UID'
    Assert-BooleanFields $Receipt @(
        'crossApkBinder', 'canonicalPfdTransaction', 'freshRemoteCompile', 'verifiedCacheHit',
        'fiveArtifactsVerified', 'productionR8OnlyLoader'
    )
    $signers = @($Receipt.providerSignerSha256)
    Require ($signers.Count -eq 1 -and [string]$signers[0] -ceq $expectedCertificateSha256) 'Happy receipt signer differs'
    Assert-Sha256 $Receipt.cacheKeySha256 'Happy cache key'
    Assert-Sha256 $Receipt.outputBundleSha256 'Happy output bundle'
    Require ([long]$Receipt.outputBundleSizeBytes -gt 0) 'Happy output bundle is empty'
    $artifacts = @($Receipt.artifacts)
    $expectedRoles = @('DEX_ZIP', 'MAPPING_TEXT', 'SEEDS_TEXT', 'USAGE_TEXT', 'RETRACE_METADATA')
    Require ($artifacts.Count -eq $expectedRoles.Count) 'Happy receipt does not contain five artifacts'
    Require ((@($artifacts | ForEach-Object { [string]$_.role }) -join ',') -ceq ($expectedRoles -join ',')) 'Happy artifact roles differ'
    foreach ($artifact in $artifacts) {
        Assert-Sha256 $artifact.sha256 "Happy $($artifact.role) artifact"
        Require ([long]$artifact.sizeBytes -ge 0) "Happy $($artifact.role) artifact has negative size"
        if ([string]$artifact.role -ne 'USAGE_TEXT') {
            Require ([long]$artifact.sizeBytes -gt 0) "Happy $($artifact.role) artifact is empty"
        }
    }
}

function Assert-LifecycleReceipt {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][object]$Target
    )
    Assert-CommonReceipt $Receipt $Target
    Require ([string]$Receipt.roadmapStage -ceq 'G6-REAL-R8-LIFECYCLE') 'Lifecycle receipt stage differs'
    Require ([string]$Receipt.providerSignerSha256 -ceq $expectedCertificateSha256) 'Lifecycle receipt signer differs'
    Assert-BooleanFields $Receipt @(
        'crossApkBinder', 'blockedInputPipe', 'busyRejected', 'singleTerminal',
        'idempotentCancelClose', 'calleeDescriptorOwnership', 'outputPipeEof',
        'sessionGateRecovered', 'hostileBundleRejected'
    )
    Require ([string]$Receipt.hostileErrorCode -ceq 'INVALID_BUNDLE') 'Lifecycle hostile error code differs'
    Require ([string]$Receipt.hostileFailurePhase -ceq 'INPUT_VALIDATION') 'Lifecycle hostile failure phase differs'
}

function Assert-ProcessDeathReceipt {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][object]$Target
    )
    Assert-CommonReceipt $Receipt $Target
    Require ([string]$Receipt.roadmapStage -ceq 'G6-REAL-R8-PROCESS-DEATH') 'Process-death receipt stage differs'
    Require ([string]$Receipt.providerSignerSha256 -ceq $expectedCertificateSha256) 'Process-death receipt signer differs'
    Assert-BooleanFields $Receipt @(
        'crossApkBinder', 'packageExactForceStop', 'binderDeathObserved', 'outputPipeEof',
        'identityRevalidated', 'authenticatedRebind', 'providerRecovered'
    )
    Require ([int]$Receipt.providerCallbackTerminalCount -eq 0) 'Dead provider emitted a terminal callback'
}

function Invoke-DeviceAcceptance {
    param([Parameter(Mandatory = $true)][object]$Target)

    $hostInstalled = Get-InstalledPackageRecord $Target $hostPackage 'host'
    $testInstalled = Get-InstalledPackageRecord $Target $testPackage 'instrumentation'
    $providerInstalled = Get-InstalledPackageRecord $Target $providerPackage 'provider'
    Require ([string]$providerInstalled.PublicRecord.sha256 -ceq $script:officialProviderSha256) 'Installed provider bytes differ from the official local.4 APK'
    Require ([long]$providerInstalled.PublicRecord.byteLength -eq $script:officialProviderByteLength) 'Installed provider length differs from the official local.4 APK'

    $happy = Invoke-Adb $Target.serial @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'org.autojs.autojs.core.plugin.r8.R8CompilerRealProviderAndroidTest',
        '-e', 'autojs.r8Compiler.realProvider.enabled', 'true',
        '-e', 'autojs.r8Compiler.realProvider.deviceIdentity', $Target.deviceIdentity,
        '-e', 'autojs.r8Compiler.realProvider.sdk', $Target.sdkInt.ToString(),
        $runner
    ) 'Happy-path instrumentation transport failed'
    Assert-InstrumentationSuccess $happy.Text 1
    $happyReceipts = @(Get-InstrumentationReceipts $happy.Lines 'autojs.r8Compiler.realProvider.result')
    Require ($happyReceipts.Count -eq 1) 'Happy-path instrumentation did not emit exactly one receipt'
    Assert-HappyReceipt $happyReceipts[0] $Target

    $lifecycle = Invoke-Adb $Target.serial @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'org.autojs.autojs.core.plugin.r8.R8CompilerRealProviderLifecycleAndroidTest',
        '-e', 'autojs.r8Compiler.realProviderLifecycle.enabled', 'true',
        '-e', 'autojs.r8Compiler.realProviderLifecycle.deviceIdentity', $Target.deviceIdentity,
        '-e', 'autojs.r8Compiler.realProviderLifecycle.sdk', $Target.sdkInt.ToString(),
        '-e', 'autojs.r8Compiler.realProviderLifecycle.forceStopPackage', $providerPackage,
        $runner
    ) 'Lifecycle instrumentation transport failed'
    Assert-InstrumentationSuccess $lifecycle.Text 2
    $lifecycleReceipts = @(Get-InstrumentationReceipts $lifecycle.Lines 'autojs.r8Compiler.realProviderLifecycle.result')
    Require ($lifecycleReceipts.Count -eq 2) 'Lifecycle instrumentation did not emit exactly two receipts'
    $lifecycleReceipt = @($lifecycleReceipts | Where-Object { $_.roadmapStage -ceq 'G6-REAL-R8-LIFECYCLE' })
    $processDeathReceipt = @($lifecycleReceipts | Where-Object { $_.roadmapStage -ceq 'G6-REAL-R8-PROCESS-DEATH' })
    Require ($lifecycleReceipt.Count -eq 1 -and $processDeathReceipt.Count -eq 1) 'Lifecycle receipt stages are not exact'
    Assert-LifecycleReceipt $lifecycleReceipt[0] $Target
    Assert-ProcessDeathReceipt $processDeathReceipt[0] $Target

    foreach ($installed in @(
        [pscustomobject]@{ packageName = $hostPackage; remotePath = $hostInstalled.RemotePath },
        [pscustomobject]@{ packageName = $testPackage; remotePath = $testInstalled.RemotePath },
        [pscustomobject]@{ packageName = $providerPackage; remotePath = $providerInstalled.RemotePath }
    )) {
        $after = Get-InstalledBasePath $Target.serial $installed.packageName
        Require ($after -ceq $installed.remotePath) "Installed package path changed during G6: $($installed.packageName)"
    }

    return [ordered]@{
        role = $Target.role
        transportSerial = $Target.serial
        deviceIdentity = $Target.deviceIdentity
        sdkInt = $Target.sdkInt
        physical = [bool]$Target.physical
        manufacturer = $Target.manufacturer
        model = $Target.model
        abiList = $Target.abiList
        buildFingerprint = $Target.buildFingerprint
        installedPackages = [ordered]@{
            host = $hostInstalled.PublicRecord
            instrumentation = $testInstalled.PublicRecord
            provider = $providerInstalled.PublicRecord
        }
        tests = [ordered]@{
            classInvocations = 2
            tests = 3
            failures = 0
            errors = 0
            skipped = 0
        }
        receipts = [ordered]@{
            happy = $happyReceipts[0]
            lifecycle = $lifecycleReceipt[0]
            processDeath = $processDeathReceipt[0]
        }
    }
}

Write-FailedGate 'IN_PROGRESS' $failureStage

try {
    $failureStage = 'EXPLICIT_AUTHORIZATION'
    Require ([bool]$AuthorizeDeviceAcceptance) 'Pass -AuthorizeDeviceAcceptance to permit G6 instrumentation and exact provider force-stop'
    $serials = @($PhysicalSerial.Trim(), $Api28AvdSerial.Trim(), $Api25AvdSerial.Trim())
    foreach ($serial in $serials) { Assert-SafeSerial $serial }
    Require (@($serials | Select-Object -Unique).Count -eq 3) 'G6 requires three distinct device serials'

    $failureStage = 'TOOLCHAIN_RESOLUTION'
    Require ([IO.Directory]::Exists($hostRepositoryRoot)) 'The sibling AutoJs6 host repository is missing'
    if ([String]::IsNullOrWhiteSpace($AdbPath)) {
        $adbCommand = Get-Command adb.exe -ErrorAction Stop
        $script:resolvedAdbPath = [IO.Path]::GetFullPath($adbCommand.Source)
    } else {
        $script:resolvedAdbPath = [IO.Path]::GetFullPath($AdbPath)
    }
    Require ([IO.File]::Exists($script:resolvedAdbPath)) 'adb executable is missing'
    $androidSdkRoot = [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($script:resolvedAdbPath))
    $script:apksignerPath = [IO.Path]::GetFullPath((Join-Path $androidSdkRoot "build-tools/$BuildToolsVersion/apksigner.bat"))
    Require ([IO.File]::Exists($script:apksignerPath)) 'Pinned apksigner is missing'

    $failureStage = 'PRIOR_EVIDENCE'
    $g2Path = Join-Path $repositoryRoot 'build/reports/r42-g2/provider-gate.json'
    $g3Path = Join-Path $repositoryRoot 'build/reports/r42-g3/host-control-plane-gate.json'
    $g4Path = Join-Path $repositoryRoot 'build/reports/r42-g4/compatibility-corpus-gate.json'
    $g5Path = Join-Path $repositoryRoot 'build/reports/r42-g5/local-release-gate.json'
    $identityPath = Join-Path $repositoryRoot 'docs/identity-reservation.json'
    $designPath = Join-Path $repositoryRoot 'docs/device-acceptance-v1.md'
    $g2 = Read-PositiveGate $g2Path 'autojs6.r8.g2.provider-gate/v2'
    $g3 = Read-PositiveGate $g3Path 'autojs6.r8.g3.host-transaction-gate/v4'
    $g4 = Read-PositiveGate $g4Path 'autojs6.r8.g4.compatibility-corpus-gate/v1'
    $g5 = Read-PositiveGate $g5Path 'autojs6.r8.g5.local-release-gate/v1'
    Require ([IO.File]::Exists($identityPath)) 'Current identity reservation is missing'
    Require ([IO.File]::Exists($designPath)) 'G6 device-acceptance design is missing'
    $identity = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
    Require ([string]$identity.status -ceq 'LOCAL_PROVIDER_HOST_AND_DEVICE_VERIFIED_NOT_REMOTELY_PUBLISHED') 'Current identity status does not match G6'
    Require ([bool]$identity.claims.providerImplemented -and [bool]$identity.claims.manifestDiscoverable) 'Current provider identity claims differ'
    Require ([bool]$identity.claims.hostIntegrated -and [bool]$identity.claims.pluginConsumed) 'Current host identity claims differ'
    Require ([bool]$identity.claims.r8Executed -and [bool]$identity.claims.compatibilityCorpusVerified) 'Current R8 identity claims differ'
    Require ([bool]$identity.claims.binderVerified -and [bool]$identity.claims.deviceVerified) 'Current G6 identity claims differ'
    Require (-not [bool]$identity.claims.retraceExecuted -and -not [bool]$identity.claims.published) 'Current identity overclaims retrace or publication'
    Require ([string]$g5.release.releaseId -ceq '0.1.0-provider-dev-local.4') 'G5 does not bind local.4'
    Require ([string]$g5.release.status -ceq 'IDENTICAL') 'G5 final invocation is not IDENTICAL'
    Require ([bool]$g5.claims.localPublished -and -not [bool]$g5.claims.remotePublished) 'G5 publication claims differ'
    Require ([bool]$g5.claims.signedApkVerified -and [bool]$g5.claims.sameEnvironmentReproducible) 'G5 release verification claims differ'
    Require ([string]$g5.signing.certificateSha256 -ceq $expectedCertificateSha256) 'G5 certificate differs'
    Require ([int]$g5.signing.signerCount -eq 1) 'G5 signer count differs'
    Require ([string]$g4.priorEvidence.g2InvocationId -ceq [string]$g2.invocationId) 'G4 does not bind current G2'
    Require ([string]$g4.priorEvidence.g3InvocationId -ceq [string]$g3.invocationId) 'G4 does not bind current G3'
    Require ([string]$g4.priorEvidence.g2ReportSha256 -ceq (Get-Sha256File $g2Path)) 'G4 G2 digest differs'
    Require ([string]$g4.priorEvidence.g3ReportSha256 -ceq (Get-Sha256File $g3Path)) 'G4 G3 digest differs'
    foreach ($prior in @($g5.priorEvidence)) {
        $priorPath = Join-Path $repositoryRoot ([string]$prior.path)
        Require ([IO.File]::Exists($priorPath)) 'A G5 prerequisite report is missing'
        Require ((Get-Item -LiteralPath $priorPath).Length -eq [long]$prior.byteLength) 'A G5 prerequisite length differs'
        Require ((Get-Sha256File $priorPath) -ceq [string]$prior.sha256) 'A G5 prerequisite digest differs'
    }
    $hostHead = (& git -C $hostRepositoryRoot rev-parse HEAD).Trim()
    Require ($LASTEXITCODE -eq 0 -and $hostHead -ceq [string]$g3.host.baseCommit) 'Host HEAD no longer matches G3'
    foreach ($record in @($g3.hostProductionSources) + @($g3.hostTestSources) + @($g3.hostIntegrationFiles) + @($g3.hostDesign)) {
        Assert-FileRecord $record $hostRepositoryRoot
    }
    Assert-FileRecord $g3.verifier $repositoryRoot

    $signedRelease = @($g5.release.files | Where-Object { $_.path -like '*-signed.apk' })
    Require ($signedRelease.Count -eq 1) 'G5 release does not contain exactly one signed APK'
    $officialProviderPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot ([string]$signedRelease[0].path)))
    Require ([IO.File]::Exists($officialProviderPath)) 'Official local.4 APK is missing'
    $script:officialProviderSha256 = [string]$signedRelease[0].sha256
    $script:officialProviderByteLength = [long]$signedRelease[0].byteLength
    Assert-Sha256 $script:officialProviderSha256 'Official provider APK digest'
    Require ((Get-Sha256File $officialProviderPath) -ceq $script:officialProviderSha256) 'Official local.4 APK digest differs'
    Require ((Get-Item -LiteralPath $officialProviderPath).Length -eq $script:officialProviderByteLength) 'Official local.4 APK length differs'

    $failureStage = 'DEVICE_MATRIX_IDENTITY'
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $targetSpecifications = @(
        [ordered]@{ role = 'PHYSICAL_API28_ARM64'; serial = $serials[0]; sdk = 28; physical = $true; abi = 'arm64-v8a' },
        [ordered]@{ role = 'AVD_API28_X86_64'; serial = $serials[1]; sdk = 28; physical = $false; abi = 'x86_64' },
        [ordered]@{ role = 'AVD_API25_X86'; serial = $serials[2]; sdk = 25; physical = $false; abi = 'x86' }
    )
    $targets = [Collections.Generic.List[object]]::new()
    foreach ($specification in $targetSpecifications) {
        $state = (Invoke-Adb $specification.serial @('get-state') 'Device is unavailable').Text.Trim()
        Require ($state -ceq 'device') 'ADB target is not in device state'
        $sdk = [int](Get-DeviceProperty $specification.serial 'ro.build.version.sdk')
        $qemu = Get-DeviceProperty $specification.serial 'ro.kernel.qemu'
        $isPhysical = $qemu -cne '1'
        $abiList = Get-DeviceProperty $specification.serial 'ro.product.cpu.abilist'
        if ([String]::IsNullOrWhiteSpace($abiList)) {
            $abiList = Get-DeviceProperty $specification.serial 'ro.product.cpu.abi'
        }
        Require ($sdk -eq [int]$specification.sdk) "Device SDK differs for $($specification.role)"
        Require ($isPhysical -eq [bool]$specification.physical) "Physical/AVD identity differs for $($specification.role)"
        Require ((@($abiList.Split(',')) -contains [string]$specification.abi)) "Required ABI is absent for $($specification.role)"
        $stableSerial = Get-DeviceProperty $specification.serial 'ro.serialno'
        if ([String]::IsNullOrWhiteSpace($stableSerial)) {
            $stableSerial = Get-DeviceProperty $specification.serial 'ro.boot.serialno'
        }
        Require (-not [String]::IsNullOrWhiteSpace($stableSerial)) 'Device exposed no stable serial property'
        $avdName = Get-DeviceProperty $specification.serial 'ro.boot.qemu.avd_name'
        if ([String]::IsNullOrWhiteSpace($avdName)) {
            $avdName = Get-DeviceProperty $specification.serial 'ro.kernel.qemu.avd_name'
        }
        if (-not $isPhysical) {
            Require (-not [String]::IsNullOrWhiteSpace($avdName)) 'AVD exposed no stable AVD name'
        }
        $deviceIdentity = if ([String]::IsNullOrWhiteSpace($avdName)) { $stableSerial } else { "$stableSerial@$avdName" }
        $targetDirectory = Join-Path $temporaryRoot ([string]$specification.role)
        [IO.Directory]::CreateDirectory($targetDirectory) | Out-Null
        $targets.Add([pscustomobject]@{
            role = [string]$specification.role
            serial = [string]$specification.serial
            sdkInt = $sdk
            physical = $isPhysical
            deviceIdentity = $deviceIdentity
            manufacturer = Get-DeviceProperty $specification.serial 'ro.product.manufacturer'
            model = Get-DeviceProperty $specification.serial 'ro.product.model'
            abiList = $abiList
            buildFingerprint = Get-DeviceProperty $specification.serial 'ro.build.fingerprint'
            tempDirectory = $targetDirectory
        })
    }

    $failureStage = 'DEVICE_INSTRUMENTATION'
    $deviceRecords = [Collections.Generic.List[object]]::new()
    foreach ($target in $targets) {
        Write-Output "G6 device acceptance started: $($target.role) [$($target.serial)]"
        $deviceRecords.Add((Invoke-DeviceAcceptance $target))
        Write-Output "G6 device acceptance passed: $($target.role) [$($target.serial)]"
    }

    $failureStage = 'FINAL_REPORT'
    $verifierRecord = New-FileRecord $PSCommandPath 'scripts/verify-g6-device-acceptance.ps1'
    $initRecord = New-FileRecord (Join-Path $repositoryRoot 'scripts/r42-g6-host-r8-androidtest.init.gradle') 'scripts/r42-g6-host-r8-androidtest.init.gradle'
    $happySourceRecord = New-FileRecord (
        Join-Path $hostRepositoryRoot 'app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderAndroidTest.kt'
    ) 'AutoJs6/app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderAndroidTest.kt'
    $lifecycleSourceRecord = New-FileRecord (
        Join-Path $hostRepositoryRoot 'app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderLifecycleAndroidTest.kt'
    ) 'AutoJs6/app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderLifecycleAndroidTest.kt'
    $identityRecord = New-FileRecord $identityPath 'docs/identity-reservation.json'
    $designRecord = New-FileRecord $designPath 'docs/device-acceptance-v1.md'
    $priorEvidence = @(
        New-FileRecord $g2Path 'build/reports/r42-g2/provider-gate.json'
        New-FileRecord $g3Path 'build/reports/r42-g3/host-control-plane-gate.json'
        New-FileRecord $g4Path 'build/reports/r42-g4/compatibility-corpus-gate.json'
        New-FileRecord $g5Path 'build/reports/r42-g5/local-release-gate.json'
    )
    for ($index = 0; $index -lt $priorEvidence.Count; $index++) {
        $priorEvidence[$index]['invocationId'] = @($g2, $g3, $g4, $g5)[$index].invocationId
        $priorEvidence[$index]['schemaVersion'] = @($g2, $g3, $g4, $g5)[$index].schemaVersion
    }
    $finalReport = [ordered]@{
        schemaVersion = 'autojs6.r8.g6.device-acceptance-gate/v1'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'AUTHORIZED_CROSS_APK_BINDER_PFD_DEVICE_ACCEPTANCE'
        release = [ordered]@{
            releaseId = [string]$g5.release.releaseId
            channel = 'LOCAL_ONLY'
            g5Status = [string]$g5.release.status
            providerApk = [ordered]@{
                path = [string]$signedRelease[0].path
                byteLength = $script:officialProviderByteLength
                sha256 = $script:officialProviderSha256
                signerSha256 = $expectedCertificateSha256
            }
            remotePublished = $false
        }
        matrix = [ordered]@{
            physicalDevices = 1
            avds = 2
            sdkLevels = @(25, 28)
            abiFamilies = @('arm64-v8a', 'x86_64', 'x86')
            devices = @($deviceRecords)
        }
        tests = [ordered]@{
            classInvocations = 6
            tests = 9
            structuredReceipts = 9
            failures = 0
            errors = 0
            skipped = 0
        }
        priorEvidence = $priorEvidence
        hostAndroidTestSources = @($happySourceRecord, $lifecycleSourceRecord)
        hostAndroidTestIsolation = $initRecord
        currentIdentity = $identityRecord
        design = $designRecord
        verifier = $verifierRecord
        operations = [ordered]@{
            explicitAuthorization = $true
            installedByGate = $false
            uninstalledByGate = $false
            packageExactProviderForceStop = $true
            protectedDevicesTouched = $false
            gitPushPerformed = $false
            remotePublicationPerformed = $false
        }
        claims = [ordered]@{
            localPublished = $true
            remotePublished = $false
            officialG5ProviderBytesInstalled = $true
            sameSignerCrossApkBoundaryVerified = $true
            crossApkBinderVerified = $true
            canonicalPfdTransactionVerified = $true
            realR8Executed = $true
            canonicalFiveArtifactBundleVerified = $true
            verifiedCacheHit = $true
            postR8DexRuntimeExecuted = $true
            pfdLifecycleVerified = $true
            hostileInputVerified = $true
            busyAndCancellationVerified = $true
            processDeathVerified = $true
            authenticatedRebindVerified = $true
            api25Verified = $true
            api28Verified = $true
            physicalDeviceVerified = $true
            deviceVerified = $true
            jniLinked = $false
        }
        summary = 'Authorized local.4 acceptance passed on one physical API 28 arm64 device and API 28/API 25 x86-family AVDs'
    }
    Write-AtomicJson $finalReport
    Write-Output "G6 device acceptance gate passed: $reportPath"
    Write-Output "Invocation ID: $invocationId"
} catch {
    try {
        Write-FailedGate ("{0}_FAILED" -f $failureStage) $failureStage
    } catch {
        Write-Error 'G6 also failed to atomically record its negative Gate'
    }
    throw
} finally {
    if ([IO.Directory]::Exists($temporaryRoot)) {
        $canonicalTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $canonicalTarget = [IO.Path]::GetFullPath($temporaryRoot)
        if ($canonicalTarget.StartsWith($canonicalTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($canonicalTarget).StartsWith('autojs6-r8-g6-', [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $canonicalTarget -Recurse -Force
        } else {
            Write-Error 'Refusing to remove a G6 temporary directory outside the canonical temp root'
        }
    }
}
