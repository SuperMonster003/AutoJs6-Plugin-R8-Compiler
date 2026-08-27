[CmdletBinding()]
param(
    [switch]$AuthorizeDeviceRuntime,
    [Parameter(Mandatory = $true)][string]$Api25Serial,
    [Parameter(Mandatory = $true)][string]$Api28Serial,
    [Parameter(Mandatory = $true)][string]$Api37Serial,
    [Parameter(Mandatory = $true)][string]$Api25HostApkPath,
    [Parameter(Mandatory = $true)][string]$Api28HostApkPath,
    [Parameter(Mandatory = $true)][string]$Api37HostApkPath,
    [Parameter(Mandatory = $true)][string]$HostTestApkPath,
    [Parameter(Mandatory = $true)][string]$ProviderApkPath,
    [Parameter(Mandatory = $true)][string]$HostSourceRoot,
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
$hostSourceRoot = [IO.Path]::GetFullPath($HostSourceRoot)
$reportPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/reports/r42-g10/device-retrace-gate.json'))
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('autojs6-r8-g10-device-{0}' -f $invocationId.Replace('-', ''))))
$failureStage = 'INITIALIZATION'
$expectedCertificateSha256 = '2e64822e13a6c80c12e1c4b47e8fb32d1e9334526289da75777b7a79145de4b8'
$expectedProgramSha256 = 'b97e8e13fb201f5c013c585f3e1a39c85712e169070b8c56ef654194d7f7ae28'
$hostPackage = 'org.autojs.autojs6'
$testPackage = 'org.autojs.autojs6.test'
$providerPackage = 'io.github.supermonster003.autojs6.plugin.r8compiler'
$providerComponent = "$providerPackage/$providerPackage.R8CompilerService"
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$receiptKey = 'autojs.r8Compiler.g10.result'

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class R8G10DeviceAtomicFile {
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

function Write-AtomicJson {
    param([Parameter(Mandatory = $true)][object]$Value)
    $directory = [IO.Path]::GetDirectoryName($reportPath)
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = Join-Path $directory ('.{0}.{1}.tmp' -f [IO.Path]::GetFileName($reportPath), [Guid]::NewGuid().ToString('N'))
    try {
        [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Depth 64 -Compress), $utf8NoBom)
        if ([IO.File]::Exists($reportPath)) {
            [R8G10DeviceAtomicFile]::Replace($temporary, $reportPath)
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
        schemaVersion = 'autojs6.r8.g10.device-retrace-gate/v1'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'AUTHORIZED_DEBUG_APK_RHINO_RETRACE_MATRIX'
        failureStage = $Stage
        summary = 'G10 device Retrace acceptance failed closed; no positive device claim is valid'
        claims = [ordered]@{
            deviceVerified = $false
            publicRhinoEntryExecuted = $false
            providerRetraceExecuted = $false
            tamperedMappingRejected = $false
            publicVisibilityChanged = $false
            remoteMutationPerformed = $false
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
    return (Invoke-Adb $Serial @('shell', 'getprop', $Name) "Unable to read device property $Name").Text.Trim()
}

function Resolve-RegularFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $resolved = [IO.Path]::GetFullPath($Path)
    Require ([IO.File]::Exists($resolved)) "$Label is missing"
    $item = Get-Item -LiteralPath $resolved -Force
    Require (-not $item.PSIsContainer -and (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)) "$Label is not a regular file"
    return $resolved
}

function Get-ApkSignerSha256 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][int]$Sdk
    )
    $verification = Invoke-Native $script:apksignerPath @(
        'verify', '--print-certs', '--min-sdk-version', $Sdk.ToString(), '--max-sdk-version', $Sdk.ToString(), $Path
    ) 'apksigner rejected an APK'
    $matches = [regex]::Matches(
        $verification.Text,
        '(?im)^(?:V\d+(?:\.\d+)? Signer|Signer #\d+): certificate SHA-256 digest:\s*([0-9a-f]{64})\s*$'
    )
    Require ($matches.Count -eq 1) 'An APK does not have exactly one signer'
    return $matches[0].Groups[1].Value.ToLowerInvariant()
}

