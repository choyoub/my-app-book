using System;
using System.ComponentModel;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Threading;
using Microsoft.Win32;
using Drawing = System.Drawing;
using Forms = System.Windows.Forms;
using RemoteClient = Durumari.Remote.RemoteClient;
using RemoteConnectionState = Durumari.Remote.ConnectionState;

namespace Durumari.Remote.Wpf;

public partial class MainWindow : Window
{
    private const string RegistryPath = @"Software\Durumari\Remote";
    private static readonly int[] ReconnectDelaysMs = [1_000, 2_000, 4_000, 8_000, 10_000];

    private readonly RemoteClient _client = new();
    private readonly DispatcherTimer _reconnectTimer = new();
    private readonly Drawing.Icon _appIcon;
    private readonly Forms.NotifyIcon _trayIcon;
    private RemoteWidgetWindow? _remoteWidget;
    private string _widgetSize = "normal";
    private string _widgetTheme = "system";
    private bool _widgetEffectsEnabled = true;
    private bool _widgetSoundEnabled = true;
    private bool _systemThemeSubscribed;
    private bool _uiReady;
    private bool _loadingSettings;
    private bool _connectionRequested;
    private bool _connectionAttemptInProgress;
    private bool _automaticReconnectPaused;
    private bool _widgetHiddenByUser;
    private bool _hasWidgetPosition;
    private bool _exiting;
    private int _reconnectAttempt;
    private double _widgetLeft;
    private double _widgetTop;

    public MainWindow()
    {
        InitializeComponent();

        var executablePath = Environment.ProcessPath ?? typeof(MainWindow).Assembly.Location;
        _appIcon = Drawing.Icon.ExtractAssociatedIcon(executablePath)
            ?? (Drawing.Icon)Drawing.SystemIcons.Application.Clone();
        _trayIcon = new Forms.NotifyIcon
        {
            Icon = _appIcon,
            Text = "두루마리 리모콘 · 연결 안 됨",
            Visible = true,
        };
        _trayIcon.MouseUp += OnTrayIconMouseUp;

        _reconnectTimer.Tick += async (_, _) =>
        {
            _reconnectTimer.Stop();
            await TryConnectAsync();
        };
        _client.StateChanged += OnClientStateChanged;

        ApplySystemTheme();
        LoadSettings();
        UpdatePortInputState();
        UpdateConnectionState();
        _uiReady = true;

        try
        {
            SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;
            _systemThemeSubscribed = true;
        }
        catch
        {
            // 시스템 테마 이벤트를 제공하지 않는 환경에서는 시작 시 테마를 유지합니다.
        }

        Closing += OnWindowClosing;
        Closed += OnWindowClosed;
    }

    private void OnUserPreferenceChanged(object? sender, UserPreferenceChangedEventArgs e) =>
        Dispatcher.BeginInvoke(ApplySystemTheme);

    private void ApplySystemTheme()
    {
        var dark = IsDarkMode();
        SetBrush("Mica", dark ? "#202020" : "#F9F9F9");
        SetBrush("Titlebar", dark ? "#CC1C1C1C" : "#CCF3F3F3");
        SetBrush("Card", dark ? "#2D2D2D" : "#FFFFFF");
        SetBrush("Input", dark ? "#323232" : "#F3F3F3");
        SetBrush("FocusedInput", dark ? "#3D3D3D" : "#FFFFFFFF");
        SetBrush("Text", dark ? "#F5F5F5" : "#1F1F1F");
        SetBrush("Muted", dark ? "#A3A3A3" : "#5F5F5F");
        SetBrush("Border", dark ? "#14FFFFFF" : "#17000000");
        SetBrush("PortInput", dark ? "#80323232" : "#80E5E5E5");
        SetBrush("PortText", dark ? "#FF737373" : "#FFA3A3A3");
        SetBrush("PortBorder", dark ? "#14FFFFFF" : "#17000000");
        SetBrush("SliderTrack", dark ? "#444444" : "#D1D1D1");
        SetBrush("SliderThumbStroke", dark ? "#FF1C1C1C" : "#FFFFFFFF");
        SetBrush("TitleMuted", dark ? "#FFA3A3A3" : "#FF737373");
        SetBrush("CaptionText", dark ? "#FFF5F5F5" : "#FF1F1F1F");
        var accent = GetWindowsThemeColor();
        SetBrush("Accent", accent);
        SetBrush("AccentSubtle", Color.FromArgb(0x40, accent.R, accent.G, accent.B));
        _remoteWidget?.SetTheme(_widgetTheme, dark, accent);
        UpdatePortInputState();
        UpdateConnectionState();
    }

