using Microsoft.Win32;

namespace Durumari.Remote;

internal sealed class MainForm : Form
{
    private const string RegistryPath = @"Software\Durumari\Remote";
    private static readonly int[] ReconnectDelaysMs = [1_000, 2_000, 4_000, 8_000, 10_000];

    private readonly RemoteClient _client = new();
    private readonly System.Windows.Forms.Timer _reconnectTimer = new();
    private readonly Icon _appIcon;
    private readonly TextBox _hostInput = new();
    private readonly CheckBox _defaultPortCheck = new();
    private readonly NumericUpDown _portInput = new();
    private readonly TrackBar _opacityInput = new();
    private readonly Label _opacityValueLabel = new();
    private readonly ComboBox _themeInput = new();
    private readonly StatusDot _statusDot = new();
    private readonly Label _statusLabel = new();
    private readonly Button _connectButton = new();
    private readonly NotifyIcon _trayIcon;
    private readonly ToolStripMenuItem _remoteMenuItem;
    private readonly ToolStripMenuItem _connectionMenuItem;
    private readonly RemoteForm _remoteForm;
    private bool _exiting;
    private bool _loadingSettings;
    private bool _hasWidgetPosition;
    private bool _connectionRequested;
    private bool _connectionAttemptInProgress;
    private bool _automaticReconnectPaused;
    private bool _widgetActivated;
    private bool _widgetHiddenByUser;
    private int _reconnectAttempt;
    private Point _widgetPosition;

    public MainForm()
    {
        _appIcon = Icon.ExtractAssociatedIcon(Application.ExecutablePath) ?? (Icon)SystemIcons.Application.Clone();
        Text = "두루마리 Windows 리모콘";
        Icon = _appIcon;
        ClientSize = new Size(520, 425);
        MinimumSize = new Size(500, 455);
        StartPosition = FormStartPosition.CenterScreen;

        var menu = new ContextMenuStrip();
        var openItem = menu.Items.Add("열기");
        _remoteMenuItem = (ToolStripMenuItem)menu.Items.Add("리모콘 보임");
        _connectionMenuItem = (ToolStripMenuItem)menu.Items.Add("연결");
        menu.Items.Add(new ToolStripSeparator());
        var exitItem = menu.Items.Add("종료");

        _trayIcon = new NotifyIcon
        {
            Icon = _appIcon,
            Text = "두루마리 리모콘 · 연결 안 됨",
            ContextMenuStrip = menu,
            Visible = true,
        };
        // 트레이 아이콘 자체 클릭에는 의도적으로 동작을 연결하지 않습니다.
        openItem.Click += (_, _) => OpenMainWindow();
        _remoteMenuItem.Click += (_, _) => ToggleRemoteWindow();
        _connectionMenuItem.Click += async (_, _) => await ToggleConnectionAsync();
        exitItem.Click += async (_, _) => await ExitApplicationAsync();

        _remoteForm = new RemoteForm(
            () => SendWidgetCommandAsync(_client.PreviousPageAsync),
            () => SendWidgetCommandAsync(_client.NextPageAsync));
        _remoteForm.VisibleChanged += (_, _) =>
        {
            UpdateRemoteMenuText();
            if (!_remoteForm.Visible) SaveSettings();
        };
        _remoteForm.LocationChanged += (_, _) =>
        {
            if (!_remoteForm.Visible) return;
            _widgetPosition = _remoteForm.Location;
            _hasWidgetPosition = true;
        };

        BuildLayout();
        LoadSettings();
        _reconnectTimer.Tick += async (_, _) =>
        {
            _reconnectTimer.Stop();
            await TryConnectAsync();
        };
        _client.StateChanged += (_, _) => UpdateConnectionStateSafe();
        UpdateConnectionStateSafe();

        Resize += (_, _) =>
        {
            if (WindowState == FormWindowState.Minimized) Hide();
        };
        FormClosing += OnFormClosing;
        FormClosed += (_, _) =>
        {
            _trayIcon.Visible = false;
            _trayIcon.Dispose();
            _reconnectTimer.Dispose();
            _remoteForm.Dispose();
            _client.DisposeAsync().AsTask().GetAwaiter().GetResult();
            _appIcon.Dispose();
        };
    }

