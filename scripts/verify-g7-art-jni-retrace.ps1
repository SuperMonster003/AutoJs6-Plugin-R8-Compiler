[CmdletBinding()]
param(
    [switch]$AuthorizeDeviceRuntime,
    [Parameter(Mandatory = $true)][string]$PhysicalSerial,
    [Parameter(Mandatory = $true)][string]$ModernAvdSerial,
    [Parameter(Mandatory = $true)][string]$Api25AvdSerial,
    [string]$AdbPath = '',
    [string]$JavaPath = '',
    [string]$R8JarPath = '',
    [string]$BuildToolsVersion = '37.0.0'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$utf8NoBom = [Text.UTF8Encoding]::new($false)
$strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
$invocationId = [Guid]::NewGuid().ToString()
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$hostRepositoryRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot '..\AutoJs6'))
$reportPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/reports/r42-g7/art-jni-retrace-gate.json'))
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('autojs6-r8-g7-runtime-{0}' -f $invocationId.Replace('-', ''))))
$failureStage = 'INITIALIZATION'
$expectedCertificateSha256 = '31a681fcfffb3e428420cae280ded89292b12a3b0f59e19b7a73e32a8ae4c213'
$expectedR8JarSha256 = 'd31fd0dc751d48740009cdd9a485126acb1d0d14c59b9f05579479940f4adf74'
$expectedR8JarSize = 18279079L
$expectedProgramSha256 = 'b97e8e13fb201f5c013c585f3e1a39c85712e169070b8c56ef654194d7f7ae28'
$hostPackage = 'org.autojs.autojs6'
$testPackage = 'org.autojs.autojs6.test'
$providerPackage = 'io.github.supermonster003.autojs6.plugin.r8compiler'
$providerComponent = "$providerPackage/$providerPackage.R8CompilerService"
$runner = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$receiptKey = 'autojs.r8Compiler.g7.result'
$protectedSerials = @('QV710AF65F', '968e9f18')

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class R8G7RuntimeAtomicFile {
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
            [R8G7RuntimeAtomicFile]::Replace($temporary, $reportPath)
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
        schemaVersion = 'autojs6.r8.g7.art-jni-retrace-gate/v1'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'AUTHORIZED_LOCAL5_ART_JNI_AND_PINNED_RETRACE'
        failureStage = $Stage
        summary = 'G7 ART/JNI/Retrace acceptance failed closed; no positive runtime claim is valid'
        claims = [ordered]@{
            localPublished = $false
            remotePublished = $false
            deviceVerified = $false
            artExecuted = $false
            jniLinked = $false
            retraceExecuted = $false
        }
    })
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
    Require ($Serial -notin $protectedSerials) 'A protected physical serial is excluded from G7'
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

function New-FileRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    Require ([IO.File]::Exists($Path)) "Evidence file is missing: $RelativePath"
    $item = Get-Item -LiteralPath $Path -Force
    Require (-not $item.PSIsContainer -and (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -eq 0)) "Evidence file is unsafe: $RelativePath"
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
    Require (-not [IO.Path]::IsPathRooted($relative) -and -not ($relative.Split('/') -contains '..')) 'An evidence record contains an unsafe path'
    $full = [IO.Path]::GetFullPath((Join-Path $Root $relative))
    Require ($full.StartsWith($Root.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) 'An evidence record escapes its root'
    Require ([IO.File]::Exists($full)) "An evidence file is missing: $relative"
    Require ((Get-Item -LiteralPath $full).Length -eq [long]$Record.byteLength) "Evidence length changed: $relative"
    Require ((Get-Sha256File $full) -ceq [string]$Record.sha256) "Evidence digest changed: $relative"
}

function Read-PositiveGate {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Schema
    )
    Require ([IO.File]::Exists($Path)) 'A prerequisite Gate is missing'
    $gate = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    Require ([string]$gate.schemaVersion -ceq $Schema -and [bool]$gate.passed) 'A prerequisite Gate is not positive or has the wrong schema'
    Require ([string]$gate.invocationId -cmatch '^[0-9a-f-]{36}$') 'A prerequisite invocation ID is invalid'
    return $gate
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

