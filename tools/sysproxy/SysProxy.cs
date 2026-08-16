// Tiny WinINET helper. Same INTERNET_PER_CONN_OPTION layout v2rayN uses.
// Usage:
//   SysProxy.exe set 127.0.0.1:1080 "localhost;127.0.0.1;<local>"
//   SysProxy.exe off
//   SysProxy.exe query
using System;
using System.Runtime.InteropServices;
using Microsoft.Win32;

internal static class SysProxy
{
    private const int INTERNET_OPTION_PER_CONNECTION_OPTION = 75;
    private const int INTERNET_OPTION_SETTINGS_CHANGED = 39;
    private const int INTERNET_OPTION_REFRESH = 37;
    private const int INTERNET_OPTION_PROXY_SETTINGS_CHANGED = 95;

    private const int INTERNET_PER_CONN_FLAGS = 1;
    private const int INTERNET_PER_CONN_PROXY_SERVER = 2;
    private const int INTERNET_PER_CONN_PROXY_BYPASS = 3;
    private const int INTERNET_PER_CONN_AUTOCONFIG_URL = 4;
    private const int INTERNET_PER_CONN_FLAGS_UI = 10;

    private const int PROXY_TYPE_DIRECT = 1;
    private const int PROXY_TYPE_PROXY = 2;
    private const int PROXY_TYPE_AUTO_PROXY_URL = 4;
    private const int PROXY_TYPE_AUTO_DETECT = 8;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct InternetPerConnOptionList
    {
        public int dwSize;
        public IntPtr szConnection;
        public int dwOptionCount;
        public int dwOptionError;
        public IntPtr options;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct InternetConnectionOption
    {
        public int m_Option;
        public InternetConnectionOptionValue m_Value;

        [StructLayout(LayoutKind.Explicit)]
        public struct InternetConnectionOptionValue
        {
            [FieldOffset(0)] public System.Runtime.InteropServices.ComTypes.FILETIME m_FileTime;
            [FieldOffset(0)] public int m_Int;
            [FieldOffset(0)] public IntPtr m_StringPtr;
        }
    }

    [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Auto)]
    private static extern bool InternetSetOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, int dwBufferLength);

    [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Auto)]
    private static extern bool InternetQueryOption(IntPtr hInternet, int dwOption, IntPtr lpBuffer, ref int lpdwBufferLength);

    private static int Main(string[] args)
    {
        if (args.Length == 0 || args[0] == "query")
        {
            Query(null);
            return 0;
        }
        if (args[0] == "off")
        {
            int e = Apply(null, false, "", "");
            Console.WriteLine("off-err=" + e);
            Notify();
            Query(null);
            return e == 0 ? 0 : 1;
        }
        if (args[0] == "set" && args.Length >= 2)
        {
            string server = args[1];
            string bypass = args.Length >= 3 ? args[2] : "localhost;127.0.0.1;<local>";
            WriteSimpleKeys(true, server, bypass);
            // Default LAN only. Applying to every Connections value creates
            // duplicate garbled adapter names and Settings then shows Off.
            int e = Apply(null, true, server, bypass);
            Console.WriteLine("default-err=" + e);
            Notify();
            Query(null);
            return e == 0 ? 0 : 1;
        }
        Console.WriteLine("usage: SysProxy.exe set host:port [bypass] | off | query");
        return 2;
    }

    private static void WriteSimpleKeys(bool enable, string server, string bypass)
    {
        using (var key = Registry.CurrentUser.OpenSubKey(
                   @"Software\Microsoft\Windows\CurrentVersion\Internet Settings", true))
        {
            if (key == null) return;
            key.SetValue("ProxyEnable", enable ? 1 : 0, RegistryValueKind.DWord);
            key.SetValue("ProxyServer", server ?? "", RegistryValueKind.String);
            key.SetValue("ProxyOverride", bypass ?? "", RegistryValueKind.String);
            key.SetValue("AutoDetect", 0, RegistryValueKind.DWord);
            try { key.DeleteValue("AutoConfigURL", false); } catch { }
        }
    }

    private static int Apply(string connection, bool enable, string server, string bypass)
    {
        // Try richest option set first, then fall back. Error 87 = bad combo/layout.
        int[][] sets =
        {
            new[] { 1, 10, 2, 3, 4 }, // FLAGS + FLAGS_UI + SERVER + BYPASS + PAC
            new[] { 1, 10, 2, 3 },    // no PAC
            new[] { 1, 2, 3, 4 },     // no FLAGS_UI
            new[] { 1, 2, 3 },        // v2rayN named-proxy minimum
            new[] { 1, 2 },
        };
        int last = 87;
        foreach (var opts in sets)
        {
            last = ApplyOnce(connection, enable, server, bypass, opts);
            Console.WriteLine("try opts=[" + string.Join(",", opts) + "] err=" + last
                              + " conn=" + (connection ?? "<lan>"));
            if (last == 0) return 0;
        }
        return last;
    }

    private static int ApplyOnce(string connection, bool enable, string server, string bypass, int[] optionIds)
    {
        int flags = PROXY_TYPE_DIRECT | (enable ? PROXY_TYPE_PROXY : 0);
        IntPtr serverPtr = Marshal.StringToHGlobalAuto(enable ? (server ?? "") : "");
        IntPtr bypassPtr = Marshal.StringToHGlobalAuto(bypass ?? "");
        IntPtr pacPtr = Marshal.StringToHGlobalAuto("");
        IntPtr namePtr = string.IsNullOrEmpty(connection)
            ? IntPtr.Zero
            : Marshal.StringToHGlobalAuto(connection);
        int optSize = Marshal.SizeOf(typeof(InternetConnectionOption));
        IntPtr optsPtr = Marshal.AllocHGlobal(optSize * optionIds.Length);
        try
        {
            for (int i = 0; i < optionIds.Length; i++)
            {
                var o = new InternetConnectionOption { m_Option = optionIds[i] };
                var v = new InternetConnectionOption.InternetConnectionOptionValue();
                if (optionIds[i] == INTERNET_PER_CONN_FLAGS || optionIds[i] == INTERNET_PER_CONN_FLAGS_UI)
                    v.m_Int = flags;
                else if (optionIds[i] == INTERNET_PER_CONN_PROXY_SERVER)
                    v.m_StringPtr = serverPtr;
                else if (optionIds[i] == INTERNET_PER_CONN_PROXY_BYPASS)
                    v.m_StringPtr = bypassPtr;
                else if (optionIds[i] == INTERNET_PER_CONN_AUTOCONFIG_URL)
                    v.m_StringPtr = pacPtr;
                o.m_Value = v;
                Marshal.StructureToPtr(o, IntPtr.Add(optsPtr, i * optSize), false);
            }

            var list = new InternetPerConnOptionList();
            list.dwSize = Marshal.SizeOf(typeof(InternetPerConnOptionList));
            list.szConnection = namePtr;
            list.dwOptionCount = optionIds.Length;
            list.dwOptionError = 0;
            list.options = optsPtr;
            IntPtr listPtr = Marshal.AllocHGlobal(list.dwSize);
            try
            {
                Marshal.StructureToPtr(list, listPtr, false);
                if (InternetSetOption(IntPtr.Zero, INTERNET_OPTION_PER_CONNECTION_OPTION, listPtr, list.dwSize))
                    return 0;
                return Marshal.GetLastWin32Error();
            }
            finally
            {
                Marshal.FreeHGlobal(listPtr);
            }
        }
        finally
        {
            Marshal.FreeHGlobal(optsPtr);
            Marshal.FreeHGlobal(serverPtr);
            Marshal.FreeHGlobal(bypassPtr);
            Marshal.FreeHGlobal(pacPtr);
            if (namePtr != IntPtr.Zero) Marshal.FreeHGlobal(namePtr);
        }
    }

    private static void Notify()
    {
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_SETTINGS_CHANGED, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_REFRESH, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, INTERNET_OPTION_PROXY_SETTINGS_CHANGED, IntPtr.Zero, 0);
    }

    private static void Query(string connection)
    {
        Console.WriteLine("sizeof OPTION=" + Marshal.SizeOf(typeof(InternetConnectionOption))
                          + " LIST=" + Marshal.SizeOf(typeof(InternetPerConnOptionList))
                          + " IntPtr=" + IntPtr.Size);
        int[] want = { INTERNET_PER_CONN_FLAGS, INTERNET_PER_CONN_FLAGS_UI,
                       INTERNET_PER_CONN_PROXY_SERVER, INTERNET_PER_CONN_PROXY_BYPASS,
                       INTERNET_PER_CONN_AUTOCONFIG_URL };
        int optSize = Marshal.SizeOf(typeof(InternetConnectionOption));
        IntPtr optsPtr = Marshal.AllocHGlobal(optSize * want.Length);
        IntPtr namePtr = string.IsNullOrEmpty(connection) ? IntPtr.Zero : Marshal.StringToHGlobalAuto(connection);
        try
        {
            for (int i = 0; i < want.Length; i++)
            {
                var o = new InternetConnectionOption { m_Option = want[i] };
                Marshal.StructureToPtr(o, IntPtr.Add(optsPtr, i * optSize), false);
            }
            var list = new InternetPerConnOptionList();
            list.dwSize = Marshal.SizeOf(typeof(InternetPerConnOptionList));
            list.szConnection = namePtr;
            list.dwOptionCount = want.Length;
            list.options = optsPtr;
            IntPtr listPtr = Marshal.AllocHGlobal(list.dwSize);
            try
            {
                Marshal.StructureToPtr(list, listPtr, false);
                int sz = list.dwSize;
                bool ok = InternetQueryOption(IntPtr.Zero, INTERNET_OPTION_PER_CONNECTION_OPTION, listPtr, ref sz);
                int err = ok ? 0 : Marshal.GetLastWin32Error();
                Console.WriteLine("query-ok=" + ok + " err=" + err + " sz=" + sz);
                if (!ok) return;
                for (int i = 0; i < want.Length; i++)
                {
                    var o = (InternetConnectionOption)Marshal.PtrToStructure(
                        IntPtr.Add(optsPtr, i * optSize), typeof(InternetConnectionOption));
                    if (o.m_Option == INTERNET_PER_CONN_FLAGS || o.m_Option == INTERNET_PER_CONN_FLAGS_UI)
                        Console.WriteLine("  opt=" + o.m_Option + " flags=0x" + o.m_Value.m_Int.ToString("X"));
                    else
                    {
                        string s = o.m_Value.m_StringPtr == IntPtr.Zero
                            ? ""
                            : Marshal.PtrToStringAuto(o.m_Value.m_StringPtr);
                        Console.WriteLine("  opt=" + o.m_Option + " str=[" + s + "]");
                    }
                }
            }
            finally { Marshal.FreeHGlobal(listPtr); }
        }
        finally
        {
            Marshal.FreeHGlobal(optsPtr);
            if (namePtr != IntPtr.Zero) Marshal.FreeHGlobal(namePtr);
        }
    }
}