function Get-LocalApkRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][int]$Sdk
    )
    $resolved = Resolve-RegularFile $Path $Label
    $record = [ordered]@{
        label = $Label
        byteLength = [long](Get-Item -LiteralPath $resolved).Length
        sha256 = Get-Sha256File $resolved
        signerSha256 = Get-ApkSignerSha256 $resolved $Sdk
    }
    Require ([string]$record.signerSha256 -ceq $expectedCertificateSha256) "$Label does not use the admitted debug signer"
    return [pscustomobject]@{ Path = $resolved; PublicRecord = $record }
}

function Get-InstalledBasePath {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName
    )
    $result = Invoke-Adb $Serial @('shell', 'pm', 'path', $PackageName) "Unable to resolve installed package $PackageName"
    $lines = @($result.Lines | Where-Object { -not [String]::IsNullOrWhiteSpace($_) })
    Require ($lines.Count -eq 1 -and $lines[0].StartsWith('package:', [StringComparison]::Ordinal)) "Installed package $PackageName has no single base APK"
    $path = $lines[0].Substring('package:'.Length).Trim()
    Require ($path.StartsWith('/data/app/', [StringComparison]::Ordinal) -and $path.EndsWith('/base.apk', [StringComparison]::Ordinal)) "Installed package $PackageName path is outside the admitted boundary"
    Require ($path -cnotmatch '[\r\n\x00]') "Installed package $PackageName returned unsafe path bytes"
    return $path
}

function Get-InstalledPackageRecord {
    param(
        [Parameter(Mandatory = $true)][object]$Target,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][object]$Expected
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
    Require ([long]$record.byteLength -eq [long]$Expected.byteLength) "Installed $Label length differs from the admitted local APK"
    Require ([string]$record.sha256 -ceq [string]$Expected.sha256) "Installed $Label bytes differ from the admitted local APK"
    Require ([string]$record.signerSha256 -ceq $expectedCertificateSha256) "Installed $Label signer differs"
    return [pscustomobject]@{ PublicRecord = $record; RemotePath = $remotePath }
}

function Assert-Sha256 {
    param(
        [AllowNull()][object]$Value,
        [Parameter(Mandatory = $true)][string]$Label
    )
    Require (([string]$Value) -cmatch '^[0-9a-f]{64}$') "$Label is not a lowercase SHA-256"
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

function Assert-InstrumentationSuccess {
    param([Parameter(Mandatory = $true)][string]$Output)
    Require ($Output.Contains('INSTRUMENTATION_CODE: -1')) 'AndroidJUnitRunner did not complete normally'
    Require ($Output.Contains('OK (1 test)')) 'Instrumentation did not report exactly one passing test'
    Require (-not $Output.Contains('FAILURES!!!') -and -not $Output.Contains('INSTRUMENTATION_FAILED')) 'Instrumentation reported failure'
}

function Get-SingleReceipt {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string[]]$Lines)
    $prefix = "INSTRUMENTATION_STATUS: $receiptKey="
    $values = @($Lines | Where-Object { $_.StartsWith($prefix, [StringComparison]::Ordinal) })
    Require ($values.Count -eq 1) 'G10 instrumentation did not emit exactly one invocation receipt'
    try {
        return $values[0].Substring($prefix.Length) | ConvertFrom-Json
    } catch {
        throw 'G10 instrumentation receipt is not valid JSON'
    }
}

