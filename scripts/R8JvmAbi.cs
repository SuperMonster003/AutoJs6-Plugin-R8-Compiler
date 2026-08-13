using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;

public sealed class AutoJs6R8JvmAbiResult {
    public string CanonicalText { get; internal set; }
    public string[] ClassEntries { get; internal set; }
    public string[] InterfaceMethodDescriptors { get; internal set; }
    public string[] BinderConstants { get; internal set; }
    public int VisibleClassCount { get; internal set; }
    public int VisibleMemberCount { get; internal set; }
}

public static class AutoJs6R8JvmAbi {
    private const int AccPublic = 0x0001;
    private const int AccProtected = 0x0004;
    private const int AccStatic = 0x0008;
    private const int AccFinal = 0x0010;
    private const int AccBridge = 0x0040;
    private const int AccInterface = 0x0200;
    private const int AccAbstract = 0x0400;
    private const int AccSynthetic = 0x1000;

    private const int ClassStableMask = 0x661D; // public/protected/static/final/interface/abstract/annotation/enum
    private const int FieldStableMask = 0x40DD; // public/protected/static/final/volatile/transient/enum
    private const int MethodStableMask = 0x0DBD; // public/protected/static/final/synchronized/varargs/native/abstract/strict

    private sealed class Cp {
        internal byte Tag;
        internal object Value;
        internal ushort A;
        internal ushort B;
        internal byte[] Raw;
    }

    private sealed class Member {
        internal int Access;
        internal string Name;
        internal string Descriptor;
        internal string Constant;
    }

    private sealed class ClassFile {
        internal string Name;
        internal int Access;
        internal int? InnerAccess;
        internal string SuperName;
        internal string[] Interfaces;
        internal List<Member> Fields;
        internal List<Member> Methods;

        internal int EffectiveAccess {
            get {
                if (!InnerAccess.HasValue) return Access;
                int structural = Access & (AccFinal | AccInterface | AccAbstract | 0x2000 | 0x4000);
                int nested = InnerAccess.Value & (AccPublic | AccProtected | AccStatic | AccFinal |
                    AccInterface | AccAbstract | 0x2000 | 0x4000);
                return structural | nested;
            }
        }
    }

    private sealed class Reader {
        private readonly byte[] bytes;
        private int offset;
        private Cp[] pool;
        private ushort thisClassIndex;

        internal Reader(byte[] bytes) {
            this.bytes = bytes ?? throw new ArgumentNullException(nameof(bytes));
        }

        private byte U1() {
            if (offset >= bytes.Length) throw new InvalidDataException("Truncated class file");
            return bytes[offset++];
        }

        private ushort U2() {
            if (offset > bytes.Length - 2) throw new InvalidDataException("Truncated class file");
            ushort value = (ushort)((bytes[offset] << 8) | bytes[offset + 1]);
            offset += 2;
            return value;
        }

        private uint U4() {
            if (offset > bytes.Length - 4) throw new InvalidDataException("Truncated class file");
            uint value = ((uint)bytes[offset] << 24) | ((uint)bytes[offset + 1] << 16) |
                ((uint)bytes[offset + 2] << 8) | bytes[offset + 3];
            offset += 4;
            return value;
        }

        private ulong U8() {
            return ((ulong)U4() << 32) | U4();
        }

        private byte[] Bytes(int count) {
            if (count < 0 || offset > bytes.Length - count) throw new InvalidDataException("Truncated class file");
            byte[] result = new byte[count];
            Buffer.BlockCopy(bytes, offset, result, 0, count);
            offset += count;
            return result;
        }

        private void Skip(int count) {
            if (count < 0 || offset > bytes.Length - count) throw new InvalidDataException("Truncated class file");
            offset += count;
        }