function Get-ApkSignerSha256 {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][int]$Sdk
    )
    $verification = Invoke-Native $script:apksignerPath @(
        'verify', '--print-certs', '--min-sdk-version', $Sdk.ToString(), '--max-sdk-version', $Sdk.ToString(), $Path
    ) 'apksigner rejected an installed APK'
    $matches = [regex]::Matches(
        $verification.Text,
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
    Require ([string]$record.signerSha256 -ceq $expectedCertificateSha256) "$Label signer differs from the authorized host signer"
    return [pscustomobject]@{ PublicRecord = $record; RemotePath = $remotePath }
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
    Require ($values.Count -eq 1) 'G7 instrumentation did not emit exactly one invocation receipt'
    try {
        return $values[0].Substring($prefix.Length) | ConvertFrom-Json
    } catch {
        throw 'G7 instrumentation receipt is not valid JSON'
    }
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

function Assert-G7Receipt {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][object]$Target
    )
    Require ([int]$Receipt.schemaVersion -eq 1 -and [string]$Receipt.roadmapStage -ceq 'G7-ART-JNI-RETRACE') 'G7 receipt schema or stage differs'
    Require ([string]$Receipt.status -ceq 'PASS' -and [string]$Receipt.invocationId -ceq $invocationId) 'G7 receipt is stale or not PASS'
    Require ([string]$Receipt.deviceIdentity -ceq $Target.deviceIdentity -and [int]$Receipt.sdkInt -eq $Target.sdkInt) 'G7 receipt device identity differs'
    Require ([string]$Receipt.hostPackage -ceq $hostPackage -and [int]$Receipt.hostVersionCode -eq 5276) 'G7 receipt host identity differs'
    Require ([string]$Receipt.providerPackage -ceq $providerPackage -and [string]$Receipt.providerComponent -ceq $providerComponent) 'G7 receipt provider identity differs'
    Require ([long]$Receipt.providerVersionCode -eq 1 -and [int]$Receipt.hostUid -ne [int]$Receipt.providerUid) 'G7 provider version or UID isolation differs'
    $signers = @($Receipt.providerSignerSha256)
    Require ($signers.Count -eq 1 -and [string]$signers[0] -ceq $expectedCertificateSha256) 'G7 receipt provider signer differs'
    Require ([string]$Receipt.compilerVersion -ceq '8.13.17') 'G7 compiler version differs'
    Require ([int]$Receipt.minApi -eq [Math]::Min($Target.sdkInt, 36)) 'G7 compiler minApi differs'
    foreach ($name in @('runtimeLibraryFingerprint', 'capabilityFingerprint', 'programSha256', 'ruleSha256', 'outputBundleSha256', 'mappingSha256', 'obfuscatedStackSha256', 'nativeLibrarySha256')) {
        Assert-Sha256 $Receipt.$name "G7 $name"
    }
    Require ([string]$Receipt.programSha256 -ceq $expectedProgramSha256) 'G7 program fixture digest differs'
    Require ([long]$Receipt.outputBundleSizeBytes -gt 0) 'G7 output bundle is empty'
    Require ([int]$Receipt.nativeInstanceResult -eq 42 -and [string]$Receipt.nativeStaticResult -ceq 'g7-jni-static') 'G7 native results differ'
    Assert-BooleanFields $Receipt @(
        'crossApkBinder', 'freshRemoteCompile', 'canonicalFiveArtifacts', 'productionR8OnlyLoader',
        'artExecuted', 'reflectionExecuted', 'dynamicNameExecuted', 'serializationExecuted',
        'publicEntryExecuted', 'jniLinked', 'removedDecoyAbsent', 'rawStackIsObfuscated'
    )
    $artifacts = @($Receipt.artifacts)
    $roles = @('DEX_ZIP', 'MAPPING_TEXT', 'SEEDS_TEXT', 'USAGE_TEXT', 'RETRACE_METADATA')
    Require ($artifacts.Count -eq 5 -and ((@($artifacts | ForEach-Object { [string]$_.role }) -join ',') -ceq ($roles -join ','))) 'G7 artifact roles differ'
    foreach ($artifact in $artifacts) {
        Require ([long]$artifact.sizeBytes -gt 0) "G7 $($artifact.role) artifact is empty"
        Assert-Sha256 $artifact.sha256 "G7 $($artifact.role) artifact"
    }
}

