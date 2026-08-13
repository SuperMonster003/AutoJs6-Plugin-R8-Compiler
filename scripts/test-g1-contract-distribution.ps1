[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ProtocolWireAar,
    [string]$R8CompilerApiAar
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$root = [IO.Path]::GetFullPath($RepositoryRoot)
if ([string]::IsNullOrWhiteSpace($ProtocolWireAar)) {
    $ProtocolWireAar = Join-Path $root 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-release.aar'
}
if ([string]::IsNullOrWhiteSpace($R8CompilerApiAar)) {
    $R8CompilerApiAar = Join-Path $root 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
}
$protocolCandidate = [IO.Path]::GetFullPath($ProtocolWireAar)
$r8Candidate = [IO.Path]::GetFullPath($R8CompilerApiAar)
if (-not [IO.File]::Exists($protocolCandidate) -or -not [IO.File]::Exists($r8Candidate)) {
    throw "Missing release AAR prerequisite. Build exactly :plugin-api:protocol-wire-api:assembleRelease and :plugin-api:r8-compiler-api:assembleRelease, then run scripts/test-g1-contract-distribution.ps1."
}

$shell = (Get-Process -Id $PID).Path
$gateRelative = 'scripts/verify-g1-contract-distribution.ps1'
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$fixtures = [Collections.Generic.List[string]]::new()
$passed = 0
$failed = 0
$expectedTestCount = 31

function Assert-True {
    param([Parameter(Mandatory)][bool]$Condition, [Parameter(Mandatory)][string]$Message)
    if (-not $Condition) { throw $Message }
}

function Test-IsWithin {
    param([Parameter(Mandatory)][string]$Child, [Parameter(Mandatory)][string]$Parent)
    $childFull = [IO.Path]::GetFullPath($Child).TrimEnd('\', '/')
    $parentFull = [IO.Path]::GetFullPath($Parent).TrimEnd('\', '/')
    return $childFull.StartsWith($parentFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Copy-RelativeFile {
    param([Parameter(Mandatory)][string]$SourceRoot, [Parameter(Mandatory)][string]$TargetRoot, [Parameter(Mandatory)][string]$Relative)
    $source = Join-Path $SourceRoot $Relative
    if (-not [IO.File]::Exists($source)) { throw "Fixture source is missing: $Relative" }
    $target = Join-Path $TargetRoot $Relative
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
    [IO.File]::Copy($source, $target, $false)
}

function Copy-RelativeTree {
    param([Parameter(Mandatory)][string]$SourceRoot, [Parameter(Mandatory)][string]$TargetRoot, [Parameter(Mandatory)][string]$RelativeRoot)
    $sourceTree = Join-Path $SourceRoot $RelativeRoot
    if (-not [IO.Directory]::Exists($sourceTree)) { throw "Fixture source tree is missing: $RelativeRoot" }
    foreach ($file in Get-ChildItem -LiteralPath $sourceTree -Recurse -File -Force) {
        $relative = [IO.Path]::GetRelativePath($SourceRoot, $file.FullName)
        Copy-RelativeFile $SourceRoot $TargetRoot $relative
    }
}

function New-Fixture {
    $fixture = Join-Path $temporaryBase ("autojs6-r8-contract-distribution-selftest-{0}" -f [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($fixture) | Out-Null
    $fixtures.Add($fixture)
    foreach ($relative in @(
        '.gitattributes',
        'settings.gradle.kts',
        'build.gradle.kts',
        'gradle.properties',
        'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.properties',
        'gradle/wrapper/gradle-wrapper.jar',
        'plugin-api/protocol-wire-api/build.gradle.kts',
        'plugin-api/protocol-wire-api/consumer-rules.pro',
        'plugin-api/r8-compiler-api/build.gradle.kts',
        'plugin-api/r8-compiler-api/consumer-rules.pro',
        'plugin-api/r8-compiler-api/abi/0.1.0-java-visible-jvm-abi.txt',
        'scripts/R8JvmAbi.cs',
        $gateRelative
    )) { Copy-RelativeFile $root $fixture $relative }
    foreach ($relativeTree in @(
        'plugin-api/protocol-wire-api/src/main',
        'plugin-api/r8-compiler-api/src/main',
        'test-consumer/src/main/java'
    )) { Copy-RelativeTree $root $fixture $relativeTree }

    $protocolOutput = Join-Path $fixture 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-release.aar'
    $r8Output = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($protocolOutput)) | Out-Null
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($r8Output)) | Out-Null
    [IO.File]::Copy($protocolCandidate, $protocolOutput, $false)
    [IO.File]::Copy($r8Candidate, $r8Output, $false)
    $fixtureSnapshotTime = [DateTime]::UtcNow.AddSeconds(1)
    [IO.File]::SetLastWriteTimeUtc($protocolOutput, $fixtureSnapshotTime)
    [IO.File]::SetLastWriteTimeUtc($r8Output, $fixtureSnapshotTime)
    return $fixture
}

function Invoke-Gate {
    param([Parameter(Mandatory)][string]$Fixture, [string[]]$ExtraArguments = @())
    $gate = Join-Path $Fixture $gateRelative
    $arguments = @(
        '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', $gate,
        '-RepositoryRoot', $Fixture
    ) + $ExtraArguments
    $output = @(& $shell @arguments 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = $output
        ReportPath = Join-Path $Fixture 'build/reports/g1-contract-distribution.json'
        ReleaseDirectory = Join-Path $Fixture 'plugin-api/r8-compiler-api/releases/0.1.0'
    }
}

function Read-Report {
    param([Parameter(Mandatory)][string]$Path)
    if (-not [IO.File]::Exists($Path)) { throw "Gate report is missing" }
    Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
}

function Assert-AllClaimsFalse {
    param([Parameter(Mandatory)]$Report)
    $nonFalse = @($Report.claims.psobject.Properties | Where-Object { $_.Value -ne $false })
    Assert-True ($nonFalse.Count -eq 0) "A runtime claim was promoted by the contract-only gate"
}

function Add-BenignZipEntry {
    param([Parameter(Mandatory)][string]$Path)
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update, $false)
    try {
        $entry = $zip.CreateEntry("META-INF/selftest-collision-{0}.txt" -f [Guid]::NewGuid().ToString('N'))
        $writer = [IO.StreamWriter]::new($entry.Open(), [Text.UTF8Encoding]::new($false))
        try { $writer.Write("different candidate bytes`n") } finally { $writer.Dispose() }
    } finally {
        $zip.Dispose()
        $stream.Dispose()
    }
}

function Add-AarEntry {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][byte[]]$Bytes
    )
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update, $false)
    try {
        $entry = $zip.CreateEntry($Name)
        $entryStream = $entry.Open()
        try { $entryStream.Write($Bytes, 0, $Bytes.Length) } finally { $entryStream.Dispose() }
    } finally {
        $zip.Dispose()
        $stream.Dispose()
    }
}

function Replace-AarEntry {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][byte[]]$Bytes
    )
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update, $false)
    try {
        $existing = @($zip.Entries | Where-Object { $_.FullName -ceq $Name })
        if ($existing.Count -ne 1) { throw "Fixture AAR entry boundary is invalid: $Name" }
        $existing[0].Delete()
        $entry = $zip.CreateEntry($Name)
        $entryStream = $entry.Open()
        try { $entryStream.Write($Bytes, 0, $Bytes.Length) } finally { $entryStream.Dispose() }
    } finally {
        $zip.Dispose()
        $stream.Dispose()
    }
}