        private static string DecodeModifiedUtf8(byte[] value) {
            StringBuilder text = new StringBuilder(value.Length);
            for (int i = 0; i < value.Length;) {
                int first = value[i++] & 0xff;
                if (first >= 0x01 && first <= 0x7f) {
                    text.Append((char)first);
                    continue;
                }
                if ((first & 0xe0) == 0xc0) {
                    if (i >= value.Length) throw new InvalidDataException("Truncated modified UTF-8 constant");
                    int second = value[i++] & 0xff;
                    if ((second & 0xc0) != 0x80) throw new InvalidDataException("Malformed modified UTF-8 constant");
                    int code = ((first & 0x1f) << 6) | (second & 0x3f);
                    if (code == 0) {
                        if (first != 0xc0 || second != 0x80) throw new InvalidDataException("Malformed modified UTF-8 NUL");
                    } else if (code < 0x80) {
                        throw new InvalidDataException("Overlong modified UTF-8 constant");
                    }
                    text.Append((char)code);
                    continue;
                }
                if ((first & 0xf0) == 0xe0) {
                    if (i > value.Length - 2) throw new InvalidDataException("Truncated modified UTF-8 constant");
                    int second = value[i++] & 0xff;
                    int third = value[i++] & 0xff;
                    if ((second & 0xc0) != 0x80 || (third & 0xc0) != 0x80)
                        throw new InvalidDataException("Malformed modified UTF-8 constant");
                    int code = ((first & 0x0f) << 12) | ((second & 0x3f) << 6) | (third & 0x3f);
                    if (code < 0x800) throw new InvalidDataException("Overlong modified UTF-8 constant");
                    text.Append((char)code);
                    continue;
                }
                throw new InvalidDataException("Malformed modified UTF-8 constant");
            }
            return text.ToString();
        }

        private Cp Entry(ushort index, byte expectedTag) {
            if (index == 0 || index >= pool.Length || pool[index] == null || pool[index].Tag != expectedTag)
                throw new InvalidDataException("Invalid constant-pool reference");
            return pool[index];
        }

        private string Utf8(ushort index) { return (string)Entry(index, 1).Value; }

        private string ClassName(ushort index, bool allowArray = false) {
            Cp entry = Entry(index, 7);
            string value = Utf8(entry.A);
            if (allowArray && value.StartsWith("[", StringComparison.Ordinal)) {
                ParseFieldDescriptor(value);
                return value;
            }
            ValidateInternalName(value);
            return value;
        }

        private static void ValidateInternalName(string value) {
            if (String.IsNullOrEmpty(value) || value[0] == '/' || value[value.Length - 1] == '/' ||
                value.IndexOf('.') >= 0 || value.IndexOf(';') >= 0 || value.IndexOf('[') >= 0 ||
                value.IndexOf('\\') >= 0 || value.IndexOf("//", StringComparison.Ordinal) >= 0)
                throw new InvalidDataException("Non-canonical internal class name");
        }

        private static void ValidateMemberName(string value, bool method) {
            if (String.IsNullOrEmpty(value) || value.IndexOf('.') >= 0 || value.IndexOf(';') >= 0 ||
                value.IndexOf('[') >= 0 || value.IndexOf('/') >= 0)
                throw new InvalidDataException("Invalid member name");
            if (value[0] == '<' && (!method || (value != "<init>" && value != "<clinit>")))
                throw new InvalidDataException("Invalid special member name");
        }

        private static int ParseType(string descriptor, int at, bool allowVoid) {
            if (at >= descriptor.Length) throw new InvalidDataException("Truncated JVM descriptor");
            char kind = descriptor[at++];
            if ("BCDFIJSZ".IndexOf(kind) >= 0 || (allowVoid && kind == 'V')) return at;
            if (kind == 'L') {
                int end = descriptor.IndexOf(';', at);
                if (end < 0) throw new InvalidDataException("Unterminated reference descriptor");
                ValidateInternalName(descriptor.Substring(at, end - at));
                return end + 1;
            }
            if (kind == '[') {
                int dimensions = 1;
                while (at < descriptor.Length && descriptor[at] == '[') { at++; dimensions++; }
                if (dimensions > 255 || (at < descriptor.Length && descriptor[at] == 'V'))
                    throw new InvalidDataException("Invalid array descriptor");
                return ParseType(descriptor, at, false);
            }
            throw new InvalidDataException("Invalid JVM descriptor");
        }

        private static void ParseFieldDescriptor(string descriptor) {
            int end = ParseType(descriptor, 0, false);
            if (end != descriptor.Length) throw new InvalidDataException("Trailing field descriptor bytes");
        }

        private static void ParseMethodDescriptor(string descriptor) {
            if (String.IsNullOrEmpty(descriptor) || descriptor[0] != '(')
                throw new InvalidDataException("Invalid method descriptor");
            int at = 1;
            int slots = 0;
            while (at < descriptor.Length && descriptor[at] != ')') {
                char first = descriptor[at];
                at = ParseType(descriptor, at, false);
                slots += first == 'J' || first == 'D' ? 2 : 1;
                if (slots > 255) throw new InvalidDataException("Method descriptor has too many parameter slots");
            }
            if (at >= descriptor.Length || descriptor[at++] != ')')
                throw new InvalidDataException("Unterminated method descriptor");
            at = ParseType(descriptor, at, true);
            if (at != descriptor.Length) throw new InvalidDataException("Trailing method descriptor bytes");
        }