function Assert-G10Receipt {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][object]$Target
    )
    $expectedFields = @(
        'artCrashExecuted', 'compileCapabilityFingerprint', 'compilerVersion', 'crossApkBinder',
        'deviceIdentity', 'dexExported', 'exportedReports', 'freshRemoteCompile', 'hostPackage',
        'hostUid', 'hostVersionCode', 'invocationId', 'invocationMarkerSha256', 'manufacturer',
        'mappingByteLength', 'mappingHashVerified', 'mappingSha256', 'metadataByteLength',
        'metadataSha256', 'minApi', 'model', 'noFallbackObserved', 'obfuscatedStackByteLength',
        'obfuscatedStackSha256', 'originalCrashClassRestored', 'originalCrashMethodRestored',
        'originalSourceLineRestored', 'outputBundleSha256', 'outputBundleSizeBytes',
        'programSha256', 'providerComponent', 'providerPackage', 'providerRetraceExecuted',
        'providerSignerSha256', 'providerUid', 'providerVersionCode', 'publicRhinoEntryExecuted',
        'rawStackIsObfuscated', 'retracedStackByteLength', 'retracedStackSha256', 'roadmapStage',
        'ruleSha256', 'runtimeLibraryFingerprint', 'schemaVersion', 'scriptEngineClass', 'sdkInt', 'status', 'supportedAbis',
        'tamperedMappingRejected', 'verifiedReportExport'
    ) | Sort-Object
    $actualFields = @($Receipt.PSObject.Properties.Name | Sort-Object)
    Require (@(Compare-Object $expectedFields $actualFields -CaseSensitive).Count -eq 0) 'G10 receipt field boundary differs'
    Require ([int]$Receipt.schemaVersion -eq 1 -and [string]$Receipt.roadmapStage -ceq 'G10-DEVICE-RETRACE') 'G10 receipt schema or stage differs'
    Require ([string]$Receipt.status -ceq 'PASS' -and [string]$Receipt.invocationId -ceq $invocationId) 'G10 receipt is stale or not PASS'
    Require ([string]$Receipt.deviceIdentity -ceq $Target.deviceIdentity -and [int]$Receipt.sdkInt -eq $Target.sdkInt) 'G10 receipt device identity differs'
    Require ([string]$Receipt.manufacturer -ceq $Target.manufacturer -and [string]$Receipt.model -ceq $Target.model) 'G10 receipt device description differs'
    Require ((@($Receipt.supportedAbis) -join ',') -ceq $Target.abiList) 'G10 receipt ABI list differs'
    Require ([string]$Receipt.hostPackage -ceq $hostPackage -and [int]$Receipt.hostVersionCode -eq 5276) 'G10 host identity differs'
    Require ([string]$Receipt.providerPackage -ceq $providerPackage -and [string]$Receipt.providerComponent -ceq $providerComponent) 'G10 provider identity differs'
    Require ([long]$Receipt.providerVersionCode -eq 1 -and [int]$Receipt.hostUid -ne [int]$Receipt.providerUid) 'G10 provider version or UID isolation differs'
    $providerSigners = @($Receipt.providerSignerSha256)
    Require ($providerSigners.Count -eq 1 -and [string]$providerSigners[0] -ceq $expectedCertificateSha256) 'G10 provider signer differs'
    Require ([string]$Receipt.compilerVersion -ceq '8.13.17') 'G10 compiler version differs'
    Require ([int]$Receipt.minApi -eq [Math]::Min($Target.sdkInt, 36)) 'G10 compiler minApi differs'
    Require ([string]$Receipt.scriptEngineClass -ceq 'org.autojs.autojs.engine.LoopBasedJavaScriptEngine') 'G10 script engine identity differs'
    foreach ($name in @(
        'runtimeLibraryFingerprint', 'compileCapabilityFingerprint', 'programSha256', 'ruleSha256',
        'outputBundleSha256', 'mappingSha256', 'metadataSha256', 'obfuscatedStackSha256',
        'retracedStackSha256', 'invocationMarkerSha256'
    )) {
        Assert-Sha256 $Receipt.$name "G10 $name"
    }
    Require ([string]$Receipt.programSha256 -ceq $expectedProgramSha256) 'G10 program fixture digest differs'
    Require ([long]$Receipt.outputBundleSizeBytes -gt 0 -and [long]$Receipt.outputBundleSizeBytes -le 128MB) 'G10 output bundle length is invalid'
    Require ([long]$Receipt.mappingByteLength -gt 0 -and [long]$Receipt.mappingByteLength -le 16MB) 'G10 mapping length is invalid'
    Require ([long]$Receipt.metadataByteLength -gt 0 -and [long]$Receipt.metadataByteLength -le 256KB) 'G10 metadata length is invalid'
    Require ([long]$Receipt.obfuscatedStackByteLength -gt 0 -and [long]$Receipt.obfuscatedStackByteLength -le 1MB) 'G10 obfuscated stack length is invalid'
    Require ([long]$Receipt.retracedStackByteLength -gt 0 -and [long]$Receipt.retracedStackByteLength -le 4MB) 'G10 retraced stack length is invalid'
    Assert-BooleanFields $Receipt @(
        'crossApkBinder', 'freshRemoteCompile', 'artCrashExecuted', 'rawStackIsObfuscated',
        'publicRhinoEntryExecuted', 'providerRetraceExecuted', 'mappingHashVerified',
        'originalCrashClassRestored', 'originalCrashMethodRestored', 'originalSourceLineRestored',
        'tamperedMappingRejected', 'noFallbackObserved', 'verifiedReportExport'
    )
    $dexExported = $Receipt.PSObject.Properties['dexExported']
    Require ($null -ne $dexExported -and $dexExported.Value -is [bool] -and -not [bool]$dexExported.Value) 'G10 exported DEX unexpectedly'
    Require ((@($Receipt.exportedReports) -join ',') -ceq 'mapping.txt,seeds.txt,usage.txt,retrace-metadata.bin') 'G10 report export boundary differs'
}

