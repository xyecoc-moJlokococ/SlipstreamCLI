using System;
using System.Runtime.InteropServices;
using System.Text;

internal static class LimitTest2
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    struct List { public int dwSize; public IntPtr szConnection; public int dwOptionCount; public int dwOptionError; public IntPtr options; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    struct Opt {
        public int m_Option;
        public Val m_Value;
        [StructLayout(LayoutKind.Explicit)]
        public struct Val {
            [FieldOffset(0)] public System.Runtime.InteropServices.ComTypes.FILETIME ft;
            [FieldOffset(0)] public int m_Int;
            [FieldOffset(0)] public IntPtr m_StringPtr;
        }
    }
    [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Auto)]
    static extern bool InternetSetOption(IntPtr h, int opt, IntPtr buf, int len);

    static int Main()
    {
        string[] tests = {
            "localhost;127.0.0.1;<local>",
            "localhost;127.0.0.1;<local>;10.*",
            "localhost;127.0.0.1;<local>;127.*",
            "localhost;127.0.0.1;<local>;*.steampowered.com",
            "localhost;127.0.0.1;<local>;::1",
            RepeatTok("host", 5),
            RepeatTok("host", 10),
            RepeatTok("host", 20),
            RepeatTok("host", 40),
            RepeatTok("xxxxxxxx", 10),
            RepeatTok("xxxxxxxx", 20),
            new string('b', 32) + ";" + new string('c', 32),
            "localhost;127.0.0.1;<local>;10.*;172.16.*;192.168.*",
        };
        foreach (string t in tests)
            Console.WriteLine("len=" + t.Length + " err=" + Try(t) + " s=" + t);
        return 0;
    }

    static string RepeatTok(string tok, int n)
    {
        var sb = new StringBuilder();
        for (int i = 0; i < n; i++) { if (i > 0) sb.Append(';'); sb.Append(tok); }
        return sb.ToString();
    }

    static int Try(string bypass)
    {
        IntPtr serverPtr = Marshal.StringToHGlobalAuto("127.0.0.1:1080");
        IntPtr bypassPtr = Marshal.StringToHGlobalAuto(bypass);
        int optSize = Marshal.SizeOf(typeof(Opt));
        IntPtr optsPtr = Marshal.AllocHGlobal(optSize * 3);
        var o0 = new Opt { m_Option = 1 }; o0.m_Value.m_Int = 3;
        var o1 = new Opt { m_Option = 2 }; o1.m_Value.m_StringPtr = serverPtr;
        var o2 = new Opt { m_Option = 3 }; o2.m_Value.m_StringPtr = bypassPtr;
        Marshal.StructureToPtr(o0, optsPtr, false);
        Marshal.StructureToPtr(o1, IntPtr.Add(optsPtr, optSize), false);
        Marshal.StructureToPtr(o2, IntPtr.Add(optsPtr, optSize * 2), false);
        var list = new List { dwSize = Marshal.SizeOf(typeof(List)), dwOptionCount = 3, options = optsPtr };
        IntPtr listPtr = Marshal.AllocHGlobal(list.dwSize);
        Marshal.StructureToPtr(list, listPtr, false);
        bool ok = InternetSetOption(IntPtr.Zero, 75, listPtr, list.dwSize);
        int err = ok ? 0 : Marshal.GetLastWin32Error();
        Marshal.FreeHGlobal(listPtr); Marshal.FreeHGlobal(optsPtr);
        Marshal.FreeHGlobal(serverPtr); Marshal.FreeHGlobal(bypassPtr);
        return err;
    }
}