        private void ReadPool() {
            ushort count = U2();
            if (count < 2) throw new InvalidDataException("Invalid constant pool");
            pool = new Cp[count];
            for (int index = 1; index < count; index++) {
                byte tag = U1();
                Cp entry = new Cp { Tag = tag };
                pool[index] = entry;
                switch (tag) {
                    case 1:
                        byte[] utf = Bytes(U2());
                        entry.Raw = utf;
                        entry.Value = DecodeModifiedUtf8(utf);
                        break;
                    case 3: case 4:
                        entry.Value = U4();
                        break;
                    case 5: case 6:
                        entry.Value = U8();
                        if (++index >= count) throw new InvalidDataException("Invalid wide constant-pool entry");
                        break;
                    case 7: case 8: case 16: case 19: case 20:
                        entry.A = U2();
                        break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        entry.A = U2();
                        entry.B = U2();
                        break;
                    case 15:
                        entry.Value = U1();
                        entry.A = U2();
                        break;
                    default:
                        throw new InvalidDataException("Unknown constant-pool tag");
                }
            }
            ValidatePool();
        }

        private void ValidatePool() {
            for (int index = 1; index < pool.Length; index++) {
                Cp entry = pool[index];
                if (entry == null) continue;
                switch (entry.Tag) {
                    case 1: case 3: case 4: case 5: case 6:
                        break;
                    case 7:
                        ClassName((ushort)index, true);
                        break;
                    case 8: case 19: case 20:
                        Utf8(entry.A);
                        break;
                    case 9: case 10: case 11:
                        Entry(entry.A, 7);
                        Entry(entry.B, 12);
                        break;
                    case 12:
                        Utf8(entry.A);
                        Utf8(entry.B);
                        break;
                    case 15:
                        int kind = (byte)entry.Value;
                        if (kind < 1 || kind > 9) throw new InvalidDataException("Invalid method-handle kind");
                        Cp target = entry.A == 0 || entry.A >= pool.Length ? null : pool[entry.A];
                        if (target == null || (target.Tag != 9 && target.Tag != 10 && target.Tag != 11))
                            throw new InvalidDataException("Invalid method-handle target");
                        break;
                    case 16:
                        ParseMethodDescriptor(Utf8(entry.A));
                        break;
                    case 17: case 18:
                        Entry(entry.B, 12);
                        break;
                    default:
                        throw new InvalidDataException("Unsupported constant-pool tag");
                }
            }
        }

        private string ConstantValue(ushort index, string descriptor) {
            Cp entry = index == 0 || index >= pool.Length ? null : pool[index];
            if (entry == null) throw new InvalidDataException("Invalid ConstantValue reference");
            switch (descriptor) {
                case "B": case "C": case "I": case "S": case "Z":
                    if (entry.Tag != 3) throw new InvalidDataException("ConstantValue type mismatch");
                    return "I:" + unchecked((int)(uint)entry.Value).ToString(CultureInfo.InvariantCulture);
                case "F":
                    if (entry.Tag != 4) throw new InvalidDataException("ConstantValue type mismatch");
                    return "F:0x" + ((uint)entry.Value).ToString("x8", CultureInfo.InvariantCulture);
                case "J":
                    if (entry.Tag != 5) throw new InvalidDataException("ConstantValue type mismatch");
                    return "J:" + unchecked((long)(ulong)entry.Value).ToString(CultureInfo.InvariantCulture);
                case "D":
                    if (entry.Tag != 6) throw new InvalidDataException("ConstantValue type mismatch");
                    return "D:0x" + ((ulong)entry.Value).ToString("x16", CultureInfo.InvariantCulture);
                case "Ljava/lang/String;":
                    if (entry.Tag != 8) throw new InvalidDataException("ConstantValue type mismatch");
                    Cp utf = Entry(entry.A, 1);
                    return "S:0x" + Hex(utf.Raw);
                default:
                    throw new InvalidDataException("ConstantValue is not legal for this descriptor");
            }
        }

        private static string Hex(byte[] value) {
            StringBuilder result = new StringBuilder(value.Length * 2);
            foreach (byte item in value) result.Append(item.ToString("x2", CultureInfo.InvariantCulture));
            return result.ToString();
        }

