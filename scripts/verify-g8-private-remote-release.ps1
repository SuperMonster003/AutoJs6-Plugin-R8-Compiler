[CmdletBinding()]
param(
    [switch]$AuthorizePrivateRemoteVerification,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[0-9a-f]{40}$')]
    [string]$ExpectedTaggedCommit
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (Get-Variable -Name PSNativeCommandUseErrorActionPreference -ErrorAction SilentlyContinue) {
    $PSNativeCommandUseErrorActionPreference = $false
}

$utf8NoBom = [Text.UTF8Encoding]::new($false)
$invocationId = [Guid]::NewGuid().ToString()
$repositoryRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$reportPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot 'build/reports/r42-g8/private-remote-release-gate.json'))
$checksumRelative = 'build/reports/r42-g8/autojs6-r8-compiler-provider-0.1.0-provider-dev-private.1-SHA256SUMS.txt'
$checksumPath = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $checksumRelative))
$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('autojs6-r8-g8-remote-{0}' -f $invocationId.Replace('-', ''))))
$repositoryName = 'SuperMonster003/AutoJs6-Plugin-R8-Compiler'
$repositoryOwner = 'SuperMonster003'
$repositoryShortName = 'AutoJs6-Plugin-R8-Compiler'
$releaseTag = 'v0.1.0-provider-dev-private.1'
$defaultBranch = 'master'
$targetEmail = '30370009+SuperMonster003@users.noreply.github.com'
$normalizedG1 = '2ce4d296a69fc78ff373a39630a1b3796bae9fe7'
$normalizedG1Tree = '892db0f4fb8a8ed618970144e80c32fbcdc381f9'
$normalizedG7Head = '884fe5be362f3ec2089514421cd4108b54349bf8'
$normalizedG7Tree = 'd1010affa348ee1587ed31d010b04146f6c0b94f'
$legacyG1 = '2a1fb3b70cfe6f4678bd0118a87c905a3fe52bbd'
$legacyG7Head = '372db374e95e5536d3a6556131c0b95e9bfdc744'
$localReleaseRelative = 'releases/provider/0.1.0-provider-dev/local.5'
$localReleaseRoot = [IO.Path]::GetFullPath((Join-Path $repositoryRoot $localReleaseRelative))
$expectedG7ReleaseGateSha256 = 'fe3df1fdce2b6ff675b41cad8d2da4720a6554da86230f11d1440f1d5f66953d'
$expectedG7RuntimeGateSha256 = '263a80a840b93d73de31e727ce9a76a824e44f326f3ae99b22a6f64850a466ff'
$failureStage = 'INITIALIZATION'

Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.Runtime.InteropServices;

