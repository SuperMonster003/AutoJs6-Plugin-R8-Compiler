[CmdletBinding()]
param(
    [string]$HostRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($HostRoot)) {
    $HostRoot = Join-Path $repositoryRoot '..\AutoJs6'
}
$hostRepositoryRoot = [IO.Path]::GetFullPath($HostRoot)
$reportPath = Join-Path $repositoryRoot 'build/reports/r42-g3/host-control-plane-gate.json'
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$invocationId = [Guid]::NewGuid().ToString('D')

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class R8G3AtomicMove {
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
        if ($json.IndexOf($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $json.IndexOf($hostRepositoryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'G3 report must not persist an absolute repository path'
        }
        [IO.File]::WriteAllText($temporary, $json, $utf8NoBom)
        [R8G3AtomicMove]::Replace($temporary, $Path)
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

function Get-HostRelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $rootUri = [Uri]::new($hostRepositoryRoot.TrimEnd('\') + '\')
    $pathUri = [Uri]::new([IO.Path]::GetFullPath($Path))
    return $rootUri.MakeRelativeUri($pathUri).ToString().Replace('\', '/')
}

function Require-HostFile {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $path = Join-Path $hostRepositoryRoot $RelativePath
    if (-not [IO.File]::Exists($path)) { throw "Required host file is missing: $RelativePath" }
    return $path
}

function Require-RepositoryFile {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $path = Join-Path $repositoryRoot $RelativePath
    if (-not [IO.File]::Exists($path)) { throw "Required R8 repository file is missing: $RelativePath" }
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
    schemaVersion = 'autojs6.r8.g3.host-transaction-gate/v4'
    invocationId = $invocationId
    passed = $false
    evidenceBoundary = 'HOST_R8_EXPLICIT_SCRIPT_INTEGRATION_JVM_AND_ANDROID_COMPILE'
    summary = 'G3 explicit host integration verification started and has not completed'
}
Write-AtomicJson -Path $reportPath -Value $invalid

try {
    $verifierPath = Require-RepositoryFile 'scripts/verify-g3-host-control-plane.ps1'
    $frozenR8Aar = Require-RepositoryFile 'plugin-api/r8-compiler-api/releases/0.1.0/r8-compiler-api-0.1.0.aar'
    $frozenProtocolSource = Require-RepositoryFile 'plugin-api/protocol-wire-api/src/main/java/org/autojs/plugin/protocol/wire/TaggedWire.kt'
    $identityPath = Require-RepositoryFile 'docs/identity-reservation.json'

    $settingsPath = Require-HostFile 'settings.gradle.kts'
    $appBuildPath = Require-HostFile 'app/build.gradle.kts'
    $wrapperBuildPath = Require-HostFile 'libs/r8-compiler-api-0_1_0/build.gradle.kts'
    $wrapperReadmePath = Require-HostFile 'libs/r8-compiler-api-0_1_0/README.md'
    $hostR8Aar = Require-HostFile 'libs/r8-compiler-api-0_1_0/r8-compiler-api-0.1.0.aar'
    $hostProtocolSource = Require-HostFile 'plugin-api/protocol-wire-api/src/main/java/org/autojs/plugin/protocol/wire/TaggedWire.kt'
    $hostDesignPath = Require-HostFile 'docs/dev/r8-compiler-host-control-plane.md'
    $scriptRuntimePath = Require-HostFile 'app/src/main/java/org/autojs/autojs/runtime/ScriptRuntime.kt'
    $androidClassLoaderPath = Require-HostFile 'app/src/main/java/org/autojs/autojs/rhino/AndroidClassLoader.kt'
    $preferencePath = Require-HostFile 'app/src/main/java/org/autojs/autojs/ui/settings/R8CompilerPreference.kt'
    $developerOptionsPath = Require-HostFile 'app/src/main/res/xml/fragment_developer_options.xml'
    $gradleWrapper = Require-HostFile 'gradlew.bat'

    $resourceDirectories = @(
        'values',
        'values-ar',
        'values-en',
        'values-es',
        'values-fr',
        'values-ja',
        'values-ko',
        'values-ru',
        'values-zh',
        'values-zh-rHK',
        'values-zh-rTW'
    )
    $resourcePaths = @($resourceDirectories | ForEach-Object {
        Require-HostFile "app/src/main/res/$_/strings.xml"
    })

    $expectedR8AarSha256 = 'e9df49b7e49992615a15bc0af2372a4525f02b4a2a915a560ddab3128bb2f066'
    $expectedProtocolSourceSha256 = '5d5c672ef0c7907b1a0cf6fd041ff85dc7c864aa07628316db5b9a9abb9e87c8'
    if ((Get-Sha256 $frozenR8Aar) -cne $expectedR8AarSha256 -or
        (Get-Sha256 $hostR8Aar) -cne $expectedR8AarSha256) {
        throw 'Host or provider-repository R8 API AAR differs from the frozen 0.1.0 bytes'
    }
    if ((Get-Sha256 $frozenProtocolSource) -cne $expectedProtocolSourceSha256 -or
        (Get-Sha256 $hostProtocolSource) -cne $expectedProtocolSourceSha256) {
        throw 'Host protocol-wire source differs from the G1 source boundary'
    }

    $settings = [IO.File]::ReadAllText($settingsPath, $utf8NoBom)
    $appBuild = [IO.File]::ReadAllText($appBuildPath, $utf8NoBom)
    $wrapperBuild = [IO.File]::ReadAllText($wrapperBuildPath, $utf8NoBom)
    $wrapperReadme = [IO.File]::ReadAllText($wrapperReadmePath, $utf8NoBom)
    Require-Contains $settings '"r8-compiler-api-0_1_0"' 'Frozen R8 wrapper module registration'
    Require-Contains $appBuild 'implementation(project(":plugin-api:protocol-wire-api"))' 'Explicit protocol-wire dependency'
    Require-Contains $appBuild 'implementation(project(":libs:r8-compiler-api-0_1_0"))' 'Frozen R8 API dependency'
    Require-Contains $wrapperBuild 'files += "r8-compiler-api-0.1.0.aar"' 'Frozen R8 AAR registration'
    Require-Contains $wrapperReadme $expectedR8AarSha256 'Frozen R8 AAR documentation digest'
    foreach ($text in @($settings, $appBuild, $wrapperBuild)) {
        if ($text.IndexOf('AutoJs6-Plugin-R8-Compiler', [StringComparison]::Ordinal) -ge 0) {
            throw 'Host Gradle wiring reaches into the sibling R8 source project'
        }
    }

    $sourceRoot = Join-Path $hostRepositoryRoot 'app/src/main/java/org/autojs/autojs/core/plugin/r8'
    $testRoot = Join-Path $hostRepositoryRoot 'app/src/test/java/org/autojs/autojs/core/plugin/r8'
    if (-not [IO.Directory]::Exists($sourceRoot) -or -not [IO.Directory]::Exists($testRoot)) {
        throw 'Host R8 source or test root is missing'
    }
    $sourceFiles = @(Get-ChildItem -LiteralPath $sourceRoot -File -Filter '*.kt' | Sort-Object Name)
    $testSources = @(Get-ChildItem -LiteralPath $testRoot -File -Filter '*.kt' | Sort-Object Name)
    $expectedSourceNames = @(
        'AndroidR8CompilerDescriptor.kt',
        'AndroidR8CompilerHost.kt',
        'AndroidR8CompilerTransactionEnvironment.kt',
        'AndroidR8CompilerTransport.kt',
        'R8CompilerArtifactAdopter.kt',
        'R8CompilerArtifactStore.kt',
        'R8CompilerAttemptAbortSignal.kt',
        'R8CompilerDescriptorFactory.kt',
        'R8CompilerDexZipValidator.kt',
        'R8CompilerDispatchFirewall.kt',
        'R8CompilerExplicitRuntimeRoute.kt',
        'R8CompilerHostControlPlane.kt',
        'R8CompilerHostDispatcher.kt',
        'R8CompilerHostRequest.kt',
        'R8CompilerInputSnapshot.kt',
        'R8CompilerOptInStore.kt',
        'R8CompilerPersistentArtifactStore.kt',
        'R8CompilerProtocolGate.kt',
        'R8CompilerRequestFactory.kt',
        'R8CompilerSelectionPolicy.kt',
        'R8CompilerSemanticCacheKey.kt',
        'R8CompilerSessionCallbackGate.kt',
        'R8CompilerTransactionRunner.kt',
        'R8CompilerTransactionWorkspace.kt',
        'R8CompilerTransport.kt'
    )
    if ($sourceFiles.Count -ne $expectedSourceNames.Count -or
        (Compare-Object -CaseSensitive $expectedSourceNames @($sourceFiles.Name))) {
        throw 'Host R8 production source set is not exact'
    }
    if ($testSources.Count -ne 13) { throw 'Host R8 test source count is not exactly 13' }

    $sourceText = ($sourceFiles | ForEach-Object {
        [IO.File]::ReadAllText($_.FullName, $utf8NoBom)
    }) -join "`n"
    foreach ($needle in @(
        'R8CompilerOptInStore::read',
        'transport.discoverExactAction(R8CompilerContract.SERVICE_ACTION)',
        '.setComponent(identity.component.toAndroidComponent())',
        'packageManager.checkSignatures(',
        'Binder.getCallingUid()',
        'transport.inspectExact(identity.component)',
        'R8CompilerValidation.validateRequestAgainst',
        'semanticFallbackAllowed: Boolean = false',
        'const val CACHE_DOMAIN = "autojs6:r8-compiler:v1"',
        'const val CACHE_DIRECTORY = "r8-compiler-cache-v1"',
        'private const val TRANSACTION_DIRECTORY = "r8-compiler-transactions-v1"',
        'R8CompilerTransactionWorkspace.recoverStale(transactionRoot)',
        'R8CompilerInputSnapshotter.snapshot(',
        'R8CompilerRequestFactory.create(',
        'R8CompilerCodec.encodeRequest(request)',
        'connection.openSession(requestMetadata, input, output, callback)',
        'R8CompilerArtifactAdopter.adopt(',
        'R8CompilerArtifactFileValidation.validate(',
        'R8CompilerDexZipValidator.validate(',
        'artifactStore.publishVerified(',
        'check(partial.renameTo(destination))',
        'require(input.sha256().contentEquals(expectedSha256))',
        'R8CompilerFailureDirective('
        'sharedRuntimeDispatcher'
        'fun runtimeDispatcher(context: Context): R8CompilerHostDispatcher'
        'R8CompilerLoadableDexZip.from('
        'no D8/dx fallback was attempted'
    )) {
        Require-Contains $sourceText $needle "Host R8 semantic seam '$needle'"
    }
    foreach ($forbidden in @(
        'import org.autojs.autojs.core.plugin.dex',
        'org.autojs.plugin.dexcompiler',
        'dex_compiler_experimental'
    )) {
        if ($sourceText.IndexOf($forbidden, [StringComparison]::Ordinal) -ge 0) {
            throw "Host R8 production source crosses a forbidden DEX boundary: $forbidden"
        }
    }

    $allHostProductionSources = @(Get-ChildItem -LiteralPath (Join-Path $hostRepositoryRoot 'app/src/main') -Recurse -File |
        Where-Object { $_.Extension -in @('.kt', '.java') })
    $outsideR8Text = ($allHostProductionSources | Where-Object {
        -not $_.FullName.StartsWith($sourceRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
    } | ForEach-Object { [IO.File]::ReadAllText($_.FullName, $utf8NoBom) }) -join "`n"
    foreach ($needle in @(
        'R8CompilerExplicitRuntimeRoute.dispatch('
        'AndroidR8CompilerHost.runtimeDispatcher(applicationContext).compile(spec)'
        'loadPublished = sClassLoader::loadR8CompilerDexZip'
        'internal fun loadR8CompilerDexZip('
        'class R8CompilerPreference : MaterialPreference'
        'R8CompilerOptInStore.select(it.component)'
    )) {
        Require-Contains $outsideR8Text $needle "Explicit host integration seam '$needle'"
    }
    foreach ($forbidden in @(
        'AndroidR8CompilerHost.createDispatcher('
        'R8CompilerHostDispatcher('
    )) {
        if ($outsideR8Text.IndexOf($forbidden, [StringComparison]::Ordinal) -ge 0) {
            throw "Host integration bypasses the process-wide R8 runtime dispatcher: $forbidden"
        }
    }

    $scriptRuntime = [IO.File]::ReadAllText($scriptRuntimePath, $utf8NoBom)
    $androidClassLoader = [IO.File]::ReadAllText($androidClassLoaderPath, $utf8NoBom)
    $preference = [IO.File]::ReadAllText($preferencePath, $utf8NoBom)
    $developerOptions = [IO.File]::ReadAllText($developerOptionsPath, $utf8NoBom)
    foreach ($needle in @(
        'fun loadJarWithR8('
        'keepRules: Array<String>'
        'consumerRuleClasspathOrdinals: IntArray'
        'R8CompilerExplicitRuntimeRoute.dispatch('
        'AndroidR8CompilerHost.runtimeDispatcher(applicationContext).compile(spec)'
        'loadPublished = sClassLoader::loadR8CompilerDexZip'
    )) {
        Require-Contains $scriptRuntime $needle "Script R8 entry '$needle'"
    }
    foreach ($needle in @(
        'internal fun loadR8CompilerDexZip('
        'adoptionPrefix = "r8_verified"'
        'return loadVerifiedDexZipSource('
    )) {
        Require-Contains $androidClassLoader $needle "R8 DEX class-loader adoption '$needle'"
    }
    $r8LoaderStart = $androidClassLoader.IndexOf('internal fun loadR8CompilerDexZip(', [StringComparison]::Ordinal)
    if ($r8LoaderStart -lt 0) { throw 'Unable to locate the R8 DEX class-loader seam' }
    $r8LoaderEnd = $androidClassLoader.IndexOf('private fun loadVerifiedDexZipSource(', $r8LoaderStart, [StringComparison]::Ordinal)
    if ($r8LoaderEnd -le $r8LoaderStart) {
        throw 'Unable to isolate the R8 DEX class-loader seam'
    }
    $r8LoaderBody = $androidClassLoader.Substring($r8LoaderStart, $r8LoaderEnd - $r8LoaderStart)
    foreach ($forbidden in @('jarToDexR8(', 'jarToDexDx(', 'loadJarInternal(', 'loadJarLocally')) {
        if ($r8LoaderBody.IndexOf($forbidden, [StringComparison]::Ordinal) -ge 0) {
            throw "R8 DEX adoption reaches a local compiler route: $forbidden"
        }
    }
    foreach ($needle in @(
        'AndroidR8CompilerTransport(context.applicationContext)'
        '.discoverExactAction(R8CompilerContract.SERVICE_ACTION)'
        'R8CompilerSelectionPolicy.decide('
        'R8CompilerOptInStore.select(it.component)'
        'R8CompilerOptInStore.disable()'
    )) {
        Require-Contains $preference $needle "R8 preference seam '$needle'"
    }
    foreach ($needle in @(
        'org.autojs.autojs.ui.settings.R8CompilerPreference'
        '@string/description_r8_compiler_explicit_preference'
        '@string/summary_r8_compiler_explicit_disabled'
        '@string/text_r8_compiler_provider'
    )) {
        Require-Contains $developerOptions $needle "R8 developer-options seam '$needle'"
    }

    $expectedR8Strings = @(
        'description_r8_compiler_explicit_preference'
        'summary_r8_compiler_explicit_disabled'
        'summary_r8_compiler_explicit_disabled_with_selection'
        'summary_r8_compiler_explicit_selected'
        'text_r8_compiler'
        'text_r8_compiler_no_eligible_providers'
        'text_r8_compiler_provider'
        'text_r8_compiler_provider_discovery_failed'
        'text_r8_compiler_use_disabled'
    )
    foreach ($resourcePath in $resourcePaths) {
        [xml]$resources = [IO.File]::ReadAllText($resourcePath, $utf8NoBom)
        foreach ($name in $expectedR8Strings) {
            $matches = @($resources.resources.string | Where-Object { $_.name -ceq $name })
            if ($matches.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string]$matches[0].InnerText)) {
                throw "R8 localized resource is missing or duplicated: $name"
            }
        }
    }
    [xml]$defaultResources = [IO.File]::ReadAllText($resourcePaths[0], $utf8NoBom)
    [xml]$englishResources = [IO.File]::ReadAllText($resourcePaths[2], $utf8NoBom)
    foreach ($name in $expectedR8Strings) {
        $defaultText = [string](@($defaultResources.resources.string | Where-Object { $_.name -ceq $name })[0].InnerText)
        $englishText = [string](@($englishResources.resources.string | Where-Object { $_.name -ceq $name })[0].InnerText)
        if (-not $defaultText.Equals($englishText, [StringComparison]::Ordinal)) {
            throw "Default and English R8 resources differ: $name"
        }
    }

    $identity = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
    if (-not [bool]$identity.claims.hostIntegrated -or [bool]$identity.claims.binderVerified -or
        [bool]$identity.claims.deviceVerified -or [bool]$identity.claims.published) {
        throw 'Identity reservation does not match the local G3 host-integration boundary'
    }

    Push-Location $hostRepositoryRoot
    try {
        & $gradleWrapper '--no-daemon' ':app:testAppDebugUnitTest' '--tests' 'org.autojs.autojs.core.plugin.r8.*' '--rerun-tasks'
        $gradleExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($gradleExitCode -ne 0) { throw "Focused host R8 Gradle gate failed with exit code $gradleExitCode" }

    $testResults = Join-Path $hostRepositoryRoot 'app/build/test-results/testAppDebugUnitTest'
    $testFiles = @(Get-ChildItem -LiteralPath $testResults -File -Filter 'TEST-org.autojs.autojs.core.plugin.r8.*.xml' | Sort-Object Name)
    $expectedSuites = [ordered]@{
        'org.autojs.autojs.core.plugin.r8.R8CompilerDexZipValidatorTest' = 3
        'org.autojs.autojs.core.plugin.r8.R8CompilerDispatchFirewallTest' = 3
        'org.autojs.autojs.core.plugin.r8.R8CompilerExplicitRuntimeRouteTest' = 8
        'org.autojs.autojs.core.plugin.r8.R8CompilerHostBoundaryTest' = 3
        'org.autojs.autojs.core.plugin.r8.R8CompilerHostControlPlaneTest' = 5
        'org.autojs.autojs.core.plugin.r8.R8CompilerHostDispatcherTest' = 4
        'org.autojs.autojs.core.plugin.r8.R8CompilerPersistentArtifactStoreTest' = 3
        'org.autojs.autojs.core.plugin.r8.R8CompilerProtocolGateTest' = 4
        'org.autojs.autojs.core.plugin.r8.R8CompilerSelectionPolicyTest' = 6
        'org.autojs.autojs.core.plugin.r8.R8CompilerSemanticCacheKeyTest' = 3
        'org.autojs.autojs.core.plugin.r8.R8CompilerTransactionRunnerTest' = 7
    }
    if ($testFiles.Count -ne $expectedSuites.Count) { throw 'Focused host R8 suite count is not exactly 11' }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($testFile in $testFiles) {
        [xml]$document = Get-Content -Raw -LiteralPath $testFile.FullName
        $suite = $document.testsuite
        if (-not $expectedSuites.Contains($suite.name) -or
            [int]$suite.tests -ne [int]$expectedSuites[$suite.name]) {
            throw "Focused host R8 suite is unexpected: $($suite.name)"
        }
        $tests += [int]$suite.tests
        $failures += [int]$suite.failures
        $errors += [int]$suite.errors
        $skipped += [int]$suite.skipped
    }
    if ($tests -ne 49 -or $failures -ne 0 -or $errors -ne 0 -or $skipped -ne 0) {
        throw 'Focused host R8 gate is not exactly 49/49 with no skipped tests'
    }

    $hostHead = (& git -C $hostRepositoryRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $hostHead -notmatch '^[0-9a-f]{40}$') {
        throw 'Unable to record the host base commit'
    }
    $sourceRecords = @($sourceFiles | ForEach-Object {
        [ordered]@{
            path = Get-HostRelativePath $_.FullName
            sha256 = Get-Sha256 $_.FullName
            byteLength = $_.Length
        }
    })
    $testSourceRecords = @($testSources | ForEach-Object {
        [ordered]@{
            path = Get-HostRelativePath $_.FullName
            sha256 = Get-Sha256 $_.FullName
            byteLength = $_.Length
        }
    })
    $integrationFiles = @(
        $scriptRuntimePath
        $androidClassLoaderPath
        $preferencePath
        $developerOptionsPath
    ) + $resourcePaths
    $integrationRecords = @($integrationFiles | ForEach-Object {
        [ordered]@{
            path = Get-HostRelativePath $_
            sha256 = Get-Sha256 $_
            byteLength = (Get-Item $_).Length
        }
    })
    $report = [ordered]@{
        schemaVersion = 'autojs6.r8.g3.host-transaction-gate/v4'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'HOST_R8_EXPLICIT_SCRIPT_INTEGRATION_JVM_AND_ANDROID_COMPILE'
        host = [ordered]@{
            repository = 'AutoJs6'
            baseCommit = $hostHead
            versionCode = 5276
            variant = 'appDebug'
        }
        selection = [ordered]@{
            defaultEnabled = $false
            action = 'org.autojs.plugin.R8_COMPILER'
            exactComponentRequired = $true
            sameSignerRequired = $true
            postBindIdentityReinspection = $true
            callbackUidPinned = $true
            developerOptionsEntry = $true
            persistedExactComponent = $true
        }
        cache = [ordered]@{
            domain = 'autojs6:r8-compiler:v1'
            directory = 'r8-compiler-cache-v1'
            verifiedGenerationCommit = $true
            openDescriptorDigestRechecked = $true
            dexNamespaceImported = $false
        }
        transaction = [ordered]@{
            workspaceDirectory = 'r8-compiler-transactions-v1'
            canonicalInputSnapshot = $true
            requestFamily = 'R8'
            requestIntent = 'R8_EXPLICIT'
            fallbackPolicy = 'NONE'
            outputArtifacts = @('DEX_ZIP', 'MAPPING_TEXT', 'SEEDS_TEXT', 'USAGE_TEXT', 'RETRACE_METADATA')
            artifactAdoptionBeforeCacheCommit = $true
            dexZipIntegrityValidated = $true
            timeoutCancellationAndBinderDeathCovered = $true
            publicOrScriptEntry = $true
            scriptMethod = 'runtime.loadJarWithR8'
            usefulOverloads = 3
            existingLoadJarSemanticsChanged = $false
        }
        tests = [ordered]@{
            suites = $testFiles.Count
            tests = $tests
            failures = $failures
            errors = $errors
            skipped = $skipped
            forcedRerun = $true
            daemon = $false
        }
        contract = [ordered]@{
            r8ApiAarSha256 = Get-Sha256 $hostR8Aar
            r8ApiAarByteLength = (Get-Item $hostR8Aar).Length
            protocolSourceSha256 = Get-Sha256 $hostProtocolSource
        }
        claims = [ordered]@{
            hostControlPlaneImplemented = $true
            androidTransportCompiled = $true
            defaultOffExactSelection = $true
            protocolPinned = $true
            semanticCacheNamespaceIsolated = $true
            runtimeDispatchImplemented = $true
            postDispatchRunnerVerified = $true
            artifactAdoptionImplemented = $true
            persistentR8CacheImplemented = $true
            hostIntegrated = $true
            binderVerified = $false
            deviceVerified = $false
            published = $false
        }
        hostProductionSources = $sourceRecords
        hostTestSources = $testSourceRecords
        hostIntegrationFiles = $integrationRecords
        hostDesign = [ordered]@{
            path = Get-HostRelativePath $hostDesignPath
            sha256 = Get-Sha256 $hostDesignPath
            byteLength = (Get-Item $hostDesignPath).Length
        }
        verifier = [ordered]@{
            path = 'scripts/verify-g3-host-control-plane.ps1'
            sha256 = Get-Sha256 $verifierPath
            byteLength = (Get-Item $verifierPath).Length
        }
        summary = 'Default-off exact R8 selection and runtime.loadJarWithR8 production integration passed the focused JVM boundary without any D8/dx fallback; cross-process and device acceptance remain open'
    }
    Write-AtomicJson -Path $reportPath -Value $report
    Write-Output "G3 host control-plane gate passed: $reportPath"
    Write-Output "Invocation ID: $invocationId"
} catch {
    $failed = [ordered]@{
        schemaVersion = 'autojs6.r8.g3.host-transaction-gate/v4'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'HOST_R8_EXPLICIT_SCRIPT_INTEGRATION_JVM_AND_ANDROID_COMPILE'
        failureType = $_.Exception.GetType().FullName
        summary = 'G3 explicit host integration verification failed; no positive claim is valid'
    }
    Write-AtomicJson -Path $reportPath -Value $failed
    throw
}