function Invoke-PinnedRetrace {
    param(
        [Parameter(Mandatory = $true)][object]$Receipt,
        [Parameter(Mandatory = $true)][object]$Target
    )
    try {
        $mappingBytes = [Convert]::FromBase64String([string]$Receipt.mappingBase64)
        $stackBytes = [Convert]::FromBase64String([string]$Receipt.obfuscatedStackBase64)
    } catch {
        throw 'G7 mapping or stack is not canonical Base64'
    }
    Require ([Convert]::ToBase64String($mappingBytes) -ceq [string]$Receipt.mappingBase64) 'G7 mapping Base64 is not canonical'
    Require ([Convert]::ToBase64String($stackBytes) -ceq [string]$Receipt.obfuscatedStackBase64) 'G7 stack Base64 is not canonical'
    Require ($mappingBytes.Length -gt 0 -and $mappingBytes.Length -le 16MB) 'G7 mapping bytes exceed their admitted boundary'
    Require ($stackBytes.Length -gt 0 -and $stackBytes.Length -le 64KB) 'G7 stack bytes exceed their admitted boundary'
    Require ((Get-Sha256Bytes $mappingBytes) -ceq [string]$Receipt.mappingSha256) 'G7 mapping receipt digest differs'
    Require ((Get-Sha256Bytes $stackBytes) -ceq [string]$Receipt.obfuscatedStackSha256) 'G7 stack receipt digest differs'
    $mappingText = $strictUtf8.GetString($mappingBytes)
    $stackText = $strictUtf8.GetString($stackBytes)
    Require ($mappingText.Contains('org.autojs.fixture.r8compiler.g7.G7ObfuscatedCrash -> ')) 'G7 mapping lacks the obfuscated crash class'
    Require ($mappingText.Contains('# pg_map_hash: SHA-256 ')) 'G7 mapping lacks a verifiable mapping hash'
    Require ($stackText.Contains('autojs6-g7-retrace:g7-obfuscated-crash')) 'G7 raw stack lacks its invocation marker'
    Require ($stackText.Contains("`tat a.a.a(")) 'G7 raw stack lacks the deliberately obfuscated frame'
    Require (-not $stackText.Contains('org.autojs.fixture.r8compiler.g7.G7ObfuscatedCrash')) 'G7 raw stack already exposes the original crash class'

    $mappingPath = Join-Path $Target.tempDirectory 'mapping.txt'
    $stackPath = Join-Path $Target.tempDirectory 'stack.txt'
    [IO.File]::WriteAllBytes($mappingPath, $mappingBytes)
    [IO.File]::WriteAllBytes($stackPath, $stackBytes)
    $mappingVerification = Invoke-Native $script:resolvedJavaPath @(
        '-cp', $script:resolvedR8JarPath, 'com.android.tools.r8.retrace.Retrace',
        '--verify-mapping-file-hash', $mappingPath
    ) 'Pinned R8 mapping-hash verification failed'
    Require ([String]::IsNullOrWhiteSpace($mappingVerification.Text)) 'Pinned R8 mapping-hash verification emitted unexpected output'
    $retrace = Invoke-Native $script:resolvedJavaPath @(
        '-cp', $script:resolvedR8JarPath, 'com.android.tools.r8.retrace.Retrace', $mappingPath, $stackPath
    ) 'Pinned R8 Retrace failed'
    $retraceText = $retrace.Text.TrimEnd() + "`n"
    Require ($retraceText.Contains('org.autojs.fixture.r8compiler.g7.G7ObfuscatedCrash.explode')) 'Retrace did not restore the crash class and method'
    Require ($retraceText.Contains('G7ObfuscatedCrash.java:8')) 'Retrace did not restore the crash source line'
    Require ($retraceText.Contains('org.autojs.fixture.r8compiler.g7.G7RuntimeFixture.crashForRetrace')) 'Retrace did not preserve the kept caller frame'
    Require ($retraceText.Contains('autojs6-g7-retrace:g7-obfuscated-crash')) 'Retrace lost the exception marker'
    Require (-not $retraceText.Contains("`tat a.a.a(")) 'Retrace left the target frame obfuscated'
    $retraceBytes = $utf8NoBom.GetBytes($retraceText)
    return [ordered]@{
        toolVersion = '8.13.17'
        mappingByteLength = $mappingBytes.Length
        mappingSha256 = [string]$Receipt.mappingSha256
        obfuscatedStackByteLength = $stackBytes.Length
        obfuscatedStackSha256 = [string]$Receipt.obfuscatedStackSha256
        retracedStackByteLength = $retraceBytes.Length
        retracedStackSha256 = Get-Sha256Bytes $retraceBytes
        mappingHashVerified = $true
        originalCrashClassRestored = $true
        originalCrashMethodRestored = $true
        originalSourceLineRestored = $true
    }
}

