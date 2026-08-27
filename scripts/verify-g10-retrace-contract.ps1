[CmdletBinding()]
param(
    [switch]$UpdateEvidence
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$version = '0.2.0'
$releaseRelative = "plugin-api/r8-compiler-api/releases/$version"
$releaseRoot = Join-Path $root $releaseRelative
$protocolName = 'protocol-wire-api-0.1.0.aar'
$r8Name = "r8-compiler-api-$version.aar"
$protocolBuild = Join-Path $root 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-release.aar'
$r8Build = Join-Path $root 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
$protocolRelease = Join-Path $releaseRoot $protocolName
$r8Release = Join-Path $releaseRoot $r8Name
$abiRelative = "plugin-api/r8-compiler-api/abi/$version-java-visible-jvm-abi.txt"
$abiPath = Join-Path $root $abiRelative
$manifestRelative = "$releaseRelative/contract-distribution-manifest.json"
$manifestPath = Join-Path $root $manifestRelative
$utf8 = [Text.UTF8Encoding]::new($false)

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-BytesSha256([byte[]]$Bytes) {
    $hash = [Security.Cryptography.SHA256]::HashData($Bytes)
    return [Convert]::ToHexString($hash).ToLowerInvariant()
}

function Get-FileRecord([string]$Path, [string]$Relative) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    return [ordered]@{
        path = $Relative.Replace('\', '/')
        byteLength = [long]$bytes.Length
        sha256 = Get-BytesSha256 $bytes
    }
}

function Get-BytesRecord([byte[]]$Bytes, [string]$Relative) {
    return [ordered]@{
        path = $Relative.Replace('\', '/')
        byteLength = [long]$Bytes.Length
        sha256 = Get-BytesSha256 $Bytes
    }
}

function Test-BytesEqual([byte[]]$Left, [byte[]]$Right) {
    if ($Left.Length -ne $Right.Length) { return $false }
    for ($index = 0; $index -lt $Left.Length; $index++) {
        if ($Left[$index] -ne $Right[$index]) { return $false }
    }
    return $true
}

function Get-SourceRecords {
    $fixed = @(
        '.gitattributes',
        'build.gradle.kts',
        'gradle.properties',
        'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.jar',
        'gradle/wrapper/gradle-wrapper.properties',
        'settings.gradle.kts',
        'plugin-api/protocol-wire-api/build.gradle.kts',
        'plugin-api/protocol-wire-api/consumer-rules.pro',
        'plugin-api/r8-compiler-api/build.gradle.kts',
        'plugin-api/r8-compiler-api/consumer-rules.pro',
        'scripts/R8JvmAbi.cs',
        'scripts/verify-g10-retrace-contract.ps1',
        'docs/retrace-protocol-v1.1.md',
        'test-consumer-g10/src/main/java/org/autojs/plugin/r8compiler/consumer/R8RetraceContractDetachedConsumer.java'
    )
    $dynamicRoots = @(
        'plugin-api/protocol-wire-api/src/main',
        'plugin-api/r8-compiler-api/src/main'
    )
    $relativePaths = [Collections.Generic.List[string]]::new()
    foreach ($relative in $fixed) { $relativePaths.Add($relative) }
    foreach ($relativeRoot in $dynamicRoots) {
        $absoluteRoot = Join-Path $root $relativeRoot
        Get-ChildItem -LiteralPath $absoluteRoot -Recurse -File | ForEach-Object {
            $relativePaths.Add([IO.Path]::GetRelativePath($root, $_.FullName).Replace('\', '/'))
        }
    }
    $records = @($relativePaths | Sort-Object -Unique | ForEach-Object {
        $path = Join-Path $root $_
        Require ([IO.File]::Exists($path)) "G10 source boundary is missing $_"
        Get-FileRecord $path $_
    })
    return $records
}

function Get-SourceFingerprint($Records) {
    $text = ($Records | ForEach-Object {
        "{0}`0{1}`0{2}" -f $_.path, $_.byteLength, $_.sha256
    }) -join "`n"
    return [ordered]@{
        algorithm = 'sha256'
        scope = 'retrace-contract-production-v1.1'
        sha256 = Get-BytesSha256 ($utf8.GetBytes($text))
        fileCount = @($Records).Count
        files = @($Records)
    }
}

function Invoke-Gradle([string[]]$Arguments) {
    Push-Location $root
    try {
        & (Join-Path $root 'gradlew.bat') @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE" }
    } finally {
        Pop-Location
    }
}

function Read-ZipEntryBytes([IO.Compression.ZipArchiveEntry]$Entry, [long]$Maximum) {
    Require ($Entry.Length -ge 0 -and $Entry.Length -le $Maximum) "ZIP entry exceeds the G10 limit"
    $stream = $Entry.Open()
    try {
        $memory = [IO.MemoryStream]::new()
        try {
            $stream.CopyTo($memory)
            $bytes = $memory.ToArray()
            Require ($bytes.Length -eq $Entry.Length) "ZIP entry length changed while reading"
            return $bytes
        } finally { $memory.Dispose() }
    } finally { $stream.Dispose() }
}

function Get-AarBoundary([byte[]]$AarBytes, [bool]$R8Api) {
    $stream = [IO.MemoryStream]::new($AarBytes, $false)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $expected = @(
            'AndroidManifest.xml',
            'classes.jar',
            'META-INF/com/android/build/gradle/aar-metadata.properties',
            'proguard.txt',
            'R.txt'
        ) | Sort-Object
        $actual = @($zip.Entries.FullName | Sort-Object)
        Require (@(Compare-Object $expected $actual -CaseSensitive).Count -eq 0) "AAR entry boundary is not exact"
        $classesEntry = $zip.GetEntry('classes.jar')
        Require ($null -ne $classesEntry) "AAR classes.jar is missing"
        $classesBytes = Read-ZipEntryBytes $classesEntry 8MB
    } finally {
        $zip.Dispose()
        $stream.Dispose()
    }

    $jarStream = [IO.MemoryStream]::new($classesBytes, $false)
    $jar = [IO.Compression.ZipArchive]::new($jarStream, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $classEntries = @($jar.Entries | Where-Object { $_.FullName.EndsWith('.class', [StringComparison]::Ordinal) })
        Require ($classEntries.Count -gt 0) "classes.jar has no class files"
        $names = [Collections.Generic.List[string]]::new()
        $bytes = [Collections.Generic.List[byte[]]]::new()
        $prefix = if ($R8Api) { 'org/autojs/plugin/r8compiler/api/' } else { 'org/autojs/plugin/protocol/wire/' }
        foreach ($entry in $classEntries | Sort-Object FullName) {
            Require ($entry.FullName.StartsWith($prefix, [StringComparison]::Ordinal)) "classes.jar crosses its package boundary"
            $entryBytes = Read-ZipEntryBytes $entry 4MB
            $names.Add($entry.FullName)
            $bytes.Add($entryBytes)
        }
        $abi = [AutoJs6R8JvmAbi]::Analyze($names.ToArray(), $bytes.ToArray(), $R8Api)
        if ($R8Api) {
            Require (-not (@($abi.ClassEntries | Where-Object {
                $_.StartsWith('org/autojs/plugin/protocol/wire/', [StringComparison]::Ordinal)
            }).Count)) "R8 API embeds protocol-wire classes"
        }
        return [pscustomobject]@{
            classesBytes = $classesBytes
            abi = $abi
        }
    } finally {
        $jar.Dispose()
        $jarStream.Dispose()
    }
}

function Assert-BinderAbi($Abi) {
    $expectedMethods = @(
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onCancelled([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onCompleted([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onFailed([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onProgress([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onStarted([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#getCapabilities()[B',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#getCompilerInfo()[B',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#getRetraceCapabilities()[B',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#openRetraceSession([BLandroid/os/ParcelFileDescriptor;Landroid/os/ParcelFileDescriptor;Lorg/autojs/plugin/r8compiler/api/IR8CompilerCallback;)Lorg/autojs/plugin/r8compiler/api/IR8CompilerSession;',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#openSession([BLandroid/os/ParcelFileDescriptor;Landroid/os/ParcelFileDescriptor;Lorg/autojs/plugin/r8compiler/api/IR8CompilerCallback;)Lorg/autojs/plugin/r8compiler/api/IR8CompilerSession;',
        'org/autojs/plugin/r8compiler/api/IR8CompilerSession#cancel()V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerSession#close()V'
    ) | Sort-Object
    Require (@(Compare-Object $expectedMethods @($Abi.InterfaceMethodDescriptors | Sort-Object) -CaseSensitive).Count -eq 0) `
        "G10 AIDL method descriptors differ"

    $expectedBinder = [Collections.Generic.List[string]]::new()
    foreach ($owner in @(
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider',
        'org/autojs/plugin/r8compiler/api/IR8CompilerSession'
    )) {
        $descriptor = $owner.Replace('/', '.')
        $hex = [Convert]::ToHexString($utf8.GetBytes($descriptor)).ToLowerInvariant()
        $expectedBinder.Add("DESCRIPTOR|$owner|S:0x$hex")
    }
    foreach ($record in @(
        'IR8CompilerCallback$Stub|TRANSACTION_onStarted|I:1',
        'IR8CompilerCallback$Stub|TRANSACTION_onProgress|I:2',
        'IR8CompilerCallback$Stub|TRANSACTION_onCompleted|I:3',
        'IR8CompilerCallback$Stub|TRANSACTION_onFailed|I:4',
        'IR8CompilerCallback$Stub|TRANSACTION_onCancelled|I:5',
        'IR8CompilerProvider$Stub|TRANSACTION_getCompilerInfo|I:1',
        'IR8CompilerProvider$Stub|TRANSACTION_getCapabilities|I:2',
        'IR8CompilerProvider$Stub|TRANSACTION_openSession|I:3',
        'IR8CompilerProvider$Stub|TRANSACTION_getRetraceCapabilities|I:4',
        'IR8CompilerProvider$Stub|TRANSACTION_openRetraceSession|I:5',
        'IR8CompilerSession$Stub|TRANSACTION_cancel|I:1',
        'IR8CompilerSession$Stub|TRANSACTION_close|I:2'
    )) {
        $expectedBinder.Add('TRANSACTION|org/autojs/plugin/r8compiler/api/' + $record)
    }
    Require (@(Compare-Object @($expectedBinder | Sort-Object) @($Abi.BinderConstants | Sort-Object) -CaseSensitive).Count -eq 0) `
        "G10 Binder constants or transaction IDs differ"
}

function Get-SdkRoot {
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and [IO.Directory]::Exists($candidate)) {
            return [IO.Path]::GetFullPath($candidate)
        }
    }
    $properties = Join-Path $root 'local.properties'
    if ([IO.File]::Exists($properties)) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) {
            $value = $line.Substring($line.IndexOf('=') + 1).Replace('\\', '\').Replace('\:', ':')
            if ([IO.Directory]::Exists($value)) { return [IO.Path]::GetFullPath($value) }
        }
    }
    throw 'Android SDK root is required for the detached G10 consumer compile'
}

function Invoke-DetachedConsumer([byte[]]$ProtocolClasses, [byte[]]$R8Classes) {
    $javac = if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        Join-Path $env:JAVA_HOME 'bin/javac.exe'
    } else {
        (Get-Command javac.exe -ErrorAction Stop).Source
    }
    Require ([IO.File]::Exists($javac)) "javac is required"
    $androidJar = Join-Path (Get-SdkRoot) 'platforms/android-36/android.jar'
    Require ([IO.File]::Exists($androidJar)) "android-36/android.jar is required"
    $sourceRelative = 'test-consumer-g10/src/main/java/org/autojs/plugin/r8compiler/consumer/R8RetraceContractDetachedConsumer.java'
    $source = Join-Path $root $sourceRelative
    $temporary = Join-Path ([IO.Path]::GetTempPath()) ("autojs6-r8-g10-consumer-{0}" -f [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($temporary) | Out-Null
    try {
        $protocolJar = Join-Path $temporary 'protocol.jar'
        $r8Jar = Join-Path $temporary 'r8-api.jar'
        $classes = Join-Path $temporary 'classes'
        $emptySource = Join-Path $temporary 'empty-source'
        [IO.Directory]::CreateDirectory($classes) | Out-Null
        [IO.Directory]::CreateDirectory($emptySource) | Out-Null
        [IO.File]::WriteAllBytes($protocolJar, $ProtocolClasses)
        [IO.File]::WriteAllBytes($r8Jar, $R8Classes)
        $classpath = @($androidJar, $protocolJar, $r8Jar) -join [IO.Path]::PathSeparator
        & $javac '--release' '17' '-encoding' 'UTF-8' '-proc:none' '-implicit:none' `
            '-sourcepath' $emptySource '-classpath' $classpath '-d' $classes $source
        if ($LASTEXITCODE -ne 0) { throw "Detached G10 javac failed with exit code $LASTEXITCODE" }
        $emitted = Join-Path $classes 'org/autojs/plugin/r8compiler/consumer/R8RetraceContractDetachedConsumer.class'
        Require ([IO.File]::Exists($emitted)) "Detached G10 consumer class was not emitted"
        $emittedBytes = [IO.File]::ReadAllBytes($emitted)
        $latin = [Text.Encoding]::GetEncoding(28591).GetString($emittedBytes)
        foreach ($symbol in @('getRetraceCapabilities', 'openRetraceSession', 'PROTOCOL_V1_1')) {
            Require ($latin.Contains($symbol, [StringComparison]::Ordinal)) "Detached G10 bytecode omits $symbol"
        }
        $versionText = @(& $javac -version 2>&1 | ForEach-Object { "$_" }) -join ' '
        return [ordered]@{
            verified = $true
            javacVersion = $versionText
            release = 17
            sourcePath = 'EMPTY'
            androidJarSha256 = Get-BytesSha256 ([IO.File]::ReadAllBytes($androidJar))
            source = Get-FileRecord $source $sourceRelative
            emittedClass = 'org/autojs/plugin/r8compiler/consumer/R8RetraceContractDetachedConsumer.class'
            emittedClassSha256 = Get-BytesSha256 $emittedBytes
        }
    } finally {
        if ([IO.Directory]::Exists($temporary)) { [IO.Directory]::Delete($temporary, $true) }
    }
}

function Get-JsonBytes($Value) {
    $json = ($Value | ConvertTo-Json -Depth 20).Replace("`r`n", "`n") + "`n"
    return $utf8.GetBytes($json)
}

$sourceRecords = Get-SourceRecords
$sourceFingerprint = Get-SourceFingerprint $sourceRecords
$parserPath = Join-Path $root 'scripts/R8JvmAbi.cs'
$parserText = $utf8.GetString([IO.File]::ReadAllBytes($parserPath))
if (-not ('AutoJs6R8JvmAbi' -as [type])) { Add-Type -TypeDefinition $parserText }

$firstArguments = @(
    ':plugin-api:protocol-wire-api:clean',
    ':plugin-api:r8-compiler-api:clean',
    ':plugin-api:r8-compiler-api:testDebugUnitTest',
    ':plugin-api:protocol-wire-api:bundleReleaseAar',
    ':plugin-api:r8-compiler-api:bundleReleaseAar',
    '--no-build-cache',
    '--console=plain'
)
Invoke-Gradle $firstArguments
$protocolFirst = [IO.File]::ReadAllBytes($protocolBuild)
$r8First = [IO.File]::ReadAllBytes($r8Build)
Invoke-Gradle @(
    ':plugin-api:protocol-wire-api:clean',
    ':plugin-api:r8-compiler-api:clean',
    ':plugin-api:protocol-wire-api:bundleReleaseAar',
    ':plugin-api:r8-compiler-api:bundleReleaseAar',
    '--no-build-cache',
    '--console=plain'
)
$protocolSecond = [IO.File]::ReadAllBytes($protocolBuild)
$r8Second = [IO.File]::ReadAllBytes($r8Build)
Require (Test-BytesEqual $protocolFirst $protocolSecond) "Protocol AAR is not byte-repeatable"
Require (Test-BytesEqual $r8First $r8Second) "R8 API 0.2.0 AAR is not byte-repeatable"

$sourceRecordsAfter = Get-SourceRecords
Require ((Get-SourceFingerprint $sourceRecordsAfter).sha256 -ceq $sourceFingerprint.sha256) `
    "G10 contract sources changed during verification"

if ($UpdateEvidence) {
    [IO.Directory]::CreateDirectory($releaseRoot) | Out-Null
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($abiPath)) | Out-Null
    [IO.File]::WriteAllBytes($protocolRelease, $protocolFirst)
    [IO.File]::WriteAllBytes($r8Release, $r8First)
} else {
    Require ([IO.File]::Exists($protocolRelease) -and [IO.File]::Exists($r8Release)) "Staged G10 AARs are missing"
    Require (Test-BytesEqual $protocolFirst ([IO.File]::ReadAllBytes($protocolRelease))) "Staged protocol AAR differs"
    Require (Test-BytesEqual $r8First ([IO.File]::ReadAllBytes($r8Release))) "Staged R8 API AAR differs"
}

$protocolBoundary = Get-AarBoundary $protocolFirst $false
$r8Boundary = Get-AarBoundary $r8First $true
Assert-BinderAbi $r8Boundary.abi
$canonicalText = "DISTRIBUTION-JAVA-VISIBLE-JVM-ABI-V1`n" +
    "ARTIFACT|$protocolName`n" + $protocolBoundary.abi.CanonicalText +
    "ARTIFACT|$r8Name`n" + $r8Boundary.abi.CanonicalText
$canonicalBytes = $utf8.GetBytes($canonicalText)
if ($UpdateEvidence) {
    [IO.File]::WriteAllBytes($abiPath, $canonicalBytes)
} else {
    Require ([IO.File]::Exists($abiPath)) "G10 JVM ABI golden is missing"
    Require (Test-BytesEqual $canonicalBytes ([IO.File]::ReadAllBytes($abiPath))) "G10 JVM ABI golden differs"
}

$aidlRecords = @(Get-ChildItem -LiteralPath (Join-Path $root 'plugin-api/r8-compiler-api/src/main/aidl') -Recurse -File -Filter '*.aidl' |
    Sort-Object Name | ForEach-Object {
        $relative = [IO.Path]::GetRelativePath($root, $_.FullName).Replace('\', '/')
        $raw = [IO.File]::ReadAllText($_.FullName).Replace("`r`n", "`n").Replace("`r", "`n")
        [ordered]@{
            path = $relative
            byteLength = [long]$utf8.GetByteCount($raw)
            normalizedLfSha256 = Get-BytesSha256 ($utf8.GetBytes($raw))
        }
    })
$providerAidl = $aidlRecords | Where-Object { $_.path.EndsWith('/IR8CompilerProvider.aidl') }
Require ($providerAidl.normalizedLfSha256 -ceq 'b8724cf852b79e5cc8b2c47a60ac32d9eac20efbb1ad655c7ff6b1f3643b0cea') `
    "G10 provider AIDL normalized hash differs"

$consumer = Invoke-DetachedConsumer $protocolBoundary.classesBytes $r8Boundary.classesBytes
$manifest = [ordered]@{
    schemaVersion = 1
    evidenceBoundary = 'RETRACE_CONTRACT_AAR_ONLY'
    distributionVersion = $version
    protocolVersion = '1.1'
    sourceFingerprint = $sourceFingerprint
    artifacts = @(
        $(Get-BytesRecord $protocolFirst $protocolName),
        $(Get-BytesRecord $r8First $r8Name)
    )
    reproducibility = [ordered]@{
        scope = 'SAME_MACHINE_CLEAN_REBUILD'
        protocolByteIdentical = $true
        r8ApiByteIdentical = $true
        buildCount = 2
        buildCache = 'DISABLED'
    }
    aidlBoundary = [ordered]@{
        sourceDescriptors = $aidlRecords
        existingProviderTransactionsPreserved = @(1, 2, 3)
        appendedProviderTransactions = @(4, 5)
        interfaceMethodDescriptors = @($r8Boundary.abi.InterfaceMethodDescriptors)
        binderConstants = @($r8Boundary.abi.BinderConstants)
    }
    abiBoundary = [ordered]@{
        status = 'VERIFIED'
        scope = 'JAVA_VISIBLE_JVM_BINARY_ABI'
        canonicalization = 'STRICT_CLASSFILE_V1'
        canonicalSha256 = Get-BytesSha256 $canonicalBytes
        golden = Get-BytesRecord $canonicalBytes $abiRelative
        parser = Get-FileRecord $parserPath 'scripts/R8JvmAbi.cs'
        protocol = [ordered]@{
            classEntryCount = @($protocolBoundary.abi.ClassEntries).Count
            visibleClassCount = $protocolBoundary.abi.VisibleClassCount
            visibleMemberCount = $protocolBoundary.abi.VisibleMemberCount
        }
        r8Api = [ordered]@{
            classEntryCount = @($r8Boundary.abi.ClassEntries).Count
            visibleClassCount = $r8Boundary.abi.VisibleClassCount
            visibleMemberCount = $r8Boundary.abi.VisibleMemberCount
        }
    }
    wireGoldens = [ordered]@{
        capabilitiesSha256 = '96c34cd4b1dbb0228c1edc09dc8a24260b144f9ef3788eafceef3fcf32a00e60'
        requestSha256 = '472df75f6d1c70d9949eb7c6ecaa3875082139648f9b36dbf3cae55b6832813a'
        inputBundleSha256 = 'a60652baad0e637a0910e3305d236fd69803caa0c1dd8fbea899ffbe0bdfd1b1'
        verifiedBy = ':plugin-api:r8-compiler-api:testDebugUnitTest'
    }
    consumerCompile = $consumer
    claims = [ordered]@{
        compilationProtocolUnchanged = $true
        retraceAppendOnly = $true
        pathFreeWire = $true
        r8EngineEmbeddedInApi = $false
        deviceExecution = $false
        crossMachineReproducibility = $false
    }
}
$manifestBytes = Get-JsonBytes $manifest
if ($UpdateEvidence) {
    [IO.File]::WriteAllBytes($manifestPath, $manifestBytes)
} else {
    Require ([IO.File]::Exists($manifestPath)) "G10 distribution manifest is missing"
    Require (Test-BytesEqual $manifestBytes ([IO.File]::ReadAllBytes($manifestPath))) "G10 distribution manifest differs"
}

Write-Output "G10_RETRACE_CONTRACT_VERIFIED version=$version r8Sha256=$(Get-BytesSha256 $r8First) abiSha256=$(Get-BytesSha256 $canonicalBytes)"