        private List<Member> ReadMembers(bool methods) {
            int count = U2();
            List<Member> result = new List<Member>(count);
            for (int index = 0; index < count; index++) {
                Member member = new Member {
                    Access = U2(),
                    Name = Utf8(U2()),
                    Descriptor = Utf8(U2())
                };
                ValidateMemberName(member.Name, methods);
                if (methods) ParseMethodDescriptor(member.Descriptor); else ParseFieldDescriptor(member.Descriptor);
                int attributeCount = U2();
                bool constantSeen = false;
                for (int attribute = 0; attribute < attributeCount; attribute++) {
                    string name = Utf8(U2());
                    uint length = U4();
                    if (length > Int32.MaxValue) throw new InvalidDataException("Class attribute is too large");
                    if (!methods && name == "ConstantValue") {
                        if (constantSeen || length != 2) throw new InvalidDataException("Malformed ConstantValue attribute");
                        constantSeen = true;
                        member.Constant = ConstantValue(U2(), member.Descriptor);
                    } else {
                        Skip((int)length);
                    }
                }
                result.Add(member);
            }
            return result;
        }

        internal ClassFile Read() {
            if (U4() != 0xCAFEBABEu) throw new InvalidDataException("Invalid class magic");
            U2();
            ushort major = U2();
            if (major < 45 || major > 70) throw new InvalidDataException("Unsupported class-file version");
            ReadPool();
            ClassFile result = new ClassFile();
            result.Access = U2();
            thisClassIndex = U2();
            result.Name = ClassName(thisClassIndex);
            ushort superIndex = U2();
            result.SuperName = superIndex == 0 ? "" : ClassName(superIndex);
            int interfaceCount = U2();
            result.Interfaces = new string[interfaceCount];
            for (int index = 0; index < interfaceCount; index++) result.Interfaces[index] = ClassName(U2());
            result.Fields = ReadMembers(false);
            result.Methods = ReadMembers(true);
            int attributeCount = U2();
            bool innerClassesSeen = false;
            for (int attribute = 0; attribute < attributeCount; attribute++) {
                string name = Utf8(U2());
                uint length = U4();
                if (length > Int32.MaxValue) throw new InvalidDataException("Class attribute is too large");
                int end = checked(offset + (int)length);
                if (end < offset || end > bytes.Length) throw new InvalidDataException("Truncated class attribute");
                if (name == "InnerClasses") {
                    if (innerClassesSeen) throw new InvalidDataException("Duplicate InnerClasses attribute");
                    innerClassesSeen = true;
                    int count = U2();
                    if (length != 2u + checked((uint)count * 8u))
                        throw new InvalidDataException("Malformed InnerClasses attribute");
                    for (int item = 0; item < count; item++) {
                        ushort innerClass = U2();
                        ushort outerClass = U2();
                        ushort innerName = U2();
                        int innerAccess = U2();
                        if (innerClass != 0) ClassName(innerClass);
                        if (outerClass != 0) ClassName(outerClass);
                        if (innerName != 0) Utf8(innerName);
                        if (innerClass == thisClassIndex) {
                            if (result.InnerAccess.HasValue && result.InnerAccess.Value != innerAccess)
                                throw new InvalidDataException("Conflicting InnerClasses visibility");
                            result.InnerAccess = innerAccess;
                        }
                    }
                } else {
                    Skip((int)length);
                }
                if (offset != end) throw new InvalidDataException("Malformed class attribute length");
            }
            if (offset != bytes.Length) throw new InvalidDataException("Class file contains trailing bytes");
            return result;
        }
    }

    private static bool Visible(int access) {
        return (access & (AccPublic | AccProtected)) != 0 && (access & AccSynthetic) == 0;
    }

    private static string Access(int access, int mask) {
        return (access & mask).ToString("x4", CultureInfo.InvariantCulture);
    }

    private static string Token(string value) {
        if (value == null) return "-";
        StringBuilder result = new StringBuilder(checked(value.Length * 4));
        foreach (char codeUnit in value)
            result.Append(((int)codeUnit).ToString("x4", CultureInfo.InvariantCulture));
        return result.ToString();
    }

