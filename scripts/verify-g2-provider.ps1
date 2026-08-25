[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$reportPath = Join-Path $repositoryRoot 'build/reports/r42-g2/provider-gate.json'
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$invocationId = [Guid]::NewGuid().ToString('D')

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class R8G2AtomicMove {
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

function Write-AtomicJson {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $directory = [IO.Path]::GetDirectoryName($Path)
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = Join-Path $directory ('.{0}.{1}.tmp' -f [IO.Path]::GetFileName($Path), [Guid]::NewGuid().ToString('N'))
    try {
        $json = ($Value | ConvertTo-Json -Depth 16 -Compress) + "`n"
        [IO.File]::WriteAllText($temporary, $json, $utf8NoBom)
        [R8G2AtomicMove]::Replace($temporary, $Path)
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Get-Sha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    $stream = [IO.File]::OpenRead($Path)
    try {
        $sha = [Security.Cryptography.SHA256]::Create()
        try { return ([BitConverter]::ToString($sha.ComputeHash($stream))).Replace('-', '').ToLowerInvariant() }
        finally { $sha.Dispose() }
    } finally {
        $stream.Dispose()
    }
}

function Get-RelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $rootUri = [Uri]::new($repositoryRoot.TrimEnd('\') + '\')
    $pathUri = [Uri]::new([IO.Path]::GetFullPath($Path))
    return [Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString()).Replace('\', '/')
}

function Require-File {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $path = Join-Path $repositoryRoot $RelativePath
    if (-not [IO.File]::Exists($path)) { throw "Required G2 file is missing: $RelativePath" }
    return $path
}

function Require-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($Text.IndexOf($Needle, [StringComparison]::Ordinal) -lt 0) { throw "$Label is missing" }
}

$invalid = [ordered]@{
    schemaVersion = 'autojs6.r8.g2.provider-gate/v2'
    invocationId = $invocationId
    passed = $false
    evidenceBoundary = 'LOCAL_PROVIDER_JVM_AND_ANDROID_BUILD'
    summary = 'G2 provider verification started and has not completed'
}
Write-AtomicJson -Path $reportPath -Value $invalid

try {
    $verifierPath = Require-File 'scripts/verify-g2-provider.ps1'
    $identityPath = Require-File 'docs/identity-reservation.json'
    $manifestPath = Require-File 'app/src/main/AndroidManifest.xml'
    $appBuildPath = Require-File 'app/build.gradle.kts'
    $catalogPath = Require-File 'gradle/libs.versions.toml'
    $settingsPath = Require-File 'settings.gradle.kts'
    $protocolAar = Require-File 'plugin-api/r8-compiler-api/releases/0.1.0/protocol-wire-api-0.1.0.aar'
    $r8Aar = Require-File 'plugin-api/r8-compiler-api/releases/0.1.0/r8-compiler-api-0.1.0.aar'
    $debugApk = Require-File 'app/build/outputs/apk/debug/app-debug.apk'
    $releaseApk = Require-File 'app/build/outputs/apk/release/app-release-unsigned.apk'
    $lintPath = Require-File 'app/build/reports/lint-results-debug.txt'

    $identity = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
    if ($identity.apiNamespace -cne 'org.autojs.plugin.r8compiler.api' -or
        $identity.serviceAction -cne 'org.autojs.plugin.R8_COMPILER' -or
        $identity.engineId -cne 'r8-compiler' -or
        $identity.cacheDomain -cne 'autojs6:r8-compiler:v1' -or
        $identity.protocol -cne '1.0') {
        throw 'Independent R8 identity reservation drifted'
    }
    if ($identity.status -cne 'LOCAL_PROVIDER_AND_HOST_INTEGRATED_NOT_PUBLISHED' -or
        -not [bool]$identity.claims.providerImplemented -or
        -not [bool]$identity.claims.manifestDiscoverable -or
        -not [bool]$identity.claims.r8Executed -or
        -not [bool]$identity.claims.pluginConsumed -or
        -not [bool]$identity.claims.hostIntegrated -or
        [bool]$identity.claims.binderVerified -or
        [bool]$identity.claims.retraceExecuted -or
        [bool]$identity.claims.deviceVerified -or
        [bool]$identity.claims.published) {
        throw 'Repository identity does not match the current post-G3 local boundary'
    }
    if ($identity.officialProviderReservation.applicationId -cne 'io.github.supermonster003.autojs6.plugin.r8compiler' -or
        $identity.officialProviderReservation.providerId -cne 'autojs6-r8' -or
        $identity.officialProviderReservation.process -cne ':r8') {
        throw 'Official R8 provider identity drifted'
    }

    [xml]$manifest = Get-Content -Raw -LiteralPath $manifestPath
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    $namespace = [Xml.XmlNamespaceManager]::new($manifest.NameTable)
    $namespace.AddNamespace('android', $androidNamespace)
    $services = @($manifest.SelectNodes('/manifest/application/service', $namespace))
    if ($services.Count -ne 1) { throw 'Manifest must expose exactly one service' }
    $service = $services[0]
    $getAndroid = { param($Node, $Name) $Node.GetAttribute($Name, $androidNamespace) }
    if ((& $getAndroid $service 'name') -cne '.R8CompilerService' -or
        (& $getAndroid $service 'exported') -cne 'true' -or
        (& $getAndroid $service 'permission') -cne 'org.autojs.permission.PLUGIN' -or
        (& $getAndroid $service 'process') -cne ':r8') {
        throw 'Manifest R8 service identity is not exact'
    }
    $actions = @($service.SelectNodes('intent-filter/action', $namespace))
    if ($actions.Count -ne 1 -or (& $getAndroid $actions[0] 'name') -cne 'org.autojs.plugin.R8_COMPILER') {
        throw 'Manifest R8 discovery action is not exact'
    }

    $appBuild = [IO.File]::ReadAllText($appBuildPath, $utf8NoBom)
    Require-Contains $appBuild 'applicationId = "io.github.supermonster003.autojs6.plugin.r8compiler"' 'Application ID pin'
    Require-Contains $appBuild 'protocol-wire-api-0.1.0.aar' 'Protocol AAR consumption'
    Require-Contains $appBuild 'r8-compiler-api-0.1.0.aar' 'R8 API AAR consumption'
    Require-Contains $appBuild 'isCoreLibraryDesugaringEnabled = true' 'R8 runtime core-library desugaring'
    Require-Contains $appBuild 'coreLibraryDesugaring(libs.desugar)' 'R8 runtime desugaring dependency'
    if ($appBuild.IndexOf('project(":plugin-api:r8-compiler-api")', [StringComparison]::Ordinal) -ge 0) {
        throw 'Provider bypasses the frozen R8 API AAR'
    }
    $catalog = [IO.File]::ReadAllText($catalogPath, $utf8NoBom)
    Require-Contains $catalog 'r8 = "8.13.17"' 'R8 compiler pin'
    Require-Contains $catalog 'desugar = "2.1.5"' 'Desugared runtime pin'
    Require-Contains $catalog 'com.android.tools:desugar_jdk_libs_nio' 'NIO desugared runtime'
    $settings = [IO.File]::ReadAllText($settingsPath, $utf8NoBom)
    Require-Contains $settings '":app"' 'Installable application module'

    if ((Get-Sha256 $protocolAar) -cne '1d97a5b44b2c20e85aa12b263fca604a32d6d89275d47a19076861cd20c29a36' -or
        (Get-Sha256 $r8Aar) -cne 'e9df49b7e49992615a15bc0af2372a4525f02b4a2a915a560ddab3128bb2f066') {
        throw 'Frozen 0.1.0 contract AAR bytes drifted'
    }

    $sourceRoot = Join-Path $repositoryRoot 'app/src/main/java'
    $sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -File -Filter '*.kt' | Sort-Object FullName)
    if ($sourceFiles.Count -lt 10) { throw 'Provider production source set is unexpectedly small' }
    $requiredSourceNames = @(
        'R8CompilerService.kt', 'R8CompilerEngine.kt', 'R8InputMaterializer.kt',
        'R8ArtifactPackager.kt', 'RemoteR8CompileSession.kt', 'OwnedParcelFileDescriptors.kt'
    )
    foreach ($name in $requiredSourceNames) {
        if (@($sourceFiles | Where-Object Name -CEQ $name).Count -ne 1) { throw "Required provider source is not exact: $name" }
    }
    $sourceText = ($sourceFiles | ForEach-Object { [IO.File]::ReadAllText($_.FullName, $utf8NoBom) }) -join "`n"
    foreach ($pattern in @('DexCompiler', '\bD8\b', '\bdx\b')) {
        if ([Text.RegularExpressions.Regex]::IsMatch($sourceText, $pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
            throw "Provider production source contains a forbidden fallback token: $pattern"
        }
    }
    foreach ($needle in @(
        'R8.run(builder.build())', 'setDisableTreeShaking(false)', 'setDisableMinification(false)',
        'R8ArtifactBundleCodec.write', 'R8CompilerValidation.validateRequestAgainst',
        'R8CompilerCodec.encodeResult'
    )) {
        if ($sourceText.IndexOf($needle, [StringComparison]::Ordinal) -lt 0) { throw "Provider semantic seam is missing: $needle" }
    }

    $testDirectory = Join-Path $repositoryRoot 'app/build/test-results/testDebugUnitTest'
    $testFiles = @(Get-ChildItem -LiteralPath $testDirectory -File -Filter 'TEST-*.xml')
    $expectedSuites = [ordered]@{
        'io.github.supermonster003.autojs6.plugin.r8compiler.PrivateSessionWorkspaceTest' = 4
        'io.github.supermonster003.autojs6.plugin.r8compiler.R8CompatibilityCorpusTest' = 26
        'io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerEngineIntegrationTest' = 3
        'io.github.supermonster003.autojs6.plugin.r8compiler.R8InputMaterializerTest' = 2
        'io.github.supermonster003.autojs6.plugin.r8compiler.R8ProviderBoundaryTest' = 4
        'io.github.supermonster003.autojs6.plugin.r8compiler.service.RemoteSessionTerminalControllerTest' = 3
    }
    if ($testFiles.Count -ne $expectedSuites.Count) { throw 'G2 provider suite count is not exactly 6' }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($testFile in $testFiles) {
        [xml]$document = Get-Content -Raw -LiteralPath $testFile.FullName
        $suite = $document.testsuite
        if (-not $expectedSuites.Contains($suite.name) -or
            [int]$suite.tests -ne [int]$expectedSuites[$suite.name]) {
            throw "G2 provider suite is unexpected: $($suite.name)"
        }
        $tests += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }
    if ($tests -ne 42 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
        throw 'G2 provider JVM gate is not exactly 42/42 with no skipped tests'
    }
    $lint = [IO.File]::ReadAllText($lintPath, $utf8NoBom)
    if ($lint.IndexOf('0 errors, 2 warnings', [StringComparison]::Ordinal) -lt 0) {
        throw 'G2 provider lint result is not the admitted 0 error / 2 warning boundary'
    }

    $androidHome = [Environment]::GetEnvironmentVariable('ANDROID_HOME')
    if ([string]::IsNullOrWhiteSpace($androidHome)) { throw 'ANDROID_HOME is required for APK manifest verification' }
    $aapt2 = @(Get-ChildItem -LiteralPath (Join-Path $androidHome 'build-tools') -Recurse -File -Filter 'aapt2.exe' |
        Sort-Object FullName -Descending | Select-Object -First 1)
    if ($aapt2.Count -ne 1) { throw 'Exactly one newest aapt2 executable could not be selected' }
    foreach ($apk in @($debugApk, $releaseApk)) {
        $dump = (& $aapt2[0].FullName dump xmltree $apk --file AndroidManifest.xml 2>&1) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not inspect a provider APK manifest' }
        foreach ($needle in @(
            'package="io.github.supermonster003.autojs6.plugin.r8compiler"',
            '="io.github.supermonster003.autojs6.plugin.r8compiler.R8CompilerService"',
            '="org.autojs.permission.PLUGIN"',
            '="org.autojs.plugin.R8_COMPILER"',
            '=":r8"'
        )) {
            if ($dump.IndexOf($needle, [StringComparison]::Ordinal) -lt 0) {
                throw "Built APK manifest is missing: $needle"
            }
        }
        if ([Text.RegularExpressions.Regex]::Matches($dump, '(?m)^\s+E: service \(').Count -ne 1) {
            throw 'Built APK manifest must contain exactly one service'
        }
    }

    $sourceRecords = @($sourceFiles | ForEach-Object {
        [ordered]@{ path = Get-RelativePath $_.FullName; sha256 = Get-Sha256 $_.FullName; byteLength = $_.Length }
    })
    $report = [ordered]@{
        schemaVersion = 'autojs6.r8.g2.provider-gate/v2'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'LOCAL_PROVIDER_JVM_AND_ANDROID_BUILD'
        compiler = [ordered]@{
            family = 'R8'
            version = '8.13.17'
            profile = 'FULL_RELEASE'
            fallback = 'NONE'
            coreLibraryDesugaring = $true
            desugaredRuntime = 'com.android.tools:desugar_jdk_libs_nio:2.1.5'
        }
        provider = [ordered]@{
            applicationId = 'io.github.supermonster003.autojs6.plugin.r8compiler'
            component = 'io.github.supermonster003.autojs6.plugin.r8compiler/.R8CompilerService'
            action = 'org.autojs.plugin.R8_COMPILER'
            process = ':r8'
        }
        repositoryState = [ordered]@{
            identityStatus = $identity.status
            laterHostIntegrationPresent = [bool]$identity.claims.hostIntegrated
            extendsThisG2EvidenceBoundary = $false
        }
        tests = [ordered]@{ suites = $testFiles.Count; tests = $tests; failures = $failures; errors = $errors; skipped = $skipped }
        lint = [ordered]@{ errors = 0; warnings = 2 }
        contractAars = @(
            [ordered]@{ path = Get-RelativePath $protocolAar; sha256 = Get-Sha256 $protocolAar; byteLength = (Get-Item $protocolAar).Length },
            [ordered]@{ path = Get-RelativePath $r8Aar; sha256 = Get-Sha256 $r8Aar; byteLength = (Get-Item $r8Aar).Length }
        )
        apks = @(
            [ordered]@{ variant = 'debug'; path = Get-RelativePath $debugApk; sha256 = Get-Sha256 $debugApk; byteLength = (Get-Item $debugApk).Length },
            [ordered]@{ variant = 'release-unsigned'; path = Get-RelativePath $releaseApk; sha256 = Get-Sha256 $releaseApk; byteLength = (Get-Item $releaseApk).Length }
        )
        verifier = [ordered]@{
            path = Get-RelativePath $verifierPath
            sha256 = Get-Sha256 $verifierPath
            byteLength = (Get-Item $verifierPath).Length
        }
        sourceFiles = $sourceRecords
        claims = [ordered]@{
            providerImplemented = $true
            manifestSourceVerified = $true
            manifestArtifactVerified = $true
            r8JvmExecuted = $true
            canonicalFiveArtifactBundleVerified = $true
            binderVerified = $false
            pfdLifecycleVerified = $false
            processDeathVerified = $false
            hostIntegrated = $false
            deviceVerified = $false
            published = $false
        }
    }
    Write-AtomicJson -Path $reportPath -Value $report
    Write-Output ('G2 provider gate PASS: {0} suites / {1} tests; lint 0/2; two APKs' -f $testFiles.Count, $tests)
} catch {
    $failure = [ordered]@{
        schemaVersion = 'autojs6.r8.g2.provider-gate/v2'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'LOCAL_PROVIDER_JVM_AND_ANDROID_BUILD'
        summary = $_.Exception.Message
    }
    Write-AtomicJson -Path $reportPath -Value $failure
    Write-Error $_.Exception.Message
    exit 1
}
