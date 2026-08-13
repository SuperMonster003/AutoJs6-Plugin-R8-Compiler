[CmdletBinding()]
param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$ProtocolWireAar,
    [string]$R8CompilerApiAar,
    [string]$ReleaseDirectory,
    [string]$OutputPath
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$abiParserPath = Join-Path $PSScriptRoot 'R8JvmAbi.cs'
$abiParserBytesAtStart = $null

$claims = [ordered]@{
    providerImplemented = $false
    manifestDiscoverable = $false
    hostIntegrated = $false
    binderVerified = $false
    r8Executed = $false
    retraceExecuted = $false
    deviceVerified = $false
    published = $false
    pluginConsumed = $false
}

$report = [ordered]@{
    schemaVersion = 1
    evidenceBoundary = "CONTRACT_AAR_ONLY"
    passed = $false
    consumerCompileVerified = $false
    checks = [ordered]@{}
    releaseState = $null
    sourceFingerprint = $null
    artifacts = @()
    dependencyBoundary = $null
    aidlBoundary = $null
    abiBoundary = $null
    consumerCompile = $null
    verificationTool = $null
    claims = $claims
    failure = $null
}

function ConvertTo-Utf8JsonBytes {
    param([Parameter(Mandatory)]$Value)
    $json = $Value | ConvertTo-Json -Depth 16
    $canonical = $json.Replace("`r`n", "`n").Replace("`r", "`n") + "`n"
    return ,[Text.UTF8Encoding]::new($false).GetBytes($canonical)
}