    private void BuildLayout()
    {
        var title = new Label
        {
            Text = "Android 두루마리 연결",
            Font = new Font(Font.FontFamily, 17f, FontStyle.Bold),
            AutoSize = true,
            Dock = DockStyle.Fill,
            TextAlign = ContentAlignment.MiddleLeft,
        };
        var description = new Label
        {
            Text = "Android 기기의 Tailscale 도메인과 리모콘 설정의 포트를 입력하세요.",
            AutoSize = true,
            ForeColor = SystemColors.GrayText,
            Dock = DockStyle.Fill,
        };

        _hostInput.Dock = DockStyle.Fill;
        _hostInput.PlaceholderText = "예: phone.tailnet-name.ts.net";
        _hostInput.AccessibleName = "Android Tailscale 도메인";

        _defaultPortCheck.Text = $"기본 포트 사용 ({RemoteClient.DefaultPort})";
        _defaultPortCheck.AutoSize = true;
        _defaultPortCheck.CheckedChanged += (_, _) => _portInput.Enabled = !_defaultPortCheck.Checked;

        _portInput.Minimum = 1;
        _portInput.Maximum = 65535;
        _portInput.Value = RemoteClient.DefaultPort;
        _portInput.Width = 110;
        _portInput.Anchor = AnchorStyles.Left;
        _portInput.AccessibleName = "수동 포트";

        var statusPanel = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false,
            Dock = DockStyle.Fill,
            Padding = new Padding(0, 6, 0, 0),
        };
        _statusDot.Margin = new Padding(0, 1, 9, 0);
        _statusLabel.AutoSize = true;
        _statusLabel.Margin = new Padding(0, 1, 0, 0);
        statusPanel.Controls.AddRange([_statusDot, _statusLabel]);

        _connectButton.Text = "연결";
        _connectButton.Size = new Size(120, 44);
        _connectButton.Anchor = AnchorStyles.Right;
        _connectButton.Click += async (_, _) => await ToggleConnectionAsync();

        _opacityInput.Minimum = 30;
        _opacityInput.Maximum = 100;
        _opacityInput.Value = 100;
        _opacityInput.TickFrequency = 10;
        _opacityInput.SmallChange = 5;
        _opacityInput.LargeChange = 10;
        _opacityInput.Width = 220;
        _opacityInput.AutoSize = false;
        _opacityInput.Height = 36;
        _opacityInput.AccessibleName = "위젯 투명도";
        _opacityValueLabel.AutoSize = true;
        _opacityValueLabel.Margin = new Padding(8, 9, 0, 0);
        var opacityPanel = new FlowLayoutPanel
        {
            AutoSize = true,
            FlowDirection = FlowDirection.LeftToRight,
            WrapContents = false,
            Dock = DockStyle.Fill,
        };
        opacityPanel.Controls.AddRange([_opacityInput, _opacityValueLabel]);
        _opacityInput.ValueChanged += (_, _) =>
        {
            _opacityValueLabel.Text = $"{_opacityInput.Value}%";
            _remoteForm.SetWidgetOpacity(_opacityInput.Value);
            if (!_loadingSettings) SaveSettings();
        };

        _themeInput.DropDownStyle = ComboBoxStyle.DropDownList;
        _themeInput.Width = 180;
        _themeInput.Anchor = AnchorStyles.Left;
        _themeInput.AccessibleName = "위젯 테마";
        _themeInput.Items.AddRange(["시스템 (기본값)", "라이트", "다크"]);
        _themeInput.SelectedIndexChanged += (_, _) =>
        {
            if (_themeInput.SelectedIndex < 0) return;
            _remoteForm.SetThemeMode((WidgetThemeMode)_themeInput.SelectedIndex);
            if (!_loadingSettings) SaveSettings();
        };

        var remoteHint = new Label
        {
            Text = "연결 후 트레이 메뉴의 ‘리모콘 보임’을 선택하면 작은 리모콘 창에서 좌우 방향키를 사용할 수 있습니다.",
            AutoSize = true,
            MaximumSize = new Size(450, 0),
            ForeColor = SystemColors.GrayText,
            Dock = DockStyle.Fill,
        };

