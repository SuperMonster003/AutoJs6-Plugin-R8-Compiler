[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$expectedTestCount = 43
$sourceRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$temporaryRoot = [IO.Path]::Combine(
    [IO.Path]::GetTempPath(),
    ('autojs6-r8-g1-source-selftest-{0}' -f [guid]::NewGuid().ToString('N'))
)
$powershell = (Get-Process -Id $PID).Path
$results = New-Object System.Collections.Generic.List[object]
$linksToRemove = New-Object System.Collections.Generic.List[string]

function Assert-True {
    param([bool] $Condition, [string] $Message)
    if (-not $Condition) { throw $Message }
}

function Assert-ExactNameSet {
    param($Value, [string[]] $Expected, [string] $Label)
    Assert-True ($null -ne $Value) "$Label is missing."
    $actual = @($Value.psobject.Properties.Name)
    Assert-True (
        $actual.Count -eq $Expected.Count -and @(Compare-Object $actual $Expected -CaseSensitive).Count -eq 0
    ) "$Label is not exact."
}

function Copy-RelativeFile {
    param([string] $FixtureRoot, [string] $RelativePath)
    $source = Join-Path $sourceRoot $RelativePath
    if (-not [IO.File]::Exists($source)) { return }
    $target = Join-Path $FixtureRoot $RelativePath
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
    [IO.File]::Copy($source, $target, $true)
}

function Copy-RelativeTreeFiles {
    param([string] $FixtureRoot, [string] $RelativePath)
    $sourceDirectory = Join-Path $sourceRoot $RelativePath
    if (-not [IO.Directory]::Exists($sourceDirectory)) { return }
    foreach ($file in @(Get-ChildItem -LiteralPath $sourceDirectory -Recurse -File -Force)) {
        $relativeFile = [IO.Path]::GetRelativePath($sourceRoot, $file.FullName)
        if ($relativeFile -match '(^|[\\/])(?:\.gradle|\.kotlin)(?:[\\/]|$)') { continue }
        if ($relativeFile -match '^plugin-api[\\/][^\\/]+[\\/]build[\\/]') { continue }
        Copy-RelativeFile -FixtureRoot $FixtureRoot -RelativePath $relativeFile
    }
}

function New-Fixture {
    param([string] $Name)
    $fixture = Join-Path $temporaryRoot "$Name/repository"
    [IO.Directory]::CreateDirectory($fixture) | Out-Null
    foreach ($file in @(
        '.gitattributes', '.gitignore', 'LICENSE', 'README.md', 'ROADMAP.md', 'build.gradle.kts', 'settings.gradle.kts',
        'gradle.properties', 'gradle/libs.versions.toml', 'scripts/verify-g1-source-boundary.ps1'
    )) {
        Copy-RelativeFile -FixtureRoot $fixture -RelativePath $file
    }
    foreach ($tree in @('docs', 'plugin-api/protocol-wire-api', 'plugin-api/r8-compiler-api')) {
        Copy-RelativeTreeFiles -FixtureRoot $fixture -RelativePath $tree
    }
    return $fixture
}

function Get-ReportPath {
    param([string] $Fixture)
    return Join-Path $Fixture 'build/reports/r42-g1/source-boundary.json'
}

function Write-StalePass {
    param([string] $Path)
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    [IO.File]::WriteAllText($Path, "{`"passed`":true}`n", [Text.UTF8Encoding]::new($false))
}

function Invoke-Gate {
    param([string] $Fixture, [string] $RequestedOutput)
    $scriptPath = Join-Path $Fixture 'scripts/verify-g1-source-boundary.ps1'
    $arguments = @('-NoLogo', '-NoProfile', '-File', $scriptPath, '-RepositoryRoot', $Fixture)
    if (-not [string]::IsNullOrWhiteSpace($RequestedOutput)) { $arguments += @('-OutputPath', $RequestedOutput) }
    $console = @(& $powershell @arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $reportPath = Get-ReportPath -Fixture $Fixture
    $report = if ([IO.File]::Exists($reportPath)) {
        [IO.File]::ReadAllText($reportPath) | ConvertFrom-Json
    } else {
        $null
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Report = $report; Console = $console; ReportPath = $reportPath }
}

function Assert-ReportSafe {
    param($Invocation, [string] $Fixture, [bool] $ExpectedPassed)
    Assert-True ($null -ne $Invocation.Report) 'Gate did not persist a report.'
    Assert-ExactNameSet -Value $Invocation.Report -Expected @(
        'schemaVersion', 'evidenceBoundary', 'passed', 'repositoryRoot', 'checkCount',
        'checks', 'claims', 'reasons', 'summary'
    ) -Label 'Report fields'
    Assert-True ($Invocation.Report.passed.GetType() -eq [bool] -and $Invocation.Report.passed -eq $ExpectedPassed) 'Gate report pass state is incorrect.'
    Assert-True ($Invocation.Report.evidenceBoundary -ceq 'SOURCE_STATIC_ONLY') 'Evidence boundary changed.'
    Assert-True ($Invocation.Report.repositoryRoot -ceq '.') 'Repository root was persisted.'
    $expectedClaims = @(
        'providerImplemented', 'manifestDiscoverable', 'hostIntegrated', 'jvmVerified', 'aarVerified',
        'binderVerified', 'r8Executed', 'retraceExecuted', 'deviceVerified', 'published', 'pluginConsumed'
    )
    Assert-ExactNameSet -Value $Invocation.Report.claims -Expected $expectedClaims -Label 'Report claims'
    foreach ($claimName in $expectedClaims) {
        $claim = $Invocation.Report.claims.$claimName
        Assert-True ($claim.GetType() -eq [bool] -and -not $claim) "Gate promoted or mistyped claim $claimName."
    }
    $raw = [IO.File]::ReadAllText($Invocation.ReportPath)
    Assert-True (-not $raw.Contains($Fixture, [StringComparison]::OrdinalIgnoreCase)) 'Report leaked fixture path.'
    Assert-True ($raw -notmatch '(?i)[A-Z]:[\\/]') 'Report leaked an absolute Windows path.'
}

function Assert-Failure {
    param($Invocation, [string] $Fixture)
    Assert-True ($Invocation.ExitCode -ne 0) 'Mutation unexpectedly returned exit code zero.'
    Assert-ReportSafe -Invocation $Invocation -Fixture $Fixture -ExpectedPassed $false
}

function Save-Json {
    param([string] $Path, $Value)
    [IO.File]::WriteAllText($Path, (($Value | ConvertTo-Json -Depth 16) + "`n"), [Text.UTF8Encoding]::new($false))
}

function Invoke-TestCase {
    param([string] $Name, [scriptblock] $Body)
    try {
        & $Body
        $results.Add([pscustomobject][ordered]@{ name = $Name; passed = $true; reason = $null })
    } catch {
        $results.Add([pscustomobject][ordered]@{ name = $Name; passed = $false; reason = $_.Exception.Message })
    }
}

try {
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null

    Invoke-TestCase 'positive clean source boundary' {
        $fixture = New-Fixture 'positive'
        $run = Invoke-Gate -Fixture $fixture -RequestedOutput (Get-ReportPath $fixture)
        Assert-True ($run.ExitCode -eq 0) ("Positive fixture failed: {0}" -f (@($run.Report.reasons) -join '; '))
        Assert-ReportSafe -Invocation $run -Fixture $fixture -ExpectedPassed $true
        Assert-True ($run.Report.checkCount -eq 16) 'Positive check count drifted.'
    }

    Invoke-TestCase 'distribution evidence report coexists with source report' {
        $fixture = New-Fixture 'coexisting-evidence'
        $distributionReport = Join-Path $fixture 'build/reports/g1-contract-distribution.json'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($distributionReport)) | Out-Null
        [IO.File]::WriteAllText($distributionReport, "{`"passed`":true}`n", [Text.UTF8Encoding]::new($false))
        $run = Invoke-Gate -Fixture $fixture -RequestedOutput (Get-ReportPath $fixture)
        Assert-True ($run.ExitCode -eq 0) ("Coexisting evidence reports failed: {0}" -f (@($run.Report.reasons) -join '; '))
        Assert-ReportSafe -Invocation $run -Fixture $fixture -ExpectedPassed $true
        Assert-True ([IO.File]::Exists($distributionReport)) 'Distribution report was removed.'
    }

    Invoke-TestCase 'unrecognized build report fails closed' {
        $fixture = New-Fixture 'unrecognized-report'
        $unexpectedReport = Join-Path $fixture 'build/reports/unrecognized.json'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($unexpectedReport)) | Out-Null
        [IO.File]::WriteAllText($unexpectedReport, "{}`n", [Text.UTF8Encoding]::new($false))
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'exact mapping format data literal is accepted' {
        $fixture = New-Fixture 'mapping-format-literal'
        $contract = Join-Path $fixture 'plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/R8CompilerContract.kt'
        Assert-True ([IO.File]::ReadAllText($contract).Contains(
            'const val MAPPING_FORMAT_ID = "com.android.tools.r8.mapping"',
            [StringComparison]::Ordinal
        )) 'Mapping format identity fixture drifted.'
        $run = Invoke-Gate -Fixture $fixture -RequestedOutput (Get-ReportPath $fixture)
        Assert-True ($run.ExitCode -eq 0) ("Mapping format data literal was mistaken for an engine dependency: {0}" -f (@($run.Report.reasons) -join '; '))
        Assert-ReportSafe -Invocation $run -Fixture $fixture -ExpectedPassed $true
    }

    Invoke-TestCase 'R8 engine import fails closed' {
        $fixture = New-Fixture 'r8-engine-import'
        $source = Join-Path $fixture 'plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/EngineImport.kt'
        [IO.File]::WriteAllText($source, "package org.autojs.plugin.r8compiler.api`nimport com.android.tools.r8.R8`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'R8 engine dependency coordinate fails closed' {
        $fixture = New-Fixture 'r8-engine-dependency'
        $buildScript = Join-Path $fixture 'plugin-api/r8-compiler-api/build.gradle.kts'
        [IO.File]::AppendAllText($buildScript, "`ndependencies { implementation(`"com.android.tools:r8:8.13.17`") }`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'computed R8 engine dependency coordinate fails closed' {
        $fixture = New-Fixture 'computed-r8-engine-dependency'
        $buildScript = Join-Path $fixture 'plugin-api/r8-compiler-api/build.gradle.kts'
        [IO.File]::AppendAllText(
            $buildScript,
            "`ndependencies { implementation(listOf(`"com.android.tools`", `"r8`").joinToString(`":`") + `":8.13.17`") }`n"
        )
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'R8Command runner reference fails closed' {
        $fixture = New-Fixture 'r8-command-reference'
        $source = Join-Path $fixture 'plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/EngineRunner.kt'
        [IO.File]::WriteAllText($source, "package org.autojs.plugin.r8compiler.api`nval forbiddenRunner = R8Command::class`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'gitattributes semantics drift fails closed' {
        $fixture = New-Fixture 'gitattributes-drift'
        $path = Join-Path $fixture '.gitattributes'
        $text = [IO.File]::ReadAllText($path).Replace('*.aar -text', '*.aar text')
        [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'legal source package named build is accepted' {
        $fixture = New-Fixture 'legal-build-package'
        $packageFile = Join-Path $fixture 'plugin-api/r8-compiler-api/src/main/java/org/autojs/build/LegalPackage.kt'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($packageFile)) | Out-Null
        [IO.File]::WriteAllText($packageFile, "package org.autojs.build`n")
        $run = Invoke-Gate -Fixture $fixture -RequestedOutput (Get-ReportPath $fixture)
        Assert-True ($run.ExitCode -eq 0) ("Legal build package was misclassified: {0}" -f (@($run.Report.reasons) -join '; '))
        Assert-ReportSafe -Invocation $run -Fixture $fixture -ExpectedPassed $true
    }

    Invoke-TestCase 'extra Gradle include fails closed' {
        $fixture = New-Fixture 'extra-module'
        [IO.File]::AppendAllText((Join-Path $fixture 'settings.gradle.kts'), "`ninclude(`":extra`")`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'commented include cannot hide includeFlat' {
        $fixture = New-Fixture 'comment-include-flat'
        [IO.File]::WriteAllText((Join-Path $fixture 'settings.gradle.kts'), @'
rootProject.name = "fixture"
// include(":plugin-api:protocol-wire-api", ":plugin-api:r8-compiler-api")
includeFlat("extra")
'@)
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'conditional nested include fails closed' {
        $fixture = New-Fixture 'conditional-include'
        [IO.File]::WriteAllText((Join-Path $fixture 'settings.gradle.kts'), @'
rootProject.name = "fixture"
if (false) {
    include(
        ":plugin-api:protocol-wire-api",
        ":plugin-api:r8-compiler-api",
    )
}
'@)
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'included build fails closed' {
        $fixture = New-Fixture 'include-build'
        [IO.File]::AppendAllText((Join-Path $fixture 'settings.gradle.kts'), "`nincludeBuild(`"other`")`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'module case mutation fails closed' {
        $fixture = New-Fixture 'module-case'
        $path = Join-Path $fixture 'settings.gradle.kts'
        $text = [IO.File]::ReadAllText($path).Replace(':plugin-api:r8-compiler-api', ':plugin-api:R8-compiler-api')
        [IO.File]::WriteAllText($path, $text)
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'application plugin in included module fails closed' {
        $fixture = New-Fixture 'application-plugin'
        $path = Join-Path $fixture 'plugin-api/r8-compiler-api/build.gradle.kts'
        $text = [IO.File]::ReadAllText($path).Replace('com.android.library', 'com.android.application')
        [IO.File]::WriteAllText($path, $text)
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'comment decoy cannot hide concatenated application plugin' {
        $fixture = New-Fixture 'application-plugin-decoy'
        $path = Join-Path $fixture 'plugin-api/r8-compiler-api/build.gradle.kts'
        $text = [IO.File]::ReadAllText($path).Replace(
            'id("com.android.library")',
            "/* id(`"com.android.library`") */`n    id(`"com.android.`" + `"application`")"
        )
        [IO.File]::WriteAllText($path, $text)
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'root application plugin mutation fails closed' {
        $fixture = New-Fixture 'root-application-plugin'
        $path = Join-Path $fixture 'build.gradle.kts'
        $text = [IO.File]::ReadAllText($path).Replace('com.android.library', 'com.android.application')
        [IO.File]::WriteAllText($path, $text, [Text.UTF8Encoding]::new($false))
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'root dynamic application apply fails closed' {
        $fixture = New-Fixture 'root-dynamic-apply'
        $path = Join-Path $fixture 'build.gradle.kts'
        [IO.File]::AppendAllText($path, "apply(plugin = `"com.android.application`")`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'root Android block mutation fails closed' {
        $fixture = New-Fixture 'root-android-block'
        $path = Join-Path $fixture 'build.gradle.kts'
        [IO.File]::AppendAllText($path, "android { namespace = `"org.autojs.forbidden.application`" }`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'lowercase Android manifest name fails closed' {
        $fixture = New-Fixture 'lowercase-manifest'
        $manifest = Join-Path $fixture 'plugin-api/r8-compiler-api/src/main/androidmanifest.xml'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($manifest)) | Out-Null
        [IO.File]::WriteAllText($manifest, '<manifest />')
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'custom sourceSets manifest fails closed' {
        $fixture = New-Fixture 'custom-manifest'
        $path = Join-Path $fixture 'plugin-api/r8-compiler-api/build.gradle.kts'
        [IO.File]::AppendAllText($path, "`nandroid { sourceSets { getByName(`"main`") { manifest.srcFile(`"custom.xml`") } } }`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'root dot-gradle tree fails closed' {
        $fixture = New-Fixture 'dot-gradle'
        [IO.Directory]::CreateDirectory((Join-Path $fixture '.gradle/state')) | Out-Null
        [IO.File]::WriteAllText((Join-Path $fixture '.gradle/state/marker'), 'x')
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'dot-kotlin under deceptive dot-git suffix fails closed' {
        $fixture = New-Fixture 'deceptive-dot-git'
        [IO.Directory]::CreateDirectory((Join-Path $fixture 'foo.git/.KoTlIn/state')) | Out-Null
        [IO.File]::WriteAllText((Join-Path $fixture 'foo.git/.KoTlIn/state/marker'), 'x')
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'mixed-case module build output fails closed' {
        $fixture = New-Fixture 'mixed-build'
        [IO.Directory]::CreateDirectory((Join-Path $fixture 'plugin-api/r8-compiler-api/BuIlD/reports')) | Out-Null
        [IO.File]::WriteAllText((Join-Path $fixture 'plugin-api/r8-compiler-api/BuIlD/reports/marker'), 'x')
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'arbitrary Windows absolute path fails closed' {
        $fixture = New-Fixture 'private-windows'
        $pathText = 'Z' + [char] 58 + [char] 92 + 'workspace' + [char] 92 + 'secret'
        [IO.File]::WriteAllText((Join-Path $fixture 'scripts/leak.ps1'), "`$leak = '$pathText'`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'UNC absolute path fails closed' {
        $fixture = New-Fixture 'private-unc'
        $separator = [string] [char] 92
        $pathText = ($separator * 2) + 'server' + $separator + 'share' + $separator + 'secret'
        [IO.File]::WriteAllText((Join-Path $fixture 'plugin-api/r8-compiler-api/private.pro'), $pathText + "`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'Unix absolute path fails closed' {
        $fixture = New-Fixture 'private-unix'
        $pathText = [string] [char] 47 + 'custom/private/secret'
        [IO.File]::WriteAllText((Join-Path $fixture 'plugin-api/r8-compiler-api/private.pro'), $pathText + "`n")
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'identity claims must be exact' {
        $fixture = New-Fixture 'identity-extra-claim'
        $path = Join-Path $fixture 'docs/identity-reservation.json'
        $identity = [IO.File]::ReadAllText($path) | ConvertFrom-Json
        $identity.claims | Add-Member -NotePropertyName extraClaim -NotePropertyValue $false
        Save-Json $path $identity
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'identity claim name case must be exact' {
        $fixture = New-Fixture 'identity-claim-case'
        $path = Join-Path $fixture 'docs/identity-reservation.json'
        $identity = [IO.File]::ReadAllText($path) | ConvertFrom-Json
        $identity.claims.psobject.Properties.Remove('providerImplemented')
        $identity.claims | Add-Member -NotePropertyName ProviderImplemented -NotePropertyValue $false
        Save-Json $path $identity
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'false-like identity claim type fails closed' {
        $fixture = New-Fixture 'identity-false-string'
        $path = Join-Path $fixture 'docs/identity-reservation.json'
        $identity = [IO.File]::ReadAllText($path) | ConvertFrom-Json
        $identity.claims.providerImplemented = 'false'
        Save-Json $path $identity
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'retrace execution identity claim remains false' {
        $fixture = New-Fixture 'identity-retrace-true'
        $path = Join-Path $fixture 'docs/identity-reservation.json'
        $identity = [IO.File]::ReadAllText($path) | ConvertFrom-Json
        $identity.claims.retraceExecuted = $true
        Save-Json $path $identity
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'duplicate JSON property fails closed' {
        $fixture = New-Fixture 'identity-duplicate'
        $path = Join-Path $fixture 'docs/identity-reservation.json'
        $text = [IO.File]::ReadAllText($path).Replace('"status": "RESERVED_NOT_IMPLEMENTED",', '"status": "RESERVED_NOT_IMPLEMENTED", "status": "RESERVED_NOT_IMPLEMENTED",')
        [IO.File]::WriteAllText($path, $text)
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'provenance JSON type must be exact' {
        $fixture = New-Fixture 'provenance-type'
        $path = Join-Path $fixture 'docs/protocol-wire-provenance.json'
        $provenance = [IO.File]::ReadAllText($path) | ConvertFrom-Json
        $provenance.schemaVersion = '1'
        Save-Json $path $provenance
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'provenance field case must be exact' {
        $fixture = New-Fixture 'provenance-case'
        $path = Join-Path $fixture 'docs/protocol-wire-provenance.json'
        $provenance = [IO.File]::ReadAllText($path) | ConvertFrom-Json
        $provenance.psobject.Properties.Remove('localBuildScript')
        $provenance | Add-Member -NotePropertyName LocalBuildScript -NotePropertyValue $true
        Save-Json $path $provenance
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'malformed input atomically replaces stale PASS' {
        $fixture = New-Fixture 'malformed-stale'
        $first = Invoke-Gate $fixture (Get-ReportPath $fixture)
        Assert-True ($first.ExitCode -eq 0 -and [bool] $first.Report.passed) 'Precondition PASS was not created.'
        [IO.File]::WriteAllText((Join-Path $fixture 'docs/identity-reservation.json'), '{broken')
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
    }

    Invoke-TestCase 'canonical report leaf reparse is rejected atomically' {
        $fixture = New-Fixture 'leaf-link'
        $report = Get-ReportPath $fixture
        $target = Join-Path (Split-Path -Parent $fixture) 'leaf-target'
        $targetMarker = Join-Path $target 'stale-pass.json'
        Write-StalePass $targetMarker
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report)) | Out-Null
        New-Item -ItemType Junction -Path $report -Target $target | Out-Null
        $linksToRemove.Add($report)
        Assert-Failure -Invocation (Invoke-Gate $fixture $report) -Fixture $fixture
        Assert-True (([IO.File]::ReadAllText($targetMarker) | ConvertFrom-Json).passed -eq $true) 'Leaf target was overwritten.'
    }

    Invoke-TestCase 'canonical report ancestor junction replaces stale PASS' {
        $fixture = New-Fixture 'ancestor-link'
        $externalBuild = Join-Path (Split-Path -Parent $fixture) 'external-build'
        $targetReport = Join-Path $externalBuild 'reports/r42-g1/source-boundary.json'
        Write-StalePass $targetReport
        $junction = Join-Path $fixture 'build'
        New-Item -ItemType Junction -Path $junction -Target $externalBuild | Out-Null
        $linksToRemove.Add($junction)
        Assert-Failure -Invocation (Invoke-Gate $fixture (Get-ReportPath $fixture)) -Fixture $fixture
        Assert-True (-not ([IO.File]::ReadAllText($targetReport) | ConvertFrom-Json).passed) 'Ancestor target retained stale PASS.'
    }

    Invoke-TestCase 'dangling canonical report link is rejected and replaced' {
        $fixture = New-Fixture 'dangling-link'
        $report = Get-ReportPath $fixture
        $target = Join-Path (Split-Path -Parent $fixture) 'missing-target'
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report)) | Out-Null
        [IO.Directory]::CreateDirectory($target) | Out-Null
        New-Item -ItemType Junction -Path $report -Target $target | Out-Null
        $linksToRemove.Add($report)
        [IO.Directory]::Delete($target)
        Assert-Failure -Invocation (Invoke-Gate $fixture $report) -Fixture $fixture
        Assert-True (-not [IO.Directory]::Exists($target)) 'Dangling link target was created.'
    }

    Invoke-TestCase 'fixed report hard link to source is rejected safely' {
        $fixture = New-Fixture 'hard-link'
        $readme = Join-Path $fixture 'README.md'
        $before = (Get-FileHash -LiteralPath $readme -Algorithm SHA256).Hash
        $report = Get-ReportPath $fixture
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report)) | Out-Null
        New-Item -ItemType HardLink -Path $report -Target $readme | Out-Null
        Assert-Failure -Invocation (Invoke-Gate $fixture $report) -Fixture $fixture
        Assert-True ($before -ceq (Get-FileHash -LiteralPath $readme -Algorithm SHA256).Hash) 'Hard link changed protected source.'
    }

    Invoke-TestCase 'same-content report alias is rejected safely' {
        $fixture = New-Fixture 'content-alias'
        $readme = Join-Path $fixture 'README.md'
        $before = (Get-FileHash -LiteralPath $readme -Algorithm SHA256).Hash
        $report = Get-ReportPath $fixture
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($report)) | Out-Null
        [IO.File]::Copy($readme, $report)
        Assert-Failure -Invocation (Invoke-Gate $fixture $report) -Fixture $fixture
        Assert-True ($before -ceq (Get-FileHash -LiteralPath $readme -Algorithm SHA256).Hash) 'Content alias changed protected source.'
    }

    Invoke-TestCase 'existing source cannot be selected as output' {
        $fixture = New-Fixture 'existing-source-output'
        $source = Join-Path $fixture 'plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/R8CompilerContract.kt'
        $before = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash
        Assert-Failure -Invocation (Invoke-Gate $fixture $source) -Fixture $fixture
        Assert-True ($before -ceq (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash) 'Existing source was overwritten.'
    }

    Invoke-TestCase 'future source cannot be selected as output' {
        $fixture = New-Fixture 'future-source-output'
        $future = Join-Path $fixture 'plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/FutureSource.kt'
        Assert-Failure -Invocation (Invoke-Gate $fixture $future) -Fixture $fixture
        Assert-True (-not [IO.File]::Exists($future)) 'Future source was created.'
    }

    Assert-True ($results.Count -eq $expectedTestCount) 'Self-test count drifted.'
    $failed = @($results | Where-Object { -not $_.passed })
    $summary = [pscustomobject][ordered]@{
        schemaVersion = 'autojs6.r8.g1.source-boundary-selftest/v1'
        passed = $failed.Count -eq 0
        expectedTests = $expectedTestCount
        passedTests = $results.Count - $failed.Count
        failedTests = $failed.Count
        tests = $results
    }
    $summary | ConvertTo-Json -Depth 8
    if ($failed.Count -ne 0) { exit 1 }
    exit 0
} finally {
    foreach ($linkPath in $linksToRemove) {
        try {
            $item = Get-Item -LiteralPath $linkPath -Force -ErrorAction SilentlyContinue
            if ($null -ne $item -and ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                if ($item.PSIsContainer) { [IO.Directory]::Delete($linkPath) } else { [IO.File]::Delete($linkPath) }
            }
        } catch { }
    }
    $temporaryFull = [IO.Path]::GetFullPath($temporaryRoot)
    $systemTemporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (
        $temporaryFull.StartsWith($systemTemporary, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($temporaryFull).StartsWith('autojs6-r8-g1-source-selftest-', [StringComparison]::Ordinal)
    ) {
        Remove-Item -LiteralPath $temporaryFull -Recurse -Force -ErrorAction SilentlyContinue
    }
}