function New-FileRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $resolved = Resolve-RegularFile $Path $Label
    return [ordered]@{
        path = $Label.Replace('\', '/')
        byteLength = [long](Get-Item -LiteralPath $resolved).Length
        sha256 = Get-Sha256File $resolved
    }
}

function Invoke-DeviceRuntime {
    param([Parameter(Mandatory = $true)][object]$Target)
    $hostInstalled = Get-InstalledPackageRecord $Target $hostPackage 'host' $Target.hostApk
    $testInstalled = Get-InstalledPackageRecord $Target $testPackage 'instrumentation' $script:hostTestApk.PublicRecord
    $providerInstalled = Get-InstalledPackageRecord $Target $providerPackage 'provider' $script:providerApk.PublicRecord

    $instrumentation = Invoke-Adb $Target.serial @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'org.autojs.autojs.core.plugin.r8.R8CompilerRealProviderG10AndroidTest#productionRhinoRetracesRealArtCrashAndRejectsTamperedMapping',
        '-e', 'autojs.r8Compiler.g10.enabled', 'true',
        '-e', 'autojs.r8Compiler.g10.invocationId', $invocationId,
        '-e', 'autojs.r8Compiler.g10.deviceIdentity', $Target.deviceIdentity,
        '-e', 'autojs.r8Compiler.g10.sdk', $Target.sdkInt.ToString(),
        $runner
    ) 'G10 instrumentation transport failed'
    Assert-InstrumentationSuccess $instrumentation.Text
    $receipt = Get-SingleReceipt $instrumentation.Lines
    Assert-G10Receipt $receipt $Target

    foreach ($installed in @(
        [pscustomobject]@{ packageName = $hostPackage; remotePath = $hostInstalled.RemotePath },
        [pscustomobject]@{ packageName = $testPackage; remotePath = $testInstalled.RemotePath },
        [pscustomobject]@{ packageName = $providerPackage; remotePath = $providerInstalled.RemotePath }
    )) {
        Require ((Get-InstalledBasePath $Target.serial $installed.packageName) -ceq $installed.remotePath) "Installed package path changed during G10: $($installed.packageName)"
    }

    return [ordered]@{
        role = $Target.role
        transportSerial = $Target.serial
        deviceIdentity = $Target.deviceIdentity
        avdName = $Target.avdName
        sdkInt = $Target.sdkInt
        compilerMinApi = [Math]::Min($Target.sdkInt, 36)
        physical = $false
        manufacturer = $Target.manufacturer
        model = $Target.model
        abiList = $Target.abiList
        pageSizeBytes = $Target.pageSizeBytes
        installedPackages = [ordered]@{
            host = $hostInstalled.PublicRecord
            instrumentation = $testInstalled.PublicRecord
            provider = $providerInstalled.PublicRecord
        }
        runtime = [ordered]@{
            hostVersionCode = [int]$receipt.hostVersionCode
            providerVersionCode = [long]$receipt.providerVersionCode
            compilerVersion = [string]$receipt.compilerVersion
            runtimeLibraryFingerprint = [string]$receipt.runtimeLibraryFingerprint
            compileCapabilityFingerprint = [string]$receipt.compileCapabilityFingerprint
            programSha256 = [string]$receipt.programSha256
            ruleSha256 = [string]$receipt.ruleSha256
            outputBundleByteLength = [long]$receipt.outputBundleSizeBytes
            outputBundleSha256 = [string]$receipt.outputBundleSha256
            mappingByteLength = [long]$receipt.mappingByteLength
            mappingSha256 = [string]$receipt.mappingSha256
            metadataByteLength = [long]$receipt.metadataByteLength
            metadataSha256 = [string]$receipt.metadataSha256
            obfuscatedStackByteLength = [long]$receipt.obfuscatedStackByteLength
            obfuscatedStackSha256 = [string]$receipt.obfuscatedStackSha256
            retracedStackByteLength = [long]$receipt.retracedStackByteLength
            retracedStackSha256 = [string]$receipt.retracedStackSha256
            invocationMarkerSha256 = [string]$receipt.invocationMarkerSha256
            scriptEngineClass = [string]$receipt.scriptEngineClass
            exportedReports = @($receipt.exportedReports)
            dexExported = $false
        }
        assertions = [ordered]@{
            crossApkBinder = $true
            freshRemoteCompile = $true
            artCrashExecuted = $true
            rawStackIsObfuscated = $true
            publicRhinoEntryExecuted = $true
            providerRetraceExecuted = $true
            mappingHashVerified = $true
            originalCrashClassRestored = $true
            originalCrashMethodRestored = $true
            originalSourceLineRestored = $true
            tamperedMappingRejected = $true
            noFallbackObserved = $true
            verifiedReportExport = $true
        }
        tests = [ordered]@{ classInvocations = 1; tests = 1; failures = 0; errors = 0; skipped = 0; structuredReceipts = 1 }
    }
}