        var layout = new TableLayoutPanel
        {
            Dock = DockStyle.Fill,
            Padding = new Padding(28, 24, 28, 24),
            ColumnCount = 2,
            RowCount = 10,
        };
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 145));
        layout.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 44));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 36));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 38));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 40));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 46));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 42));
        layout.RowStyles.Add(new RowStyle(SizeType.Absolute, 52));
        layout.RowStyles.Add(new RowStyle(SizeType.Percent, 100));

        layout.Controls.Add(title, 0, 0);
        layout.SetColumnSpan(title, 2);
        layout.Controls.Add(description, 0, 1);
        layout.SetColumnSpan(description, 2);
        layout.Controls.Add(CreateFieldLabel("Tailscale 도메인"), 0, 2);
        layout.Controls.Add(_hostInput, 1, 2);
        layout.Controls.Add(CreateFieldLabel("포트 설정"), 0, 3);
        layout.Controls.Add(_defaultPortCheck, 1, 3);
        layout.Controls.Add(CreateFieldLabel("수동 포트"), 0, 4);
        layout.Controls.Add(_portInput, 1, 4);
        layout.Controls.Add(CreateFieldLabel("연결 상태"), 0, 5);
        layout.Controls.Add(statusPanel, 1, 5);
        layout.Controls.Add(CreateFieldLabel("위젯 투명도"), 0, 6);
        layout.Controls.Add(opacityPanel, 1, 6);
        layout.Controls.Add(CreateFieldLabel("위젯 테마"), 0, 7);
        layout.Controls.Add(_themeInput, 1, 7);
        layout.Controls.Add(_connectButton, 1, 8);
        layout.Controls.Add(remoteHint, 0, 9);
        layout.SetColumnSpan(remoteHint, 2);
        Controls.Add(layout);
    }

    private static Label CreateFieldLabel(string text) => new()
    {
        Text = text,
        AutoSize = true,
        Anchor = AnchorStyles.Left,
        Font = new Font(SystemFonts.MessageBoxFont ?? SystemFonts.DefaultFont, FontStyle.Bold),
    };

    private async Task ToggleConnectionAsync()
    {
        if (_connectionRequested || _client.State is ConnectionState.Connected or ConnectionState.Connecting)
        {
            StopAutomaticReconnect();
            _widgetActivated = false;
            _widgetHiddenByUser = false;
            if (_remoteForm.Visible) _remoteForm.Hide();
            await _client.DisconnectAsync();
            return;
        }

        SaveSettings();
        _connectionRequested = true;
        _automaticReconnectPaused = false;
        _reconnectAttempt = 0;
        await TryConnectAsync();
    }

    private async Task SendWidgetCommandAsync(Func<Task> sendCommand)
    {
        if (_client.State != ConnectionState.Connected)
        {
            if (!_widgetActivated || _connectionAttemptInProgress) return;
            _connectionRequested = true;
            _automaticReconnectPaused = false;
            _reconnectAttempt = 0;
            await TryConnectAsync();
        }

        if (_client.State == ConnectionState.Connected) await sendCommand();
    }

    private async Task TryConnectAsync()
    {
        if (!_connectionRequested || _connectionAttemptInProgress || _exiting) return;

        _reconnectTimer.Stop();
        _connectionAttemptInProgress = true;
        try
        {
            var port = _defaultPortCheck.Checked ? RemoteClient.DefaultPort : Decimal.ToInt32(_portInput.Value);
            await _client.ConnectAsync(_hostInput.Text, port);
        }
        finally
        {
            _connectionAttemptInProgress = false;
            if (_connectionRequested && !_exiting && _client.State != ConnectionState.Connected)
            {
                ScheduleReconnect();
            }
            UpdateConnectionStateSafe();
        }
    }

    private void ScheduleReconnect()
    {
        if (!_connectionRequested ||
            _connectionAttemptInProgress ||
            _automaticReconnectPaused ||
            _exiting ||
            _reconnectTimer.Enabled)
        {
            return;
        }

        if (_reconnectAttempt >= ReconnectDelaysMs.Length)
        {
            _automaticReconnectPaused = true;
            return;
        }

        _reconnectTimer.Interval = ReconnectDelaysMs[_reconnectAttempt];
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

    private void ToggleRemoteWindow()
    {
        if (!_widgetActivated) return;
        if (_remoteForm.Visible)
        {
            _widgetHiddenByUser = true;
            _remoteForm.Hide();
        }
        else
        {
            _widgetHiddenByUser = false;
            ShowRemoteWindow();
        }
        UpdateRemoteMenuText();
    }

    private void ShowRemoteWindow()
    {
        _remoteForm.Location = ResolveWidgetPosition();
        _remoteForm.Show();
        _remoteForm.Activate();
    }

    internal void OpenMainWindow()
    {
        Show();
        WindowState = FormWindowState.Normal;
        BringToFront();
        Activate();
    }

    private void UpdateConnectionStateSafe()
    {
        if (IsDisposed) return;
        if (InvokeRequired)
        {
            BeginInvoke(UpdateConnectionStateSafe);
            return;
        }

        if (_client.State == ConnectionState.Connected)
        {
            _reconnectTimer.Stop();
            _automaticReconnectPaused = false;
            _reconnectAttempt = 0;
            _widgetActivated = true;
        }
        else if (_connectionRequested &&
            !_connectionAttemptInProgress &&
            !_automaticReconnectPaused &&
            _client.State is ConnectionState.Disconnected or ConnectionState.Error)
        {
            ScheduleReconnect();
        }

        var disconnectedWhileRequested = _connectionRequested &&
            !_connectionAttemptInProgress &&
            _client.State is ConnectionState.Disconnected or ConnectionState.Error;
        var reconnectWaiting = disconnectedWhileRequested && !_automaticReconnectPaused;
        var reconnectPaused = disconnectedWhileRequested && _automaticReconnectPaused;
        var presentationState = reconnectWaiting ? ConnectionState.Connecting :
            reconnectPaused ? ConnectionState.Disconnected : _client.State;
        var (color, defaultText) = StatePresentation.For(presentationState);
        var text = reconnectWaiting ? "재연결 대기 중" :
            reconnectPaused ? "연결 끊김 · 위젯 버튼으로 재연결" : defaultText;
        _statusDot.DotColor = color;
        _statusLabel.Text = _client.StateDetail is { Length: > 0 }
            ? $"{text} · {_client.StateDetail}"
            : text;
        var connected = _client.State == ConnectionState.Connected;
        var connectionActive = _connectionRequested || connected || _client.State == ConnectionState.Connecting;
        _connectButton.Text = connectionActive ? "연결 끊기" : "연결";
        _connectionMenuItem.Text = connectionActive ? "끊기" : "연결";
        _trayIcon.Text = $"두루마리 리모콘 · {text}";
        _remoteMenuItem.Enabled = _widgetActivated;
        if (connected)
        {
            if (!_remoteForm.Visible && !_widgetHiddenByUser) ShowRemoteWindow();
        }
        else if (!_widgetActivated && _remoteForm.Visible)
        {
            _remoteForm.Hide();
        }
        UpdateRemoteMenuText();
    }

    private void UpdateRemoteMenuText()
    {
        _remoteMenuItem.Text = _remoteForm.Visible ? "리모콘 숨김" : "리모콘 보임";
    }

    private void OnFormClosing(object? sender, FormClosingEventArgs eventArgs)
    {
        SaveSettings();
        if (_exiting) return;
        if (_connectionRequested || _client.State is ConnectionState.Connected or ConnectionState.Connecting)
        {
            eventArgs.Cancel = true;
            Hide();
        }
    }

    private async Task ExitApplicationAsync()
    {
        _exiting = true;
        StopAutomaticReconnect();
        await _client.DisconnectAsync();
        Close();
    }

    private void LoadSettings()
    {
        _loadingSettings = true;
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RegistryPath);
            _hostInput.Text = key?.GetValue("Host") as string ?? string.Empty;
            _defaultPortCheck.Checked = (key?.GetValue("UseDefaultPort") as int? ?? 1) != 0;
            var port = key?.GetValue("Port") as int? ?? RemoteClient.DefaultPort;
            _portInput.Value = Math.Clamp(port, 1, 65535);
            _portInput.Enabled = !_defaultPortCheck.Checked;
            var opacity = key?.GetValue("WidgetOpacity") as int? ?? 100;
            _opacityInput.Value = Math.Clamp(opacity, _opacityInput.Minimum, _opacityInput.Maximum);
            _opacityValueLabel.Text = $"{_opacityInput.Value}%";
            _remoteForm.SetWidgetOpacity(_opacityInput.Value);
            var themeMode = key?.GetValue("WidgetThemeMode") as int? ?? (int)WidgetThemeMode.System;
            _themeInput.SelectedIndex = Math.Clamp(themeMode, 0, 2);
            _remoteForm.SetThemeMode((WidgetThemeMode)_themeInput.SelectedIndex);
            if (key?.GetValue("WidgetX") is int x && key.GetValue("WidgetY") is int y)
            {
                _widgetPosition = new Point(x, y);
                _hasWidgetPosition = true;
            }
        }
        finally
        {
            _loadingSettings = false;
        }
    }

    private void SaveSettings()
    {
        using var key = Registry.CurrentUser.CreateSubKey(RegistryPath);
        key.SetValue("Host", _hostInput.Text.Trim());
        key.SetValue("UseDefaultPort", _defaultPortCheck.Checked ? 1 : 0);
        key.SetValue("Port", Decimal.ToInt32(_portInput.Value));
        key.SetValue("WidgetOpacity", _opacityInput.Value);
        key.SetValue("WidgetThemeMode", Math.Max(_themeInput.SelectedIndex, 0));
        if (_hasWidgetPosition)
        {
            key.SetValue("WidgetX", _widgetPosition.X);
            key.SetValue("WidgetY", _widgetPosition.Y);
        }
    }

    private Point ResolveWidgetPosition()
    {
        if (_hasWidgetPosition)
        {
            var widgetBounds = new Rectangle(_widgetPosition, _remoteForm.Size);
            if (Screen.AllScreens.Any(screen => screen.WorkingArea.IntersectsWith(widgetBounds))) return _widgetPosition;
        }
        var area = Screen.FromControl(this).WorkingArea;
        return new Point(area.Right - _remoteForm.Width - 24, area.Bottom - _remoteForm.Height - 24);
    }
}
