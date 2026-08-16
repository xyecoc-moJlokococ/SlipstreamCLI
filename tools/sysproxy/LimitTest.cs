using System;
using System.Runtime.InteropServices;

internal static class LimitTest
{
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    struct List { public int dwSize; public IntPtr szConnection; public int dwOptionCount; public int dwOptionError; public IntPtr options; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    struct Opt { public int m_Option; public Val m_Value; [StructLayout(LayoutKind.Explicit)] public struct Val { [FieldOffset(0)] public int m_Int; [FieldOffset(0)] public IntPtr m_StringPtr; } }
    [DllImport("wininet.dll", SetLastError = true, CharSet = CharSet.Auto)]
    static extern bool InternetSetOption(IntPtr h, int opt, IntPtr buf, int len);

    static int Main()
    {
        int[] lens = { 32, 64, 128, 200, 240, 255, 256, 300, 400, 500, 517, 600, 1024 };
        foreach (int n in lens)
        {
            string bypass = new string('a', n);
            Console.WriteLine("len=" + n + " err=" + Try(bypass));
        }
        // also try the real steam list
        string steam = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;ntc.party;*.steampowered.com;steampowered.com;*.steamcommunity.com;steamcommunity.com;*.steamgames.com;steamgames.com;*.steamusercontent.com;*.steamcontent.com;*.steamstatic.com;*.steam-chat.com;*.steamserver.net;*.akamaihd.net;*.steambroadcast.akamaized.net;*.cdn.cloudflare.steamstatic.com;*.cdn.akamai.steamstatic.com;<local>;127.0.0.1;::1";
        Console.WriteLine("steam-len=" + steam.Length + " err=" + Try(steam));
        // without stars
        string noStar = steam.Replace(".*", "").Replace("*.", "");
        Console.WriteLine("nostar-len=" + noStar.Length + " err=" + Try(noStar));
        return 0;
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