Write-FailedGate $failureStage

try {
    $failureStage = 'EXPLICIT_AUTHORIZATION'
    Require ([bool]$AuthorizeDeviceRuntime) 'Pass -AuthorizeDeviceRuntime to permit G10 instrumentation on the fixed AVD matrix'
    $serials = @($Api25Serial.Trim(), $Api28Serial.Trim(), $Api37Serial.Trim())
    foreach ($serial in $serials) {
        Require ($serial -cmatch '^emulator-[0-9]+$') 'G10 accepts only emulator transports'
    }
    Require (@($serials | Select-Object -Unique).Count -eq 3) 'G10 requires three distinct AVD transports'

    $failureStage = 'TOOLCHAIN_AND_SOURCE_BOUNDARY'
    Require ([IO.Directory]::Exists($hostSourceRoot)) 'The clean AutoJs6 source worktree is missing'
    if ([String]::IsNullOrWhiteSpace($AdbPath)) {
        $script:resolvedAdbPath = [IO.Path]::GetFullPath((Get-Command adb.exe -ErrorAction Stop).Source)
    } else {
        $script:resolvedAdbPath = [IO.Path]::GetFullPath($AdbPath)
    }
    Require ([IO.File]::Exists($script:resolvedAdbPath)) 'adb executable is missing'
    $sdkRoot = [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($script:resolvedAdbPath))
    $script:apksignerPath = [IO.Path]::GetFullPath((Join-Path $sdkRoot "build-tools/$BuildToolsVersion/apksigner.bat"))
    Require ([IO.File]::Exists($script:apksignerPath)) 'Pinned apksigner is missing'
    $script:gitPath = [IO.Path]::GetFullPath((Get-Command git.exe -ErrorAction Stop).Source)
    $hostStatus = Invoke-Native $script:gitPath @('-C', $hostSourceRoot, 'status', '--short') 'Unable to inspect the host worktree'
    Require ([String]::IsNullOrWhiteSpace($hostStatus.Text)) 'The admitted AutoJs6 source worktree is not clean'
    $providerProductionStatus = Invoke-Native $script:gitPath @('-C', $repositoryRoot, 'status', '--short', '--', 'app', 'plugin-api', 'build.gradle.kts', 'settings.gradle.kts', 'gradle') 'Unable to inspect provider production sources'
    Require ([String]::IsNullOrWhiteSpace($providerProductionStatus.Text)) 'Provider production sources differ from the committed boundary'
    $providerCommit = (Invoke-Native $script:gitPath @('-C', $repositoryRoot, 'rev-parse', 'HEAD') 'Unable to resolve provider commit').Text.Trim()
    $hostCommit = (Invoke-Native $script:gitPath @('-C', $hostSourceRoot, 'rev-parse', 'HEAD') 'Unable to resolve host commit').Text.Trim()
    Require ($providerCommit -cmatch '^[0-9a-f]{40}$' -and $hostCommit -cmatch '^[0-9a-f]{40}$') 'A source commit ID is invalid'

    $script:providerApk = Get-LocalApkRecord $ProviderApkPath 'provider-debug.apk' 24
    $script:hostTestApk = Get-LocalApkRecord $HostTestApkPath 'host-androidTest-debug.apk' 24
    $hostApks = @(
        Get-LocalApkRecord $Api25HostApkPath 'host-api25-x86-debug.apk' 25
        Get-LocalApkRecord $Api28HostApkPath 'host-api28-x86_64-debug.apk' 28
        Get-LocalApkRecord $Api37HostApkPath 'host-api37-x86_64-debug.apk' 37
    )

    $failureStage = 'DEVICE_MATRIX_IDENTITY'
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $specifications = @(
        [ordered]@{ role = 'AVD_API25_X86'; serial = $serials[0]; sdk = 25; abi = 'x86'; avdName = 'R1_API25_Play'; pageSize = 0; hostApk = $hostApks[0].PublicRecord },
        [ordered]@{ role = 'AVD_API28_X86_64'; serial = $serials[1]; sdk = 28; abi = 'x86_64'; avdName = 'DEX_R1_API28_X64'; pageSize = 0; hostApk = $hostApks[1].PublicRecord },
        [ordered]@{ role = 'AVD_API37_X86_64_16K'; serial = $serials[2]; sdk = 37; abi = 'x86_64'; avdName = 'AVD_API_37'; pageSize = 16384; hostApk = $hostApks[2].PublicRecord }
    )
    $targets = [Collections.Generic.List[object]]::new()
    foreach ($specification in $specifications) {
        Require ((Invoke-Adb $specification.serial @('get-state') 'AVD is unavailable').Text.Trim() -ceq 'device') 'ADB target is not in device state'
        $sdk = [int](Get-DeviceProperty $specification.serial 'ro.build.version.sdk')
        Require ($sdk -eq [int]$specification.sdk) "G10 target SDK differs: $($specification.role)"
        Require ((Get-DeviceProperty $specification.serial 'ro.kernel.qemu') -ceq '1') "G10 target is not an AVD: $($specification.role)"
        $abiList = Get-DeviceProperty $specification.serial 'ro.product.cpu.abilist'
        if ([String]::IsNullOrWhiteSpace($abiList)) { $abiList = Get-DeviceProperty $specification.serial 'ro.product.cpu.abi' }
        Require (@($abiList.Split(',')) -contains [string]$specification.abi) "G10 target ABI differs: $($specification.role)"
        $stableSerial = Get-DeviceProperty $specification.serial 'ro.serialno'
        if ([String]::IsNullOrWhiteSpace($stableSerial)) { $stableSerial = Get-DeviceProperty $specification.serial 'ro.boot.serialno' }
        Require (-not [String]::IsNullOrWhiteSpace($stableSerial)) 'G10 AVD has no stable serial property'
        $avdName = Get-DeviceProperty $specification.serial 'ro.boot.qemu.avd_name'
        if ([String]::IsNullOrWhiteSpace($avdName)) { $avdName = Get-DeviceProperty $specification.serial 'ro.kernel.qemu.avd_name' }
        Require ($avdName -ceq [string]$specification.avdName) "G10 AVD name differs: $($specification.role)"
        $pageSize = 0
        if ([int]$specification.pageSize -gt 0) {
            $pageSize = [int](Invoke-Adb $specification.serial @('shell', 'getconf', 'PAGE_SIZE') 'Unable to read API 37 page size').Text.Trim()
            Require ($pageSize -eq [int]$specification.pageSize) 'The API 37 G10 AVD is not using a 16 KiB page size'
        }
        $targetDirectory = Join-Path $temporaryRoot ([string]$specification.role)
        [IO.Directory]::CreateDirectory($targetDirectory) | Out-Null
        $targets.Add([pscustomobject]@{
            role = [string]$specification.role
            serial = [string]$specification.serial
            sdkInt = $sdk
            deviceIdentity = "$stableSerial@$avdName"
            avdName = $avdName
            manufacturer = Get-DeviceProperty $specification.serial 'ro.product.manufacturer'
            model = Get-DeviceProperty $specification.serial 'ro.product.model'
            abiList = $abiList
            pageSizeBytes = $pageSize
            hostApk = $specification.hostApk
            tempDirectory = $targetDirectory
        })
    }

    $failureStage = 'RHINO_ART_PROVIDER_RETRACE'
    $deviceRecords = [Collections.Generic.List[object]]::new()
    foreach ($target in $targets) {
        Write-Output "G10 device Retrace started: $($target.role) [$($target.serial)]"
        $deviceRecords.Add((Invoke-DeviceRuntime $target))
        Write-Output "G10 device Retrace passed: $($target.role) [$($target.serial)]"
    }

    $failureStage = 'FINAL_REPORT'
    $hostTestSource = Join-Path $hostSourceRoot 'app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderG10AndroidTest.kt'
    $sourceFiles = @(
        New-FileRecord (Join-Path $repositoryRoot 'app/src/main/java/io/github/supermonster003/autojs6/plugin/r8compiler/R8CompilerRuntime.kt') 'app/src/main/java/io/github/supermonster003/autojs6/plugin/r8compiler/R8CompilerRuntime.kt'
        New-FileRecord (Join-Path $repositoryRoot 'app/src/main/java/io/github/supermonster003/autojs6/plugin/r8compiler/Api25CompatibleR8RetraceCommandRunner.kt') 'app/src/main/java/io/github/supermonster003/autojs6/plugin/r8compiler/Api25CompatibleR8RetraceCommandRunner.kt'
        New-FileRecord (Join-Path $repositoryRoot 'app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/r8compiler/R8RetraceAndroidTest.kt') 'app/src/androidTest/java/io/github/supermonster003/autojs6/plugin/r8compiler/R8RetraceAndroidTest.kt'
        New-FileRecord $hostTestSource 'AutoJs6/app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderG10AndroidTest.kt'
        New-FileRecord (Join-Path $hostSourceRoot 'app/src/main/java/org/autojs/autojs/core/plugin/r8/R8CompilerArtifactExporter.kt') 'AutoJs6/app/src/main/java/org/autojs/autojs/core/plugin/r8/R8CompilerArtifactExporter.kt'
        New-FileRecord (Join-Path $repositoryRoot 'scripts/r42-g7-host-r8-runtime.init.gradle') 'scripts/r42-g7-host-r8-runtime.init.gradle'
        New-FileRecord (Join-Path $repositoryRoot 'docs/retrace-device-acceptance-v1.md') 'docs/retrace-device-acceptance-v1.md'
        New-FileRecord $PSCommandPath 'scripts/verify-g10-device-retrace.ps1'
    )
    $finalReport = [ordered]@{
        schemaVersion = 'autojs6.r8.g10.device-retrace-gate/v1'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'AUTHORIZED_DEBUG_APK_RHINO_RETRACE_MATRIX'
        source = [ordered]@{
            providerCommit = $providerCommit
            hostCommit = $hostCommit
            files = $sourceFiles
            localApks = [ordered]@{
                provider = $script:providerApk.PublicRecord
                instrumentation = $script:hostTestApk.PublicRecord
                hosts = @($hostApks | ForEach-Object { $_.PublicRecord })
            }
        }
        matrix = [ordered]@{
            physicalDevices = 0
            avds = 3
            sdkLevels = @(25, 28, 37)
            compilerMinApis = @(25, 28, 36)
            abiFamilies = @('x86', 'x86_64')
            includes16KiBPageDevice = $true
            devices = @($deviceRecords)
        }
        tests = [ordered]@{
            classInvocations = 3
            tests = 3
            structuredReceipts = 3
            realArtCrashes = 3
            providerRetraceExecutions = 3
            tamperedMappingRejections = 3
            failures = 0
            errors = 0
            skipped = 0
        }
        operations = [ordered]@{
            explicitAuthorization = $true
            debugSignerOnly = $true
            packageInstallPerformedByGate = $false
            packageUninstallPerformedByGate = $false
            packageDataClearedByGate = $false
            explicitForceStopCommandPerformedByGate = $false
            instrumentationLifecycleMayForceStopHost = $true
            avdStartedByGate = $false
            physicalDeviceTouched = $false
            rawMappingMetadataOrStackPersistedInGate = $false
            officialSigningMaterialAccessed = $false
            gitPushPerformed = $false
            remoteReleaseMutationPerformed = $false
            visibilityChangePerformed = $false
        }
        claims = [ordered]@{
            exactInstalledDebugApksVerified = $true
            crossApkBinderVerified = $true
            freshRemoteCompileVerified = $true
            realArtCrashExecuted = $true
            publicRhinoEntryExecuted = $true
            providerRetraceExecuted = $true
            mappingHashVerified = $true
            originalCrashClassRestored = $true
            originalCrashMethodRestored = $true
            originalSourceLineRestored = $true
            tamperedMappingRejected = $true
            noFallbackObserved = $true
            verifiedFourReportExport = $true
            dexNotExported = $true
            api25Verified = $true
            api28Verified = $true
            api37MinApi36Verified = $true
            pageSize16KiBVerified = $true
            deviceVerified = $true
            publicVisibilityChanged = $false
            remoteMutationPerformed = $false
        }
        summary = 'Authorized debug-only G10 Rhino/ART/provider Retrace and tampered-mapping rejection passed on API 25 x86, API 28 x86_64, and API 37 x86_64 16 KiB AVDs; no G9 publication action was performed'
    }
    Write-AtomicJson $finalReport
    Write-Output "G10 device Retrace Gate passed: $reportPath"
    Write-Output "Invocation ID: $invocationId"
} catch {
    try { Write-FailedGate $failureStage } catch { Write-Error 'G10 device Gate also failed to record its negative result' }
    throw
} finally {
    if ([IO.Directory]::Exists($temporaryRoot)) {
        $systemTempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $candidate = [IO.Path]::GetFullPath($temporaryRoot)
        if ($candidate.StartsWith($systemTempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($candidate).StartsWith('autojs6-r8-g10-device-', [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $candidate -Recurse -Force
        }
    }
}