function Add-ClassesJarEntry {
    param(
        [Parameter(Mandatory)][string]$AarPath,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][byte[]]$Bytes
    )
    $stream = [IO.File]::Open($AarPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $aar = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update, $false)
    try {
        $classesEntry = @($aar.Entries | Where-Object { $_.FullName -ceq 'classes.jar' })
        if ($classesEntry.Count -ne 1) { throw 'Fixture AAR classes.jar boundary is invalid' }
        $classesMemory = [IO.MemoryStream]::new()
        $classesInput = $classesEntry[0].Open()
        try { $classesInput.CopyTo($classesMemory) } finally { $classesInput.Dispose() }
        $classesMemory.Position = 0
        $classes = [IO.Compression.ZipArchive]::new($classesMemory, [IO.Compression.ZipArchiveMode]::Update, $true)
        try {
            $entry = $classes.CreateEntry($Name)
            $entryStream = $entry.Open()
            try { $entryStream.Write($Bytes, 0, $Bytes.Length) } finally { $entryStream.Dispose() }
        } finally { $classes.Dispose() }
        $classesBytes = $classesMemory.ToArray()
        $classesMemory.Dispose()
        $classesEntry[0].Delete()
        $replacement = $aar.CreateEntry('classes.jar')
        $replacementStream = $replacement.Open()
        try { $replacementStream.Write($classesBytes, 0, $classesBytes.Length) } finally { $replacementStream.Dispose() }
    } finally {
        $aar.Dispose()
        $stream.Dispose()
    }
    [IO.File]::SetLastWriteTimeUtc($AarPath, [DateTime]::UtcNow.AddSeconds(2))
}

function Edit-ClassesJarClass {
    param(
        [Parameter(Mandatory)][string]$AarPath,
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][scriptblock]$Mutation
    )
    $stream = [IO.File]::Open($AarPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $aar = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Update, $false)
    try {
        $classesEntry = @($aar.Entries | Where-Object { $_.FullName -ceq 'classes.jar' })
        if ($classesEntry.Count -ne 1) { throw 'Fixture AAR classes.jar boundary is invalid' }
        $classesMemory = [IO.MemoryStream]::new()
        $classesInput = $classesEntry[0].Open()
        try { $classesInput.CopyTo($classesMemory) } finally { $classesInput.Dispose() }
        $classesMemory.Position = 0
        $classes = [IO.Compression.ZipArchive]::new($classesMemory, [IO.Compression.ZipArchiveMode]::Update, $true)
        try {
            $target = @($classes.Entries | Where-Object { $_.FullName -ceq $Name })
            if ($target.Count -ne 1) { throw "Fixture class entry boundary is invalid: $Name" }
            $input = $target[0].Open()
            $classMemory = [IO.MemoryStream]::new()
            try { $input.CopyTo($classMemory) } finally { $input.Dispose() }
            [byte[]]$mutated = & $Mutation ([byte[]]$classMemory.ToArray())
            $classMemory.Dispose()
            $target[0].Delete()
            $replacementClass = $classes.CreateEntry($Name)
            $replacementClassStream = $replacementClass.Open()
            try { $replacementClassStream.Write($mutated, 0, $mutated.Length) } finally { $replacementClassStream.Dispose() }
        } finally { $classes.Dispose() }
        $classesBytes = $classesMemory.ToArray()
        $classesMemory.Dispose()
        $classesEntry[0].Delete()
        $replacement = $aar.CreateEntry('classes.jar')
        $replacementStream = $replacement.Open()
        try { $replacementStream.Write($classesBytes, 0, $classesBytes.Length) } finally { $replacementStream.Dispose() }
    } finally {
        $aar.Dispose()
        $stream.Dispose()
    }
    [IO.File]::SetLastWriteTimeUtc($AarPath, [DateTime]::UtcNow.AddSeconds(2))
}