function Invoke-DeviceRuntime {
    param([Parameter(Mandatory = $true)][object]$Target)
    $hostInstalled = Get-InstalledPackageRecord $Target $hostPackage 'host'
    $testInstalled = Get-InstalledPackageRecord $Target $testPackage 'instrumentation'
    $providerInstalled = Get-InstalledPackageRecord $Target $providerPackage 'provider'
    Require ([string]$providerInstalled.PublicRecord.sha256 -ceq $script:officialProviderSha256) 'Installed provider bytes differ from local.5'
    Require ([long]$providerInstalled.PublicRecord.byteLength -eq $script:officialProviderByteLength) 'Installed provider length differs from local.5'

    $instrumentation = Invoke-Adb $Target.serial @(
        'shell', 'am', 'instrument', '-w', '-r',
        '-e', 'class', 'org.autojs.autojs.core.plugin.r8.R8CompilerRealProviderG7AndroidTest#optimizedDexExecutesOnArtLinksJniAndEmitsRetraceInput',
        '-e', 'autojs.r8Compiler.g7.enabled', 'true',
        '-e', 'autojs.r8Compiler.g7.invocationId', $invocationId,
        '-e', 'autojs.r8Compiler.g7.deviceIdentity', $Target.deviceIdentity,
        '-e', 'autojs.r8Compiler.g7.sdk', $Target.sdkInt.ToString(),
        $runner
    ) 'G7 instrumentation transport failed'
    Assert-InstrumentationSuccess $instrumentation.Text
    $receipt = Get-SingleReceipt $instrumentation.Lines
    Assert-G7Receipt $receipt $Target
    $retrace = Invoke-PinnedRetrace $receipt $Target

    foreach ($installed in @(
        [pscustomobject]@{ packageName = $hostPackage; remotePath = $hostInstalled.RemotePath },
        [pscustomobject]@{ packageName = $testPackage; remotePath = $testInstalled.RemotePath },
        [pscustomobject]@{ packageName = $providerPackage; remotePath = $providerInstalled.RemotePath }
    )) {
        Require ((Get-InstalledBasePath $Target.serial $installed.packageName) -ceq $installed.remotePath) "Installed package path changed during G7: $($installed.packageName)"
    }

    return [ordered]@{
        role = $Target.role
        transportSerial = $Target.serial
        deviceIdentity = $Target.deviceIdentity
        sdkInt = $Target.sdkInt
        compilerMinApi = [Math]::Min($Target.sdkInt, 36)
        physical = [bool]$Target.physical
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
            capabilityFingerprint = [string]$receipt.capabilityFingerprint
            programSha256 = [string]$receipt.programSha256
            ruleSha256 = [string]$receipt.ruleSha256
            outputBundleByteLength = [long]$receipt.outputBundleSizeBytes
            outputBundleSha256 = [string]$receipt.outputBundleSha256
            nativeLibrarySha256 = [string]$receipt.nativeLibrarySha256
            canonicalFiveArtifacts = $true
            artExecuted = $true
            reflectionExecuted = $true
            dynamicNameExecuted = $true
            serializationExecuted = $true
            publicEntryExecuted = $true
            jniLinked = $true
            nativeInstanceResult = 42
            nativeStaticResult = 'g7-jni-static'
            removedDecoyAbsent = $true
            rawStackIsObfuscated = $true
            artifacts = @($receipt.artifacts)
        }
        retrace = $retrace
        tests = [ordered]@{ classInvocations = 1; tests = 1; failures = 0; errors = 0; skipped = 0; structuredReceipts = 1 }
    }
}

