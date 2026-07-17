using System;
using System.Runtime.InteropServices;

namespace Durumari.Remote.Wpf;

internal static class NativeTrayMenu
{
    public const uint OpenCommand = 1;
    public const uint RemoteCommand = 2;
    public const uint ConnectionCommand = 3;
    public const uint ExitCommand = 4;

    private const uint MfString = 0x0000;
    private const uint MfGrayEd = 0x0001;
    private const uint MfSeparator = 0x0800;
    private const uint TpmRightButton = 0x0002;
    private const uint TpmRightAlign = 0x0008;
    private const uint TpmBottomAlign = 0x0020;
    private const uint TpmReturnCmd = 0x0100;
    private const uint WmNull = 0x0000;

    public static uint Show(
        IntPtr ownerHandle,
        bool remoteVisible,
        bool remoteEnabled,
        bool connectionActive)
    {
        var menuHandle = CreatePopupMenu();
        if (menuHandle == IntPtr.Zero) return 0;

        try
        {
            AppendMenu(menuHandle, MfString, OpenCommand, "열기");
            AppendMenu(
                menuHandle,
                MfString | (remoteEnabled ? 0 : MfGrayEd),
                RemoteCommand,
                remoteVisible ? "리모콘 숨김" : "리모콘 보임");
            AppendMenu(
                menuHandle,
                MfString,
                ConnectionCommand,
                connectionActive ? "끊기" : "연결");
            AppendMenu(menuHandle, MfSeparator, 0, null);
            AppendMenu(menuHandle, MfString, ExitCommand, "종료");

            if (!GetCursorPos(out var cursorPosition)) return 0;

            SetForegroundWindow(ownerHandle);
            var command = TrackPopupMenuEx(
                menuHandle,
                TpmRightButton | TpmRightAlign | TpmBottomAlign | TpmReturnCmd,
                cursorPosition.X,
                cursorPosition.Y,
                ownerHandle,
                IntPtr.Zero);
            PostMessage(ownerHandle, WmNull, IntPtr.Zero, IntPtr.Zero);
            return command;
        }
        finally
        {
            DestroyMenu(menuHandle);
        }
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr CreatePopupMenu();

    [DllImport("user32.dll", EntryPoint = "AppendMenuW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AppendMenu(
        IntPtr menuHandle,
        uint flags,
        uint itemId,
        string? itemText);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyMenu(IntPtr menuHandle);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetCursorPos(out NativePoint point);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr windowHandle);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint TrackPopupMenuEx(
        IntPtr menuHandle,
        uint flags,
        int x,
        int y,
        IntPtr ownerHandle,
        IntPtr parameters);

    [DllImport("user32.dll", EntryPoint = "PostMessageW", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool PostMessage(
        IntPtr windowHandle,
        uint message,
        IntPtr wParam,
        IntPtr lParam);

    [StructLayout(LayoutKind.Sequential)]
    private readonly struct NativePoint
    {
        public readonly int X;
        public readonly int Y;
    }
}