function Replace-ClassUtf8ExactlyOnce {
    param(
        [Parameter(Mandatory)][byte[]]$ClassBytes,
        [Parameter(Mandatory)][string]$Old,
        [Parameter(Mandatory)][string]$New
    )
    $oldBytes = [Text.UTF8Encoding]::new($false).GetBytes($Old)
    $newBytes = [Text.UTF8Encoding]::new($false).GetBytes($New)
    if ($oldBytes.Length -ne $newBytes.Length -or $oldBytes.Length -gt 65535) { throw 'Selftest UTF8 mutation length differs' }
    $pattern = [byte[]](1, (($oldBytes.Length -shr 8) -band 0xff), ($oldBytes.Length -band 0xff)) + $oldBytes
    $matches = [Collections.Generic.List[int]]::new()
    for ($at = 0; $at -le $ClassBytes.Length - $pattern.Length; $at++) {
        $same = $true
        for ($i = 0; $i -lt $pattern.Length; $i++) {
            if ($ClassBytes[$at + $i] -ne $pattern[$i]) { $same = $false; break }
        }
        if ($same) { $matches.Add($at) }
    }
    if ($matches.Count -ne 1) { throw "Selftest UTF8 constant match count was $($matches.Count): $Old" }
    $result = [byte[]]$ClassBytes.Clone()
    [Array]::Copy($newBytes, 0, $result, $matches[0] + 3, $newBytes.Length)
    return ,$result
}

function Get-ClassAccessOffset {
    param([Parameter(Mandatory)][byte[]]$ClassBytes)
    if ($ClassBytes.Length -lt 11) { throw 'Selftest class is too short' }
    $count = ($ClassBytes[8] -shl 8) -bor $ClassBytes[9]
    $offset = 10
    for ($index = 1; $index -lt $count; $index++) {
        $tag = $ClassBytes[$offset++]
        switch ($tag) {
            1 { $length = ($ClassBytes[$offset] -shl 8) -bor $ClassBytes[$offset + 1]; $offset += 2 + $length }
            { $_ -in 3, 4 } { $offset += 4 }
            { $_ -in 5, 6 } { $offset += 8; $index++ }
            { $_ -in 7, 8, 16, 19, 20 } { $offset += 2 }
            { $_ -in 9, 10, 11, 12, 17, 18 } { $offset += 4 }
            15 { $offset += 3 }
            default { throw "Selftest encountered unknown constant-pool tag: $tag" }
        }
        if ($offset -gt $ClassBytes.Length) { throw 'Selftest class constant pool is truncated' }
    }
    return $offset
}

function New-MinimalClassBytes {
    param(
        [Parameter(Mandatory)][string]$InternalName,
        [Parameter(Mandatory)][string]$SuperInternalName
    )
    $stream = [IO.MemoryStream]::new()
    function Write-U2([int]$value) {
        $stream.WriteByte(($value -shr 8) -band 0xff)
        $stream.WriteByte($value -band 0xff)
    }
    function Write-Utf8([string]$value) {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($value)
        $stream.WriteByte(1)
        Write-U2 $bytes.Length
        $stream.Write($bytes, 0, $bytes.Length)
    }
    try {
        foreach ($byte in [byte[]](0xca, 0xfe, 0xba, 0xbe)) { $stream.WriteByte($byte) }
        Write-U2 0
        Write-U2 52
        Write-U2 5
        Write-Utf8 $InternalName
        $stream.WriteByte(7); Write-U2 1
        Write-Utf8 $SuperInternalName
        $stream.WriteByte(7); Write-U2 3
        Write-U2 0x21
        Write-U2 2
        Write-U2 4
        Write-U2 0
        Write-U2 0
        Write-U2 0
        Write-U2 0
        return ,$stream.ToArray()
    } finally { $stream.Dispose() }
}

function Invoke-TestCase {
    param([Parameter(Mandatory)][string]$Name, [Parameter(Mandatory)][scriptblock]$Body)
    try {
        & $Body
        $script:passed++
        Write-Output "PASS: $Name"
    } catch {
        $script:failed++
        Write-Output "FAIL: $Name -- $($_.Exception.Message)"
    }
}