public static class R8G8RemoteAtomicFile {
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
            [R8G8RemoteAtomicFile]::Replace($temporary, $reportPath)
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
        schemaVersion = 'autojs6.r8.g8.private-remote-release-gate/v1'
        invocationId = $invocationId
        passed = $false
        evidenceBoundary = 'PRIVACY_NORMALIZED_PRIVATE_GITHUB_RELEASE'
        failureStage = $Stage
        summary = 'G8 private remote verification failed closed; no remote publication claim is valid'
        claims = [ordered]@{
            localPublished = $false
            remotePublished = $false
            remoteVisibility = 'UNVERIFIED'
            publicPublished = $false
            sourcePushed = $false
            releaseAssetsPublished = $false
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

function Invoke-GhJson {
    param(
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$FailureMessage
    )
    $result = Invoke-Native $script:ghPath $Arguments $FailureMessage
    Require (-not [String]::IsNullOrWhiteSpace($result.Text)) "$FailureMessage returned no JSON"
    try {
        return ($result.Text | ConvertFrom-Json)
    } catch {
        throw "$FailureMessage returned malformed JSON"
    }
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

function Read-PositiveGate {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Schema,
        [Parameter(Mandatory = $true)][string]$ExpectedSha256
    )
    Require ([IO.File]::Exists($Path)) 'A prerequisite G7 Gate is missing'
    Require ((Get-Sha256File $Path) -ceq $ExpectedSha256) 'A prerequisite G7 Gate digest differs'
    $gate = Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json
    Require ([string]$gate.schemaVersion -ceq $Schema -and [bool]$gate.passed) 'A prerequisite G7 Gate is not positive or has the wrong schema'
    Require ([string]$gate.invocationId -cmatch '^[0-9a-f-]{36}$') 'A prerequisite G7 invocation ID is invalid'
    return $gate
}

function Get-CommitRecord {
    param([Parameter(Mandatory = $true)][string]$Commit)
    $parentText = [string](Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'show', '-s', '--format=%P', $Commit) 'Git could not read commit parents').Text
    return [ordered]@{
        sha = $Commit
        tree = [string](Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'show', '-s', '--format=%T', $Commit) 'Git could not read a commit tree').Text
        parents = @($parentText.Split(' ', [StringSplitOptions]::RemoveEmptyEntries))
        authorName = [string](Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'show', '-s', '--format=%an', $Commit) 'Git could not read an author name').Text
        authorEmail = [string](Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'show', '-s', '--format=%ae', $Commit) 'Git could not read an author email').Text
        committerName = [string](Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'show', '-s', '--format=%cn', $Commit) 'Git could not read a committer name').Text
        committerEmail = [string](Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'show', '-s', '--format=%ce', $Commit) 'Git could not read a committer email').Text
    }
}

Write-FailedGate $failureStage

