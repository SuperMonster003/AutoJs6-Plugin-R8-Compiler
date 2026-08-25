[CmdletBinding()]
param(
    [string]$HostRoot,
    [string]$ProviderTestFilter = 'io.github.supermonster003.autojs6.plugin.r8compiler.R8CompatibilityCorpusTest',
    [string]$HostTestFilter = 'org.autojs.autojs.core.plugin.r8.R8CompilerExplicitRuntimeRouteTest'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if ([string]::IsNullOrWhiteSpace($HostRoot)) {
    $HostRoot = Join-Path $repositoryRoot '..\AutoJs6'
}
$hostRepositoryRoot = [IO.Path]::GetFullPath($HostRoot)
$reportPath = Join-Path $repositoryRoot 'build/reports/r42-g4/compatibility-corpus-gate.json'
$receiptDirectory = Join-Path $repositoryRoot 'build/reports/r42-g4/corpus-cases'
$utf8NoBom = [Text.UTF8Encoding]::new($false)
$invocationId = [Guid]::NewGuid().ToString('D')

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class R8G4AtomicMove {
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
        $json = ($Value | ConvertTo-Json -Depth 20 -Compress) + "`n"
        if ($json.IndexOf($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $json.IndexOf($hostRepositoryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw 'G4 report must not persist an absolute repository path'
        }
        [IO.File]::WriteAllText($temporary, $json, $utf8NoBom)
        [R8G4AtomicMove]::Replace($temporary, $Path)
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

function Get-RepositoryRelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $rootUri = [Uri]::new($repositoryRoot.TrimEnd('\') + '\')
    $pathUri = [Uri]::new([IO.Path]::GetFullPath($Path))
    return [Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString()).Replace('\', '/')
}

function Get-HostRelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)
    $rootUri = [Uri]::new($hostRepositoryRoot.TrimEnd('\') + '\')
    $pathUri = [Uri]::new([IO.Path]::GetFullPath($Path))
    return [Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString()).Replace('\', '/')
}

function Require-RepositoryFile {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $path = Join-Path $repositoryRoot $RelativePath
    if (-not [IO.File]::Exists($path)) { throw "Required G4 repository file is missing: $RelativePath" }
    return $path
}

function Require-HostFile {
    param([Parameter(Mandatory = $true)][string]$RelativePath)
    $path = Join-Path $hostRepositoryRoot $RelativePath
    if (-not [IO.File]::Exists($path)) { throw "Required G4 host file is missing: $RelativePath" }
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

function Require-Sha256Text {
    param([Parameter(Mandatory = $true)][string]$Value, [Parameter(Mandatory = $true)][string]$Label)
    if ($Value -cnotmatch '^[0-9a-f]{64}$') { throw "$Label is not a canonical SHA-256" }
}

function New-FileRecord {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][ValidateSet('repository', 'host')][string]$Owner
    )
    $relative = if ($Owner -ceq 'repository') {
        Get-RepositoryRelativePath $Path
    } else {
        Get-HostRelativePath $Path
    }
    return [ordered]@{
        path = $relative
        sha256 = Get-Sha256 $Path
        byteLength = (Get-Item -LiteralPath $Path).Length
    }
}

$invalid = [ordered]@{
    schemaVersion = 'autojs6.r8.g4.compatibility-corpus-gate/v1'
    invocationId = $invocationId
    passed = $false
    evidenceBoundary = 'R8_COMPATIBILITY_CORPUS_JVM_ARTIFACT_AND_SCRIPT_ROUTE'
    summary = 'G4 compatibility verification started and has not completed'
}
Write-AtomicJson -Path $reportPath -Value $invalid

try {
    $verifierPath = Require-RepositoryFile 'scripts/verify-g4-compatibility-corpus.ps1'
    $corpusTestPath = Require-RepositoryFile 'app/src/test/java/io/github/supermonster003/autojs6/plugin/r8compiler/R8CompatibilityCorpusTest.kt'
    $corpusSupportPath = Require-RepositoryFile 'app/src/test/java/io/github/supermonster003/autojs6/plugin/r8compiler/R8CompatibilityCorpusSupport.kt'
    $kotlinFixturePath = Require-RepositoryFile 'app/src/test/java/compat/corpus/kotlin/R8CompatibilityKotlinFixture.kt'
    $appBuildPath = Require-RepositoryFile 'app/build.gradle.kts'
    $designPath = Require-RepositoryFile 'docs/r8-compatibility-corpus-v1.md'
    $identityPath = Require-RepositoryFile 'docs/identity-reservation.json'
    $g2ReportPath = Require-RepositoryFile 'build/reports/r42-g2/provider-gate.json'
    $g3ReportPath = Require-RepositoryFile 'build/reports/r42-g3/host-control-plane-gate.json'
    $providerGradle = Require-RepositoryFile 'gradlew.bat'

    $hostGradle = Require-HostFile 'gradlew.bat'
    $hostScriptRuntimePath = Require-HostFile 'app/src/main/java/org/autojs/autojs/runtime/ScriptRuntime.kt'
    $hostRouteTestPath = Require-HostFile 'app/src/test/java/org/autojs/autojs/core/plugin/r8/R8CompilerExplicitRuntimeRouteTest.kt'

    $identity = [IO.File]::ReadAllText($identityPath, $utf8NoBom) | ConvertFrom-Json
    if ($identity.status -cne 'LOCAL_PROVIDER_AND_HOST_INTEGRATED_NOT_PUBLISHED' -or
        -not [bool]$identity.claims.providerImplemented -or
        -not [bool]$identity.claims.hostIntegrated -or
        -not [bool]$identity.claims.r8Executed -or
        [bool]$identity.claims.binderVerified -or
        [bool]$identity.claims.deviceVerified -or
        [bool]$identity.claims.published) {
        throw 'Identity reservation does not match the local pre-device G4 boundary'
    }

    $g2 = [IO.File]::ReadAllText($g2ReportPath, $utf8NoBom) | ConvertFrom-Json
    $g3 = [IO.File]::ReadAllText($g3ReportPath, $utf8NoBom) | ConvertFrom-Json
    if ($g2.schemaVersion -cne 'autojs6.r8.g2.provider-gate/v2' -or -not [bool]$g2.passed) {
        throw 'Current positive G2 provider evidence is required before G4'
    }
    if ($g3.schemaVersion -cne 'autojs6.r8.g3.host-transaction-gate/v4' -or -not [bool]$g3.passed) {
        throw 'Current positive G3 host evidence is required before G4'
    }

    $hostHead = (& git -C $hostRepositoryRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $hostHead -cne [string]$g3.host.baseCommit) {
        throw 'Host HEAD no longer matches the positive G3 base commit'
    }
    $g3RouteRecord = @($g3.hostTestSources | Where-Object { $_.path -ceq 'app/src/test/java/org/autojs/autojs/core/plugin/r8/R8CompilerExplicitRuntimeRouteTest.kt' })
    $g3RuntimeRecord = @($g3.hostIntegrationFiles | Where-Object { $_.path -ceq 'app/src/main/java/org/autojs/autojs/runtime/ScriptRuntime.kt' })
    if ($g3RouteRecord.Count -ne 1 -or $g3RuntimeRecord.Count -ne 1 -or
        (Get-Sha256 $hostRouteTestPath) -cne [string]$g3RouteRecord[0].sha256 -or
        (Get-Sha256 $hostScriptRuntimePath) -cne [string]$g3RuntimeRecord[0].sha256) {
        throw 'Host script-route files no longer match the positive G3 evidence'
    }

    $corpusTest = [IO.File]::ReadAllText($corpusTestPath, $utf8NoBom)
    $corpusSupport = [IO.File]::ReadAllText($corpusSupportPath, $utf8NoBom)
    $kotlinFixture = [IO.File]::ReadAllText($kotlinFixturePath, $utf8NoBom)
    $appBuild = [IO.File]::ReadAllText($appBuildPath, $utf8NoBom)
    $hostScriptRuntime = [IO.File]::ReadAllText($hostScriptRuntimePath, $utf8NoBom)
    $hostRouteTest = [IO.File]::ReadAllText($hostRouteTestPath, $utf8NoBom)
    foreach ($needle in @(
        'R8CorpusLanguage.values()',
        '(24..36)',
        'realR8PreservesTheFiveCompatibilitySurfaces'
    )) {
        Require-Contains $corpusTest $needle "Compatibility matrix seam '$needle'"
    }
    foreach ($needle in @(
        'R8CompilerEngine(runtime, sdkInt = { 26 }).compile(',
        'R8InputMaterializer.materialize(',
        'R8ArtifactBundleCodec.read(',
        'reflectionClassAndMembersRetained',
        'dynamicClassNameTargetRetained',
        'jniClassAndNativeNamesRetained',
        'serializationClassNameAndHooksRetained',
        'autoJs6ScriptApiNamesRetained',
        'postR8DexRuntimeExecuted',
        'RemovedDecoy',
        'parseDex(dex)'
    )) {
        Require-Contains $corpusSupport $needle "Compatibility observation seam '$needle'"
    }
    foreach ($needle in @(
        'Class.forName("compat.corpus.kotlin.ReflectiveTarget")',
        'Class.forName("compat.corpus.kotlin.$simpleName")',
        'external fun nativeRoundTrip',
        'serialVersionUID',
        'class ScriptApi',
        'class RemovedDecoy'
    )) {
        Require-Contains $kotlinFixture $needle "Kotlin corpus seam '$needle'"
    }
    Require-Contains $appBuild 'r8.compatibility.report.dir' 'Compatibility receipt output registration'
    Require-Contains $hostScriptRuntime 'fun loadJarWithR8(' 'Public AutoJs6 R8 script entry'
    Require-Contains $hostRouteTest 'rhinoConvertsScriptArraysForSimpleAndOwnedConsumerRuleShapes' 'Rhino full-overload test'
    Require-Contains $hostRouteTest 'selectedProviderFailurePreservesR8OnlyDirectiveAndNeverLoads' 'Script no-fallback test'

    [IO.Directory]::CreateDirectory($receiptDirectory) | Out-Null
    foreach ($child in @(Get-ChildItem -LiteralPath $receiptDirectory -Force)) {
        if ($child.PSIsContainer) { throw 'Compatibility receipt directory contains an unexpected subdirectory' }
        [IO.File]::Delete($child.FullName)
    }

    Push-Location $repositoryRoot
    try {
        & $providerGradle '--no-daemon' ':app:testDebugUnitTest' '--tests' $ProviderTestFilter '--rerun-tasks'
        $providerExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($providerExitCode -ne 0) { throw "Focused provider compatibility test failed with exit code $providerExitCode" }

    $providerXmlPath = Join-Path $repositoryRoot 'app/build/test-results/testDebugUnitTest/TEST-io.github.supermonster003.autojs6.plugin.r8compiler.R8CompatibilityCorpusTest.xml'
    if (-not [IO.File]::Exists($providerXmlPath)) { throw 'Provider compatibility JUnit XML is missing' }
    [xml]$providerXml = [IO.File]::ReadAllText($providerXmlPath, $utf8NoBom)
    $providerSuite = $providerXml.testsuite
    if ($providerSuite.name -cne 'io.github.supermonster003.autojs6.plugin.r8compiler.R8CompatibilityCorpusTest' -or
        [int]$providerSuite.tests -ne 26 -or [int]$providerSuite.failures -ne 0 -or
        [int]$providerSuite.errors -ne 0 -or [int]$providerSuite.skipped -ne 0) {
        throw 'Provider compatibility suite is not exactly 26/26 with no skipped tests'
    }

    $expectedCaseIds = @()
    foreach ($language in @('java', 'kotlin')) {
        foreach ($minApi in 24..36) { $expectedCaseIds += "$language-minapi-$minApi" }
    }
    $receiptFiles = @(Get-ChildItem -LiteralPath $receiptDirectory -File -Filter '*.json' | Sort-Object Name)
    if ($receiptFiles.Count -ne 26 -or
        @(Compare-Object -CaseSensitive ($expectedCaseIds | Sort-Object) @($receiptFiles.BaseName)).Count -ne 0) {
        throw 'Compatibility receipt set is not the exact 2 x 13 matrix'
    }

    $expectedRoles = @('DEX_ZIP', 'MAPPING_TEXT', 'SEEDS_TEXT', 'USAGE_TEXT', 'RETRACE_METADATA')
    $caseRecords = @()
    foreach ($receiptFile in $receiptFiles) {
        $raw = [IO.File]::ReadAllText($receiptFile.FullName, $utf8NoBom)
        if ($raw.IndexOf($repositoryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0 -or
            $raw.IndexOf($hostRepositoryRoot, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "Compatibility receipt persists an absolute path: $($receiptFile.Name)"
        }
        $case = $raw | ConvertFrom-Json
        if ($case.schemaVersion -cne 'autojs6.r8.compatibility-case/v1' -or
            $case.caseId -cne $receiptFile.BaseName -or
            $case.language -cnotin @('JAVA', 'KOTLIN') -or
            [int]$case.minApi -notin 24..36 -or
            -not [bool]$case.minApiIsCompilerParameter -or
            $case.compilerFamily -cne 'R8' -or $case.compilerVersion -cne '8.13.17' -or
            $case.profile -cne 'FULL_RELEASE' -or $case.fallbackPolicy -cne 'NONE') {
            throw "Compatibility receipt identity is invalid: $($receiptFile.Name)"
        }
        $expectedId = '{0}-minapi-{1}' -f $case.language.ToLowerInvariant(), [int]$case.minApi
        if ($case.caseId -cne $expectedId) { throw "Compatibility receipt case ID is non-canonical: $($receiptFile.Name)" }
        foreach ($property in @('programSha256', 'ruleSha256', 'inputBundleSha256', 'outputBundleSha256')) {
            Require-Sha256Text ([string]$case.$property) "$($case.caseId).$property"
        }
        $artifacts = @($case.artifacts)
        if ($artifacts.Count -ne 5 -or
            @(Compare-Object -CaseSensitive $expectedRoles @($artifacts.role) -SyncWindow 0).Count -ne 0) {
            throw "Compatibility artifact roles are not exact: $($case.caseId)"
        }
        foreach ($artifact in $artifacts) {
            if ([long]$artifact.sizeBytes -le 0) { throw "Compatibility artifact is empty: $($case.caseId)/$($artifact.role)" }
            Require-Sha256Text ([string]$artifact.sha256) "$($case.caseId)/$($artifact.role)"
        }
        $dexEntries = @($case.dexEntries)
        if ($dexEntries.Count -lt 1) { throw "Compatibility DEX topology is empty: $($case.caseId)" }
        for ($index = 0; $index -lt $dexEntries.Count; $index++) {
            $expectedDex = if ($index -eq 0) { 'classes.dex' } else { 'classes{0}.dex' -f ($index + 1) }
            if ($dexEntries[$index] -cne $expectedDex) { throw "Compatibility DEX topology is non-canonical: $($case.caseId)" }
        }
        $package = if ($case.language -ceq 'JAVA') { 'java' } else { 'kotlin' }
        $expectedDescriptors = @(
            'ReflectionEntry', 'ReflectiveTarget', 'DynamicEntry', 'DynamicTarget',
            'NativeBridge', 'SerializableState', 'ScriptApi'
        ) | ForEach-Object { "Lcompat/corpus/$package/$_;" }
        if (@($case.observedClassDescriptors).Count -ne 7 -or
            @(Compare-Object -CaseSensitive $expectedDescriptors @($case.observedClassDescriptors) -SyncWindow 0).Count -ne 0) {
            throw "Compatibility class observations are not exact: $($case.caseId)"
        }
        foreach ($property in @(
            'reflectionClassAndMembersRetained', 'dynamicClassNameTargetRetained',
            'jniClassAndNativeNamesRetained', 'serializationClassNameAndHooksRetained',
            'autoJs6ScriptApiNamesRetained', 'unusedDecoyRemoved', 'originalJvmControlPassed'
        )) {
            if (-not [bool]$case.observations.$property) { throw "Compatibility observation is false: $($case.caseId)/$property" }
        }
        if (-not [bool]$case.claims.realR8Executed -or
            -not [bool]$case.claims.canonicalFiveArtifactBundleConsumed -or
            [bool]$case.claims.postR8DexRuntimeExecuted -or [bool]$case.claims.jniLinked -or
            [bool]$case.claims.deviceVerified) {
            throw "Compatibility receipt overclaims its local evidence: $($case.caseId)"
        }
        $caseRecords += [ordered]@{
            caseId = [string]$case.caseId
            language = [string]$case.language
            minApi = [int]$case.minApi
            programSha256 = [string]$case.programSha256
            ruleSha256 = [string]$case.ruleSha256
            inputBundleSha256 = [string]$case.inputBundleSha256
            outputBundleSha256 = [string]$case.outputBundleSha256
            artifactIdentities = @($artifacts | ForEach-Object {
                [ordered]@{ role = [string]$_.role; sizeBytes = [long]$_.sizeBytes; sha256 = [string]$_.sha256 }
            })
            receiptSha256 = Get-Sha256 $receiptFile.FullName
            receiptByteLength = $receiptFile.Length
        }
    }
    foreach ($language in @('JAVA', 'KOTLIN')) {
        $languageCases = @($caseRecords | Where-Object { $_.language -ceq $language })
        foreach ($property in @('programSha256', 'ruleSha256', 'inputBundleSha256')) {
            if (@($languageCases.$property | Sort-Object -Unique).Count -ne 1) {
                throw "$language compatibility $property drifted across minApi cells"
            }
        }
    }

    Push-Location $hostRepositoryRoot
    try {
        & $hostGradle '--no-daemon' ':app:testAppDebugUnitTest' '--tests' $HostTestFilter '--rerun-tasks'
        $hostExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($hostExitCode -ne 0) { throw "Focused host script-route test failed with exit code $hostExitCode" }

    $hostXmlPath = Join-Path $hostRepositoryRoot 'app/build/test-results/testAppDebugUnitTest/TEST-org.autojs.autojs.core.plugin.r8.R8CompilerExplicitRuntimeRouteTest.xml'
    if (-not [IO.File]::Exists($hostXmlPath)) { throw 'Host script-route JUnit XML is missing' }
    [xml]$hostXml = [IO.File]::ReadAllText($hostXmlPath, $utf8NoBom)
    $hostSuite = $hostXml.testsuite
    if ($hostSuite.name -cne 'org.autojs.autojs.core.plugin.r8.R8CompilerExplicitRuntimeRouteTest' -or
        [int]$hostSuite.tests -ne 8 -or [int]$hostSuite.failures -ne 0 -or
        [int]$hostSuite.errors -ne 0 -or [int]$hostSuite.skipped -ne 0) {
        throw 'Host explicit R8 script-route suite is not exactly 8/8 with no skipped tests'
    }

    $report = [ordered]@{
        schemaVersion = 'autojs6.r8.g4.compatibility-corpus-gate/v1'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'R8_COMPATIBILITY_CORPUS_JVM_ARTIFACT_AND_SCRIPT_ROUTE'
        compiler = [ordered]@{
            family = 'R8'
            version = '8.13.17'
            profile = 'FULL_RELEASE'
            fallbackPolicy = 'NONE'
            minApiIsCompilerParameter = $true
        }
        matrix = [ordered]@{
            languages = @('JAVA', 'KOTLIN')
            minApis = @(24..36)
            cells = 26
            compatibilitySurfaces = @('REFLECTION', 'DYNAMIC_CLASS_NAME', 'JNI', 'JAVA_SERIALIZATION', 'AUTOJS6_SCRIPT_API')
            canonicalArtifacts = $expectedRoles
            unusedDecoyRemovedInEveryCell = $true
            perCellReceipt = $true
            determinismClaim = 'NOT_CLAIMED'
        }
        tests = [ordered]@{
            providerSuites = 1
            providerTests = 26
            hostSuites = 1
            hostTests = 8
            failures = 0
            errors = 0
            skipped = 0
            forcedRerun = $true
            daemon = $false
        }
        priorEvidence = [ordered]@{
            g2InvocationId = [string]$g2.invocationId
            g2ReportSha256 = Get-Sha256 $g2ReportPath
            g3InvocationId = [string]$g3.invocationId
            g3ReportSha256 = Get-Sha256 $g3ReportPath
            hostBaseCommit = $hostHead
        }
        cases = $caseRecords
        corpusSources = @(
            New-FileRecord $corpusTestPath 'repository'
            New-FileRecord $corpusSupportPath 'repository'
            New-FileRecord $kotlinFixturePath 'repository'
            New-FileRecord $appBuildPath 'repository'
        )
        hostScriptRouteSources = @(
            New-FileRecord $hostScriptRuntimePath 'host'
            New-FileRecord $hostRouteTestPath 'host'
        )
        design = New-FileRecord $designPath 'repository'
        verifier = New-FileRecord $verifierPath 'repository'
        claims = [ordered]@{
            compatibilityCorpusImplemented = $true
            javaAndKotlinInputsVerified = $true
            everyMinApi24Through36Compiled = $true
            realR8Executed = $true
            canonicalFiveArtifactBundlesConsumed = $true
            reflectionArtifactCompatibilityVerified = $true
            dynamicClassNameArtifactCompatibilityVerified = $true
            jniNameAndDescriptorCompatibilityVerified = $true
            javaSerializationArtifactCompatibilityVerified = $true
            autoJs6ScriptApiArtifactCompatibilityVerified = $true
            hostRhinoRouteVerified = $true
            postR8DexRuntimeExecuted = $false
            jniLinked = $false
            binderVerified = $false
            deviceVerified = $false
            published = $false
        }
        summary = 'Java and Kotlin compatibility programs passed real R8 8.13.17 for every minApi 24-36 with exact five-artifact and host Rhino-route observations; ART, JNI linking, Binder, device, and publication remain open'
    }
    Write-AtomicJson -Path $reportPath -Value $report
    Write-Output "G4 compatibility corpus gate passed: $reportPath"
    Write-Output "Invocation ID: $invocationId"
} catch {
    $failure = [ordered]@{
        schemaVersion = 'autojs6.r8.g4.compatibility-corpus-gate/v1'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'R8_COMPATIBILITY_CORPUS_JVM_ARTIFACT_AND_SCRIPT_ROUTE'
        failureType = $_.Exception.GetType().FullName
        summary = 'G4 compatibility verification failed; no positive compatibility claim is valid'
    }
    Write-AtomicJson -Path $reportPath -Value $failure
    throw
}
