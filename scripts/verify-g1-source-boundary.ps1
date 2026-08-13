[CmdletBinding()]
param(
    [string] $RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string] $OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ReportRelativePath = 'build/reports/r42-g1/source-boundary.json'
$script:PathComparison = if ([OperatingSystem]::IsWindows()) {
    [StringComparison]::OrdinalIgnoreCase
} else {
    [StringComparison]::Ordinal
}

function Resolve-CanonicalPathInfo {
    param([Parameter(Mandatory)] [string] $Path)

    $pathToResolve = [IO.Path]::GetFullPath($Path)
    $sawReparse = $false
    $sawAncestorReparse = $false
    $sawLeafReparse = $false
    for ($pass = 0; $pass -lt 64; $pass++) {
        $root = [IO.Path]::GetPathRoot($pathToResolve)
        if ([string]::IsNullOrWhiteSpace($root)) { throw 'Path has no filesystem root.' }
        [char[]] $separators = @([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
        $segments = @($pathToResolve.Substring($root.Length).Split($separators, [StringSplitOptions]::RemoveEmptyEntries))
        $cursor = $root
        $resolvedOne = $false
        for ($index = 0; $index -lt $segments.Count; $index++) {
            $candidate = [IO.Path]::Combine($cursor, $segments[$index])
            $item = Get-Item -LiteralPath $candidate -Force -ErrorAction SilentlyContinue
            if ($null -ne $item) {
                if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    $sawReparse = $true
                    if ($index -eq $segments.Count - 1) { $sawLeafReparse = $true } else { $sawAncestorReparse = $true }
                    $target = $item.ResolveLinkTarget($true)
                    if ($null -eq $target) {
                        return [pscustomobject][ordered]@{
                            Canonical = $pathToResolve
                            SawReparse = $true
                            SawAncestorReparse = $sawAncestorReparse
                            SawLeafReparse = $sawLeafReparse
                            UnresolvedReparse = $true
                        }
                    }
                    $remaining = if ($index + 1 -lt $segments.Count) {
                        [IO.Path]::Combine([string[]] $segments[($index + 1)..($segments.Count - 1)])
                    } else {
                        ''
                    }
                    $pathToResolve = if ([string]::IsNullOrEmpty($remaining)) {
                        [IO.Path]::GetFullPath($target.FullName)
                    } else {
                        [IO.Path]::GetFullPath([IO.Path]::Combine($target.FullName, $remaining))
                    }
                    $resolvedOne = $true
                    break
                }
            }
            $cursor = $candidate
        }
        if (-not $resolvedOne) {
            return [pscustomobject][ordered]@{
                Canonical = [IO.Path]::GetFullPath($cursor)
                SawReparse = $sawReparse
                SawAncestorReparse = $sawAncestorReparse
                SawLeafReparse = $sawLeafReparse
                UnresolvedReparse = $false
            }
        }
    }
    throw 'Path reparse-point resolution exceeded its limit.'
}

function Write-AtomicJson {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] $Value
    )

    $fullPath = [IO.Path]::GetFullPath($Path)
    $parent = [IO.Path]::GetDirectoryName($fullPath)
    if ([string]::IsNullOrWhiteSpace($parent)) { throw 'Report path has no parent directory.' }
    $parentInfo = Resolve-CanonicalPathInfo -Path $parent
    if ($parentInfo.SawReparse) { throw 'Report parent contains a reparse point.' }
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $parentInfo = Resolve-CanonicalPathInfo -Path $parent
    if ($parentInfo.SawReparse) { throw 'Report parent changed to a reparse point.' }
    $temporary = [IO.Path]::Combine(
        $parent,
        ('.{0}.{1}.tmp' -f [IO.Path]::GetFileName($fullPath), [guid]::NewGuid().ToString('N'))
    )
    try {
        $json = ($Value | ConvertTo-Json -Depth 16) + "`n"
        [IO.File]::WriteAllText($temporary, $json, [Text.UTF8Encoding]::new($false))
        $leaf = Get-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue
        if (
            $null -ne $leaf -and
            $leaf.PSIsContainer -and
            ($leaf.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
        ) {
            $quarantine = [IO.Path]::Combine(
                $parent,
                ('.{0}.{1}.reparse' -f [IO.Path]::GetFileName($fullPath), [guid]::NewGuid().ToString('N'))
            )
            [IO.Directory]::Move($fullPath, $quarantine)
            try {
                [IO.File]::Move($temporary, $fullPath, $false)
                [IO.Directory]::Delete($quarantine)
            } catch {
                if (-not (Get-Item -LiteralPath $fullPath -Force -ErrorAction SilentlyContinue)) {
                    [IO.Directory]::Move($quarantine, $fullPath)
                }
                throw
            }
        } else {
            [IO.File]::Move($temporary, $fullPath, $true)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function ConvertTo-SafeReason {
    param(
        [Parameter(Mandatory)] [AllowEmptyString()] [string] $Message,
        [string[]] $PrivateRoot = @()
    )

    $safe = $Message
    foreach ($privatePath in @($PrivateRoot | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object Length -Descending -Unique)) {
        $safe = [regex]::Replace(
            $safe,
            [regex]::Escape([IO.Path]::GetFullPath($privatePath)),
            '<private-path>',
            [Text.RegularExpressions.RegexOptions]::IgnoreCase
        )
    }
    $drivePrefix = '(?i)[A-Z]:' + '[\\/]'
    $uncPrefix = '(?<!\\)' + ('\' * 2) + '(?!\\)'
    $unixPrefix = '(?<![:/A-Za-z0-9_.+-])' + [char] 47
    $safe = [regex]::Replace($safe, $drivePrefix + '[^\s"'']+', '<private-path>')
    $safe = [regex]::Replace($safe, $uncPrefix + '[^\s"'']+', '<private-path>')
    $safe = [regex]::Replace($safe, $unixPrefix + '[^\s"'']+', '<private-path>')
    return $safe
}

function Get-NormalizedRelativePath {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Path
    )

    return [IO.Path]::GetRelativePath($Root, $Path).Replace('\', '/')
}

function Test-IsConventionalBuildDirectory {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Directory
    )

    $full = [IO.Path]::GetFullPath($Directory)
    if (-not [string]::Equals([IO.Path]::GetFileName($full), 'build', [StringComparison]::OrdinalIgnoreCase)) { return $false }
    $rootBuild = [IO.Path]::GetFullPath((Join-Path $Root 'build'))
    if ([string]::Equals($full, $rootBuild, $script:PathComparison)) { return $true }
    $parent = [IO.Path]::GetDirectoryName($full)
    $markerNames = @('build.gradle', 'build.gradle.kts', 'settings.gradle', 'settings.gradle.kts')
    return @(
        Get-ChildItem -LiteralPath $parent -File -Force | Where-Object {
            $name = $_.Name
            @($markerNames | Where-Object { [string]::Equals($_, $name, [StringComparison]::OrdinalIgnoreCase) }).Count -ne 0
        }
    ).Count -ne 0
}

function Test-IsInsideGeneratedTree {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Path
    )

    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $cursor = if ([IO.Directory]::Exists($Path)) { [IO.Path]::GetFullPath($Path) } else { [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Path)) }
    while (-not [string]::IsNullOrWhiteSpace($cursor) -and $cursor.StartsWith($rootFull, $script:PathComparison)) {
        $name = [IO.Path]::GetFileName($cursor)
        $relativeCursor = Get-NormalizedRelativePath -Root $rootFull -Path $cursor
        if ($relativeCursor -ceq '.git' -or $relativeCursor.StartsWith('.git/', [StringComparison]::Ordinal)) { return $true }
        if (
            [string]::Equals($name, '.gradle', [StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($name, '.kotlin', [StringComparison]::OrdinalIgnoreCase)
        ) { return $true }
        if ([string]::Equals($name, 'build', [StringComparison]::OrdinalIgnoreCase) -and (Test-IsConventionalBuildDirectory -Root $rootFull -Directory $cursor)) { return $true }
        if ([string]::Equals($cursor.TrimEnd([IO.Path]::DirectorySeparatorChar), $rootFull, $script:PathComparison)) { break }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    return $false
}

function Get-ProtectedSourceFiles {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $ReportPath
    )

    return @(Get-ChildItem -LiteralPath $Root -Recurse -File -Force | Where-Object {
        -not [string]::Equals([IO.Path]::GetFullPath($_.FullName), [IO.Path]::GetFullPath($ReportPath), $script:PathComparison) -and
        -not (Test-IsInsideGeneratedTree -Root $Root -Path $_.FullName)
    })
}

function Test-OutputAliasesProtectedSource {
    param(
        [Parameter(Mandatory)] [string] $Output,
        [Parameter(Mandatory)] [System.IO.FileInfo[]] $ProtectedFiles
    )

    if (-not [IO.File]::Exists($Output)) { return $false }
    $outputInfo = Resolve-CanonicalPathInfo -Path $Output
    $outputItem = Get-Item -LiteralPath $Output -Force
    $outputHash = $null
    foreach ($source in $ProtectedFiles) {
        $sourceInfo = Resolve-CanonicalPathInfo -Path $source.FullName
        if ([string]::Equals($outputInfo.Canonical, $sourceInfo.Canonical, $script:PathComparison)) { return $true }
        if ($outputItem.Length -eq $source.Length) {
            if ($null -eq $outputHash) { $outputHash = (Get-FileHash -LiteralPath $Output -Algorithm SHA256).Hash }
            if ($outputHash -ceq (Get-FileHash -LiteralPath $source.FullName -Algorithm SHA256).Hash) { return $true }
        }
    }
    return $false
}

function Assert-ExactPropertySet {
    param(
        [Parameter(Mandatory)] $Value,
        [Parameter(Mandatory)] [string[]] $Expected,
        [Parameter(Mandatory)] [string] $Label
    )

    if ($null -eq $Value) { throw "$Label is missing." }
    $actual = @($Value.psobject.Properties.Name)
    if ($actual.Count -ne $Expected.Count -or @(Compare-Object $actual $Expected -CaseSensitive).Count -ne 0) {
        throw "$Label property set is not exact."
    }
}

function Assert-NoDuplicateJsonProperties {
    param(
        [Parameter(Mandatory)] [Text.Json.JsonElement] $Element,
        [Parameter(Mandatory)] [string] $Label
    )

    if ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Object) {
        $names = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($property in $Element.EnumerateObject()) {
            if (-not $names.Add($property.Name)) { throw "$Label contains a duplicate JSON property." }
            Assert-NoDuplicateJsonProperties -Element $property.Value -Label $Label
        }
    } elseif ($Element.ValueKind -eq [Text.Json.JsonValueKind]::Array) {
        foreach ($entry in $Element.EnumerateArray()) {
            Assert-NoDuplicateJsonProperties -Element $entry -Label $Label
        }
    }
}

function Read-StrictJsonObject {
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    $text = [IO.File]::ReadAllText($Path)
    $document = [Text.Json.JsonDocument]::Parse($text)
    try {
        if ($document.RootElement.ValueKind -ne [Text.Json.JsonValueKind]::Object) { throw "$Label must be a JSON object." }
        Assert-NoDuplicateJsonProperties -Element $document.RootElement -Label $Label
    } finally {
        $document.Dispose()
    }
    return $text | ConvertFrom-Json
}

function Test-JsonString {
    param($Value)
    return $null -ne $Value -and $Value.GetType() -eq [string]
}

function Test-JsonBoolean {
    param($Value)
    return $null -ne $Value -and $Value.GetType() -eq [bool]
}

function Test-JsonInteger {
    param($Value)
    return $null -ne $Value -and $Value.GetType() -eq [long]
}

function Remove-KotlinComments {
    param([Parameter(Mandatory)] [string] $Text)

    $output = [Text.StringBuilder]::new($Text.Length)
    $index = 0
    $state = 'code'
    $blockDepth = 0
    while ($index -lt $Text.Length) {
        $character = $Text[$index]
        $next = if ($index + 1 -lt $Text.Length) { $Text[$index + 1] } else { [char] 0 }
        if ($state -eq 'code') {
            if ($character -eq '/' -and $next -eq '/') {
                [void] $output.Append('  ')
                $index += 2
                $state = 'line-comment'
                continue
            }
            if ($character -eq '/' -and $next -eq '*') {
                [void] $output.Append('  ')
                $index += 2
                $blockDepth = 1
                $state = 'block-comment'
                continue
            }
            [void] $output.Append($character)
            if ($character -eq '"') { $state = 'string' }
            elseif ($character -eq "'") { $state = 'character' }
            $index++
            continue
        }
        if ($state -eq 'line-comment') {
            if ($character -eq "`r" -or $character -eq "`n") {
                [void] $output.Append($character)
                $state = 'code'
            } else {
                [void] $output.Append(' ')
            }
            $index++
            continue
        }
        if ($state -eq 'block-comment') {
            if ($character -eq '/' -and $next -eq '*') {
                [void] $output.Append('  ')
                $blockDepth++
                $index += 2
                continue
            }
            if ($character -eq '*' -and $next -eq '/') {
                [void] $output.Append('  ')
                $blockDepth--
                $index += 2
                if ($blockDepth -eq 0) { $state = 'code' }
                continue
            }
            [void] $output.Append($(if ($character -eq "`r" -or $character -eq "`n") { $character } else { ' ' }))
            $index++
            continue
        }
        [void] $output.Append($character)
        if ($character -eq '\\') {
            if ($index + 1 -lt $Text.Length) {
                [void] $output.Append($Text[$index + 1])
                $index += 2
            } else {
                $index++
            }
            continue
        }
        if (($state -eq 'string' -and $character -eq '"') -or ($state -eq 'character' -and $character -eq "'")) {
            $state = 'code'
        }
        $index++
    }
    if ($state -eq 'block-comment') { throw 'settings.gradle.kts contains an unterminated block comment.' }
    return $output.ToString()
}

function Get-GradleIncludeCalls {
    param([Parameter(Mandatory)] [string] $Text)

    $calls = New-Object System.Collections.Generic.List[object]
    $index = 0
    $braceDepth = 0
    while ($index -lt $Text.Length) {
        $character = $Text[$index]
        if ($character -eq '"' -or $character -eq "'") {
            $quote = $character
            $index++
            while ($index -lt $Text.Length) {
                if ($Text[$index] -eq '\\') { $index += 2; continue }
                if ($Text[$index] -eq $quote) { $index++; break }
                $index++
            }
            continue
        }
        if ($character -eq '{') { $braceDepth++; $index++; continue }
        if ($character -eq '}') {
            $braceDepth--
            if ($braceDepth -lt 0) { throw 'settings.gradle.kts contains an unmatched closing brace.' }
            $index++
            continue
        }
        if ([char]::IsLetter($character) -or $character -eq '_') {
            $start = $index
            $callBraceDepth = $braceDepth
            $lineStart = $Text.LastIndexOf("`n", [Math]::Max(0, $start - 1)) + 1
            $linePrefixIsWhitespace = [string]::IsNullOrWhiteSpace($Text.Substring($lineStart, $start - $lineStart))
            $index++
            while ($index -lt $Text.Length -and ([char]::IsLetterOrDigit($Text[$index]) -or $Text[$index] -eq '_')) { $index++ }
            $name = $Text.Substring($start, $index - $start)
            if ($name -cin @('include', 'includeBuild', 'includeFlat')) {
                while ($index -lt $Text.Length -and [char]::IsWhiteSpace($Text[$index])) { $index++ }
                if ($index -ge $Text.Length -or $Text[$index] -ne '(') {
                    $calls.Add([pscustomobject]@{
                        Name = $name; Arguments = ''; Malformed = $true
                        BraceDepth = $callBraceDepth; LineIsolated = $false
                    })
                    continue
                }
                $argumentStart = ++$index
                $depth = 1
                $quote = [char] 0
                while ($index -lt $Text.Length -and $depth -gt 0) {
                    $current = $Text[$index]
                    if ($quote -ne [char] 0) {
                        if ($current -eq '\\') { $index += 2; continue }
                        if ($current -eq $quote) { $quote = [char] 0 }
                    } elseif ($current -eq '"' -or $current -eq "'") {
                        $quote = $current
                    } elseif ($current -eq '(') {
                        $depth++
                    } elseif ($current -eq ')') {
                        $depth--
                    }
                    $index++
                }
                if ($depth -ne 0) {
                    $calls.Add([pscustomobject]@{
                        Name = $name; Arguments = ''; Malformed = $true
                        BraceDepth = $callBraceDepth; LineIsolated = $false
                    })
                } else {
                    $lineEnd = $Text.IndexOf("`n", $index)
                    if ($lineEnd -lt 0) { $lineEnd = $Text.Length }
                    $lineSuffixIsWhitespace = [string]::IsNullOrWhiteSpace($Text.Substring($index, $lineEnd - $index))
                    $calls.Add([pscustomobject]@{
                        Name = $name
                        Arguments = $Text.Substring($argumentStart, ($index - 1) - $argumentStart)
                        Malformed = $false
                        BraceDepth = $callBraceDepth
                        LineIsolated = $linePrefixIsWhitespace -and $lineSuffixIsWhitespace
                    })
                }
            }
            continue
        }
        $index++
    }
    if ($braceDepth -ne 0) { throw 'settings.gradle.kts contains unmatched braces.' }
    return @($calls | ForEach-Object { $_ })
}

function Test-CleanGeneratedTree {
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $ReportPath
    )

    $rootFull = [IO.Path]::GetFullPath($Root)
    $reportFull = [IO.Path]::GetFullPath($ReportPath)
    $allowedRootBuildItems = @(
        [IO.Path]::GetFullPath((Join-Path $rootFull 'build')),
        [IO.Path]::GetFullPath((Join-Path $rootFull 'build/reports')),
        [IO.Path]::GetFullPath((Join-Path $rootFull 'build/reports/r42-g1')),
        [IO.Path]::GetFullPath((Join-Path $rootFull 'build/reports/g1-contract-distribution.json')),
        $reportFull
    )

    $forbidden = New-Object System.Collections.Generic.List[string]
    foreach ($directory in @(Get-ChildItem -LiteralPath $rootFull -Recurse -Directory -Force)) {
        $relative = Get-NormalizedRelativePath -Root $rootFull -Path $directory.FullName
        if ($relative -ceq '.git' -or $relative.StartsWith('.git/', [StringComparison]::Ordinal)) { continue }
        if (
            [string]::Equals($directory.Name, '.gradle', [StringComparison]::OrdinalIgnoreCase) -or
            [string]::Equals($directory.Name, '.kotlin', [StringComparison]::OrdinalIgnoreCase)
        ) {
            $forbidden.Add($directory.FullName)
            continue
        }
        if ([string]::Equals($directory.Name, 'build', [StringComparison]::OrdinalIgnoreCase) -and (Test-IsConventionalBuildDirectory -Root $rootFull -Directory $directory.FullName)) {
            $rootBuild = [IO.Path]::GetFullPath((Join-Path $rootFull 'build'))
            if (-not [string]::Equals($directory.FullName, $rootBuild, $script:PathComparison)) {
                $forbidden.Add($directory.FullName)
            }
        }
    }

    $rootBuildPath = Join-Path $rootFull 'build'
    if ([IO.Directory]::Exists($rootBuildPath)) {
        foreach ($item in @(Get-ChildItem -LiteralPath $rootBuildPath -Recurse -Force)) {
            $itemFull = [IO.Path]::GetFullPath($item.FullName)
            if (-not @($allowedRootBuildItems | Where-Object { [string]::Equals($_, $itemFull, $script:PathComparison) }).Count) {
                $forbidden.Add($itemFull)
            }
        }
    }
    return $forbidden.Count -eq 0
}

function New-SourceReport {
    param(
        [Parameter(Mandatory)] [bool] $Passed,
        [Parameter(Mandatory)] $Checks,
        [string[]] $Reasons = @()
    )

    return [pscustomobject][ordered]@{
        schemaVersion = 'autojs6.r8.g1.source-boundary/v1'
        evidenceBoundary = 'SOURCE_STATIC_ONLY'
        passed = $Passed
        repositoryRoot = '.'
        checkCount = $Checks.Count
        checks = $Checks
        claims = [pscustomobject][ordered]@{
            providerImplemented = $false
            manifestDiscoverable = $false
            hostIntegrated = $false
            jvmVerified = $false
            aarVerified = $false
            binderVerified = $false
            r8Executed = $false
            retraceExecuted = $false
            deviceVerified = $false
            published = $false
            pluginConsumed = $false
        }
        reasons = @($Reasons)
        summary = if ($Passed) {
            'R4.2-G1 independent R8 contract source boundary passed.'
        } else {
            'R4.2-G1 independent R8 contract source boundary failed closed.'
        }
    }
}

$checks = [ordered]@{}
$repositoryRootFull = $null
$safeReportPath = $null
$failureReportPath = $null
$safeReportEstablished = $false

try {
    $rootInfo = Resolve-CanonicalPathInfo -Path $RepositoryRoot
    $repositoryRootFull = $rootInfo.Canonical
    if (-not [IO.Directory]::Exists($repositoryRootFull)) { throw 'Repository root is missing.' }

    $safeReportPath = [IO.Path]::GetFullPath((Join-Path $repositoryRootFull $script:ReportRelativePath))
    $failureReportPath = $safeReportPath
    $safeReportEstablished = $true
    $fixedReportInfo = Resolve-CanonicalPathInfo -Path $safeReportPath
    if ($fixedReportInfo.SawAncestorReparse -and -not $fixedReportInfo.UnresolvedReparse) {
        $failureReportPath = $fixedReportInfo.Canonical
    }

    $candidate = if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $safeReportPath
    } elseif ([IO.Path]::IsPathRooted($OutputPath)) {
        [IO.Path]::GetFullPath($OutputPath)
    } else {
        [IO.Path]::GetFullPath((Join-Path $repositoryRootFull $OutputPath))
    }
    $candidateInfo = Resolve-CanonicalPathInfo -Path $candidate

    $protectedFiles = Get-ProtectedSourceFiles -Root $repositoryRootFull -ReportPath $safeReportPath
    if (Test-OutputAliasesProtectedSource -Output $safeReportPath -ProtectedFiles $protectedFiles) {
        throw 'Existing report aliases protected source path or content.'
    }
    if (
        -not [string]::Equals([IO.Path]::GetFullPath($candidate), $safeReportPath, $script:PathComparison) -or
        $candidateInfo.SawReparse
    ) {
        throw 'OutputPath must be the canonical fixed source-boundary report path and contain no links.'
    }
    if ([IO.Directory]::Exists($candidate)) { throw 'OutputPath must be a file path.' }

    $settingsPath = Join-Path $repositoryRootFull 'settings.gradle.kts'
    $settings = [IO.File]::ReadAllText($settingsPath)
    $settingsWithoutComments = Remove-KotlinComments -Text $settings
    $gradleIncludeCalls = @(Get-GradleIncludeCalls -Text $settingsWithoutComments)
    $includeCalls = @($gradleIncludeCalls | Where-Object { $_.Name -ceq 'include' })
    $includeBuildCalls = @($gradleIncludeCalls | Where-Object { $_.Name -ceq 'includeBuild' })
    $includeFlatCalls = @($gradleIncludeCalls | Where-Object { $_.Name -ceq 'includeFlat' })
    $expectedModules = @(':plugin-api:protocol-wire-api', ':plugin-api:r8-compiler-api')
    $declaredModules = @()
    if ($includeCalls.Count -eq 1 -and -not $includeCalls[0].Malformed) {
        $argumentMatch = [regex]::Match(
            $includeCalls[0].Arguments,
            '^\s*"(?<module>[^"\\\r\n]+)"\s*,\s*"(?<module>[^"\\\r\n]+)"\s*,?\s*$',
            [Text.RegularExpressions.RegexOptions]::CultureInvariant
        )
        if ($argumentMatch.Success) { $declaredModules = @($argumentMatch.Groups['module'].Captures | ForEach-Object Value) }
    }
    $checks.exactModules =
        (Get-FileHash -LiteralPath $settingsPath -Algorithm SHA256).Hash -ceq 'DD5C762BAE4686504949A805C92FA8DD9AC50E6071C170A3C848C846384F0445' -and
        $gradleIncludeCalls.Count -eq 1 -and
        $includeCalls.Count -eq 1 -and
        $includeCalls[0].BraceDepth -eq 0 -and
        $includeCalls[0].LineIsolated -and
        $includeBuildCalls.Count -eq 0 -and
        $includeFlatCalls.Count -eq 0 -and
        $declaredModules.Count -eq $expectedModules.Count -and
        @(Compare-Object $declaredModules $expectedModules -CaseSensitive).Count -eq 0

    $moduleBuildPaths = @(
        Join-Path $repositoryRootFull 'plugin-api/protocol-wire-api/build.gradle.kts'
        Join-Path $repositoryRootFull 'plugin-api/r8-compiler-api/build.gradle.kts'
    )
    $moduleBuildTexts = @($moduleBuildPaths | ForEach-Object {
        Remove-KotlinComments -Text ([IO.File]::ReadAllText($_))
    })
    $libraryPluginPreamblePattern = '(?ms)\A\s*plugins\s*\{\s*id\s*\(\s*"com\.android\.library"\s*\)\s*\}'
    $pluginsBlockPattern = '(?m)(?<![A-Za-z0-9_])plugins\s*\{'
    $pluginIdCallPattern = '(?m)(?<![A-Za-z0-9_])id\s*\('
    $applicationPluginPattern = '(?i)(?:com\.android\.application|android\.application)'
    $manifestOverridePattern = '(?i)(?:\bsourceSets\b|\bmanifest\s*\.(?:srcFile|srcFiles|setSrcFile)\b|\bmanifest\.srcFile\b)'
    $moduleLibraryBoundaries = @($moduleBuildTexts | ForEach-Object {
        $normalizedConcatenatedStrings = [regex]::Replace($_, '"\s*\+\s*"', '')
        [regex]::IsMatch($_, $libraryPluginPreamblePattern, [Text.RegularExpressions.RegexOptions]::CultureInvariant) -and
        @([regex]::Matches($_, $pluginsBlockPattern)).Count -eq 1 -and
        @([regex]::Matches($_, $pluginIdCallPattern)).Count -eq 1 -and
        $normalizedConcatenatedStrings -notmatch $applicationPluginPattern
    })
    $rootBuildPath = Join-Path $repositoryRootFull 'build.gradle.kts'
    $expectedRootBuild = @'
plugins {
    id("com.android.library") version "9.2.1" apply false
}

tasks.register<Delete>("clean") {
    delete(layout.buildDirectory)
}
'@ + "`n"
    $rootBuildText = if ([IO.File]::Exists($rootBuildPath)) {
        [Text.UTF8Encoding]::new($false, $true).GetString([IO.File]::ReadAllBytes($rootBuildPath))
    } else {
        ''
    }
    $checks.rootBuildBoundary = $rootBuildText -ceq $expectedRootBuild
    $checks.noApplicationModule =
        -not [IO.Directory]::Exists((Join-Path $repositoryRootFull 'app')) -and
        (Get-FileHash -LiteralPath $moduleBuildPaths[0] -Algorithm SHA256).Hash -ceq '6F098B978388CB976622BD4CD452D0871C48F2F20BEC6BC2A234495F1660C08B' -and
        (Get-FileHash -LiteralPath $moduleBuildPaths[1] -Algorithm SHA256).Hash -ceq 'DFF870F70C0EE4D1EF5DC666459D22B5C262090A277C3DAE16528718B6E03E52' -and
        $moduleBuildTexts.Count -eq 2 -and
        @($moduleLibraryBoundaries | Where-Object { $_ -eq $true }).Count -eq 2
    $checks.noDiscoverableManifest =
        @($protectedFiles | Where-Object {
            [string]::Equals($_.Name, 'AndroidManifest.xml', [StringComparison]::OrdinalIgnoreCase)
        }).Count -eq 0 -and
        @($moduleBuildTexts | Where-Object { $_ -match $manifestOverridePattern }).Count -eq 0

    $textExtensions = @('.kt', '.java', '.aidl', '.kts', '.xml', '.toml', '.md', '.json', '.properties', '.ps1', '.cs', '.pro')
    $textFiles = @($protectedFiles | Where-Object {
        $_.Extension.ToLowerInvariant() -in $textExtensions -or $_.Name -ceq '.gitattributes'
    })
    $allText = ($textFiles | ForEach-Object { [IO.File]::ReadAllText($_.FullName) }) -join "`n"
    $productionFiles = @($textFiles | Where-Object {
        $relative = Get-NormalizedRelativePath -Root $repositoryRootFull -Path $_.FullName
        $relative -match '^plugin-api/[^/]+/src/main/' -or
        $relative -in @('settings.gradle.kts', 'build.gradle.kts', 'gradle/libs.versions.toml') -or
        $relative -match '^plugin-api/[^/]+/build\.gradle\.kts$'
    })
    $productionText = ($productionFiles | ForEach-Object {
        $text = [IO.File]::ReadAllText($_.FullName)
        if ($_.Extension.ToLowerInvariant() -in @('.kt', '.kts', '.java', '.aidl')) {
            Remove-KotlinComments -Text $text
        } else {
            $text
        }
    }) -join "`n"
    $checks.noDexApi = $productionText -notmatch 'org\.autojs\.plugin\.dexcompiler|dex-compiler-api|DEX_COMPILER'
    $mappingFormatLiteral = '"com.android.tools.r8.mapping"'
    $mappingFormatAssignment = 'const val MAPPING_FORMAT_ID = "com.android.tools.r8.mapping"'
    $contractPath = Join-Path $repositoryRootFull 'plugin-api/r8-compiler-api/src/main/java/org/autojs/plugin/r8compiler/api/R8CompilerContract.kt'
    $contractText = if ([IO.File]::Exists($contractPath)) {
        Remove-KotlinComments -Text ([IO.File]::ReadAllText($contractPath))
    } else {
        ''
    }
    $mappingLiteralCount = @([regex]::Matches(
        $productionText,
        [regex]::Escape($mappingFormatLiteral),
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )).Count
    $mappingAssignmentCount = @([regex]::Matches(
        $contractText,
        [regex]::Escape($mappingFormatAssignment),
        [Text.RegularExpressions.RegexOptions]::CultureInvariant
    )).Count
    $productionTextWithoutMappingFormat = $productionText.Replace($mappingFormatLiteral, '')
    $checks.noR8Engine =
        $mappingLiteralCount -eq 1 -and
        $mappingAssignmentCount -eq 1 -and
        $productionTextWithoutMappingFormat -notmatch 'com\.android\.tools(?::|\.)r8|\bR8Command\b|\bR8\.run\b|\bR8\.main\b'
    $checks.noCommonApi = $productionText -notmatch 'common-plugin-api|org\.autojs\.plugin\.common\.api'
    $backslash = [string] [char] 92
    $slash = [string] [char] 47
    $escapedBackslash = [regex]::Escape($backslash)
    $pathComponent = '[A-Za-z0-9._~-]{2,}'
    $windowsDrivePattern = '(?i)(?<![A-Z0-9_])[A-Z]:' + '[\\/]' + $pathComponent + '(?:[\\/][A-Za-z0-9._~-]+)*'
    $uncPathPattern = '(?<!' + $escapedBackslash + ')' + [regex]::Escape($backslash + $backslash) +
        '(?!' + $escapedBackslash + ')' + '[^\\/\s"''<>|]+' + '[\\/]' + '[^\\/\s"''<>|]+'
    $slashClass = '[' + [regex]::Escape($slash + $backslash) + ']'
    $unixPathPattern = '(?<![:/A-Za-z0-9_.+-])' + [regex]::Escape($slash) +
        '(?!' + $slashClass + ')(?:[A-Za-z0-9._~-]+/)+[A-Za-z0-9._~-]+'
    $checks.noPrivatePaths =
        $allText -notmatch $windowsDrivePattern -and
        $allText -notmatch $uncPathPattern -and
        $allText -notmatch $unixPathPattern
    $gitAttributesPath = Join-Path $repositoryRootFull '.gitattributes'
    $expectedGitAttributes = "* text=auto eol=lf`n`n*.bat text eol=crlf`n*.jar -text`n*.aar -text`n"
    $gitAttributesText = if ([IO.File]::Exists($gitAttributesPath)) {
        [Text.UTF8Encoding]::new($false, $true).GetString([IO.File]::ReadAllBytes($gitAttributesPath))
    } else {
        ''
    }
    $checks.gitAttributes = $gitAttributesText -ceq $expectedGitAttributes
    $checks.noGeneratedTree = Test-CleanGeneratedTree -Root $repositoryRootFull -ReportPath $safeReportPath

    $identityPath = Join-Path $repositoryRootFull 'docs/identity-reservation.json'
    $identity = Read-StrictJsonObject -Path $identityPath -Label 'Identity reservation'
    Assert-ExactPropertySet -Value $identity -Expected @(
        'schemaVersion', 'status', 'apiNamespace', 'serviceAction', 'engineId', 'cacheDomain', 'protocol',
        'distributionCoordinate', 'officialProviderReservation', 'claims'
    ) -Label 'Identity reservation'
    Assert-ExactPropertySet -Value $identity.officialProviderReservation -Expected @(
        'applicationId', 'pluginId', 'variant', 'providerId', 'serviceComponent', 'process'
    ) -Label 'Provider identity reservation'
    $claimNames = @(
        'providerImplemented', 'manifestDiscoverable', 'hostIntegrated', 'binderVerified',
        'r8Executed', 'retraceExecuted', 'deviceVerified', 'published', 'pluginConsumed'
    )
    Assert-ExactPropertySet -Value $identity.claims -Expected $claimNames -Label 'Identity claims'
    $identityStringFields = @('status', 'apiNamespace', 'serviceAction', 'engineId', 'cacheDomain', 'protocol', 'distributionCoordinate')
    $providerStringFields = @('applicationId', 'pluginId', 'variant', 'providerId', 'serviceComponent', 'process')
    $identityStringsTyped = @($identityStringFields | Where-Object { -not (Test-JsonString $identity.$_) }).Count -eq 0
    $providerStringsTyped = @($providerStringFields | Where-Object { -not (Test-JsonString $identity.officialProviderReservation.$_) }).Count -eq 0
    $claimsExactlyFalse = @($claimNames | Where-Object {
        -not (Test-JsonBoolean $identity.claims.$_) -or $identity.claims.$_ -ne $false
    }).Count -eq 0
    $checks.identityReservation =
        (Test-JsonInteger $identity.schemaVersion) -and $identity.schemaVersion -eq 1L -and
        $identityStringsTyped -and
        $providerStringsTyped -and
        $claimsExactlyFalse -and
        $identity.status -ceq 'RESERVED_NOT_IMPLEMENTED' -and
        $identity.apiNamespace -ceq 'org.autojs.plugin.r8compiler.api' -and
        $identity.serviceAction -ceq 'org.autojs.plugin.R8_COMPILER' -and
        $identity.engineId -ceq 'r8-compiler' -and
        $identity.cacheDomain -ceq 'autojs6:r8-compiler:v1' -and
        $identity.protocol -ceq '1.0' -and
        $identity.distributionCoordinate -ceq 'org.autojs.plugin.r8compiler:r8-compiler-api:0.1.0' -and
        $identity.officialProviderReservation.applicationId -ceq 'io.github.supermonster003.autojs6.plugin.r8compiler' -and
        $identity.officialProviderReservation.pluginId -ceq 'r8-compiler' -and
        $identity.officialProviderReservation.variant -ceq 'r8' -and
        $identity.officialProviderReservation.providerId -ceq 'autojs6-r8' -and
        $identity.officialProviderReservation.serviceComponent -ceq 'io.github.supermonster003.autojs6.plugin.r8compiler/.R8CompilerService' -and
        $identity.officialProviderReservation.process -ceq ':r8'

    $provenancePath = Join-Path $repositoryRootFull 'docs/protocol-wire-provenance.json'
    $provenance = Read-StrictJsonObject -Path $provenancePath -Label 'Protocol provenance'
    Assert-ExactPropertySet -Value $provenance -Expected @(
        'schemaVersion', 'sourceRepository', 'sourceCommit', 'sourceModule', 'upstreamCopiedFiles',
        'localBuildScript', 'referenceReleaseAar', 'runtimeDependencyOnDexCompilerApi'
    ) -Label 'Protocol provenance'
    Assert-ExactPropertySet -Value $provenance.referenceReleaseAar -Expected @('byteLength', 'sha256') -Label 'Protocol AAR provenance'
    $expectedProtocolFiles = @(
        [pscustomobject]@{
            path = 'plugin-api/protocol-wire-api/consumer-rules.pro'
            byteLength = 58L
            sha256 = '3dc6c7ae2edf584270c4f3cb29a48f5c7028bd63c6e288b6d5a340f6131ed5c6'
        },
        [pscustomobject]@{
            path = 'plugin-api/protocol-wire-api/src/main/java/org/autojs/plugin/protocol/wire/TaggedWire.kt'
            byteLength = 19856L
            sha256 = '5d5c672ef0c7907b1a0cf6fd041ff85dc7c864aa07628316db5b9a9abb9e87c8'
        },
        [pscustomobject]@{
            path = 'plugin-api/protocol-wire-api/src/test/java/org/autojs/plugin/protocol/wire/TaggedWireTest.kt'
            byteLength = 9713L
            sha256 = 'd892de2da45109db52e5e9cee663143a650ee7eabc45646aae0fcbc939177f21'
        }
    )
    $upstreamFilesTyped = $null -ne $provenance.upstreamCopiedFiles -and $provenance.upstreamCopiedFiles.GetType() -eq [object[]]
    $upstreamFilesValid = $upstreamFilesTyped -and @($provenance.upstreamCopiedFiles).Count -eq $expectedProtocolFiles.Count
    if ($upstreamFilesValid) {
        foreach ($entry in @($provenance.upstreamCopiedFiles)) {
            Assert-ExactPropertySet -Value $entry -Expected @('path', 'byteLength', 'sha256') -Label 'Protocol copied-file provenance'
        }
        foreach ($expectedFile in $expectedProtocolFiles) {
            $matches = @($provenance.upstreamCopiedFiles | Where-Object { (Test-JsonString $_.path) -and $_.path -ceq $expectedFile.path })
            if ($matches.Count -ne 1) { $upstreamFilesValid = $false; continue }
            $entry = $matches[0]
            $sourcePath = Join-Path $repositoryRootFull $expectedFile.path
            if (
                -not (Test-JsonInteger $entry.byteLength) -or $entry.byteLength -ne $expectedFile.byteLength -or
                -not (Test-JsonString $entry.sha256) -or $entry.sha256 -cne $expectedFile.sha256 -or
                -not [IO.File]::Exists($sourcePath) -or (Get-Item -LiteralPath $sourcePath).Length -ne $expectedFile.byteLength -or
                (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant() -cne $expectedFile.sha256
            ) { $upstreamFilesValid = $false }
        }
    }
    $checks.protocolProvenance =
        (Test-JsonInteger $provenance.schemaVersion) -and $provenance.schemaVersion -eq 1L -and
        (Test-JsonString $provenance.sourceRepository) -and
        (Test-JsonString $provenance.sourceCommit) -and
        (Test-JsonString $provenance.sourceModule) -and
        $provenance.sourceRepository -ceq 'AutoJs6' -and
        $provenance.sourceCommit -ceq 'a33a1a81c6b0fbd947e2255eb0b36e68bf7f5322' -and
        $provenance.sourceModule -ceq 'plugin-api/protocol-wire-api' -and
        $upstreamFilesValid -and
        (Test-JsonBoolean $provenance.localBuildScript) -and $provenance.localBuildScript -eq $true -and
        (Test-JsonInteger $provenance.referenceReleaseAar.byteLength) -and $provenance.referenceReleaseAar.byteLength -eq 29462L -and
        (Test-JsonString $provenance.referenceReleaseAar.sha256) -and
        $provenance.referenceReleaseAar.sha256 -ceq 'fff8a79b3eb35a7b719b23b7893093bd6496228bb20adc38f2663e8322855577' -and
        (Test-JsonBoolean $provenance.runtimeDependencyOnDexCompilerApi) -and
        $provenance.runtimeDependencyOnDexCompilerApi -eq $false

    $protocolSourcePath = Join-Path $repositoryRootFull 'plugin-api/protocol-wire-api/src/main/java/org/autojs/plugin/protocol/wire/TaggedWire.kt'
    $checks.protocolSource = [IO.File]::Exists($protocolSourcePath) -and
        (Get-FileHash -Algorithm SHA256 -LiteralPath $protocolSourcePath).Hash.ToLowerInvariant() -ceq
            '5d5c672ef0c7907b1a0cf6fd041ff85dc7c864aa07628316db5b9a9abb9e87c8'

    $r8ApiRoot = Join-Path $repositoryRootFull 'plugin-api/r8-compiler-api'
    $requiredApiSources = @(
        'R8CompilerContract.kt', 'R8CompilerMessages.kt', 'R8CompilerCodec.kt',
        'R8CompilerValidation.kt', 'R8InputBundle.kt', 'R8RulePolicy.kt'
    ) | ForEach-Object { Join-Path $r8ApiRoot "src/main/java/org/autojs/plugin/r8compiler/api/$_" }
    $checks.r8ApiSource = @($requiredApiSources | Where-Object { [IO.File]::Exists($_) -and (Get-Item -LiteralPath $_).Length -gt 0 }).Count -eq $requiredApiSources.Count
    $apiBuildPath = Join-Path $r8ApiRoot 'build.gradle.kts'
    $apiBuild = [IO.File]::ReadAllText($apiBuildPath)
    $checks.apiDependencyBoundary =
        $apiBuild.Contains('api(project(":plugin-api:protocol-wire-api"))') -and
        $apiBuild -notmatch 'dex-compiler|common-plugin|com\.android\.tools(?::|\.)r8'
    $aidlRoot = Join-Path $r8ApiRoot 'src/main/aidl'
    $aidlNames = @(Get-ChildItem -LiteralPath $aidlRoot -Recurse -File -Filter '*.aidl' | ForEach-Object Name | Sort-Object)
    $checks.aidlSet = $aidlNames.Count -eq 3 -and
        @(Compare-Object $aidlNames @('IR8CompilerCallback.aidl', 'IR8CompilerProvider.aidl', 'IR8CompilerSession.aidl') -CaseSensitive).Count -eq 0

    $failedChecks = @($checks.GetEnumerator() | Where-Object { -not [bool] $_.Value } | ForEach-Object Key)
    if ($failedChecks.Count -ne 0) { throw "Source boundary checks failed: $($failedChecks -join ', ')." }

    $report = New-SourceReport -Passed $true -Checks $checks
    Write-AtomicJson -Path $safeReportPath -Value $report
    $report | ConvertTo-Json -Depth 16
    exit 0
} catch {
    $reason = ConvertTo-SafeReason -Message $_.Exception.Message -PrivateRoot @(
        $repositoryRootFull,
        $RepositoryRoot,
        $OutputPath,
        $PSScriptRoot,
        [IO.Path]::GetTempPath()
    )
    $report = New-SourceReport -Passed $false -Checks $checks -Reasons @($reason)
    if ($safeReportEstablished -and -not [string]::IsNullOrWhiteSpace($failureReportPath)) {
        try {
            Write-AtomicJson -Path $failureReportPath -Value $report
        } catch {
            $report | Add-Member -NotePropertyName persistenceError -NotePropertyValue 'Atomic failure report persistence failed.'
        }
    }
    $report | ConvertTo-Json -Depth 16
    exit 1
}