Write-FailedGate $failureStage

try {
    $failureStage = 'EXPLICIT_AUTHORIZATION'
    Require ([bool]$AuthorizeDeviceRuntime) 'Pass -AuthorizeDeviceRuntime to permit G7 instrumentation on the fixed matrix'
    $serials = @($PhysicalSerial.Trim(), $ModernAvdSerial.Trim(), $Api25AvdSerial.Trim())
    foreach ($serial in $serials) { Assert-SafeSerial $serial }
    Require (@($serials | Select-Object -Unique).Count -eq 3) 'G7 requires three distinct device transports'

    $failureStage = 'TOOLCHAIN_RESOLUTION'
    Require ([IO.Directory]::Exists($hostRepositoryRoot)) 'The sibling AutoJs6 host repository is missing'
    if ([String]::IsNullOrWhiteSpace($AdbPath)) {
        $script:resolvedAdbPath = [IO.Path]::GetFullPath((Get-Command adb.exe -ErrorAction Stop).Source)
    } else {
        $script:resolvedAdbPath = [IO.Path]::GetFullPath($AdbPath)
    }
    Require ([IO.File]::Exists($script:resolvedAdbPath)) 'adb executable is missing'
    $sdkRoot = [IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($script:resolvedAdbPath))
    $script:apksignerPath = [IO.Path]::GetFullPath((Join-Path $sdkRoot "build-tools/$BuildToolsVersion/apksigner.bat"))
    Require ([IO.File]::Exists($script:apksignerPath)) 'Pinned apksigner is missing'
    if ([String]::IsNullOrWhiteSpace($JavaPath)) {
        Require (-not [String]::IsNullOrWhiteSpace($env:JAVA_HOME)) 'JAVA_HOME is required when JavaPath is omitted'
        $script:resolvedJavaPath = [IO.Path]::GetFullPath((Join-Path $env:JAVA_HOME 'bin/java.exe'))
    } else {
        $script:resolvedJavaPath = [IO.Path]::GetFullPath($JavaPath)
    }
    Require ([IO.File]::Exists($script:resolvedJavaPath)) 'Pinned Java executable is missing'
    $javaVersion = Invoke-Native $script:resolvedJavaPath @('-version') 'Java version probe failed'
    Require ($javaVersion.Text -match '(?m)version "21\.') 'G7 Retrace requires the selected JDK 21 runtime'
    if ([String]::IsNullOrWhiteSpace($R8JarPath)) {
        $gradleHome = if (-not [String]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
            [IO.Path]::GetFullPath($env:GRADLE_USER_HOME)
        } else {
            [IO.Path]::GetFullPath((Join-Path $env:USERPROFILE '.gradle'))
        }
        $r8Directory = Join-Path $gradleHome 'caches/modules-2/files-2.1/com.android.tools/r8/8.13.17'
        $candidates = @(Get-ChildItem -LiteralPath $r8Directory -Recurse -Filter 'r8-8.13.17.jar' -File)
        Require ($candidates.Count -eq 1) 'Exactly one pinned R8 8.13.17 cache artifact is required'
        $script:resolvedR8JarPath = $candidates[0].FullName
    } else {
        $script:resolvedR8JarPath = [IO.Path]::GetFullPath($R8JarPath)
    }
    Require ([IO.File]::Exists($script:resolvedR8JarPath)) 'Pinned R8 jar is missing'
    Require ((Get-Item -LiteralPath $script:resolvedR8JarPath).Length -eq $expectedR8JarSize) 'Pinned R8 jar length differs'
    Require ((Get-Sha256File $script:resolvedR8JarPath) -ceq $expectedR8JarSha256) 'Pinned R8 jar digest differs'

    $failureStage = 'PRIOR_EVIDENCE'
    $g6Path = Join-Path $repositoryRoot 'build/reports/r42-g6/device-acceptance-gate.json'
    $releaseGatePath = Join-Path $repositoryRoot 'build/reports/r42-g7/local-release-gate.json'
    $identityPath = Join-Path $repositoryRoot 'docs/identity-reservation.json'
    $g6 = Read-PositiveGate $g6Path 'autojs6.r8.g6.device-acceptance-gate/v1'
    $releaseGate = Read-PositiveGate $releaseGatePath 'autojs6.r8.g7.local-release-gate/v1'
    Require ([string]$releaseGate.release.releaseId -ceq '0.1.0-provider-dev-local.5') 'G7 release Gate does not bind local.5'
    Require ([string]$releaseGate.release.status -ceq 'IDENTICAL') 'Final local.5 publication did not prove append-only identity'
    Require ([bool]$releaseGate.claims.localPublished -and -not [bool]$releaseGate.claims.remotePublished) 'G7 release publication claims differ'
    Require ([bool]$releaseGate.claims.platformLibraryFixPackaged) 'G7 release lacks the platform-library fix claim'
    Require ([string]$releaseGate.signing.certificateSha256 -ceq $expectedCertificateSha256) 'G7 release signer differs'
    foreach ($record in @($releaseGate.release.files)) { Assert-FileRecord $record $repositoryRoot }
    Require ([IO.File]::Exists($identityPath)) 'Current G7 identity reservation is missing'
    $identity = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
    Require ([string]$identity.status -ceq 'LOCAL_PROVIDER_ART_JNI_RETRACE_VERIFIED_NOT_REMOTELY_PUBLISHED') 'Current identity status does not match G7'
    Require ([bool]$identity.claims.providerImplemented -and [bool]$identity.claims.hostIntegrated) 'Current identity lost provider or host claims'
    Require ([bool]$identity.claims.binderVerified -and [bool]$identity.claims.deviceVerified) 'Current identity lost G6 claims'
    Require ([bool]$identity.claims.platformLibraryFixPackaged -and [bool]$identity.claims.jniLinked -and [bool]$identity.claims.retraceExecuted) 'Current identity lacks G7 claims'
    Require ([bool]$identity.claims.localPublished -and -not [bool]$identity.claims.remotePublished -and -not [bool]$identity.claims.published) 'Current identity overclaims remote publication'
    $signedRecords = @($releaseGate.release.files | Where-Object { $_.path -like '*-signed.apk' })
    Require ($signedRecords.Count -eq 1) 'G7 release does not contain exactly one signed APK'
    $officialProviderPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot ([string]$signedRecords[0].path)))
    $script:officialProviderSha256 = [string]$signedRecords[0].sha256
    $script:officialProviderByteLength = [long]$signedRecords[0].byteLength
    Require ((Get-Sha256File $officialProviderPath) -ceq $script:officialProviderSha256) 'Official local.5 APK digest differs'

    $failureStage = 'DEVICE_MATRIX_IDENTITY'
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    $specifications = @(
        [ordered]@{ role = 'PHYSICAL_API28_ARM64'; serial = $serials[0]; sdk = 28; physical = $true; abi = 'arm64-v8a'; pageSize = 0 },
        [ordered]@{ role = 'AVD_API37_X86_64_16K'; serial = $serials[1]; sdk = 37; physical = $false; abi = 'x86_64'; pageSize = 16384 },
        [ordered]@{ role = 'AVD_API25_X86'; serial = $serials[2]; sdk = 25; physical = $false; abi = 'x86'; pageSize = 0 }
    )
    $targets = [Collections.Generic.List[object]]::new()
    foreach ($specification in $specifications) {
        Require ((Invoke-Adb $specification.serial @('get-state') 'Device is unavailable').Text.Trim() -ceq 'device') 'ADB target is not in device state'
        $sdk = [int](Get-DeviceProperty $specification.serial 'ro.build.version.sdk')
        $physical = (Get-DeviceProperty $specification.serial 'ro.kernel.qemu') -cne '1'
        $abiList = Get-DeviceProperty $specification.serial 'ro.product.cpu.abilist'
        if ([String]::IsNullOrWhiteSpace($abiList)) { $abiList = Get-DeviceProperty $specification.serial 'ro.product.cpu.abi' }
        Require ($sdk -eq [int]$specification.sdk -and $physical -eq [bool]$specification.physical) "G7 target role differs: $($specification.role)"
        Require (@($abiList.Split(',')) -contains [string]$specification.abi) "G7 target ABI differs: $($specification.role)"
        $stableSerial = Get-DeviceProperty $specification.serial 'ro.serialno'
        if ([String]::IsNullOrWhiteSpace($stableSerial)) { $stableSerial = Get-DeviceProperty $specification.serial 'ro.boot.serialno' }
        Require (-not [String]::IsNullOrWhiteSpace($stableSerial)) 'G7 target has no stable serial property'
        $avdName = Get-DeviceProperty $specification.serial 'ro.boot.qemu.avd_name'
        if ([String]::IsNullOrWhiteSpace($avdName)) { $avdName = Get-DeviceProperty $specification.serial 'ro.kernel.qemu.avd_name' }
        if (-not $physical) { Require (-not [String]::IsNullOrWhiteSpace($avdName)) 'G7 AVD has no stable AVD name' }
        $deviceIdentity = if ([String]::IsNullOrWhiteSpace($avdName)) { $stableSerial } else { "$stableSerial@$avdName" }
        $pageSize = 0
        if ([int]$specification.pageSize -gt 0) {
            $pageSize = [int](Invoke-Adb $specification.serial @('shell', 'getconf', 'PAGE_SIZE') 'Unable to read modern AVD page size').Text.Trim()
            Require ($pageSize -eq [int]$specification.pageSize) 'Modern G7 AVD is not using the required 16 KiB page size'
        }
        $targetDirectory = Join-Path $temporaryRoot ([string]$specification.role)
        [IO.Directory]::CreateDirectory($targetDirectory) | Out-Null
        $targets.Add([pscustomobject]@{
            role = [string]$specification.role
            serial = [string]$specification.serial
            sdkInt = $sdk
            physical = $physical
            deviceIdentity = $deviceIdentity
            manufacturer = Get-DeviceProperty $specification.serial 'ro.product.manufacturer'
            model = Get-DeviceProperty $specification.serial 'ro.product.model'
            abiList = $abiList
            pageSizeBytes = $pageSize
            tempDirectory = $targetDirectory
        })
    }

    $failureStage = 'DEVICE_ART_JNI_RETRACE'
    $deviceRecords = [Collections.Generic.List[object]]::new()
    foreach ($target in $targets) {
        Write-Output "G7 runtime acceptance started: $($target.role) [$($target.serial)]"
        $deviceRecords.Add((Invoke-DeviceRuntime $target))
        Write-Output "G7 runtime acceptance passed: $($target.role) [$($target.serial)]"
    }

    $failureStage = 'FINAL_REPORT'
    $designPath = Join-Path $repositoryRoot 'docs/art-jni-retrace-acceptance-v1.md'
    Require ([IO.File]::Exists($designPath)) 'G7 runtime design document is missing'
    $hostTestPath = Join-Path $hostRepositoryRoot 'app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderG7AndroidTest.kt'
    $fixtureRecords = @(Get-ChildItem -LiteralPath (Join-Path $repositoryRoot 'test-fixtures/g7') -Recurse -File | Sort-Object FullName | ForEach-Object {
        $relative = $repositoryRoot.TrimEnd('\').Length + 1
        New-FileRecord $_.FullName $_.FullName.Substring($relative)
    })
    $finalReport = [ordered]@{
        schemaVersion = 'autojs6.r8.g7.art-jni-retrace-gate/v1'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'AUTHORIZED_LOCAL5_ART_JNI_AND_PINNED_RETRACE'
        release = [ordered]@{
            releaseId = '0.1.0-provider-dev-local.5'
            channel = 'LOCAL_ONLY'
            providerApk = $signedRecords[0]
            signerSha256 = $expectedCertificateSha256
            remotePublished = $false
        }
        matrix = [ordered]@{
            physicalDevices = 1
            avds = 2
            sdkLevels = @(25, 28, 37)
            compilerMinApis = @(25, 28, 36)
            abiFamilies = @('arm64-v8a', 'x86_64', 'x86')
            includes16KiBPageDevice = $true
            devices = @($deviceRecords)
        }
        tests = [ordered]@{
            classInvocations = 3
            tests = 3
            structuredReceipts = 3
            retraceExecutions = 3
            failures = 0
            errors = 0
            skipped = 0
        }
        retraceTool = [ordered]@{
            compilerVersion = '8.13.17'
            jarByteLength = $expectedR8JarSize
            jarSha256 = $expectedR8JarSha256
            javaMajor = 21
        }
        priorEvidence = @(
            [ordered]@{ path = 'build/reports/r42-g6/device-acceptance-gate.json'; schemaVersion = [string]$g6.schemaVersion; invocationId = [string]$g6.invocationId; byteLength = [long](Get-Item -LiteralPath $g6Path).Length; sha256 = Get-Sha256File $g6Path },
            [ordered]@{ path = 'build/reports/r42-g7/local-release-gate.json'; schemaVersion = [string]$releaseGate.schemaVersion; invocationId = [string]$releaseGate.invocationId; byteLength = [long](Get-Item -LiteralPath $releaseGatePath).Length; sha256 = Get-Sha256File $releaseGatePath }
        )
        hostAndroidTestSource = New-FileRecord $hostTestPath 'AutoJs6/app/src/androidTest/java/org/autojs/autojs/core/plugin/r8/R8CompilerRealProviderG7AndroidTest.kt'
        hostAndroidTestIsolation = New-FileRecord (Join-Path $repositoryRoot 'scripts/r42-g7-host-r8-runtime.init.gradle') 'scripts/r42-g7-host-r8-runtime.init.gradle'
        fixtures = $fixtureRecords
        currentIdentity = New-FileRecord $identityPath 'docs/identity-reservation.json'
        design = New-FileRecord $designPath 'docs/art-jni-retrace-acceptance-v1.md'
        verifier = New-FileRecord $PSCommandPath 'scripts/verify-g7-art-jni-retrace.ps1'
        operations = [ordered]@{
            explicitAuthorization = $true
            installedByGate = $false
            uninstalledByGate = $false
            forceStopPerformed = $false
            protectedDevicesTouched = $false
            rawMappingOrStackPersistedInGate = $false
            gitPushPerformed = $false
            remotePublicationPerformed = $false
        }
        claims = [ordered]@{
            localPublished = $true
            remotePublished = $false
            officialLocal5ProviderBytesInstalled = $true
            crossApkBinderVerified = $true
            canonicalFiveArtifactsVerified = $true
            artExecuted = $true
            reflectionExecuted = $true
            dynamicNameExecuted = $true
            serializationExecuted = $true
            publicEntryExecuted = $true
            jniLinked = $true
            retraceExecuted = $true
            mappingHashVerified = $true
            api25CliVerified = $true
            api28CommandVerified = $true
            api37MinApi36Verified = $true
            pageSize16KiBVerified = $true
            physicalDeviceVerified = $true
            deviceVerified = $true
        }
        summary = 'Authorized local.5 ART/JNI execution and pinned R8 Retrace passed on API 28 arm64 physical, API 37 x86_64 16 KiB, and API 25 x86 devices; remote publication remains false'
    }
    Write-AtomicJson $finalReport
    Write-Output "G7 ART/JNI/Retrace Gate passed: $reportPath"
    Write-Output "Invocation ID: $invocationId"
} catch {
    try { Write-FailedGate $failureStage } catch { Write-Error 'G7 runtime also failed to record its negative Gate' }
    throw
} finally {
    if ([IO.Directory]::Exists($temporaryRoot)) {
        $systemTempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $candidate = [IO.Path]::GetFullPath($temporaryRoot)
        if ($candidate.StartsWith($systemTempPrefix, [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($candidate).StartsWith('autojs6-r8-g7-runtime-', [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $candidate -Recurse -Force
        }
    }
}