try {
    Invoke-TestCase 'valid candidates create and then match the immutable distribution' {
        $fixture = New-Fixture
        $first = Invoke-Gate $fixture
        Assert-True ($first.ExitCode -eq 0) ("Initial valid gate invocation failed: {0}" -f ($first.Output -join ' '))
        $firstReport = Read-Report $first.ReportPath
        Assert-True ([bool]$firstReport.passed) "Initial report did not pass"
        Assert-True ([bool]$firstReport.consumerCompileVerified) "Detached consumer proof is false"
        Assert-True ($firstReport.evidenceBoundary -eq 'CONTRACT_AAR_ONLY') "Evidence boundary drifted"
        Assert-AllClaimsFalse $firstReport
        Assert-True ($firstReport.releaseState -eq 'CREATED') "Initial release was not atomically created"
        $manifestBytes = [IO.File]::ReadAllBytes((Join-Path $first.ReleaseDirectory 'contract-distribution-manifest.json'))
        Assert-True (-not ($manifestBytes -contains [byte]13)) "Immutable manifest is not canonical LF-only JSON"
        $second = Invoke-Gate $fixture
        Assert-True ($second.ExitCode -eq 0) ("Identical candidate recheck failed: {0}" -f ($second.Output -join ' '))
        $secondReport = Read-Report $second.ReportPath
        Assert-True ($secondReport.releaseState -eq 'IDENTICAL') "Identical candidate was not recognized"
    }

    Invoke-TestCase 'different candidate bytes cannot collide with an existing version' {
        $fixture = New-Fixture
        $first = Invoke-Gate $fixture
        Assert-True ($first.ExitCode -eq 0) ("Collision fixture could not create its baseline: {0}" -f ($first.Output -join ' '))
        $staged = Join-Path $first.ReleaseDirectory 'r8-compiler-api-0.1.0.aar'
        $before = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash
        Add-BenignZipEntry (Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar')
        $collision = Invoke-Gate $fixture
        Assert-True ($collision.ExitCode -ne 0) "Different bytes were accepted for an immutable version"
        $report = Read-Report $collision.ReportPath
        Assert-True (-not [bool]$report.passed) "Collision retained passed=true"
        Assert-AllClaimsFalse $report
        $after = (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash
        Assert-True ($before -eq $after) "Collision mutated the immutable staged AAR"
    }

    Invoke-TestCase 'malformed candidate atomically replaces a stale PASS report' {
        $fixture = New-Fixture
        $reportPath = Join-Path $fixture 'build/reports/g1-contract-distribution.json'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($reportPath)) | Out-Null
        [IO.File]::WriteAllText($reportPath, '{"passed":true}', [Text.UTF8Encoding]::new($false))
        [IO.File]::WriteAllBytes(
            (Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'),
            [Text.Encoding]::ASCII.GetBytes('not-an-aar')
        )
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) "Malformed AAR unexpectedly passed"
        $report = Read-Report $reportPath
        Assert-True (-not [bool]$report.passed) "Malformed AAR retained stale passed=true"
        Assert-True (-not [bool]$report.consumerCompileVerified) "Malformed AAR retained consumer proof"
        Assert-AllClaimsFalse $report
    }

    Invoke-TestCase 'invalid candidate path atomically replaces a stale PASS report' {
        $fixture = New-Fixture
        $reportPath = Join-Path $fixture 'build/reports/g1-contract-distribution.json'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($reportPath)) | Out-Null
        [IO.File]::WriteAllText($reportPath, '{"passed":true}', [Text.UTF8Encoding]::new($false))
        $wrongCandidate = Join-Path $fixture 'wrong-protocol.aar'
        $result = Invoke-Gate $fixture @('-ProtocolWireAar', $wrongCandidate)
        Assert-True ($result.ExitCode -ne 0) 'Invalid candidate path unexpectedly passed'
        $report = Read-Report $reportPath
        Assert-True (-not [bool]$report.passed) 'Invalid candidate path retained stale passed=true'
        Assert-AllClaimsFalse $report
    }

    Invoke-TestCase 'missing ABI parser or golden atomically replaces a stale PASS report' {
        foreach ($relative in @(
            'scripts/R8JvmAbi.cs',
            'plugin-api/r8-compiler-api/abi/0.1.0-java-visible-jvm-abi.txt'
        )) {
            $fixture = New-Fixture
            $reportPath = Join-Path $fixture 'build/reports/g1-contract-distribution.json'
            [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($reportPath)) | Out-Null
            [IO.File]::WriteAllText($reportPath, '{"passed":true}', [Text.UTF8Encoding]::new($false))
            [IO.File]::Delete((Join-Path $fixture $relative))
            $result = Invoke-Gate $fixture
            Assert-True ($result.ExitCode -ne 0) "Missing required ABI input unexpectedly passed: $relative"
            $report = Read-Report $reportPath
            Assert-True (-not [bool]$report.passed) "Missing required ABI input retained stale passed=true: $relative"
            Assert-AllClaimsFalse $report
        }
    }

    Invoke-TestCase 'gitattributes drift invalidates distribution provenance' {
        $fixture = New-Fixture
        $baseline = Invoke-Gate $fixture
        Assert-True ($baseline.ExitCode -eq 0) 'gitattributes baseline failed'
        $path = Join-Path $fixture '.gitattributes'
        [IO.File]::AppendAllText($path, "*.zip -text`n")
        $stamp = [DateTime]::UtcNow.AddSeconds(2)
        [IO.File]::SetLastWriteTimeUtc((Join-Path $fixture 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-release.aar'), $stamp)
        [IO.File]::SetLastWriteTimeUtc((Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'), $stamp)
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'gitattributes drift unexpectedly passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'gitattributes drift produced PASS'
    }

    Invoke-TestCase 'direct and hard-link output aliases cannot mutate a protected source' {
        $fixture = New-Fixture
        $protected = Join-Path $fixture 'settings.gradle.kts'
        $before = (Get-FileHash -LiteralPath $protected -Algorithm SHA256).Hash
        $direct = Invoke-Gate $fixture @('-OutputPath', $protected)
        Assert-True ($direct.ExitCode -ne 0) "Direct output alias unexpectedly passed"
        Assert-True ($before -eq (Get-FileHash -LiteralPath $protected -Algorithm SHA256).Hash) "Direct alias mutated source"

        $reportPath = Join-Path $fixture 'build/reports/g1-contract-distribution.json'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($reportPath)) | Out-Null
        New-Item -ItemType HardLink -Path $reportPath -Target $protected | Out-Null
        $hardLink = Invoke-Gate $fixture
        Assert-True ($hardLink.ExitCode -ne 0) "Hard-link output alias unexpectedly passed"
        Assert-True ($before -eq (Get-FileHash -LiteralPath $protected -Algorithm SHA256).Hash) "Hard-link alias mutated source"

        $releaseFixture = New-Fixture
        $baseline = Invoke-Gate $releaseFixture
        Assert-True ($baseline.ExitCode -eq 0) 'Release-manifest alias baseline failed'
        $releaseManifest = Join-Path $baseline.ReleaseDirectory 'contract-distribution-manifest.json'
        $manifestBefore = (Get-FileHash -LiteralPath $releaseManifest -Algorithm SHA256).Hash
        [IO.File]::Delete($baseline.ReportPath)
        New-Item -ItemType HardLink -Path $baseline.ReportPath -Target $releaseManifest | Out-Null
        $releaseAlias = Invoke-Gate $releaseFixture
        Assert-True ($releaseAlias.ExitCode -ne 0) 'Report hard-link to the immutable release manifest unexpectedly passed'
        Assert-True ($manifestBefore -eq (Get-FileHash -LiteralPath $releaseManifest -Algorithm SHA256).Hash) 'Report alias mutated the immutable release manifest'
        Assert-True ($manifestBefore -eq (Get-FileHash -LiteralPath $baseline.ReportPath -Algorithm SHA256).Hash) 'Report alias was replaced despite failing preflight'
    }

    Invoke-TestCase 'release path reparse aliases fail closed' {
        $fixture = New-Fixture
        $outside = Join-Path $fixture 'alias-target'
        [IO.Directory]::CreateDirectory($outside) | Out-Null
        $releases = Join-Path $fixture 'plugin-api/r8-compiler-api/releases'
        New-Item -ItemType Junction -Path $releases -Target $outside | Out-Null
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) "Release junction unexpectedly passed"
        $report = Read-Report $result.ReportPath
        Assert-True (-not [bool]$report.passed) "Release junction produced a passing report"
        Assert-AllClaimsFalse $report
        Assert-True (-not [IO.Directory]::Exists((Join-Path $outside '0.1.0'))) "Release junction received staged artifacts"
    }

    Invoke-TestCase 'outer AAR unexpected native payload fails closed' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Add-AarEntry $candidate 'payload.bin' ([byte[]](0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01))
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Unexpected outer AAR payload passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'Unexpected outer payload produced PASS'
    }

    Invoke-TestCase 'outer AAR directory entry fails closed' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Add-AarEntry $candidate 'unexpected/' ([byte[]]@())
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Outer AAR directory entry passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'Outer directory entry produced PASS'
    }

    Invoke-TestCase 'classes jar unexpected nested payload fails closed' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Add-ClassesJarEntry $candidate 'org/autojs/plugin/r8compiler/api/payload.bin' ([byte[]](0x50, 0x4b, 0x03, 0x04))
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Unexpected classes.jar payload passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'Unexpected classes payload produced PASS'
    }

    Invoke-TestCase 'tracked ABI golden drift fails byte-exact comparison' {
        $fixture = New-Fixture
        $golden = Join-Path $fixture 'plugin-api/r8-compiler-api/abi/0.1.0-java-visible-jvm-abi.txt'
        [IO.File]::AppendAllText($golden, "GOLDEN-DRIFT`n", [Text.UTF8Encoding]::new($false))
        $stamp = [DateTime]::UtcNow.AddSeconds(2)
        [IO.File]::SetLastWriteTimeUtc((Join-Path $fixture 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-release.aar'), $stamp)
        [IO.File]::SetLastWriteTimeUtc((Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'), $stamp)
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Mutated ABI golden passed'
        $report = Read-Report $result.ReportPath
        Assert-True ($report.failure -like '*differs from the tracked*golden*') 'Mutated ABI golden did not fail at byte-exact ABI comparison'
        Assert-True (-not [bool]$report.passed) 'Mutated ABI golden produced PASS'
    }

    Invoke-TestCase 'malformed class constant-pool tag fails strict parser' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        $name = 'org/autojs/plugin/r8compiler/api/SelftestMalformedClass'
        $malformed = New-MinimalClassBytes $name 'java/lang/Object'
        $malformed[10] = 99
        Add-ClassesJarEntry $candidate ($name + '.class') $malformed
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Malformed class constant-pool tag passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'Malformed class produced PASS'
    }

    Invoke-TestCase 'public class visibility drift fails ABI golden' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Edit-ClassesJarClass $candidate 'org/autojs/plugin/r8compiler/api/R8CompilerFamily.class' {
            param([byte[]]$classBytes)
            $access = Get-ClassAccessOffset $classBytes
            $result = [byte[]]$classBytes.Clone()
            $result[$access + 1] = $result[$access + 1] -band 0xfe
            return ,$result
        }
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Public class visibility drift passed'
        Assert-True ((Read-Report $result.ReportPath).failure -like '*ABI differs*golden*') 'Visibility drift did not fail ABI golden'
    }

    Invoke-TestCase 'legal delimiter in class identity remains injective canonical ABI input' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        $name = 'org/autojs/plugin/r8compiler/api/Selftest|Delimiter'
        Add-ClassesJarEntry $candidate ($name + '.class') (New-MinimalClassBytes $name 'java/lang/Object')
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Delimiter-bearing class identity passed the frozen class set'
        Assert-True ((Read-Report $result.ReportPath).failure -like '*ABI differs*golden*') 'Delimiter-bearing identity was rejected before injective ABI canonicalization'
    }

    Invoke-TestCase 'public member name drift fails ABI golden' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Edit-ClassesJarClass $candidate 'org/autojs/plugin/r8compiler/api/R8WireCode.class' {
            param([byte[]]$classBytes)
            Replace-ClassUtf8ExactlyOnce $classBytes 'getWireCode' 'getWireMode'
        }
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Public member name drift passed'
        Assert-True ((Read-Report $result.ReportPath).failure -like '*ABI differs*golden*') 'Member drift did not fail ABI golden'
    }

    Invoke-TestCase 'AIDL interface method descriptor drift fails exact Binder contract' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Edit-ClassesJarClass $candidate 'org/autojs/plugin/r8compiler/api/IR8CompilerProvider.class' {
            param([byte[]]$classBytes)
            Replace-ClassUtf8ExactlyOnce $classBytes '()[B' '()[I'
        }
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'AIDL interface descriptor drift passed'
        $failure = (Read-Report $result.ReportPath).failure
        Assert-True ($failure -like '*AIDL abstract interface method descriptors*' -or $failure -like '*ABI differs*golden*') 'Descriptor drift did not fail the compiled ABI contract'
    }

    Invoke-TestCase 'Binder Stub transaction name drift fails exact Binder contract' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Edit-ClassesJarClass $candidate 'org/autojs/plugin/r8compiler/api/IR8CompilerSession$Stub.class' {
            param([byte[]]$classBytes)
            Replace-ClassUtf8ExactlyOnce $classBytes 'TRANSACTION_cancel' 'TRANSACTION_canzel'
        }
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Binder transaction name drift passed'
        $failure = (Read-Report $result.ReportPath).failure
        Assert-True ($failure -like '*Binder DESCRIPTOR or TRANSACTION constants*' -or $failure -like '*ABI differs*golden*') 'Transaction drift did not fail the compiled Binder contract'
    }

    Invoke-TestCase 'ListActivity component superclass fails closed' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        $name = 'org/autojs/plugin/r8compiler/api/SelftestListActivity'
        Add-ClassesJarEntry $candidate ($name + '.class') (New-MinimalClassBytes $name 'android/app/ListActivity')
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'ListActivity superclass passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'ListActivity mutation produced PASS'
    }

    Invoke-TestCase 'InputMethodService component superclass fails closed' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        $name = 'org/autojs/plugin/r8compiler/api/SelftestInputMethodService'
        Add-ClassesJarEntry $candidate ($name + '.class') (New-MinimalClassBytes $name 'android/inputmethodservice/InputMethodService')
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'InputMethodService superclass passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'InputMethodService mutation produced PASS'
    }

    Invoke-TestCase 'class file trailing bytes fail closed' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        $name = 'org/autojs/plugin/r8compiler/api/SelftestTrailingClass'
        $classBytes = New-MinimalClassBytes $name 'java/lang/Object'
        Add-ClassesJarEntry $candidate ($name + '.class') ([byte[]]($classBytes + 0x00))
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Class trailing bytes passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'Class trailing bytes produced PASS'
    }

    Invoke-TestCase 'classes jar directory entry fails closed' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Add-ClassesJarEntry $candidate 'org/autojs/plugin/r8compiler/api/unexpected/' ([byte[]]@())
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'classes.jar directory entry passed'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'classes.jar directory entry produced PASS'
    }

    Invoke-TestCase 'allowed classes jar directory entry cannot carry payload bytes' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Edit-ClassesJarClass $candidate 'META-INF/' {
            param([byte[]]$ignored)
            return ,[byte[]](0x7f, 0x45, 0x4c, 0x46)
        }
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Payload in an allowed classes.jar directory entry passed'
        $report = Read-Report $result.ReportPath
        Assert-True ($report.failure -like '*directory entries must have zero declared length*') 'Allowed directory payload did not fail its exact zero-length boundary'
        Assert-True (-not [bool]$report.passed) 'Allowed directory payload produced PASS'
    }

    Invoke-TestCase 'manifest instrumentation and permission fail closed' {
        foreach ($element in @(
            '<instrumentation android:name="sample.TestRunner" android:targetPackage="sample" />',
            '<uses-permission android:name="android.permission.INTERNET" />'
        )) {
            $fixture = New-Fixture
            $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
            $manifest = "<?xml version=`"1.0`" encoding=`"utf-8`"?><manifest xmlns:android=`"http://schemas.android.com/apk/res/android`" package=`"org.autojs.plugin.r8compiler.api`"><uses-sdk android:minSdkVersion=`"24`" />$element</manifest>"
            Replace-AarEntry $candidate 'AndroidManifest.xml' ([Text.UTF8Encoding]::new($false).GetBytes($manifest))
            $result = Invoke-Gate $fixture
            Assert-True ($result.ExitCode -ne 0) 'Forbidden manifest element passed'
            Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'Forbidden manifest element produced PASS'
        }
    }

    Invoke-TestCase 'R and consumer-rules payload drift fails closed' {
        $rFixture = New-Fixture
        $rCandidate = Join-Path $rFixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Replace-AarEntry $rCandidate 'R.txt' ([Text.UTF8Encoding]::new($false).GetBytes('int string hidden 0x7f010001'))
        $rResult = Invoke-Gate $rFixture
        Assert-True ($rResult.ExitCode -ne 0) 'Non-empty R.txt passed'
        Assert-True (-not [bool](Read-Report $rResult.ReportPath).passed) 'Non-empty R.txt produced PASS'

        $rulesFixture = New-Fixture
        $rulesCandidate = Join-Path $rulesFixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        Replace-AarEntry $rulesCandidate 'proguard.txt' ([Text.UTF8Encoding]::new($false).GetBytes("-include secret.pro`n"))
        $rulesResult = Invoke-Gate $rulesFixture
        Assert-True ($rulesResult.ExitCode -ne 0) 'Mutated proguard.txt passed'
        Assert-True (-not [bool](Read-Report $rulesResult.ReportPath).passed) 'Mutated proguard.txt produced PASS'
    }

    Invoke-TestCase 'candidate replacement cannot change published snapshot' {
        $fixture = New-Fixture
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        $before = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -eq 0) 'Snapshot baseline failed'
        $published = Join-Path $result.ReleaseDirectory 'r8-compiler-api-0.1.0.aar'
        $publishedBeforeReplacement = (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash
        [IO.File]::WriteAllBytes($candidate, [Text.Encoding]::ASCII.GetBytes('replacement-after-snapshot'))
        $publishedAfterReplacement = (Get-FileHash -LiteralPath $published -Algorithm SHA256).Hash
        $candidateAfterReplacement = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash
        Assert-True ($publishedBeforeReplacement -eq $before) "Published release did not initially bind inspected snapshot: candidate=$before published=$publishedBeforeReplacement"
        Assert-True ($publishedAfterReplacement -eq $before) "Candidate replacement changed published snapshot: candidateBefore=$before candidateAfter=$candidateAfterReplacement publishedBefore=$publishedBeforeReplacement publishedAfter=$publishedAfterReplacement"
        Assert-True ($candidateAfterReplacement -ne $before) 'Candidate replacement precondition failed'
    }

    Invoke-TestCase 'ABI parser mutation during atomic staging prevents publication' {
        $fixture = New-Fixture
        $parser = Join-Path $fixture 'scripts/R8JvmAbi.cs'
        $releaseParent = Join-Path $fixture 'plugin-api/r8-compiler-api/releases'
        $watcher = Start-Job -ArgumentList @($releaseParent, $parser) -ScriptBlock {
            param([string]$parent, [string]$parserPath)
            $deadline = [DateTime]::UtcNow.AddSeconds(30)
            while ([DateTime]::UtcNow -lt $deadline) {
                if ([IO.Directory]::Exists($parent) -and
                    @(Get-ChildItem -LiteralPath $parent -Directory -Force -Filter '.0.1.0.*.tmp' -ErrorAction SilentlyContinue).Count -gt 0) {
                    [IO.File]::AppendAllText($parserPath, "`n// concurrent selftest mutation`n", [Text.UTF8Encoding]::new($false))
                    return 'MUTATED'
                }
                Start-Sleep -Milliseconds 1
            }
            return 'TIMED_OUT'
        }
        try {
            $result = Invoke-Gate $fixture
            $watchResult = @(Receive-Job -Job $watcher -Wait)
        } finally {
            Remove-Job -Job $watcher -Force -ErrorAction SilentlyContinue
        }
        Assert-True ($watchResult -contains 'MUTATED') 'Concurrent parser mutation hook did not execute'
        Assert-True ($result.ExitCode -ne 0) 'Concurrent parser mutation passed publication'
        Assert-True (-not [IO.Directory]::Exists($result.ReleaseDirectory)) 'Concurrent parser mutation left a published release'
        $failure = (Read-Report $result.ReportPath).failure
        Assert-True ($failure -like '*source changed*' -or $failure -like '*ABI parser changed*') 'Concurrent parser mutation did not fail a stable-input validator'
    }

    Invoke-TestCase 'existing release child reparse and hard-link aliases fail closed' {
        $reparseFixture = New-Fixture
        $baseline = Invoke-Gate $reparseFixture
        Assert-True ($baseline.ExitCode -eq 0) 'Release reparse baseline failed'
        $manifest = Join-Path $baseline.ReleaseDirectory 'contract-distribution-manifest.json'
        [IO.File]::Delete($manifest)
        $target = Join-Path $reparseFixture 'manifest-target'
        [IO.Directory]::CreateDirectory($target) | Out-Null
        New-Item -ItemType Junction -Path $manifest -Target $target | Out-Null
        $reparse = Invoke-Gate $reparseFixture
        Assert-True ($reparse.ExitCode -ne 0) 'Release child reparse passed'
        Assert-True (-not [bool](Read-Report $reparse.ReportPath).passed) 'Release child reparse produced PASS'

        $hardLinkFixture = New-Fixture
        $hardBaseline = Invoke-Gate $hardLinkFixture
        Assert-True ($hardBaseline.ExitCode -eq 0) 'Release hard-link baseline failed'
        $hardManifest = Join-Path $hardBaseline.ReleaseDirectory 'contract-distribution-manifest.json'
        $outside = Join-Path $hardLinkFixture 'manifest-copy.json'
        [IO.File]::Copy($hardManifest, $outside)
        [IO.File]::Delete($hardManifest)
        New-Item -ItemType HardLink -Path $hardManifest -Target $outside | Out-Null
        $hardLink = Invoke-Gate $hardLinkFixture
        Assert-True ($hardLink.ExitCode -ne 0) 'Release child hard-link passed'
        Assert-True (-not [bool](Read-Report $hardLink.ReportPath).passed) 'Release child hard-link produced PASS'
    }

    Invoke-TestCase 'existing release child name case drift fails closed' {
        $fixture = New-Fixture
        $baseline = Invoke-Gate $fixture
        Assert-True ($baseline.ExitCode -eq 0) 'Release case-drift baseline failed'
        $original = Join-Path $baseline.ReleaseDirectory 'contract-distribution-manifest.json'
        $temporary = Join-Path $baseline.ReleaseDirectory '.manifest-case-drift.tmp'
        $variant = Join-Path $baseline.ReleaseDirectory 'CONTRACT-DISTRIBUTION-MANIFEST.JSON'
        Move-Item -LiteralPath $original -Destination $temporary
        Move-Item -LiteralPath $temporary -Destination $variant
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Case-variant immutable release child name passed'
        $report = Read-Report $result.ReportPath
        Assert-True ($report.failure -like '*not the exact immutable distribution*') 'Case-variant release child did not fail the exact name boundary'
        Assert-True (-not [bool]$report.passed) 'Case-variant release child produced PASS'
    }

    Invoke-TestCase 'empty detached consumer cannot claim compile verification' {
        $fixture = New-Fixture
        $consumer = Join-Path $fixture 'test-consumer/src/main/java/org/autojs/plugin/r8compiler/consumer/R8ContractDetachedConsumer.java'
        [IO.File]::WriteAllText(
            $consumer,
            "package org.autojs.plugin.r8compiler.consumer; public final class R8ContractDetachedConsumer {}`n",
            [Text.UTF8Encoding]::new($false)
        )
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Empty detached consumer passed'
        $report = Read-Report $result.ReportPath
        Assert-True (-not [bool]$report.passed -and -not [bool]$report.consumerCompileVerified) 'Empty consumer retained compile proof'
    }

    Invoke-TestCase 'build input drift invalidates an immutable release and stale PASS' {
        $fixture = New-Fixture
        $baseline = Invoke-Gate $fixture
        Assert-True ($baseline.ExitCode -eq 0) 'Build-input baseline failed'
        $input = Join-Path $fixture 'plugin-api/r8-compiler-api/build.gradle.kts'
        $candidate = Join-Path $fixture 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
        [IO.File]::AppendAllText($input, "`n// selftest build-input drift`n")
        [IO.File]::SetLastWriteTimeUtc($input, (Get-Item $candidate).LastWriteTimeUtc.AddMinutes(-1))
        $result = Invoke-Gate $fixture
        Assert-True ($result.ExitCode -ne 0) 'Build-input drift passed immutable distribution'
        Assert-True (-not [bool](Read-Report $result.ReportPath).passed) 'Build-input drift retained stale PASS'
    }
} finally {
    foreach ($fixture in $fixtures) {
        $full = [IO.Path]::GetFullPath($fixture)
        $safeName = [IO.Path]::GetFileName($full).StartsWith('autojs6-r8-contract-distribution-selftest-', [StringComparison]::Ordinal)
        if ($safeName -and (Test-IsWithin $full $temporaryBase) -and [IO.Directory]::Exists($full)) {
            Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

$total = $passed + $failed
Write-Output "SELFTEST: $passed/$total passed"
if ($total -ne $expectedTestCount) {
    Write-Output "FAIL: fixed test count drifted; expected $expectedTestCount"
    exit 1
}
if ($failed -ne 0) { exit 1 }