function Write-AtomicBytes {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][byte[]]$Bytes)
    $fullPath = [IO.Path]::GetFullPath($Path)
    $directory = [IO.Path]::GetDirectoryName($fullPath)
    [IO.Directory]::CreateDirectory($directory) | Out-Null
    $temporary = Join-Path $directory (".{0}.{1}.tmp" -f [IO.Path]::GetFileName($fullPath), [Guid]::NewGuid().ToString("N"))
    try {
        [IO.File]::WriteAllBytes($temporary, $Bytes)
        [IO.File]::Move($temporary, $fullPath, $true)
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Get-CanonicalPath {
    param([Parameter(Mandatory)][string]$Path)
    $current = [IO.Path]::GetFullPath($Path)
    for ($pass = 0; $pass -lt 64; $pass++) {
        $rootPart = [IO.Path]::GetPathRoot($current)
        $segments = $current.Substring($rootPart.Length).Split(
            [char[]]@([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar),
            [StringSplitOptions]::RemoveEmptyEntries
        )
        $cursor = $rootPart
        $changed = $false
        for ($index = 0; $index -lt $segments.Length; $index++) {
            $cursor = Join-Path $cursor $segments[$index]
            if (-not [IO.File]::Exists($cursor) -and -not [IO.Directory]::Exists($cursor)) { break }
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                $target = $item.ResolveLinkTarget($true)
                if ($null -eq $target) { throw "Unable to resolve a path link" }
                $remaining = if ($index + 1 -lt $segments.Length) {
                    [IO.Path]::Combine($segments[($index + 1)..($segments.Length - 1)])
                } else { "" }
                $current = [IO.Path]::GetFullPath($(if ($remaining) { Join-Path $target.FullName $remaining } else { $target.FullName }))
                $changed = $true
                break
            }
        }
        if (-not $changed) { return $current }
    }
    throw "Path link resolution exceeded its limit"
}

function Test-IsWithin {
    param([Parameter(Mandatory)][string]$Child, [Parameter(Mandatory)][string]$Parent)
    $childFull = [IO.Path]::GetFullPath($Child).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    $parentFull = [IO.Path]::GetFullPath($Parent).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
    return $childFull.Equals($parentFull, [StringComparison]::OrdinalIgnoreCase) -or
        $childFull.StartsWith($parentFull + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)
}

function Assert-NoReparseBelowRoot {
    param([Parameter(Mandatory)][string]$Root, [Parameter(Mandatory)][string]$Path)
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $pathFull = [IO.Path]::GetFullPath($Path)
    if (-not (Test-IsWithin $pathFull $rootFull)) { throw "Path escapes repository root" }
    $relative = [IO.Path]::GetRelativePath($rootFull, $pathFull)
    $cursor = $rootFull
    foreach ($segment in $relative.Split([char[]]@('\', '/'), [StringSplitOptions]::RemoveEmptyEntries)) {
        $cursor = Join-Path $cursor $segment
        if (-not [IO.File]::Exists($cursor) -and -not [IO.Directory]::Exists($cursor)) { break }
        $item = Get-Item -LiteralPath $cursor -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Repository distribution paths must not traverse links"
        }
    }
}

if (-not ("AutoJs6R8DistributionFileIdentity" -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

public static class AutoJs6R8DistributionFileIdentity {
    [StructLayout(LayoutKind.Sequential)]
    private struct BY_HANDLE_FILE_INFORMATION {
        public uint FileAttributes;
        public uint CreationTimeLow;
        public uint CreationTimeHigh;
        public uint LastAccessTimeLow;
        public uint LastAccessTimeHigh;
        public uint LastWriteTimeLow;
        public uint LastWriteTimeHigh;
        public uint VolumeSerialNumber;
        public uint FileSizeHigh;
        public uint FileSizeLow;
        public uint NumberOfLinks;
        public uint FileIndexHigh;
        public uint FileIndexLow;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetFileInformationByHandle(
        SafeFileHandle handle,
        out BY_HANDLE_FILE_INFORMATION information
    );

    public static string Get(string path) {
        using (var stream = new FileStream(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete
        )) {
            BY_HANDLE_FILE_INFORMATION info;
            if (!GetFileInformationByHandle(stream.SafeFileHandle, out info)) {
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            }
            return info.VolumeSerialNumber.ToString("x8") + ":" +
                info.FileIndexHigh.ToString("x8") + info.FileIndexLow.ToString("x8");
        }
    }

    public static uint GetLinkCount(string path) {
        using (var stream = new FileStream(
            path,
            FileMode.Open,
            FileAccess.Read,
            FileShare.ReadWrite | FileShare.Delete
        )) {
            BY_HANDLE_FILE_INFORMATION info;
            if (!GetFileInformationByHandle(stream.SafeFileHandle, out info)) {
                throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
            }
            return info.NumberOfLinks;
        }
    }

}
'@
}

function Get-FileIdentity {
    param([Parameter(Mandatory)][string]$Path)
    if (-not [IO.File]::Exists($Path)) { return $null }
    if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) {
        return [AutoJs6R8DistributionFileIdentity]::Get([IO.Path]::GetFullPath($Path))
    }
    return $null
}

function Get-FileLinkCount {
    param([Parameter(Mandatory)][string]$Path)
    if (-not [IO.File]::Exists($Path)) { return $null }
    if ([Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) {
        $count = [AutoJs6R8DistributionFileIdentity]::GetLinkCount([IO.Path]::GetFullPath($Path))
        return [long]$count
    }
    return $null
}

function Get-Sha256 {
    param([Parameter(Mandatory)][string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-BytesSha256 {
    param([Parameter(Mandatory)][byte[]]$Bytes)
    $hash = [Security.Cryptography.SHA256]::HashData($Bytes)
    ([Convert]::ToHexString($hash)).ToLowerInvariant()
}

function Test-BytesEqual {
    param([Parameter(Mandatory)][byte[]]$Left, [Parameter(Mandatory)][byte[]]$Right)
    if ($Left.Length -ne $Right.Length) { return $false }
    for ($index = 0; $index -lt $Left.Length; $index++) {
        if ($Left[$index] -ne $Right[$index]) { return $false }
    }
    return $true
}

function Get-FileRecord {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)][string]$LogicalPath)
    $item = Get-Item -LiteralPath $Path -Force
    [ordered]@{
        path = $LogicalPath.Replace('\', '/')
        byteLength = [long]$item.Length
        sha256 = Get-Sha256 $item.FullName
    }
}

function Get-BytesRecord {
    param([Parameter(Mandatory)][byte[]]$Bytes, [Parameter(Mandatory)][string]$LogicalPath)
    [ordered]@{
        path = $LogicalPath.Replace('\', '/')
        byteLength = [long]$Bytes.Length
        sha256 = Get-BytesSha256 $Bytes
    }
}

function Read-LockedFileBytes {
    param([Parameter(Mandatory)][string]$Path, [long]$MaximumBytes = 64MB)
    $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::None)
    try {
        if ($stream.Length -le 0 -or $stream.Length -gt $MaximumBytes) { throw "Candidate AAR size is invalid" }
        $bytes = [byte[]]::new([int]$stream.Length)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $read = $stream.Read($bytes, $offset, $bytes.Length - $offset)
            if ($read -le 0) { throw "Candidate AAR read is truncated" }
            $offset += $read
        }
        return ,$bytes
    } finally { $stream.Dispose() }
}

function Compare-FileBytes {
    param([Parameter(Mandatory)][string]$Left, [Parameter(Mandatory)][string]$Right)
    $leftInfo = Get-Item -LiteralPath $Left
    $rightInfo = Get-Item -LiteralPath $Right
    if ($leftInfo.Length -ne $rightInfo.Length) { return $false }
    $a = [IO.File]::OpenRead($leftInfo.FullName)
    $b = [IO.File]::OpenRead($rightInfo.FullName)
    try {
        $leftBuffer = [byte[]]::new(65536)
        $rightBuffer = [byte[]]::new(65536)
        while ($true) {
            $leftRead = $a.Read($leftBuffer, 0, $leftBuffer.Length)
            $rightRead = $b.Read($rightBuffer, 0, $rightBuffer.Length)
            if ($leftRead -ne $rightRead) { return $false }
            if ($leftRead -eq 0) { return $true }
            for ($i = 0; $i -lt $leftRead; $i++) {
                if ($leftBuffer[$i] -ne $rightBuffer[$i]) { return $false }
            }
        }
    } finally {
        $a.Dispose()
        $b.Dispose()
    }
}

function Get-SourceFiles {
    param([Parameter(Mandatory)][string]$Root)
    $fixed = @(
        '.gitattributes',
        'settings.gradle.kts',
        'build.gradle.kts',
        'gradle.properties',
        'gradle/libs.versions.toml',
        'gradle/wrapper/gradle-wrapper.properties',
        'gradle/wrapper/gradle-wrapper.jar',
        'scripts/R8JvmAbi.cs',
        'plugin-api/protocol-wire-api/build.gradle.kts',
        'plugin-api/protocol-wire-api/consumer-rules.pro',
        'plugin-api/r8-compiler-api/build.gradle.kts',
        'plugin-api/r8-compiler-api/consumer-rules.pro',
        'plugin-api/r8-compiler-api/abi/0.1.0-java-visible-jvm-abi.txt',
        'test-consumer/src/main/java/org/autojs/plugin/r8compiler/consumer/R8ContractDetachedConsumer.java'
    )
    $paths = [Collections.Generic.List[string]]::new()
    foreach ($relative in $fixed) {
        $path = Join-Path $Root $relative
        if (-not [IO.File]::Exists($path)) { throw "Required source input is missing: $relative" }
        $paths.Add([IO.Path]::GetFullPath($path))
    }
    foreach ($relativeRoot in @(
        'plugin-api/protocol-wire-api/src/main',
        'plugin-api/r8-compiler-api/src/main'
    )) {
        $path = Join-Path $Root $relativeRoot
        if (-not [IO.Directory]::Exists($path)) { throw "Required source tree is missing: $relativeRoot" }
        Get-ChildItem -LiteralPath $path -Recurse -File -Force | ForEach-Object { $paths.Add($_.FullName) }
    }
    @($paths | Sort-Object -Unique)
}

function Get-SourceFingerprint {
    param([Parameter(Mandatory)][string]$Root)
    $records = @()
    foreach ($path in (Get-SourceFiles $Root)) {
        $relative = [IO.Path]::GetRelativePath($Root, $path).Replace('\', '/')
        $records += Get-FileRecord $path $relative
    }
    $canonical = ($records | ForEach-Object { "{0}`0{1}`0{2}" -f $_.path, $_.byteLength, $_.sha256 }) -join "`n"
    [ordered]@{
        algorithm = "sha256"
        scope = "contract-production-v1"
        sha256 = Get-BytesSha256 ([Text.UTF8Encoding]::new($false).GetBytes($canonical + "`n"))
        fileCount = $records.Count
        files = $records
    }
}

function Assert-ZipEntryNames {
    param([Parameter(Mandatory)]$Entries)
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $Entries) {
        $name = [string]$entry.FullName
        if ([string]::IsNullOrWhiteSpace($name) -or $name.Contains('\') -or $name.StartsWith('/') -or
            $name -match '^[A-Za-z]:' -or $name.IndexOf([char]0) -ge 0) {
            throw "Archive contains a malformed entry path"
        }
        $trimmed = $name.TrimEnd('/')
        $segments = $trimmed.Split('/')
        if ($segments.Count -eq 0 -or @($segments | Where-Object { $_ -eq '' -or $_ -eq '.' -or $_ -eq '..' }).Count -ne 0) {
            throw "Archive contains a non-canonical entry path"
        }
        if (-not $seen.Add($name)) { throw "Archive contains a duplicate entry path" }
        if ($entry.Length -lt 0 -or $entry.Length -gt 32MB) { throw "Archive entry exceeds the contract scan limit" }
    }
}

function Read-ZipEntryBytes {
    param([Parameter(Mandatory)]$Entry, [long]$MaximumBytes = 32MB)
    if ($Entry.Length -lt 0 -or $Entry.Length -gt $MaximumBytes -or $Entry.Length -gt [int]::MaxValue) {
        throw "Archive entry exceeds the contract scan limit"
    }
    $declaredLength = [long]$Entry.Length
    $stream = $Entry.Open()
    try {
        $bytes = [byte[]]::new([int]$declaredLength)
        $offset = 0
        while ($offset -lt $bytes.Length) {
            $read = $stream.Read($bytes, $offset, [Math]::Min(65536, $bytes.Length - $offset))
            if ($read -le 0) { throw "Archive entry length is inconsistent" }
            $offset += $read
        }
        if ($stream.ReadByte() -ne -1) { throw "Archive entry expands beyond its declared length" }
        return ,$bytes
    } finally {
        $stream.Dispose()
    }
}

function Assert-NoForbiddenBytes {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes,
        [Parameter(Mandatory)][string]$Label,
        [switch]$AllowMappingFormatLiteral
    )
    $text = [Text.Encoding]::GetEncoding(28591).GetString($Bytes)
    $mappingLiteral = 'com.android.tools.r8.mapping'
    if ($AllowMappingFormatLiteral) {
        $first = $text.IndexOf($mappingLiteral, [StringComparison]::Ordinal)
        if ($first -lt 0 -or $text.IndexOf($mappingLiteral, $first + $mappingLiteral.Length, [StringComparison]::Ordinal) -ge 0) {
            throw "$Label must contain exactly one canonical R8 mapping-format data literal"
        }
        $text = $text.Remove($first, $mappingLiteral.Length)
    }
    foreach ($pattern in @(
        'org/autojs/plugin/dexcompiler',
        'org.autojs.plugin.dexcompiler',
        'dex-compiler-api',
        'org/autojs/plugin/common',
        'org.autojs.plugin.common',
        'common-plugin-api',
        'com/android/tools/r8',
        'com.android.tools.r8'
    )) {
        if ($text.IndexOf($pattern, [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            throw "$Label contains a forbidden DEX/R8-engine reference"
        }
    }
}

function Assert-MetadataZipClean {
    param([Parameter(Mandatory)][byte[]]$Bytes, [Parameter(Mandatory)][string]$Label)
    $memory = [IO.MemoryStream]::new($Bytes, $false)
    $archive = $null
    try {
        $archive = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $false)
        Assert-ZipEntryNames $archive.Entries
        foreach ($entry in $archive.Entries | Where-Object { -not $_.FullName.EndsWith('/') }) {
            if ($entry.FullName -match '(?i)(dexcompiler|dex-compiler|common-plugin|com/android/tools/r8|\.class$|\.so$|\.dex$)') {
                throw "$Label contains a forbidden nested path"
            }
            Assert-NoForbiddenBytes (Read-ZipEntryBytes $entry 4MB) $Label
        }
    } catch [IO.InvalidDataException] {
        throw "$Label is not a valid metadata ZIP"
    } finally {
        if ($null -ne $archive) { $archive.Dispose() }
        $memory.Dispose()
    }
}

if (-not ("AutoJs6R8ClassFileBoundary" -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

public static class AutoJs6R8ClassFileBoundary {
    private static ushort U2(byte[] bytes, ref int offset) {
        if (offset > bytes.Length - 2) throw new InvalidDataException("Truncated class file");
        ushort value = (ushort)((bytes[offset] << 8) | bytes[offset + 1]);
        offset += 2;
        return value;
    }

    private static uint U4(byte[] bytes, ref int offset) {
        if (offset > bytes.Length - 4) throw new InvalidDataException("Truncated class file");
        uint value = ((uint)bytes[offset] << 24) | ((uint)bytes[offset + 1] << 16) |
            ((uint)bytes[offset + 2] << 8) | bytes[offset + 3];
        offset += 4;
        return value;
    }

    private static void Skip(byte[] bytes, ref int offset, int count) {
        if (count < 0 || offset > bytes.Length - count) throw new InvalidDataException("Truncated class file");
        offset += count;
    }

    private static void SkipAttributes(byte[] bytes, ref int offset, ushort count) {
        for (int index = 0; index < count; index++) {
            U2(bytes, ref offset);
            uint length = U4(bytes, ref offset);
            if (length > Int32.MaxValue) throw new InvalidDataException("Class attribute is too large");
            Skip(bytes, ref offset, (int)length);
        }
    }

    private static void SkipMembers(byte[] bytes, ref int offset, ushort count) {
        for (int index = 0; index < count; index++) {
            U2(bytes, ref offset);
            U2(bytes, ref offset);
            U2(bytes, ref offset);
            SkipAttributes(bytes, ref offset, U2(bytes, ref offset));
        }
    }

    public static string[] ReadIdentity(byte[] bytes) {
        int offset = 0;
        if (U4(bytes, ref offset) != 0xCAFEBABEu) throw new InvalidDataException("Invalid class magic");
        U2(bytes, ref offset);
        U2(bytes, ref offset);
        ushort count = U2(bytes, ref offset);
        if (count < 2) throw new InvalidDataException("Invalid constant pool");
        object[] pool = new object[count];
        for (int index = 1; index < count; index++) {
            if (offset >= bytes.Length) throw new InvalidDataException("Truncated constant pool");
            byte tag = bytes[offset++];
            switch (tag) {
                case 1:
                    ushort length = U2(bytes, ref offset);
                    if (offset > bytes.Length - length) throw new InvalidDataException("Truncated UTF8 constant");
                    pool[index] = Encoding.UTF8.GetString(bytes, offset, length);
                    offset += length;
                    break;
                case 3: case 4: Skip(bytes, ref offset, 4); break;
                case 5: case 6:
                    Skip(bytes, ref offset, 8);
                    index++;
                    break;
                case 7:
                    pool[index] = U2(bytes, ref offset);
                    break;
                case 8: case 16: case 19: case 20: Skip(bytes, ref offset, 2); break;
                case 9: case 10: case 11: case 12: case 17: case 18: Skip(bytes, ref offset, 4); break;
                case 15: Skip(bytes, ref offset, 3); break;
                default: throw new InvalidDataException("Unknown constant-pool tag");
            }
        }
        U2(bytes, ref offset);
        ushort thisClass = U2(bytes, ref offset);
        ushort superClass = U2(bytes, ref offset);
        string thisName = ResolveClass(pool, thisClass);
        string superName = superClass == 0 ? "" : ResolveClass(pool, superClass);
        ushort interfaceCount = U2(bytes, ref offset);
        Skip(bytes, ref offset, checked(interfaceCount * 2));
        SkipMembers(bytes, ref offset, U2(bytes, ref offset));
        SkipMembers(bytes, ref offset, U2(bytes, ref offset));
        SkipAttributes(bytes, ref offset, U2(bytes, ref offset));
        if (offset != bytes.Length) throw new InvalidDataException("Class file contains trailing bytes");
        List<string> identities = new List<string>();
        identities.Add(thisName);
        identities.Add(superName);
        for (int index = 1; index < pool.Length; index++) {
            if (pool[index] is ushort) {
                string referenced = ResolveClass(pool, (ushort)index);
                if (!identities.Contains(referenced)) identities.Add(referenced);
            }
        }
        return identities.ToArray();
    }

    private static string ResolveClass(object[] pool, ushort classIndex) {
        if (classIndex == 0 || classIndex >= pool.Length || !(pool[classIndex] is ushort))
            throw new InvalidDataException("Invalid class constant");
        ushort nameIndex = (ushort)pool[classIndex];
        if (nameIndex == 0 || nameIndex >= pool.Length || !(pool[nameIndex] is string))
            throw new InvalidDataException("Invalid class-name constant");
        string name = (string)pool[nameIndex];
        if (String.IsNullOrEmpty(name) || name.IndexOf('.') >= 0 || name.IndexOf('\\') >= 0)
            throw new InvalidDataException("Non-canonical internal class name");
        return name;
    }
}
'@
}

function Assert-NoNestedOrNativeMagic {
    param(
        [Parameter(Mandatory)][AllowEmptyCollection()][byte[]]$Bytes,
        [Parameter(Mandatory)][string]$Label
    )
    if ($Bytes.Length -ge 4) {
        $zip = $Bytes[0] -eq 0x50 -and $Bytes[1] -eq 0x4b -and
            (($Bytes[2] -eq 0x03 -and $Bytes[3] -eq 0x04) -or
             ($Bytes[2] -eq 0x05 -and $Bytes[3] -eq 0x06) -or
             ($Bytes[2] -eq 0x07 -and $Bytes[3] -eq 0x08))
        $elf = $Bytes[0] -eq 0x7f -and $Bytes[1] -eq 0x45 -and $Bytes[2] -eq 0x4c -and $Bytes[3] -eq 0x46
        $dex = $Bytes[0] -eq 0x64 -and $Bytes[1] -eq 0x65 -and $Bytes[2] -eq 0x78 -and $Bytes[3] -eq 0x0a
        if ($zip -or $elf -or $dex) { throw "$Label contains nested archive, native, or DEX magic" }
    }
    if ($Bytes.Length -ge 2 -and $Bytes[0] -eq 0x4d -and $Bytes[1] -eq 0x5a) {
        throw "$Label contains native executable magic"
    }
}

function Inspect-ClassesJar {
    param(
        [Parameter(Mandatory)][byte[]]$Bytes,
        [Parameter(Mandatory)][string]$ExpectedPackagePrefix,
        [Parameter(Mandatory)][string]$ExpectedKotlinMetadata,
        [switch]$R8Api
    )
    $memory = [IO.MemoryStream]::new($Bytes, $false)
    $jar = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        Assert-ZipEntryNames $jar.Entries
        $expectedDirectories = [Collections.Generic.List[string]]::new()
        $expectedDirectories.Add('META-INF/')
        $directoryCursor = ''
        foreach ($segment in $ExpectedPackagePrefix.TrimEnd('/').Split('/')) {
            $directoryCursor += $segment + '/'
            $expectedDirectories.Add($directoryCursor)
        }
        $directoryEntries = @($jar.Entries | Where-Object { $_.FullName.EndsWith('/') })
        $actualDirectories = @($directoryEntries |
            ForEach-Object FullName | Sort-Object)
        if (@(Compare-Object @($expectedDirectories | Sort-Object) $actualDirectories -CaseSensitive).Count -ne 0) {
            throw "classes.jar directory entry boundary is not exact"
        }
        foreach ($directoryEntry in $directoryEntries) {
            if ([long]$directoryEntry.Length -ne 0) {
                throw "classes.jar directory entries must have zero declared length"
            }
            $directoryBytes = Read-ZipEntryBytes $directoryEntry 0
            if ($directoryBytes.Length -ne 0) {
                throw "classes.jar directory entries must have exact EOF with no payload"
            }
        }
        $maximumClassEntries = if ($R8Api) { 256 } else { 64 }
        if ($jar.Entries.Count -gt $maximumClassEntries + 1 + $expectedDirectories.Count) {
            throw "classes.jar entry count exceeds its contract limit"
        }
        $totalUncompressed = 0L
        foreach ($entry in $jar.Entries) {
            $entryLength = [long]$entry.Length
            if ($entryLength -lt 0 -or $totalUncompressed -gt [long]::MaxValue - $entryLength) {
                throw "classes.jar aggregate size overflows"
            }
            $totalUncompressed += $entryLength
        }
        if ($totalUncompressed -gt 24MB) { throw "classes.jar aggregate uncompressed size exceeds its contract limit" }
        $files = @($jar.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
        $classEntries = @($files | Where-Object { $_.FullName.EndsWith('.class', [StringComparison]::Ordinal) })
        if ($classEntries.Count -eq 0) { throw "classes.jar contains no classes" }
        $metadataEntries = @($files | Where-Object { $_.FullName -ceq $ExpectedKotlinMetadata })
        if ($metadataEntries.Count -ne 1 -or $files.Count -ne $classEntries.Count + 1) {
            throw "classes.jar entries must be classes plus the exact Kotlin module metadata"
        }
        $protocolReferenceFound = $false
        $kotlinRuntimeReferenceFound = $false
        $jetbrainsAnnotationsReferenceFound = $false
        $abiEntryNames = [Collections.Generic.List[string]]::new()
        $abiClassBytes = [Collections.Generic.List[byte[]]]::new()
        foreach ($entry in $files) {
            $entryBytes = Read-ZipEntryBytes $entry 4MB
            $allowMappingFormat = $R8Api -and $entry.FullName -in @(
                'org/autojs/plugin/r8compiler/api/R8CompilerContract.class',
                'org/autojs/plugin/r8compiler/api/R8CompilerValidation.class'
            )
            Assert-NoForbiddenBytes $entryBytes "classes.jar/$($entry.FullName)" -AllowMappingFormatLiteral:$allowMappingFormat
            if ($entry.FullName -ceq $ExpectedKotlinMetadata) {
                Assert-NoNestedOrNativeMagic $entryBytes "classes.jar Kotlin metadata"
                continue
            }
            if (-not $entry.FullName.StartsWith($ExpectedPackagePrefix, [StringComparison]::Ordinal)) {
                throw "classes.jar crosses its declared package boundary"
            }
            try { $identity = [AutoJs6R8ClassFileBoundary]::ReadIdentity($entryBytes) }
            catch { throw "classes.jar contains a malformed class file" }
            if (($identity[0] + '.class') -cne $entry.FullName) {
                throw "classes.jar entry path differs from its internal class identity"
            }
            $abiEntryNames.Add($entry.FullName)
            $abiClassBytes.Add($entryBytes)
            $classText = [Text.Encoding]::GetEncoding(28591).GetString($entryBytes)
            if ($classText.Contains('kotlin/', [StringComparison]::Ordinal)) { $kotlinRuntimeReferenceFound = $true }
            if ($classText.Contains('org/jetbrains/annotations/', [StringComparison]::Ordinal)) {
                $jetbrainsAnnotationsReferenceFound = $true
            }
            $allowedAndroidReferences = @(
                'android/os/Binder',
                'android/os/IBinder',
                'android/os/IInterface',
                'android/os/Parcel',
                'android/os/Parcelable',
                'android/os/Parcelable$Creator',
                'android/os/ParcelFileDescriptor',
                'android/os/RemoteException'
            )
            foreach ($match in [regex]::Matches($classText, 'android(?:x)?/[A-Za-z0-9_$/]+')) {
                if ($allowedAndroidReferences -cnotcontains $match.Value) {
                    throw "classes.jar contains an Android type outside the exact Binder/PFD allowlist"
                }
            }
            if ($R8Api -and $classText.Contains('org/autojs/plugin/protocol/wire/', [StringComparison]::Ordinal)) {
                $protocolReferenceFound = $true
            }
        }

        $classNames = @($classEntries.FullName | Sort-Object)
        try {
            $abi = [AutoJs6R8JvmAbi]::Analyze($abiEntryNames.ToArray(), $abiClassBytes.ToArray(), [bool]$R8Api)
        } catch {
            throw "classes.jar JVM ABI parsing failed"
        }
        if ($R8Api) {
            foreach ($required in @(
                'org/autojs/plugin/r8compiler/api/R8CompilerContract.class',
                'org/autojs/plugin/r8compiler/api/R8CompilerCodec.class'
            )) {
                if ($required -notin $classNames) { throw "R8 API classes.jar is missing a required contract class" }
            }
            if (@($classNames | Where-Object { $_ -like 'org/autojs/plugin/protocol/wire/*' }).Count -ne 0) {
                throw "R8 API classes.jar embeds its protocol-wire dependency"
            }
            foreach ($name in $classNames) {
                $leaf = [IO.Path]::GetFileNameWithoutExtension($name).Split('$')[0]
                if ($leaf -match '(Application|Activity|Service|Receiver)$' -or
                    ($leaf -match 'Provider$' -and $leaf -ne 'IR8CompilerProvider')) {
                    throw "R8 API classes.jar contains an Android component class"
                }
            }
            $expectedAidlClasses = @(
                'org/autojs/plugin/r8compiler/api/IR8CompilerCallback$Default.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerCallback$Stub$Proxy.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerCallback$Stub.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerCallback.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerProvider$Default.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerProvider$Stub$Proxy.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerProvider$Stub.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerProvider$_Parcel.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerProvider.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerSession$Default.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerSession$Stub$Proxy.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerSession$Stub.class',
                'org/autojs/plugin/r8compiler/api/IR8CompilerSession.class'
            ) | Sort-Object
            $actualAidlClasses = @($classNames | Where-Object { $_ -match '/IR8Compiler(?:Callback|Provider|Session)(?:\$[^/]*)?\.class$' })
            if (@(Compare-Object $expectedAidlClasses $actualAidlClasses).Count -ne 0) {
                throw "R8 API generated Binder class boundary is not exact"
            }
            if (-not $protocolReferenceFound) { throw "R8 API classes do not expose their protocol-wire dependency" }
            return [pscustomobject]@{
                classCount = $classNames.Count
                aidlClassEntries = $actualAidlClasses
                abi = $abi
                classesJarBytes = $Bytes
                kotlinRuntimeReferenceFound = $kotlinRuntimeReferenceFound
                jetbrainsAnnotationsReferenceFound = $jetbrainsAnnotationsReferenceFound
            }
        }

        if ('org/autojs/plugin/protocol/wire/TaggedWireDocument.class' -notin $classNames -or
            'org/autojs/plugin/protocol/wire/TaggedWireWriter.class' -notin $classNames) {
            throw "Protocol classes.jar is missing TaggedWire public classes"
        }
        return [pscustomobject]@{
            classCount = $classNames.Count
            aidlClassEntries = @()
            abi = $abi
            classesJarBytes = $Bytes
            kotlinRuntimeReferenceFound = $kotlinRuntimeReferenceFound
            jetbrainsAnnotationsReferenceFound = $jetbrainsAnnotationsReferenceFound
        }
    } finally {
        $jar.Dispose()
        $memory.Dispose()
    }
}

function Inspect-Aar {
    param(
        [Parameter(Mandatory)][byte[]]$Bytes,
        [Parameter(Mandatory)][string]$LogicalPath,
        [Parameter(Mandatory)][ValidateSet('protocol', 'r8-api')][string]$Kind,
        [Parameter(Mandatory)][string]$Root
    )
    if ($Bytes.Length -le 0 -or $Bytes.Length -gt 64MB) { throw "Candidate AAR size is invalid" }
    $stream = [IO.MemoryStream]::new($Bytes, $false)
    $zip = $null
    try {
        $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Read, $false)
        Assert-ZipEntryNames $zip.Entries
        if (@($zip.Entries | Where-Object { $_.FullName.EndsWith('/') }).Count -ne 0) {
            throw "Contract AAR must not contain directory entries"
        }
        if ($zip.Entries.Count -ne 5) { throw "Contract AAR entry count is not exactly five" }
        $files = @($zip.Entries | Where-Object { -not $_.FullName.EndsWith('/') })
        $expectedAarEntries = @(
            'AndroidManifest.xml',
            'classes.jar',
            'META-INF/com/android/build/gradle/aar-metadata.properties',
            'proguard.txt',
            'R.txt'
        ) | Sort-Object
        $actualAarEntries = @($files.FullName | Sort-Object)
        if (@(Compare-Object $expectedAarEntries $actualAarEntries -CaseSensitive).Count -ne 0) {
            throw "Contract AAR entry boundary is not the exact five-file allowlist"
        }
        foreach ($entry in $files) {
            if ($entry.FullName -match '(?i)(^|/)(jni|lib|libs|prefab|assets|res)/|\.so$|\.dex$|\.apk$|\.aab$') {
                throw "Contract AAR contains a runtime, resource, native, or nested payload"
            }
            if ($entry.FullName.EndsWith('.jar', [StringComparison]::OrdinalIgnoreCase) -and
                $entry.FullName -cne 'classes.jar') {
                throw "Contract AAR contains an unexpected nested JAR"
            }
            if ($entry.FullName -match '(?i)(dexcompiler|dex-compiler|com/android/tools/r8|com\.android\.tools\.r8)') {
                throw "Contract AAR contains a forbidden DEX/R8-engine path"
            }
            if ($entry.FullName -cne 'classes.jar') {
                $entryBytes = Read-ZipEntryBytes $entry 8MB
                Assert-NoForbiddenBytes $entryBytes $entry.FullName
                Assert-NoNestedOrNativeMagic $entryBytes $entry.FullName
            }
        }
        $classesEntries = @($files | Where-Object { $_.FullName -ceq 'classes.jar' })
        $manifestEntries = @($files | Where-Object { $_.FullName -ceq 'AndroidManifest.xml' })
        if ($classesEntries.Count -ne 1 -or $manifestEntries.Count -ne 1) {
            throw "Contract AAR must contain exactly one classes.jar and AndroidManifest.xml"
        }

        $manifestBytes = Read-ZipEntryBytes $manifestEntries[0] 1MB
        Assert-NoForbiddenBytes $manifestBytes "AndroidManifest.xml"
        try { [xml]$manifestXml = [Text.UTF8Encoding]::new($false, $true).GetString($manifestBytes) }
        catch { throw "AndroidManifest.xml is not strict textual XML" }
        if ($manifestXml.DocumentElement.LocalName -ne 'manifest') { throw "AAR manifest root is invalid" }
        $expectedPackage = if ($Kind -eq 'r8-api') { 'org.autojs.plugin.r8compiler.api' } else { 'org.autojs.plugin.protocol.wire' }
        $manifest = $manifestXml.DocumentElement
        $manifestAttributes = @($manifest.Attributes | Where-Object { $_.Name -cne 'xmlns:android' })
        if ($manifestAttributes.Count -ne 1 -or $manifestAttributes[0].Name -cne 'package' -or
            $manifestAttributes[0].Value -cne $expectedPackage) {
            throw "Contract AAR manifest identity or attributes are not exact"
        }
        $elementChildren = @($manifest.ChildNodes | Where-Object { $_.NodeType -eq [Xml.XmlNodeType]::Element })
        if ($elementChildren.Count -ne 1 -or $elementChildren[0].LocalName -cne 'uses-sdk') {
            throw "Contract AAR manifest element allowlist is not exact"
        }
        $usesSdk = $elementChildren[0]
        $usesSdkAttributes = @($usesSdk.Attributes)
        if ($usesSdkAttributes.Count -ne 1 -or $usesSdkAttributes[0].LocalName -cne 'minSdkVersion' -or
            $usesSdkAttributes[0].NamespaceURI -cne 'http://schemas.android.com/apk/res/android' -or
            $usesSdkAttributes[0].Value -cne '24') {
            throw "Contract AAR uses-sdk boundary is not exact"
        }

        $rBytes = Read-ZipEntryBytes ($files | Where-Object { $_.FullName -ceq 'R.txt' } | Select-Object -First 1) 1KB
        if ($rBytes.Length -ne 0) { throw "Contract AAR R.txt must be empty" }
        $proguardBytes = Read-ZipEntryBytes ($files | Where-Object { $_.FullName -ceq 'proguard.txt' } | Select-Object -First 1) 256KB
        $consumerRulesRelative = if ($Kind -eq 'r8-api') {
            'plugin-api/r8-compiler-api/consumer-rules.pro'
        } else {
            'plugin-api/protocol-wire-api/consumer-rules.pro'
        }
        $expectedProguard = [IO.File]::ReadAllBytes((Join-Path $Root $consumerRulesRelative))
        if (-not (Test-BytesEqual $proguardBytes $expectedProguard)) {
            throw "Contract AAR proguard.txt differs from its exact production consumer rules"
        }
        $metadataBytes = Read-ZipEntryBytes ($files | Where-Object {
            $_.FullName -ceq 'META-INF/com/android/build/gradle/aar-metadata.properties'
        } | Select-Object -First 1) 16KB
        $metadataText = [Text.UTF8Encoding]::new($false, $true).GetString($metadataBytes).Replace("`r`n", "`n")
        $expectedMetadata = @(
            'aarFormatVersion=1.0',
            'aarMetadataVersion=1.0',
            'minCompileSdk=36',
            'minCompileSdkExtension=0',
            'minAndroidGradlePluginVersion=1.0.0',
            'coreLibraryDesugaringEnabled=false'
        ) -join "`n"
        if ($metadataText -cne ($expectedMetadata + "`n")) { throw "Contract AAR metadata properties are not exact" }

        $classesBytes = Read-ZipEntryBytes $classesEntries[0] 32MB
        Assert-NoForbiddenBytes $classesBytes "classes.jar"
        $classBoundary = if ($Kind -eq 'r8-api') {
            Inspect-ClassesJar $classesBytes 'org/autojs/plugin/r8compiler/api/' 'META-INF/r8-compiler-api.kotlin_module' -R8Api
        } else {
            Inspect-ClassesJar $classesBytes 'org/autojs/plugin/protocol/wire/' 'META-INF/protocol-wire-api.kotlin_module'
        }

        $aidlRecords = @()
        if ($Kind -eq 'r8-api') {
            $expectedAidl = @(
                'aidl/org/autojs/plugin/r8compiler/api/IR8CompilerCallback.aidl',
                'aidl/org/autojs/plugin/r8compiler/api/IR8CompilerProvider.aidl',
                'aidl/org/autojs/plugin/r8compiler/api/IR8CompilerSession.aidl'
            )
            $actualAidl = @($files | Where-Object { $_.FullName.EndsWith('.aidl', [StringComparison]::OrdinalIgnoreCase) })
            if ($actualAidl.Count -ne 0) { throw "R8 API AAR unexpectedly embeds source AIDL" }
            $sourceAidlRoot = Join-Path $Root 'plugin-api/r8-compiler-api/src/main/aidl'
            $actualSourceAidl = @(Get-ChildItem -LiteralPath $sourceAidlRoot -Recurse -File -Filter '*.aidl' |
                ForEach-Object { 'aidl/' + [IO.Path]::GetRelativePath($sourceAidlRoot, $_.FullName).Replace('\', '/') } |
                Sort-Object)
            if (@(Compare-Object ($expectedAidl | Sort-Object) $actualSourceAidl).Count -ne 0) {
                throw "R8 API source AIDL boundary is not exactly the three frozen descriptors"
            }
            foreach ($name in ($expectedAidl | Sort-Object)) {
                $sourceRelative = 'plugin-api/r8-compiler-api/src/main/' + $name
                $sourcePath = Join-Path $Root $sourceRelative
                if (-not [IO.File]::Exists($sourcePath)) { throw "Frozen AIDL source is missing" }
                # PowerShell variable names are case-insensitive. Keep this name distinct
                # from the candidate AAR parameter ($Bytes), or the published snapshot can
                # be replaced by the final AIDL descriptor read in this loop.
                $aidlSourceBytes = [IO.File]::ReadAllBytes($sourcePath)
                Assert-NoForbiddenBytes $aidlSourceBytes $name
                $aidlRecords += [ordered]@{
                    path = $sourceRelative.Replace('\', '/')
                    byteLength = [long]$aidlSourceBytes.Length
                    sha256 = Get-BytesSha256 $aidlSourceBytes
                }
            }
        } else {
            $protocolAidl = @($files | Where-Object { $_.FullName.EndsWith('.aidl', [StringComparison]::OrdinalIgnoreCase) })
            if ($protocolAidl.Count -ne 0) { throw "Protocol AAR unexpectedly contains AIDL" }
        }

        return [pscustomobject]@{
            artifact = Get-BytesRecord $Bytes $LogicalPath
            aarBytes = $Bytes
            classesJarBytes = $classBoundary.classesJarBytes
            classCount = $classBoundary.classCount
            aidlClassEntries = @($classBoundary.aidlClassEntries)
            aidlRecords = $aidlRecords
            abi = $classBoundary.abi
            kotlinRuntimeReferenceFound = [bool]$classBoundary.kotlinRuntimeReferenceFound
            jetbrainsAnnotationsReferenceFound = [bool]$classBoundary.jetbrainsAnnotationsReferenceFound
        }
    } catch [IO.InvalidDataException] {
        throw "Candidate AAR is not a valid ZIP archive"
    } finally {
        if ($null -ne $zip) { $zip.Dispose() }
        $stream.Dispose()
    }
}

function Get-VerifiedJvmAbiBoundary {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)]$ProtocolAbi,
        [Parameter(Mandatory)]$R8Abi,
        [Parameter(Mandatory)][byte[]]$ParserBytes
    )
    $goldenRelative = 'plugin-api/r8-compiler-api/abi/0.1.0-java-visible-jvm-abi.txt'
    $goldenPath = Join-Path $Root $goldenRelative
    $goldenBytes = Read-LockedFileBytes $goldenPath 4MB
    $canonicalText = "DISTRIBUTION-JAVA-VISIBLE-JVM-ABI-V1`n" +
        "ARTIFACT|protocol-wire-api-0.1.0.aar`n" + $ProtocolAbi.CanonicalText +
        "ARTIFACT|r8-compiler-api-0.1.0.aar`n" + $R8Abi.CanonicalText
    $canonicalBytes = [Text.UTF8Encoding]::new($false).GetBytes($canonicalText)
    if (-not (Test-BytesEqual $canonicalBytes $goldenBytes)) {
        throw 'Compiled Java-visible JVM ABI differs from the tracked 0.1.0 golden'
    }

    $expectedMethods = @(
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onCancelled([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onCompleted([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onFailed([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onProgress([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback#onStarted([B)V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#getCapabilities()[B',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#getCompilerInfo()[B',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider#openSession([BLandroid/os/ParcelFileDescriptor;Landroid/os/ParcelFileDescriptor;Lorg/autojs/plugin/r8compiler/api/IR8CompilerCallback;)Lorg/autojs/plugin/r8compiler/api/IR8CompilerSession;',
        'org/autojs/plugin/r8compiler/api/IR8CompilerSession#cancel()V',
        'org/autojs/plugin/r8compiler/api/IR8CompilerSession#close()V'
    ) | Sort-Object
    $actualMethods = @($R8Abi.InterfaceMethodDescriptors | Sort-Object)
    if (@(Compare-Object $expectedMethods $actualMethods -CaseSensitive).Count -ne 0) {
        throw 'Compiled AIDL abstract interface method descriptors differ from the exact 0.1.0 contract'
    }

    $expectedBinder = [Collections.Generic.List[string]]::new()
    foreach ($owner in @(
        'org/autojs/plugin/r8compiler/api/IR8CompilerCallback',
        'org/autojs/plugin/r8compiler/api/IR8CompilerProvider',
        'org/autojs/plugin/r8compiler/api/IR8CompilerSession'
    )) {
        $descriptorValue = $owner.Replace('/', '.')
        $descriptorHex = [Convert]::ToHexString([Text.UTF8Encoding]::new($false).GetBytes($descriptorValue)).ToLowerInvariant()
        $expectedBinder.Add("DESCRIPTOR|$owner|S:0x$descriptorHex")
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
        'IR8CompilerSession$Stub|TRANSACTION_cancel|I:1',
        'IR8CompilerSession$Stub|TRANSACTION_close|I:2'
    )) {
        $expectedBinder.Add('TRANSACTION|org/autojs/plugin/r8compiler/api/' + $record)
    }
    $expectedBinderValues = @($expectedBinder | Sort-Object)
    $actualBinderValues = @($R8Abi.BinderConstants | Sort-Object)
    if (@(Compare-Object $expectedBinderValues $actualBinderValues -CaseSensitive).Count -ne 0) {
        throw 'Compiled Binder DESCRIPTOR or TRANSACTION constants differ from the exact 0.1.0 contract'
    }

    [ordered]@{
        status = 'VERIFIED'
        scope = 'JAVA_VISIBLE_JVM_BINARY_ABI'
        goldenVerified = $true
        canonicalization = 'STRICT_CLASSFILE_V1'
        canonicalSha256 = Get-BytesSha256 $canonicalBytes
        golden = Get-BytesRecord $goldenBytes $goldenRelative
        parser = Get-BytesRecord $ParserBytes 'scripts/R8JvmAbi.cs'
        artifacts = @(
            [ordered]@{
                path = 'protocol-wire-api-0.1.0.aar/classes.jar'
                classEntries = @($ProtocolAbi.ClassEntries)
                classEntryCount = @($ProtocolAbi.ClassEntries).Count
                visibleClassCount = $ProtocolAbi.VisibleClassCount
                visibleMemberCount = $ProtocolAbi.VisibleMemberCount
            },
            [ordered]@{
                path = 'r8-compiler-api-0.1.0.aar/classes.jar'
                classEntries = @($R8Abi.ClassEntries)
                classEntryCount = @($R8Abi.ClassEntries).Count
                visibleClassCount = $R8Abi.VisibleClassCount
                visibleMemberCount = $R8Abi.VisibleMemberCount
            }
        )
    }
}

function Invoke-DetachedConsumerCompile {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][byte[]]$ProtocolClasses,
        [Parameter(Mandatory)][byte[]]$R8Classes
    )
    if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { throw "JAVA_HOME is required" }
    $javac = Join-Path $env:JAVA_HOME 'bin/javac.exe'
    if (-not [IO.File]::Exists($javac)) { throw "JAVA_HOME/bin/javac.exe is required" }
    $sdkRoot = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) { $env:ANDROID_HOME } else { $env:ANDROID_SDK_ROOT }
    if ([string]::IsNullOrWhiteSpace($sdkRoot)) { throw "ANDROID_HOME or ANDROID_SDK_ROOT is required" }
    $androidJar = Join-Path $sdkRoot 'platforms/android-36/android.jar'
    if (-not [IO.File]::Exists($androidJar)) { throw "Android platform android-36/android.jar is required" }

    $consumerRoot = Join-Path $Root 'test-consumer/src/main/java'
    $expectedSourceRelative = 'org/autojs/plugin/r8compiler/consumer/R8ContractDetachedConsumer.java'
    $expectedSource = [IO.Path]::GetFullPath((Join-Path $consumerRoot $expectedSourceRelative))
    $sources = @(Get-ChildItem -LiteralPath $consumerRoot -Recurse -File -Filter '*.java' | Sort-Object FullName)
    if ($sources.Count -ne 1 -or -not $sources[0].FullName.Equals($expectedSource, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Detached Java consumer source allowlist is not exact"
    }
    $sourceBytes = Read-LockedFileBytes $expectedSource 1MB
    $sourceText = [Text.UTF8Encoding]::new($false, $true).GetString($sourceBytes)
    $sourceEvidenceText = [regex]::Replace(
        [regex]::Replace($sourceText, '(?s)/\*.*?\*/', ''),
        '(?m)//.*$',
        ''
    )
    $requiredSourceSymbols = @(
        'org.autojs.plugin.protocol.wire.TaggedWireProtocol',
        'org.autojs.plugin.protocol.wire.TaggedWireLimits',
        'org.autojs.plugin.protocol.wire.TaggedWireWriter',
        'org.autojs.plugin.r8compiler.api.IR8CompilerCallback',
        'org.autojs.plugin.r8compiler.api.IR8CompilerProvider',
        'org.autojs.plugin.r8compiler.api.IR8CompilerSession',
        'org.autojs.plugin.r8compiler.api.R8CompilerContract',
        'org.autojs.plugin.r8compiler.api.R8CompilerFamily',
        'org.autojs.plugin.r8compiler.api.R8CompilerIntent',
        'org.autojs.plugin.r8compiler.api.R8FallbackPolicy',
        'org.autojs.plugin.r8compiler.api.R8Sha256'
    )
    foreach ($symbol in $requiredSourceSymbols) {
        if (-not $sourceEvidenceText.Contains($symbol, [StringComparison]::Ordinal)) {
            throw "Detached Java consumer omits a required contract symbol"
        }
    }
    foreach ($methodName in @(
        'getCompilerInfo', 'getCapabilities', 'openSession', 'cancel', 'close',
        'onStarted', 'onProgress', 'onCompleted', 'onFailed', 'onCancelled'
    )) {
        if (-not $sourceEvidenceText.Contains($methodName + '(', [StringComparison]::Ordinal)) {
            throw "Detached Java consumer omits a required Binder method call"
        }
    }
    $sourceRecords = @(Get-BytesRecord $sourceBytes ('test-consumer/src/main/java/' + $expectedSourceRelative))

    $temporary = Join-Path ([IO.Path]::GetTempPath()) ("autojs6-r8-contract-consumer-{0}" -f [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($temporary) | Out-Null
    try {
        $protocolJar = Join-Path $temporary 'protocol-wire-classes.jar'
        $r8Jar = Join-Path $temporary 'r8-compiler-api-classes.jar'
        $emptySourcePath = Join-Path $temporary 'empty-sourcepath'
        $classes = Join-Path $temporary 'classes'
        $stagedSource = Join-Path $temporary $expectedSourceRelative
        [IO.Directory]::CreateDirectory($emptySourcePath) | Out-Null
        [IO.Directory]::CreateDirectory($classes) | Out-Null
        [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($stagedSource)) | Out-Null
        [IO.File]::WriteAllBytes($stagedSource, $sourceBytes)
        [IO.File]::WriteAllBytes($protocolJar, $ProtocolClasses)
        [IO.File]::WriteAllBytes($r8Jar, $R8Classes)
        $classpath = @($androidJar, $protocolJar, $r8Jar) -join [IO.Path]::PathSeparator
        $arguments = @(
            '--release', '17',
            '-encoding', 'UTF-8',
            '-proc:none',
            '-implicit:none',
            '-sourcepath', $emptySourcePath,
            '-classpath', $classpath,
            '-d', $classes
        ) + @($stagedSource)
        Push-Location $temporary
        try {
            $compilerOutput = @(& $javac @arguments 2>&1 | ForEach-Object { "$_" })
            $exitCode = $LASTEXITCODE
        } finally { Pop-Location }
        if ($exitCode -ne 0) {
            $summary = ($compilerOutput -join ' ') -replace '(?i)(?:[A-Z]:[\\/]|/tmp/)[^\s"'']+', '<path>'
            throw "Detached javac failed: $summary"
        }
        $expectedClass = Join-Path $classes 'org/autojs/plugin/r8compiler/consumer/R8ContractDetachedConsumer.class'
        if (-not [IO.File]::Exists($expectedClass)) { throw "Detached javac did not emit the expected consumer class" }
        $emittedBytes = [IO.File]::ReadAllBytes($expectedClass)
        $requiredLinkageSymbols = @(
            'org/autojs/plugin/protocol/wire/TaggedWireProtocol',
            'org/autojs/plugin/protocol/wire/TaggedWireLimits',
            'org/autojs/plugin/protocol/wire/TaggedWireWriter',
            'org/autojs/plugin/r8compiler/api/IR8CompilerCallback',
            'org/autojs/plugin/r8compiler/api/IR8CompilerProvider',
            'org/autojs/plugin/r8compiler/api/IR8CompilerSession',
            'org/autojs/plugin/r8compiler/api/R8CompilerContract',
            'org/autojs/plugin/r8compiler/api/R8CompilerFamily',
            'org/autojs/plugin/r8compiler/api/R8CompilerIntent',
            'org/autojs/plugin/r8compiler/api/R8FallbackPolicy',
            'org/autojs/plugin/r8compiler/api/R8Sha256'
        )
        try { $emittedClassReferences = [AutoJs6R8ClassFileBoundary]::ReadIdentity($emittedBytes) }
        catch { throw "Detached Java consumer emitted malformed bytecode" }
        foreach ($symbol in $requiredLinkageSymbols) {
            if ($emittedClassReferences -cnotcontains $symbol) {
                throw "Detached Java consumer bytecode omits required contract linkage"
            }
        }
        $emittedText = [Text.Encoding]::GetEncoding(28591).GetString($emittedBytes)
        foreach ($methodName in @(
            'getCompilerInfo', 'getCapabilities', 'openSession', 'cancel', 'close',
            'onStarted', 'onProgress', 'onCompleted', 'onFailed', 'onCancelled'
        )) {
            if (-not $emittedText.Contains($methodName, [StringComparison]::Ordinal)) {
                throw "Detached Java consumer bytecode omits a required Binder method reference"
            }
        }
        $versionText = @(& $javac -version 2>&1 | ForEach-Object { "$_" }) -join ' '
        return [ordered]@{
            verified = $true
            javac = "JAVA_HOME/bin/javac.exe"
            javacVersion = $versionText
            release = 17
            sourcePath = "EMPTY"
            classpath = @('android-36/android.jar', 'protocol-wire-api/classes.jar', 'r8-compiler-api/classes.jar')
            androidJarSha256 = Get-Sha256 $androidJar
            sourceFiles = $sourceRecords
            emittedClass = 'org/autojs/plugin/r8compiler/consumer/R8ContractDetachedConsumer.class'
            emittedClassSha256 = Get-BytesSha256 $emittedBytes
            linkageSymbols = $requiredLinkageSymbols
            binderCallsCompileVerified = $true
            jvmBinaryAbiGoldenVerified = $false
        }
    } finally {
        $temporaryFull = [IO.Path]::GetFullPath($temporary)
        $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (Test-IsWithin $temporaryFull $tempRoot) { Remove-Item -LiteralPath $temporaryFull -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

function Publish-AppendOnlyDistribution {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)][byte[]]$ProtocolBytes,
        [Parameter(Mandatory)][byte[]]$R8Bytes,
        [Parameter(Mandatory)]$ExpectedRecords,
        [Parameter(Mandatory)][byte[]]$ManifestBytes,
        [Parameter(Mandatory)][scriptblock]$ValidateStableInputs
    )
    $files = [ordered]@{
        'protocol-wire-api-0.1.0.aar' = $ProtocolBytes
        'r8-compiler-api-0.1.0.aar' = $R8Bytes
    }
    $manifestName = 'contract-distribution-manifest.json'
    if ([IO.Directory]::Exists($Directory)) {
        $item = Get-Item -LiteralPath $Directory -Force
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "Release directory must not be a link" }
        $expectedNames = @($files.Keys) + $manifestName | Sort-Object
        $releaseChildren = @(Get-ChildItem -LiteralPath $Directory -Force)
        $actualNames = @($releaseChildren | ForEach-Object Name | Sort-Object)
        if (@(Compare-Object $expectedNames $actualNames -CaseSensitive).Count -ne 0) { throw "Existing release directory is not the exact immutable distribution" }
        $releaseIdentities = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($child in $releaseChildren) {
            if ($child.PSIsContainer -or ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
                -not [IO.File]::Exists($child.FullName)) {
                throw "Existing release child must be a regular non-link file"
            }
            $linkCount = Get-FileLinkCount $child.FullName
            if ($null -eq $linkCount -or $linkCount -ne 1) {
                throw "Existing release child must have exactly one hard link"
            }
            $identity = Get-FileIdentity $child.FullName
            if ($null -eq $identity -or -not $releaseIdentities.Add($identity)) {
                throw "Existing release children must have distinct file identities"
            }
        }
        foreach ($name in $files.Keys) {
            $staged = Join-Path $Directory $name
            $stagedLinkCount = Get-FileLinkCount $staged
            if ($null -eq $stagedLinkCount -or $stagedLinkCount -ne 1) {
                throw "Existing release AAR must have exactly one hard link"
            }
            $expected = @($ExpectedRecords | Where-Object { $_.path -ceq $name })
            $stagedBytes = Read-LockedFileBytes $staged 64MB
            if ($expected.Count -ne 1 -or
                $stagedBytes.Length -ne $expected[0].byteLength -or
                (Get-BytesSha256 $stagedBytes) -cne $expected[0].sha256) {
                throw "Append-only release collision: staged AAR differs from inspected snapshot"
            }
            if (-not (Test-BytesEqual $stagedBytes $files[$name])) {
                throw "Append-only release collision: candidate snapshot bytes differ"
            }
        }
        $manifestPath = Join-Path $Directory $manifestName
        $manifestLinkCount = Get-FileLinkCount $manifestPath
        if ($null -eq $manifestLinkCount -or $manifestLinkCount -ne 1) {
            throw "Existing release manifest must have exactly one hard link"
        }
        $manifestInfo = Get-Item -LiteralPath $manifestPath -Force
        if ($manifestInfo.Length -ne $ManifestBytes.Length -or $manifestInfo.Length -gt 4MB) {
            throw "Append-only release collision: manifest length differs"
        }
        $existingManifest = Read-LockedFileBytes $manifestPath 4MB
        if (-not (Test-BytesEqual $existingManifest $ManifestBytes)) {
            throw "Append-only release collision: manifest bytes differ"
        }
        & $ValidateStableInputs
        return "IDENTICAL"
    }
    if ([IO.File]::Exists($Directory)) { throw "Release path exists but is not a directory" }
    $parent = [IO.Path]::GetDirectoryName($Directory)
    [IO.Directory]::CreateDirectory($parent) | Out-Null
    $temporary = Join-Path $parent (".{0}.{1}.tmp" -f [IO.Path]::GetFileName($Directory), [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($temporary) | Out-Null
    try {
        foreach ($name in $files.Keys) { [IO.File]::WriteAllBytes((Join-Path $temporary $name), $files[$name]) }
        [IO.File]::WriteAllBytes((Join-Path $temporary $manifestName), $ManifestBytes)
        foreach ($name in $files.Keys) {
            $expected = @($ExpectedRecords | Where-Object { $_.path -ceq $name })
            $temporaryFile = Join-Path $temporary $name
            if ($expected.Count -ne 1 -or
                (Get-Item -LiteralPath $temporaryFile).Length -ne $expected[0].byteLength -or
                (Get-Sha256 $temporaryFile) -cne $expected[0].sha256 -or
                -not (Test-BytesEqual (Read-LockedFileBytes $temporaryFile 64MB) $files[$name])) {
                throw "Atomic release copy verification failed"
            }
        }
        $temporaryChildren = @(Get-ChildItem -LiteralPath $temporary -Force)
        $temporaryNames = @($temporaryChildren | ForEach-Object Name | Sort-Object)
        $expectedTemporaryNames = @($files.Keys) + $manifestName | Sort-Object
        if (@(Compare-Object $expectedTemporaryNames $temporaryNames -CaseSensitive).Count -ne 0) {
            throw "Atomic release child set is not exact"
        }
        $temporaryIdentities = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($child in $temporaryChildren) {
            if ($child.PSIsContainer -or ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or
                -not [IO.File]::Exists($child.FullName)) {
                throw "Atomic release child identity verification failed"
            }
            $temporaryLinkCount = Get-FileLinkCount $child.FullName
            $temporaryIdentity = Get-FileIdentity $child.FullName
            if ($null -eq $temporaryLinkCount -or $temporaryLinkCount -ne 1 -or
                $null -eq $temporaryIdentity -or -not $temporaryIdentities.Add($temporaryIdentity)) {
                throw "Atomic release children must have one hard link and distinct identities"
            }
        }
        $temporaryManifest = Join-Path $temporary $manifestName
        if (-not (Test-BytesEqual (Read-LockedFileBytes $temporaryManifest 4MB) $ManifestBytes)) {
            throw "Atomic release manifest differs from verified bytes"
        }
        & $ValidateStableInputs
        # Commit point: the fully verified temporary directory and all source/tool
        # snapshots are stable immediately before this atomic rename. No fallible
        # verification is permitted after a successful move; later invocations use
        # the IDENTICAL path to re-verify the append-only release.
        [IO.Directory]::Move($temporary, $Directory)
        return "CREATED"
    } finally {
        $temporaryFull = [IO.Path]::GetFullPath($temporary)
        $parentFull = [IO.Path]::GetFullPath($parent)
        $safeTemporary = (Test-IsWithin $temporaryFull $parentFull) -and
            [IO.Path]::GetFileName($temporaryFull).StartsWith('.0.1.0.', [StringComparison]::Ordinal)
        if ($safeTemporary -and [IO.Directory]::Exists($temporaryFull)) {
            Remove-Item -LiteralPath $temporaryFull -Recurse -Force
        }
    }
}

function Get-SanitizedFailure {
    param([Parameter(Mandatory)][string]$Message)
    (($Message -replace '(?i)(?:[A-Z]:[\\/]|\\\\|/home/|/Users/|/tmp/)[^\s"'']+', '<path>') -replace '[\r\n]+', ' ').Trim()
}

$rootLexical = [IO.Path]::GetFullPath($RepositoryRoot)
if (-not [IO.Directory]::Exists($rootLexical)) { Write-Error "Repository root is missing"; exit 1 }
$root = Get-CanonicalPath $rootLexical

if ([string]::IsNullOrWhiteSpace($ProtocolWireAar)) {
    $ProtocolWireAar = Join-Path $rootLexical 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-release.aar'
}
if ([string]::IsNullOrWhiteSpace($R8CompilerApiAar)) {
    $R8CompilerApiAar = Join-Path $rootLexical 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'
}
if ([string]::IsNullOrWhiteSpace($ReleaseDirectory)) {
    $ReleaseDirectory = Join-Path $rootLexical 'plugin-api/r8-compiler-api/releases/0.1.0'
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $rootLexical 'build/reports/g1-contract-distribution.json'
}

$expectedProtocolLexical = [IO.Path]::GetFullPath((Join-Path $rootLexical 'plugin-api/protocol-wire-api/build/outputs/aar/protocol-wire-api-release.aar'))
$expectedR8Lexical = [IO.Path]::GetFullPath((Join-Path $rootLexical 'plugin-api/r8-compiler-api/build/outputs/aar/r8-compiler-api-release.aar'))
$expectedReleaseLexical = [IO.Path]::GetFullPath((Join-Path $rootLexical 'plugin-api/r8-compiler-api/releases/0.1.0'))
$expectedOutputLexical = [IO.Path]::GetFullPath((Join-Path $rootLexical 'build/reports/g1-contract-distribution.json'))
$outputLexical = [IO.Path]::GetFullPath($OutputPath)
$allowedReportRootLexical = [IO.Path]::GetFullPath((Join-Path $rootLexical 'build/reports'))
$safeOutputEstablished = $false

try {
    if (-not $outputLexical.Equals($expectedOutputLexical, [StringComparison]::OrdinalIgnoreCase)) {
        throw "OutputPath must be the exact fixed distribution report path"
    }
    Assert-NoReparseBelowRoot $rootLexical $outputLexical
    $outputCanonical = Get-CanonicalPath $outputLexical
    if (-not (Test-IsWithin $outputCanonical $root)) { throw "OutputPath resolves outside the repository" }
    if ([IO.Directory]::Exists($outputLexical)) { throw "OutputPath must be a file path" }
    $repositoryProtected = @(Get-ChildItem -LiteralPath $rootLexical -Recurse -File -Force | Where-Object {
        $relative = [IO.Path]::GetRelativePath($rootLexical, $_.FullName).Replace('\', '/')
        -not $relative.Equals('build/reports/g1-contract-distribution.json', [StringComparison]::OrdinalIgnoreCase) -and
        $relative -notmatch '^(?:\.git|\.gradle|\.kotlin|build)/' -and
        $relative -notmatch '^plugin-api/[^/]+/build/'
    } | ForEach-Object FullName)
    $protected = @($expectedProtocolLexical, $expectedR8Lexical) + $repositoryProtected
    foreach ($path in $protected) {
        if ($outputCanonical.Equals((Get-CanonicalPath $path), [StringComparison]::OrdinalIgnoreCase)) {
            throw "OutputPath aliases a protected input"
        }
        if ([IO.File]::Exists($outputLexical) -and [IO.File]::Exists($path)) {
            $outputIdentity = Get-FileIdentity $outputLexical
            $protectedIdentity = Get-FileIdentity $path
            if ($null -ne $outputIdentity -and $outputIdentity -eq $protectedIdentity) {
                throw "OutputPath hard-links a protected input"
            }
        }
    }
    $safeOutputEstablished = $true
    if (-not [IO.Path]::GetFullPath($ProtocolWireAar).Equals($expectedProtocolLexical, [StringComparison]::OrdinalIgnoreCase) -or
        -not [IO.Path]::GetFullPath($R8CompilerApiAar).Equals($expectedR8Lexical, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Candidate AAR paths must be the two exact release build outputs"
    }
    if (-not [IO.Path]::GetFullPath($ReleaseDirectory).Equals($expectedReleaseLexical, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Release directory must be the exact stable 0.1.0 location"
    }
} catch {
    $report.failure = Get-SanitizedFailure $_.Exception.Message
    if ($safeOutputEstablished) { Write-AtomicBytes $expectedOutputLexical (ConvertTo-Utf8JsonBytes $report) }
    Write-Error $report.failure
    exit 1
}

try {
    $verifierBytesAtStart = [IO.File]::ReadAllBytes($PSCommandPath)
    $verifierRecord = Get-BytesRecord $verifierBytesAtStart 'scripts/verify-g1-contract-distribution.ps1'
    $report.verificationTool = $verifierRecord
    if (-not [IO.File]::Exists($abiParserPath)) { throw 'Tracked JVM ABI parser is missing' }
    $abiParserInfo = Get-Item -LiteralPath $abiParserPath -Force
    if ($abiParserInfo.Length -le 0 -or $abiParserInfo.Length -gt 1MB) { throw 'Tracked JVM ABI parser size is invalid' }
    $abiParserBytesAtStart = Read-LockedFileBytes $abiParserPath 1MB
    $abiParserTextAtStart = [Text.UTF8Encoding]::new($false, $true).GetString($abiParserBytesAtStart)
    if ('AutoJs6R8JvmAbi' -as [type]) { throw 'JVM ABI parser type was already loaded before verification' }
    Add-Type -TypeDefinition $abiParserTextAtStart
    $protocolCanonical = Get-CanonicalPath $expectedProtocolLexical
    $r8Canonical = Get-CanonicalPath $expectedR8Lexical
    $releaseCanonical = Get-CanonicalPath $expectedReleaseLexical
    foreach ($path in @($protocolCanonical, $r8Canonical, $releaseCanonical)) {
        if (-not (Test-IsWithin $path $root)) { throw "Distribution path resolves outside the repository" }
    }
    Assert-NoReparseBelowRoot $rootLexical $expectedProtocolLexical
    Assert-NoReparseBelowRoot $rootLexical $expectedR8Lexical
    Assert-NoReparseBelowRoot $rootLexical $expectedReleaseLexical
    if (-not [IO.File]::Exists($protocolCanonical) -or -not [IO.File]::Exists($r8Canonical)) {
        throw "Missing release AAR prerequisite: run :plugin-api:protocol-wire-api:assembleRelease and :plugin-api:r8-compiler-api:assembleRelease first"
    }
    $report.checks.candidatePaths = $true

    $sourceFingerprint = Get-SourceFingerprint $rootLexical
    $report.sourceFingerprint = $sourceFingerprint
    $report.checks.sourceFingerprint = $true
    $latestSourceWrite = (Get-SourceFiles $rootLexical | ForEach-Object { (Get-Item -LiteralPath $_).LastWriteTimeUtc } |
        Sort-Object -Descending | Select-Object -First 1)
    foreach ($candidate in @($protocolCanonical, $r8Canonical)) {
        if ((Get-Item -LiteralPath $candidate).LastWriteTimeUtc -lt $latestSourceWrite) {
            throw "Release AAR is older than a production source input; rebuild both release AARs"
        }
    }
    $report.checks.candidateFreshness = $true

    $protocolSnapshot = Read-LockedFileBytes $protocolCanonical
    $r8Snapshot = Read-LockedFileBytes $r8Canonical
    $protocol = Inspect-Aar $protocolSnapshot 'protocol-wire-api-0.1.0.aar' 'protocol' $rootLexical
    $r8 = Inspect-Aar $r8Snapshot 'r8-compiler-api-0.1.0.aar' 'r8-api' $rootLexical
    $report.checks.aarStructure = $true
    $report.checks.noAndroidComponents = $true
    $report.checks.noDexCompilerOrR8Engine = $true
    $report.checks.noNativeOrRuntimePayload = $true
    $report.checks.sourceAidlAndGeneratedClassSet = $true
    $report.checks.boundedClassArchiveBoundary = $true
    $report.artifacts = @($protocol.artifact, $r8.artifact)
    $abiBoundary = Get-VerifiedJvmAbiBoundary $rootLexical $protocol.abi $r8.abi $abiParserBytesAtStart
    $report.aidlBoundary = [ordered]@{
        sourceDescriptors = $r8.aidlRecords
        embeddedDescriptorCount = 0
        generatedClassEntries = $r8.aidlClassEntries
        descriptorCount = $r8.aidlRecords.Count
        binderMethodDescriptorsVerified = $true
        interfaceMethodDescriptors = @($r8.abi.InterfaceMethodDescriptors)
        binderConstants = @($r8.abi.BinderConstants)
    }
    $report.abiBoundary = $abiBoundary
    $report.checks.javaVisibleJvmAbiGolden = $true
    $report.checks.binderMethodDescriptorsAndConstants = $true
    if (-not ($protocol.kotlinRuntimeReferenceFound -or $r8.kotlinRuntimeReferenceFound) -or
        -not ($protocol.jetbrainsAnnotationsReferenceFound -or $r8.jetbrainsAnnotationsReferenceFound)) {
        throw 'Expected external runtime package references were not observed in the compiled classfiles'
    }
    $report.dependencyBoundary = [ordered]@{
        scope = 'INTRA_DISTRIBUTION_PLUS_DECLARED_EXTERNALS'
        r8ApiDependencies = @([ordered]@{
            role = 'protocol-wire-api'
            artifactSha256 = $protocol.artifact.sha256
            embeddedInR8Api = $false
        })
        r8EngineDependencies = @()
        detachedClasspathAarCount = 2
        externalRuntimeRequirements = @(
            [ordered]@{
                binaryPackagePrefix = 'kotlin/'
                version = 'UNVERIFIED'
                evidence = 'OBSERVED_CLASSFILE_REFERENCE'
                status = 'NOT_STAGED_NOT_RUNTIME_VERIFIED'
            },
            [ordered]@{
                binaryPackagePrefix = 'org/jetbrains/annotations/'
                version = 'UNVERIFIED'
                evidence = 'OBSERVED_CLASSFILE_REFERENCE'
                status = 'NOT_STAGED_NOT_RUNTIME_VERIFIED'
            }
        )
    }

    $consumer = Invoke-DetachedConsumerCompile $rootLexical $protocol.classesJarBytes $r8.classesJarBytes
    $consumer.jvmBinaryAbiGoldenVerified = $true
    $report.consumerCompile = $consumer
    $report.consumerCompileVerified = $true
    $report.checks.detachedConsumerCompile = $true

    $sourceFingerprintBytesAtStart = ConvertTo-Utf8JsonBytes $sourceFingerprint
    $validateStableInputs = {
        $currentSourceFingerprintBytes = ConvertTo-Utf8JsonBytes (Get-SourceFingerprint $rootLexical)
        if (-not (Test-BytesEqual $sourceFingerprintBytesAtStart $currentSourceFingerprintBytes)) {
            throw 'Production source changed during distribution verification'
        }
        if (-not (Test-BytesEqual $verifierBytesAtStart (Read-LockedFileBytes $PSCommandPath 4MB))) {
            throw 'Distribution verifier changed during execution'
        }
        if (-not (Test-BytesEqual $abiParserBytesAtStart (Read-LockedFileBytes $abiParserPath 1MB))) {
            throw 'JVM ABI parser changed during execution'
        }
    }.GetNewClosure()
    & $validateStableInputs
    $report.checks.sourceStableThroughPublish = $true
    $report.checks.verifierStableThroughPublish = $true
    $report.checks.abiParserStableThroughPublish = $true

    $manifest = [ordered]@{
        schemaVersion = 1
        evidenceBoundary = 'CONTRACT_AAR_ONLY'
        distributionVersion = '0.1.0'
        sourceFingerprint = $sourceFingerprint
        artifacts = @($protocol.artifact, $r8.artifact)
        dependencyBoundary = $report.dependencyBoundary
        aidlBoundary = $report.aidlBoundary
        abiBoundary = $report.abiBoundary
        consumerCompileVerified = $true
        consumerCompile = $consumer
        verificationTool = $verifierRecord
        claims = $claims
    }
    $manifestBytes = ConvertTo-Utf8JsonBytes $manifest
    $state = Publish-AppendOnlyDistribution `
        $expectedReleaseLexical `
        $protocol.aarBytes `
        $r8.aarBytes `
        @($protocol.artifact, $r8.artifact) `
        $manifestBytes `
        $validateStableInputs
    $report.releaseState = $state
    $report.checks.appendOnlyDistribution = $true
    $report.releaseManifest = [ordered]@{
        path = 'plugin-api/r8-compiler-api/releases/0.1.0/contract-distribution-manifest.json'
        byteLength = [long]$manifestBytes.Length
        sha256 = Get-BytesSha256 $manifestBytes
    }
    $report.passed = $true
} catch {
    $report.failure = Get-SanitizedFailure $_.Exception.Message
}

Write-AtomicBytes $outputLexical (ConvertTo-Utf8JsonBytes $report)
if (-not $report.passed) { Write-Error $report.failure; exit 1 }
$report | ConvertTo-Json -Depth 16