    private void SetBrush(string key, string hex) =>
        Resources[key] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(hex));

    private void SetBrush(string key, Color color) =>
        Resources[key] = new SolidColorBrush(color);

    private static Color GetWindowsThemeColor()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\DWM");
        if (key?.GetValue("ColorizationColor") is int raw)
        {
            var argb = unchecked((uint)raw);
            return Color.FromRgb((byte)(argb >> 16), (byte)(argb >> 8), (byte)argb);
        }

        var fallback = SystemParameters.WindowGlassColor;
        return Color.FromRgb(fallback.R, fallback.G, fallback.B);
    }

    private void DefaultPortToggle_Changed(object sender, RoutedEventArgs e)
    {
        UpdatePortInputState();
        SaveSettingsIfReady();
    }

    private void ConnectionSetting_LostKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e) =>
        SaveSettingsIfReady();

    private void WidgetSizeOption_Checked(object sender, RoutedEventArgs e)
    {
        if (sender is not RadioButton { Tag: string size }) return;
        _widgetSize = size;
        _remoteWidget?.SetSize(_widgetSize);
        SaveSettingsIfReady();
    }

    private void WidgetOpacitySlider_ValueChanged(
        object sender,
        RoutedPropertyChangedEventArgs<double> e)
    {
        var opacity = (int)Math.Round(e.NewValue);
        if (WidgetOpacityText is not null) WidgetOpacityText.Text = $"{opacity}%";
        _remoteWidget?.SetWidgetOpacity(opacity);
        SaveSettingsIfReady();
    }

    private void WidgetThemeCombo_SelectionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (WidgetThemeCombo?.SelectedItem is not ComboBoxItem { Tag: string theme }) return;
        _widgetTheme = theme;
        _remoteWidget?.SetTheme(_widgetTheme, IsDarkMode(), GetWindowsThemeColor());
        SaveSettingsIfReady();
    }

    private void WidgetFeedbackOption_Changed(object sender, RoutedEventArgs e)
    {
        _widgetEffectsEnabled = WidgetEffectCheckBox.IsChecked == true;
        _widgetSoundEnabled = WidgetSoundCheckBox.IsChecked == true;
        _remoteWidget?.SetFeedbackOptions(_widgetEffectsEnabled, _widgetSoundEnabled);
        SaveSettingsIfReady();
    }

    private async void ConnectionButton_Click(object sender, RoutedEventArgs e) =>
        await ToggleConnectionAsync();

    private async Task ToggleConnectionAsync()
    {
        if (_connectionRequested ||
            _client.State is RemoteConnectionState.Connected or RemoteConnectionState.Connecting)
        {
            StopAutomaticReconnect();
            _widgetHiddenByUser = false;
            HideRemoteWidget();
            await _client.DisconnectAsync();
            return;
        }

        SaveSettings();
        _connectionRequested = true;
        _automaticReconnectPaused = false;
        _reconnectAttempt = 0;
        await TryConnectAsync();
    }

    private async Task TryConnectAsync()
    {
        if (!_connectionRequested || _connectionAttemptInProgress || _exiting) return;
        if (!TryGetEndpoint(out var host, out var port, out var validationMessage))
        {
            StopAutomaticReconnect();
            ApplyConnectionPresentation(RemoteConnectionState.Error, validationMessage, connectionActive: false);
            return;
        }

        _reconnectTimer.Stop();
        _connectionAttemptInProgress = true;
        UpdateConnectionState();
        try
        {
            await _client.ConnectAsync(host, port);
        }
        finally
        {
            _connectionAttemptInProgress = false;
            if (_connectionRequested && !_exiting && _client.State != RemoteConnectionState.Connected)
            {
                ScheduleReconnect();
            }
            UpdateConnectionState();
        }
    }

    private bool TryGetEndpoint(
        out string host,
        out int port,
        out string validationMessage)
    {
        host = HostInput.Text.Trim();
        if (string.IsNullOrWhiteSpace(host))
        {
            port = RemoteClient.DefaultPort;
            validationMessage = "Android Tailscale 도메인을 입력하세요.";
            return false;
        }

        if (DefaultPortToggle.IsChecked != false)
        {
            port = RemoteClient.DefaultPort;
            validationMessage = string.Empty;
            return true;
        }

        if (!int.TryParse(PortNumberInput.Text, out port) || port is < 1 or > 65535)
        {
            validationMessage = "포트 번호는 1~65535 사이여야 합니다.";
            return false;
        }

        validationMessage = string.Empty;
        return true;
    }

    private void ScheduleReconnect()
    {
        if (!_connectionRequested ||
            _connectionAttemptInProgress ||
            _automaticReconnectPaused ||
            _exiting ||
            _reconnectTimer.IsEnabled)
        {
            return;
        }

        if (_reconnectAttempt >= ReconnectDelaysMs.Length)
        {
            _automaticReconnectPaused = true;
            UpdateConnectionState();
            return;
        }

        _reconnectTimer.Interval = TimeSpan.FromMilliseconds(ReconnectDelaysMs[_reconnectAttempt]);
        _reconnectAttempt++;
        _reconnectTimer.Start();
    }

    private void StopAutomaticReconnect()
    {
        _connectionRequested = false;
        _reconnectTimer.Stop();
        _automaticReconnectPaused = false;
        _reconnectAttempt = 0;
    }

    private async Task SendWidgetCommandAsync(Func<Task> sendCommand)
    {
        if (_client.State == RemoteConnectionState.Connected)
        {
            await sendCommand();
        }
    }

    private void OnClientStateChanged(object? sender, EventArgs e)
    {
        if (Dispatcher.HasShutdownStarted) return;
        if (Dispatcher.CheckAccess()) UpdateConnectionState();
        else Dispatcher.BeginInvoke(UpdateConnectionState);
    }

    private void UpdateConnectionState()
    {
        if (ConnectionButton is null || _exiting) return;

        if (_client.State == RemoteConnectionState.Connected)
        {
            _reconnectTimer.Stop();
            _automaticReconnectPaused = false;
            _reconnectAttempt = 0;
        }
        else if (_connectionRequested &&
                 !_connectionAttemptInProgress &&
                 !_automaticReconnectPaused &&
                 _client.State is RemoteConnectionState.Disconnected or RemoteConnectionState.Error)
        {
            ScheduleReconnect();
        }

        var disconnectedWhileRequested =
            _connectionRequested &&
            !_connectionAttemptInProgress &&
            _client.State is RemoteConnectionState.Disconnected or RemoteConnectionState.Error;
        var reconnectWaiting = disconnectedWhileRequested && !_automaticReconnectPaused;
        var reconnectPaused = disconnectedWhileRequested && _automaticReconnectPaused;
        var presentationState = reconnectWaiting
            ? RemoteConnectionState.Connecting
            : reconnectPaused
                ? RemoteConnectionState.Disconnected
                : _client.State;
        var text = reconnectWaiting
            ? "재연결 대기 중"
            : reconnectPaused
                ? "연결 끊김"
                : presentationState switch
                {
                    RemoteConnectionState.Connected => "연결 성공",
                    RemoteConnectionState.Connecting => "연결 대상 검색 중",
                    RemoteConnectionState.Error => "연결 오류",
                    _ => "연결 안 됨",
                };
        var connectionActive =
            _connectionRequested ||
            _client.State is RemoteConnectionState.Connected or RemoteConnectionState.Connecting;

        ApplyConnectionPresentation(presentationState, text, connectionActive);

        if (_client.State == RemoteConnectionState.Connected)
        {
            if (!_widgetHiddenByUser) ShowRemoteWidget();
        }
        else
        {
            HideRemoteWidget();
        }
    }

    private void ApplyConnectionPresentation(
        RemoteConnectionState state,
        string text,
        bool connectionActive)
    {
        var (dotColor, textBrush) = state switch
        {
            RemoteConnectionState.Connected =>
                (Color.FromRgb(0x00, 0xC8, 0x53), new SolidColorBrush(Color.FromRgb(0x00, 0xC8, 0x53))),
            RemoteConnectionState.Connecting =>
                (Color.FromRgb(0xCA, 0x8A, 0x04), new SolidColorBrush(Color.FromRgb(0xCA, 0x8A, 0x04))),
            RemoteConnectionState.Error =>
                (Color.FromRgb(0xE8, 0x11, 0x55), new SolidColorBrush(Color.FromRgb(0xE8, 0x11, 0x55))),
            _ =>
                (Color.FromRgb(0x73, 0x73, 0x73), (Brush)Resources["Muted"]),
        };

        ConnectionStatusDot.Fill = new SolidColorBrush(dotColor);
        ConnectionStatusText.Foreground = textBrush;
        ConnectionStatusText.Text = text;
        ConnectionButtonText.Text = state == RemoteConnectionState.Connecting
            ? "연결 진행..."
            : connectionActive
                ? "연결 끊기"
                : "연결";
        ConnectionButton.Background =
            connectionActive && state != RemoteConnectionState.Connecting
                ? new SolidColorBrush(Color.FromRgb(0xE8, 0x11, 0x55))
                : (Brush)Resources["Accent"];

        HostInput.IsEnabled = !connectionActive;
        DefaultPortToggle.IsEnabled = !connectionActive;
        UpdatePortInputState();
        SetTrayTooltip($"두루마리 리모콘 · {text}");
    }

    private void SetTrayTooltip(string text) =>
        _trayIcon.Text = text.Length <= 63 ? text : text[..63];

    private RemoteWidgetWindow EnsureRemoteWidget()
    {
        if (_remoteWidget is not null) return _remoteWidget;

        _remoteWidget = new RemoteWidgetWindow
        {
            PreviousRequested = () => SendWidgetCommandAsync(_client.PreviousPageAsync),
            NextRequested = () => SendWidgetCommandAsync(_client.NextPageAsync),
        };
        _remoteWidget.LocationChanged += (_, _) =>
        {
            if (_remoteWidget is not { IsVisible: true } widget) return;
            _widgetLeft = widget.Left;
            _widgetTop = widget.Top;
            _hasWidgetPosition = true;
            SaveSettingsIfReady();
        };
        return _remoteWidget;
    }

    private void ShowRemoteWidget()
    {
        var widget = EnsureRemoteWidget();
        widget.SetSize(_widgetSize);
        widget.SetTheme(_widgetTheme, IsDarkMode(), GetWindowsThemeColor());
        widget.SetFeedbackOptions(_widgetEffectsEnabled, _widgetSoundEnabled);
        widget.SetWidgetOpacity((int)Math.Round(WidgetOpacitySlider.Value));
        widget.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));

        if (_hasWidgetPosition &&
            IsWidgetPositionVisible(widget, _widgetLeft, _widgetTop))
        {
            widget.Left = _widgetLeft;
            widget.Top = _widgetTop;
        }
        else
        {
            PositionWidgetNextToMainWindow(widget);
        }

        if (!widget.IsVisible) widget.Show();
    }

    private static bool IsWidgetPositionVisible(
        RemoteWidgetWindow widget,
        double left,
        double top)
    {
        var width = Math.Max(widget.DesiredSize.Width, 48);
        var height = Math.Max(widget.DesiredSize.Height, 48);
        var widgetBounds = new Rect(left, top, width, height);
        var virtualScreen = new Rect(
            SystemParameters.VirtualScreenLeft,
            SystemParameters.VirtualScreenTop,
            SystemParameters.VirtualScreenWidth,
            SystemParameters.VirtualScreenHeight);
        var visibleBounds = Rect.Intersect(widgetBounds, virtualScreen);
        return !visibleBounds.IsEmpty &&
               visibleBounds.Width >= Math.Min(48, width) &&
               visibleBounds.Height >= Math.Min(48, height);
    }

    private void PositionWidgetNextToMainWindow(RemoteWidgetWindow widget)
    {
        var width = Math.Max(widget.DesiredSize.Width, 48);
        var height = Math.Max(widget.DesiredSize.Height, 48);
        var virtualLeft = SystemParameters.VirtualScreenLeft;
        var virtualTop = SystemParameters.VirtualScreenTop;
        var virtualRight = virtualLeft + SystemParameters.VirtualScreenWidth;
        var virtualBottom = virtualTop + SystemParameters.VirtualScreenHeight;

        _widgetLeft = Math.Clamp(
            Left + Width - width - 24,
            virtualLeft,
            Math.Max(virtualLeft, virtualRight - width));
        _widgetTop = Math.Clamp(
            Top + 68,
            virtualTop,
            Math.Max(virtualTop, virtualBottom - height));
        _hasWidgetPosition = true;
        widget.Left = _widgetLeft;
        widget.Top = _widgetTop;
        SaveSettingsIfReady();
    }

    private void HideRemoteWidget()
    {
        if (_remoteWidget?.IsVisible == true) _remoteWidget.Hide();
    }

    private void ToggleRemoteWidget()
    {
        if (_client.State != RemoteConnectionState.Connected) return;
        if (_remoteWidget?.IsVisible == true)
        {
            _widgetHiddenByUser = true;
            HideRemoteWidget();
        }
        else
        {
            _widgetHiddenByUser = false;
            ShowRemoteWidget();
        }
    }

    private void PortNumberInput_GotKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        if (DefaultPortToggle?.IsChecked != false) return;
        PortNumberInput.Background = (Brush)Resources["FocusedInput"];
        PortNumberInput.Foreground = (Brush)Resources["Text"];
        PortNumberInput.BorderBrush = (Brush)Resources["AccentSubtle"];
    }

    private void PortNumberInput_LostKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
    {
        UpdatePortInputState();
        SaveSettingsIfReady();
    }

    private void UpdatePortInputState()
    {
        if (DefaultPortToggle is null || PortNumberInput is null) return;

        var connectionActive =
            _connectionRequested ||
            _client.State is RemoteConnectionState.Connected or RemoteConnectionState.Connecting;
        var useDefaultPort = DefaultPortToggle.IsChecked != false;
        var manualInputEnabled = !useDefaultPort && !connectionActive;
        PortNumberInput.IsHitTestVisible = manualInputEnabled;
        PortNumberInput.Focusable = manualInputEnabled;
        PortNumberInput.Cursor = manualInputEnabled ? Cursors.IBeam : Cursors.Arrow;
        PortNumberInput.Opacity = useDefaultPort ? 0.60 : 1.0;
        var isManualInputFocused = manualInputEnabled && PortNumberInput.IsKeyboardFocused;
        PortNumberInput.Background = (Brush)Resources[
            useDefaultPort ? "PortInput" : isManualInputFocused ? "FocusedInput" : "Input"];
        PortNumberInput.Foreground = (Brush)Resources[useDefaultPort ? "PortText" : "Text"];
        PortNumberInput.BorderBrush = (Brush)Resources[
            useDefaultPort ? "PortBorder" : isManualInputFocused ? "AccentSubtle" : "Border"];

        if (useDefaultPort) PortNumberInput.Text = RemoteClient.DefaultPort.ToString();
    }

    private void LoadSettings()
    {
        _loadingSettings = true;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RegistryPath);
            HostInput.Text = key?.GetValue("Host") as string ?? string.Empty;
            DefaultPortToggle.IsChecked = ReadInt(key, "UseDefaultPort", 1) != 0;
            PortNumberInput.Text = Math.Clamp(
                ReadInt(key, "Port", RemoteClient.DefaultPort),
                1,
                65535).ToString();

            var opacity = Math.Clamp(ReadInt(key, "WidgetOpacity", 95), 20, 100);
            WidgetOpacitySlider.Value = opacity;
            WidgetOpacityText.Text = $"{opacity}%";

            _widgetSize = key?.GetValue("WidgetSizeName") as string ?? "normal";
            (_widgetSize switch
            {
                "small" => SmallWidgetSizeOption,
                "large" => LargeWidgetSizeOption,
                _ => NormalWidgetSizeOption,
            }).IsChecked = true;

            _widgetTheme = key?.GetValue("WidgetTheme") as string ?? ThemeFromLegacySetting(key);
            foreach (var item in WidgetThemeCombo.Items.OfType<ComboBoxItem>())
            {
                if (Equals(item.Tag, _widgetTheme))
                {
                    WidgetThemeCombo.SelectedItem = item;
                    break;
                }
            }

            _widgetEffectsEnabled = ReadInt(key, "WidgetEffectsEnabled", 1) != 0;
            _widgetSoundEnabled = ReadInt(key, "WidgetSoundEnabled", 1) != 0;
            WidgetEffectCheckBox.IsChecked = _widgetEffectsEnabled;
            WidgetSoundCheckBox.IsChecked = _widgetSoundEnabled;

            if (key?.GetValue("WidgetX") is int x && key.GetValue("WidgetY") is int y)
            {
                _widgetLeft = x;
                _widgetTop = y;
                _hasWidgetPosition = true;
            }
        }
        finally
        {
            _loadingSettings = false;
        }
    }

    private static string ThemeFromLegacySetting(RegistryKey? key) =>
        ReadInt(key, "WidgetThemeMode", 0) switch
        {
            1 => "light",
            2 => "dark",
            _ => "system",
        };

    private static int ReadInt(RegistryKey? key, string name, int fallback)
    {
        var value = key?.GetValue(name);
        return value is int number ? number : fallback;
    }

    private void SaveSettingsIfReady()
    {
        if (_uiReady && !_loadingSettings) SaveSettings();
    }

    private void SaveSettings()
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RegistryPath);
            key.SetValue("Host", HostInput.Text.Trim());
            key.SetValue("UseDefaultPort", DefaultPortToggle.IsChecked == true ? 1 : 0);
            if (int.TryParse(PortNumberInput.Text, out var port))
            {
                key.SetValue("Port", Math.Clamp(port, 1, 65535));
            }
            key.SetValue("WidgetOpacity", (int)Math.Round(WidgetOpacitySlider.Value));
            key.SetValue("WidgetSizeName", _widgetSize);
            key.SetValue("WidgetTheme", _widgetTheme);
            key.SetValue("WidgetEffectsEnabled", _widgetEffectsEnabled ? 1 : 0);
            key.SetValue("WidgetSoundEnabled", _widgetSoundEnabled ? 1 : 0);
            if (_hasWidgetPosition)
            {
                key.SetValue("WidgetX", (int)Math.Round(_widgetLeft));
                key.SetValue("WidgetY", (int)Math.Round(_widgetTop));
            }
        }
        catch
        {
            // 설정 저장 실패가 리모콘 동작을 중단시키지 않도록 합니다.
        }
    }

    private async void OnTrayIconMouseUp(object? sender, Forms.MouseEventArgs e)
    {
        if (e.Button != Forms.MouseButtons.Right) return;
        if (!Dispatcher.CheckAccess())
        {
            _ = Dispatcher.BeginInvoke(() => OnTrayIconMouseUp(sender, e));
            return;
        }

        var command = NativeTrayMenu.Show(
            new WindowInteropHelper(this).Handle,
            _remoteWidget?.IsVisible == true,
            _client.State == RemoteConnectionState.Connected,
            _connectionRequested ||
            _client.State is RemoteConnectionState.Connected or RemoteConnectionState.Connecting);

        switch (command)
        {
            case NativeTrayMenu.OpenCommand:
                OpenMainWindow();
                break;
            case NativeTrayMenu.RemoteCommand:
                ToggleRemoteWidget();
                break;
            case NativeTrayMenu.ConnectionCommand:
                await ToggleConnectionAsync();
                break;
            case NativeTrayMenu.ExitCommand:
                await ExitApplicationAsync();
                break;
        }
    }

    internal void OpenMainWindow()
    {
        Show();
        WindowState = WindowState.Normal;
        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    private void OnWindowClosing(object? sender, CancelEventArgs e)
    {
        SaveSettings();
        if (_exiting) return;
        if (_connectionRequested ||
            _client.State is RemoteConnectionState.Connected or RemoteConnectionState.Connecting)
        {
            e.Cancel = true;
            Hide();
        }
    }

    private void OnWindowClosed(object? sender, EventArgs e)
    {
        _exiting = true;
        if (_systemThemeSubscribed)
        {
            SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
            _systemThemeSubscribed = false;
        }
        _client.StateChanged -= OnClientStateChanged;
        _reconnectTimer.Stop();
        _trayIcon.Visible = false;
        _trayIcon.MouseUp -= OnTrayIconMouseUp;
        _trayIcon.Dispose();
        _remoteWidget?.Close();
        _client.DisposeAsync().AsTask().GetAwaiter().GetResult();
        _appIcon.Dispose();
    }

    private async Task ExitApplicationAsync()
    {
        _exiting = true;
        StopAutomaticReconnect();
        HideRemoteWidget();
        await _client.DisconnectAsync();
        Close();
    }

    private static bool IsDarkMode()
    {
        using var key = Registry.CurrentUser.OpenSubKey(
            @"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return key?.GetValue("AppsUseLightTheme") is int value && value == 0;
    }

    private void TitleBar_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.LeftButton == MouseButtonState.Pressed) DragMove();
    }

    private void Minimize_Click(object sender, RoutedEventArgs e)
    {
        WindowState = WindowState.Minimized;
        Hide();
    }

    private void Close_Click(object sender, RoutedEventArgs e) => Close();
}