try {
    $failureStage = 'EXPLICIT_AUTHORIZATION'
    Require ([bool]$AuthorizePrivateRemoteVerification) 'G8 requires explicit private-remote verification authorization'

    $failureStage = 'TOOL_AND_ACCOUNT_IDENTITY'
    $ghCommand = Get-Command 'gh.exe' -ErrorAction Stop
    $script:ghPath = $ghCommand.Source
    $account = Invoke-GhJson @('api', 'user') 'GitHub account lookup failed'
    Require ([string]$account.login -ceq $repositoryOwner -and [long]$account.id -eq 30370009L) 'Authenticated GitHub account differs from the authorized owner'

    $failureStage = 'LOCAL_HISTORY_AND_TAG'
    $branch = (Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'branch', '--show-current') 'Git could not resolve the current branch').Text
    Require ($branch -ceq $defaultBranch) 'The local default branch differs'
    $localHead = (Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'rev-parse', 'HEAD') 'Git could not resolve HEAD').Text
    Require ($localHead -ceq $ExpectedTaggedCommit) 'Local HEAD differs from the expected tagged commit'
    $localCommits = @((Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'rev-list', '--reverse', 'HEAD') 'Git could not enumerate local history').Lines)
    Require ($localCommits.Count -eq 3) 'The local private-release history must contain exactly three commits'
    Require ($localCommits[0] -ceq $normalizedG1 -and $localCommits[1] -ceq $normalizedG7Head -and $localCommits[2] -ceq $ExpectedTaggedCommit) 'The normalized local commit sequence differs'
    Require (-not ($localCommits -contains $legacyG1) -and -not ($localCommits -contains $legacyG7Head)) 'A legacy identity-bearing commit remains locally reachable'

    $localCommitRecords = @($localCommits | ForEach-Object { Get-CommitRecord $_ })
    foreach ($record in $localCommitRecords) {
        Require ([string]$record.authorName -ceq $repositoryOwner -and [string]$record.committerName -ceq $repositoryOwner) 'A local Git name differs from the authorized owner'
        Require ([string]$record.authorEmail -ceq $targetEmail -and [string]$record.committerEmail -ceq $targetEmail) 'A local Git email differs from the authorized noreply identity'
    }
    Require ([string]$localCommitRecords[0].tree -ceq $normalizedG1Tree -and @($localCommitRecords[0].parents).Count -eq 0) 'The normalized G1 tree or topology differs'
    Require ([string]$localCommitRecords[1].tree -ceq $normalizedG7Tree -and @($localCommitRecords[1].parents).Count -eq 1 -and [string]$localCommitRecords[1].parents[0] -ceq $normalizedG1) 'The normalized G7 tree or topology differs'
    Require (@($localCommitRecords[2].parents).Count -eq 1 -and [string]$localCommitRecords[2].parents[0] -ceq $normalizedG7Head) 'The G8 preparation commit parent differs'

    $localTagType = (Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'cat-file', '-t', "refs/tags/$releaseTag") 'The local release tag is missing').Text
    Require ($localTagType -ceq 'tag') 'The private-release tag must be annotated'
    $localTagCommit = (Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'rev-list', '-n', '1', $releaseTag) 'Git could not resolve the local release tag').Text
    Require ($localTagCommit -ceq $ExpectedTaggedCommit) 'The local release tag targets a different commit'
    $localTagObject = (Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'cat-file', 'tag', $releaseTag) 'Git could not inspect the annotated tag').Text
    Require ($localTagObject -cmatch ('(?m)^tagger ' + [regex]::Escape($repositoryOwner) + ' <' + [regex]::Escape($targetEmail) + '> [0-9]+ [+-][0-9]{4}$')) 'The annotated tagger identity differs'

    $failureStage = 'LOCAL_RELEASE_AND_PRIOR_EVIDENCE'
    $localNames = @(
        'autojs6-r8-compiler-provider-0.1.0-provider-dev-signed.apk',
        'protocol-wire-api-0.1.0.aar',
        'r8-compiler-api-0.1.0.aar',
        'release-manifest.json'
    )
    $localAssetRecords = @($localNames | ForEach-Object {
        New-FileRecord (Join-Path $localReleaseRoot $_) ("$localReleaseRelative/$_")
    })
    $checksumRecord = New-FileRecord $checksumPath $checksumRelative
    $expectedChecksumText = (($localAssetRecords | ForEach-Object { '{0}  {1}' -f $_.sha256, [IO.Path]::GetFileName([string]$_.path) }) -join "`n") + "`n"
    Require ([IO.File]::ReadAllText($checksumPath, $utf8NoBom) -ceq $expectedChecksumText) 'The SHA256SUMS asset is not canonical for local.5'

    $manifest = Get-Content -Raw -LiteralPath (Join-Path $localReleaseRoot 'release-manifest.json') | ConvertFrom-Json
    Require ([string]$manifest.schemaVersion -ceq 'autojs6.r8.local-release/v2' -and [string]$manifest.releaseId -ceq '0.1.0-provider-dev-local.5') 'The local.5 manifest identity differs'
    Require ([bool]$manifest.claims.localPublished -and -not [bool]$manifest.claims.remotePublished) 'The historical local.5 publication claims differ'

    $g7ReleasePath = Join-Path $repositoryRoot 'build/reports/r42-g7/local-release-gate.json'
    $g7RuntimePath = Join-Path $repositoryRoot 'build/reports/r42-g7/art-jni-retrace-gate.json'
    $g7Release = Read-PositiveGate $g7ReleasePath 'autojs6.r8.g7.local-release-gate/v1' $expectedG7ReleaseGateSha256
    $g7Runtime = Read-PositiveGate $g7RuntimePath 'autojs6.r8.g7.art-jni-retrace-gate/v1' $expectedG7RuntimeGateSha256
    Require ([bool]$g7Release.claims.localPublished -and -not [bool]$g7Release.claims.remotePublished) 'The frozen G7 release claims differ'
    Require ([bool]$g7Runtime.claims.localPublished -and -not [bool]$g7Runtime.claims.remotePublished) 'The frozen G7 runtime claims differ'

    $identityPath = Join-Path $repositoryRoot 'docs/identity-reservation.json'
    $designPath = Join-Path $repositoryRoot 'docs/private-remote-release-v1.md'
    $identity = Get-Content -Raw -LiteralPath $identityPath | ConvertFrom-Json
    Require ([long]$identity.schemaVersion -eq 2L -and [string]$identity.status -ceq 'PRIVATE_REMOTE_PROVIDER_ART_JNI_RETRACE_VERIFIED_NOT_PUBLIC') 'The current identity status does not match G8'
    Require ([bool]$identity.claims.localPublished -and [bool]$identity.claims.remotePublished -and [bool]$identity.claims.published -and -not [bool]$identity.claims.publicPublished) 'The current G8 publication claims differ'
    Require ([string]$identity.publication.repository -ceq $repositoryName -and [string]$identity.publication.visibility -ceq 'PRIVATE') 'The current publication repository or visibility differs'
    Require ([string]$identity.publication.releaseTag -ceq $releaseTag -and -not [bool]$identity.publication.publicPublished) 'The current publication tag or public boundary differs'

    $failureStage = 'PRIVATE_REMOTE_REPOSITORY'
    $repository = Invoke-GhJson @('api', "repos/$repositoryName") 'GitHub repository lookup failed'
    Require ([string]$repository.owner.login -ceq $repositoryOwner -and [string]$repository.name -ceq $repositoryShortName) 'The remote repository identity differs'
    Require ([bool]$repository.private -and [string]$repository.visibility -ceq 'private') 'The GitHub repository is not private'
    Require (-not [bool]$repository.archived -and -not [bool]$repository.disabled) 'The GitHub repository is archived or disabled'
    Require ([string]$repository.default_branch -ceq $defaultBranch) 'The GitHub default branch differs'

    $originUrl = (Invoke-Native 'git.exe' @('-C', $repositoryRoot, 'remote', 'get-url', 'origin') 'Git origin is missing').Text
    $allowedOriginUrls = @(
        "https://github.com/$repositoryName.git",
        "git@github.com:$repositoryName.git"
    )
    Require ($allowedOriginUrls -ccontains $originUrl) 'Git origin does not identify the authorized private repository'

    $branchRef = Invoke-GhJson @('api', "repos/$repositoryName/git/ref/heads/$defaultBranch") 'Remote branch lookup failed'
    Require ([string]$branchRef.object.type -ceq 'commit' -and [string]$branchRef.object.sha -ceq $ExpectedTaggedCommit) 'The remote branch head differs at G8 verification time'

    $tagRef = Invoke-GhJson @('api', "repos/$repositoryName/git/ref/tags/$releaseTag") 'Remote tag ref lookup failed'
    Require ([string]$tagRef.object.type -ceq 'tag') 'The remote release tag is not annotated'
    $tagObject = Invoke-GhJson @('api', "repos/$repositoryName/git/tags/$($tagRef.object.sha)") 'Remote annotated tag lookup failed'
    Require ([string]$tagObject.tag -ceq $releaseTag -and [string]$tagObject.object.type -ceq 'commit' -and [string]$tagObject.object.sha -ceq $ExpectedTaggedCommit) 'The remote annotated tag target differs'
    Require ([string]$tagObject.tagger.name -ceq $repositoryOwner -and [string]$tagObject.tagger.email -ceq $targetEmail) 'The remote tagger identity differs'

    $remoteCommits = @(Invoke-GhJson @('api', "repos/$repositoryName/commits?sha=$defaultBranch&per_page=100") 'Remote commit-history lookup failed')
    Require ($remoteCommits.Count -eq 3) 'The remote branch must contain exactly three commits at G8 verification time'
    $expectedRemoteOrder = @($ExpectedTaggedCommit, $normalizedG7Head, $normalizedG1)
    for ($index = 0; $index -lt $remoteCommits.Count; $index++) {
        $remoteCommit = $remoteCommits[$index]
        Require ([string]$remoteCommit.sha -ceq $expectedRemoteOrder[$index]) 'The remote commit sequence differs'
        Require ([string]$remoteCommit.commit.author.name -ceq $repositoryOwner -and [string]$remoteCommit.commit.committer.name -ceq $repositoryOwner) 'A remote commit name differs'
        Require ([string]$remoteCommit.commit.author.email -ceq $targetEmail -and [string]$remoteCommit.commit.committer.email -ceq $targetEmail) 'A remote commit email differs from the authorized noreply identity'
    }
    Require (-not (@($remoteCommits.sha) -contains $legacyG1) -and -not (@($remoteCommits.sha) -contains $legacyG7Head)) 'A legacy identity-bearing commit is remotely reachable'

    $failureStage = 'PRIVATE_PRERELEASE_METADATA'
    $release = Invoke-GhJson @('api', "repos/$repositoryName/releases/tags/$releaseTag") 'GitHub prerelease lookup failed'
    Require ([string]$release.tag_name -ceq $releaseTag -and -not [bool]$release.draft -and [bool]$release.prerelease) 'The GitHub release is not the required published prerelease'
    $expectedRemoteNames = @($localNames + [IO.Path]::GetFileName($checksumPath))
    [Array]::Sort($expectedRemoteNames, [StringComparer]::Ordinal)
    $remoteAssets = @($release.assets)
    $remoteNames = [string[]]@($remoteAssets | ForEach-Object { [string]$_.name })
    [Array]::Sort($remoteNames, [StringComparer]::Ordinal)
    Require ($remoteNames.Count -eq 5 -and (($remoteNames -join "`n") -ceq ($expectedRemoteNames -join "`n"))) 'The GitHub release asset set differs'
    foreach ($asset in $remoteAssets) {
        Require ([string]$asset.state -ceq 'uploaded' -and [long]$asset.size -gt 0L) 'A GitHub release asset is not fully uploaded'
    }

    $failureStage = 'REMOTE_ASSET_REDOWNLOAD'
    Require (-not [IO.Directory]::Exists($temporaryRoot) -and -not [IO.File]::Exists($temporaryRoot)) 'The invocation temporary path already exists'
    [IO.Directory]::CreateDirectory($temporaryRoot) | Out-Null
    Invoke-Native $script:ghPath @('release', 'download', $releaseTag, '--repo', $repositoryName, '--dir', $temporaryRoot) 'GitHub release asset download failed' | Out-Null
    $downloadedItems = @(Get-ChildItem -LiteralPath $temporaryRoot -Force)
    Require (@($downloadedItems | Where-Object { $_.PSIsContainer }).Count -eq 0) 'The downloaded release contains a directory'
    $downloadedNames = [string[]]@($downloadedItems | ForEach-Object { $_.Name })
    [Array]::Sort($downloadedNames, [StringComparer]::Ordinal)
    Require (($downloadedNames -join "`n") -ceq ($expectedRemoteNames -join "`n")) 'The downloaded release asset set differs'

    $allExpectedRecords = @($localAssetRecords + $checksumRecord)
    $downloadRecords = [Collections.Generic.List[object]]::new()
    foreach ($record in $allExpectedRecords) {
        $name = [IO.Path]::GetFileName([string]$record.path)
        $downloadedPath = Join-Path $temporaryRoot $name
        $downloaded = New-FileRecord $downloadedPath $name
        Require ([long]$downloaded.byteLength -eq [long]$record.byteLength -and [string]$downloaded.sha256 -ceq [string]$record.sha256) "Downloaded release bytes differ: $name"
        $downloadRecords.Add([ordered]@{ name = $name; byteLength = [long]$downloaded.byteLength; sha256 = [string]$downloaded.sha256 })
    }

    $failureStage = 'FINAL_REPORT'
    $finalReport = [ordered]@{
        schemaVersion = 'autojs6.r8.g8.private-remote-release-gate/v1'
        invocationId = $invocationId
        passed = $true
        evidenceBoundary = 'PRIVACY_NORMALIZED_PRIVATE_GITHUB_RELEASE'
        repository = [ordered]@{
            nameWithOwner = $repositoryName
            repositoryId = [long]$repository.id
            visibility = 'PRIVATE'
            private = $true
            defaultBranch = $defaultBranch
            branchHeadAtVerification = $ExpectedTaggedCommit
        }
        privacyMigration = [ordered]@{
            targetNoreplyIdentity = $targetEmail
            authorAndCommitterIdentityVerified = $true
            annotatedTaggerIdentityVerified = $true
            legacyCommitIdsReachable = $false
            mappings = @(
                [ordered]@{ predecessor = $legacyG1; successor = $normalizedG1; identicalTree = $normalizedG1Tree },
                [ordered]@{ predecessor = $legacyG7Head; successor = $normalizedG7Head; identicalTree = $normalizedG7Tree }
            )
            remoteCommits = @($remoteCommits | ForEach-Object { [string]$_.sha })
        }
        release = [ordered]@{
            releaseId = [long]$release.id
            tag = $releaseTag
            taggedCommit = $ExpectedTaggedCommit
            draft = $false
            prerelease = $true
            htmlUrl = [string]$release.html_url
            assets = @($downloadRecords)
            allAssetsRedownloaded = $true
        }
        localSource = [ordered]@{
            releaseId = '0.1.0-provider-dev-local.5'
            channel = 'LOCAL_ONLY'
            files = @($localAssetRecords)
            checksumAsset = $checksumRecord
        }
        priorEvidence = @(
            [ordered]@{ path = 'build/reports/r42-g7/local-release-gate.json'; schemaVersion = [string]$g7Release.schemaVersion; invocationId = [string]$g7Release.invocationId; byteLength = [long](Get-Item -LiteralPath $g7ReleasePath).Length; sha256 = Get-Sha256File $g7ReleasePath },
            [ordered]@{ path = 'build/reports/r42-g7/art-jni-retrace-gate.json'; schemaVersion = [string]$g7Runtime.schemaVersion; invocationId = [string]$g7Runtime.invocationId; byteLength = [long](Get-Item -LiteralPath $g7RuntimePath).Length; sha256 = Get-Sha256File $g7RuntimePath }
        )
        currentIdentity = New-FileRecord $identityPath 'docs/identity-reservation.json'
        design = New-FileRecord $designPath 'docs/private-remote-release-v1.md'
        verifier = New-FileRecord $PSCommandPath 'scripts/verify-g8-private-remote-release.ps1'
        operations = [ordered]@{
            githubMutationPerformedByVerifier = $false
            adbInvoked = $false
            signingMaterialRead = $false
            remoteMavenPublished = $false
            downloadedTemporaryAssetsDeletedAfterVerification = $true
        }
        claims = [ordered]@{
            localPublished = $true
            remotePublished = $true
            remoteVisibility = 'PRIVATE'
            publicPublished = $false
            sourcePushed = $true
            releaseAssetsPublished = $true
            releaseAssetsRedownloadedAndRehashed = $true
        }
        summary = 'Privacy-normalized source history and exact local.5 APK/API bytes are published to a verified private GitHub repository and prerelease; public publication remains false'
    }
    Write-AtomicJson $finalReport
    Write-Output "G8 private remote release Gate passed: $reportPath"
    Write-Output "Invocation ID: $invocationId"
} catch {
    try { Write-FailedGate $failureStage } catch { Write-Error 'G8 verification also failed to record its negative Gate' }
    throw
} finally {
    if ([IO.Directory]::Exists($temporaryRoot)) {
        $temporaryParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $candidate = [IO.Path]::GetFullPath($temporaryRoot)
        $candidateName = [IO.Path]::GetFileName($candidate)
        if ($candidate.StartsWith($temporaryParent, [StringComparison]::OrdinalIgnoreCase) -and
            $candidateName.StartsWith('autojs6-r8-g8-remote-', [StringComparison]::Ordinal)) {
            Remove-Item -LiteralPath $candidate -Recurse -Force
        }
    }
}