    public static AutoJs6R8JvmAbiResult Analyze(string[] entryNames, byte[][] classBytes, bool requireBinderEvidence) {
        if (entryNames == null || classBytes == null || entryNames.Length != classBytes.Length || entryNames.Length == 0)
            throw new InvalidDataException("Class archive input is invalid");
        SortedDictionary<string, ClassFile> classes = new SortedDictionary<string, ClassFile>(StringComparer.Ordinal);
        for (int index = 0; index < entryNames.Length; index++) {
            string entry = entryNames[index];
            if (String.IsNullOrEmpty(entry) || !entry.EndsWith(".class", StringComparison.Ordinal))
                throw new InvalidDataException("Class entry name is invalid");
            ClassFile parsed = new Reader(classBytes[index]).Read();
            if (!String.Equals(parsed.Name + ".class", entry, StringComparison.Ordinal))
                throw new InvalidDataException("Class entry path differs from its internal identity");
            if (classes.ContainsKey(parsed.Name)) throw new InvalidDataException("Duplicate class identity");
            classes.Add(parsed.Name, parsed);
        }

        List<string> records = new List<string>();
        foreach (string entry in entryNames.OrderBy(value => value, StringComparer.Ordinal))
            records.Add("ENTRY|" + Token(entry));
        int visibleClasses = 0;
        int visibleMembers = 0;
        foreach (ClassFile parsed in classes.Values) {
            int classAccess = parsed.EffectiveAccess;
            if (!Visible(classAccess)) continue;
            visibleClasses++;
            string interfaces = String.Join(",", parsed.Interfaces.OrderBy(value => value, StringComparer.Ordinal).Select(Token));
            records.Add("CLASS|" + Token(parsed.Name) + "|access=" + Access(classAccess, ClassStableMask) +
                "|super=" + Token(parsed.SuperName) + "|interfaces=" + interfaces);
            foreach (Member field in parsed.Fields) {
                if (!Visible(field.Access)) continue;
                visibleMembers++;
                records.Add("FIELD|" + Token(parsed.Name) + "|access=" + Access(field.Access, FieldStableMask) +
                    "|name=" + Token(field.Name) + "|descriptor=" + Token(field.Descriptor) +
                    "|constant=" + (field.Constant ?? "-"));
            }
            foreach (Member method in parsed.Methods) {
                if (!Visible(method.Access) || (method.Access & (AccSynthetic | AccBridge)) != 0) continue;
                visibleMembers++;
                records.Add("METHOD|" + Token(parsed.Name) + "|access=" + Access(method.Access, MethodStableMask) +
                    "|name=" + Token(method.Name) + "|descriptor=" + Token(method.Descriptor));
            }
        }
        records.Sort(StringComparer.Ordinal);

        string[] aidlOwners = requireBinderEvidence ? new[] {
            "org/autojs/plugin/r8compiler/api/IR8CompilerCallback",
            "org/autojs/plugin/r8compiler/api/IR8CompilerProvider",
            "org/autojs/plugin/r8compiler/api/IR8CompilerSession"
        } : new string[0];
        List<string> interfaceMethods = new List<string>();
        List<string> binderConstants = new List<string>();
        foreach (string owner in aidlOwners) {
            if (!classes.TryGetValue(owner, out ClassFile contract) || (contract.Access & AccInterface) == 0)
                throw new InvalidDataException("AIDL interface class is missing or not an interface");
            foreach (Member method in contract.Methods) {
                if ((method.Access & (AccPublic | AccAbstract)) == (AccPublic | AccAbstract) &&
                    (method.Access & (AccSynthetic | AccBridge)) == 0)
                    interfaceMethods.Add(owner + "#" + method.Name + method.Descriptor);
            }
            Member descriptor = contract.Fields.SingleOrDefault(field => field.Name == "DESCRIPTOR");
            if (descriptor == null || descriptor.Descriptor != "Ljava/lang/String;" || descriptor.Constant == null)
                throw new InvalidDataException("AIDL DESCRIPTOR constant is missing");
            binderConstants.Add("DESCRIPTOR|" + owner + "|" + descriptor.Constant);

            string stubName = owner + "$Stub";
            if (!classes.TryGetValue(stubName, out ClassFile stub))
                throw new InvalidDataException("AIDL Stub class is missing");
            foreach (Member field in stub.Fields.Where(field => field.Name.StartsWith("TRANSACTION_", StringComparison.Ordinal))) {
                if (field.Descriptor != "I" || field.Constant == null)
                    throw new InvalidDataException("AIDL transaction constant is malformed");
                binderConstants.Add("TRANSACTION|" + stubName + "|" + field.Name + "|" + field.Constant);
            }
        }
        interfaceMethods.Sort(StringComparer.Ordinal);
        binderConstants.Sort(StringComparer.Ordinal);
        foreach (string value in interfaceMethods) records.Add("AIDL_METHOD|" + Token(value));
        foreach (string value in binderConstants) records.Add("BINDER|" + Token(value));
        records.Sort(StringComparer.Ordinal);

        return new AutoJs6R8JvmAbiResult {
            CanonicalText = "ABI-GOLDEN-V1\n" + String.Join("\n", records) + "\n",
            ClassEntries = entryNames.OrderBy(value => value, StringComparer.Ordinal).ToArray(),
            InterfaceMethodDescriptors = interfaceMethods.ToArray(),
            BinderConstants = binderConstants.ToArray(),
            VisibleClassCount = visibleClasses,
            VisibleMemberCount = visibleMembers
        };
    }
}
