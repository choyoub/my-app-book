using System.Drawing.Drawing2D;
using Microsoft.Win32;

namespace Durumari.Remote;

internal enum WidgetThemeMode
{
    System,
    Light,
    Dark,
}

internal sealed class RemoteForm : Form
{
    private static readonly Color TransparentKeyColor = Color.FromArgb(1, 2, 3);
    private readonly SlimKeycap[] _keycaps;
    private bool _systemEventsSubscribed;
    private WidgetThemeMode _themeMode = WidgetThemeMode.System;

    public RemoteForm(Func<Task> previousPage, Func<Task> nextPage)
    {
        Text = "두루마리 리모콘";
        ClientSize = new Size(168, 62);
        FormBorderStyle = FormBorderStyle.None;
        BackColor = TransparentKeyColor;
        TransparencyKey = TransparentKeyColor;
        AllowTransparency = true;
        KeyPreview = true;
        StartPosition = FormStartPosition.Manual;
        ShowInTaskbar = false;
        TopMost = true;

        var previous = CreateKeycap(pointsRight: false, new Point(0, 0), "이전 페이지");
        var next = CreateKeycap(pointsRight: true, new Point(88, 0), "다음 페이지");
        previous.Click += async (_, _) => await previousPage();
        next.Click += async (_, _) => await nextPage();
        _keycaps = [previous, next];
        Controls.AddRange(_keycaps);

        RefreshSystemTheme();
        try
        {
            SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;
            _systemEventsSubscribed = true;
        }
        catch
        {
            // 시스템 이벤트를 지원하지 않는 환경에서는 시작 시 확인한 테마를 유지합니다.
        }

        KeyDown += async (_, eventArgs) =>
        {
            if (eventArgs.KeyCode == Keys.Left)
            {
                eventArgs.SuppressKeyPress = true;
                previous.PlayTapAnimation();
                await previousPage();
            }
            else if (eventArgs.KeyCode == Keys.Right)
            {
                eventArgs.SuppressKeyPress = true;
                next.PlayTapAnimation();
                await nextPage();
            }
        };
    }

    public void SetWidgetOpacity(int percent)
    {
        Opacity = Math.Clamp(percent, 30, 100) / 100d;
    }

    public void SetThemeMode(WidgetThemeMode themeMode)
    {
        _themeMode = themeMode;
        RefreshSystemTheme();
    }

    protected override void OnVisibleChanged(EventArgs eventArgs)
    {
        if (Visible) RefreshSystemTheme();
        base.OnVisibleChanged(eventArgs);
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing && _systemEventsSubscribed)
        {
            SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
            _systemEventsSubscribed = false;
        }
        base.Dispose(disposing);
    }

    private SlimKeycap CreateKeycap(bool pointsRight, Point location, string accessibleName)
    {
        return new SlimKeycap(this, pointsRight, TransparentKeyColor)
        {
            Location = location,
            Size = new Size(80, 62),
            AccessibleName = accessibleName,
            Cursor = Cursors.Hand,
            TabStop = false,
        };
    }

    private void OnUserPreferenceChanged(object sender, UserPreferenceChangedEventArgs eventArgs)
    {
        if (IsDisposed || !IsHandleCreated) return;
        BeginInvoke(RefreshSystemTheme);
    }

    private void RefreshSystemTheme()
    {
        var darkTheme = _themeMode switch
        {
            WidgetThemeMode.Light => false,
            WidgetThemeMode.Dark => true,
            _ => IsWindowsDarkTheme(),
        };
        foreach (var keycap in _keycaps) keycap.SetDarkTheme(darkTheme);
    }

    private static bool IsWindowsDarkTheme()
    {
        using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        var value = key?.GetValue("AppsUseLightTheme");
        return value is not null && Convert.ToInt32(value) == 0;
    }

    private sealed class SlimKeycap : Control
    {
        private readonly Form _moveTarget;
        private readonly bool _pointsRight;
        private readonly Color _transparentKeyColor;
        private readonly System.Windows.Forms.Timer _releaseTimer = new() { Interval = 75 };
        private Point _mouseDownScreen;
        private Point _targetStartLocation;
        private bool _darkTheme;
        private bool _dragged;
        private bool _pressed;

        public SlimKeycap(Form moveTarget, bool pointsRight, Color transparentKeyColor)
        {
            _moveTarget = moveTarget;
            _pointsRight = pointsRight;
            _transparentKeyColor = transparentKeyColor;
            SetStyle(
                ControlStyles.AllPaintingInWmPaint |
                ControlStyles.OptimizedDoubleBuffer |
                ControlStyles.ResizeRedraw |
                ControlStyles.StandardClick |
                ControlStyles.UserPaint,
                true);
            _releaseTimer.Tick += (_, _) =>
            {
                _releaseTimer.Stop();
                _pressed = false;
                Invalidate();
            };
        }

        public void PlayTapAnimation()
        {
            _releaseTimer.Stop();
            _pressed = true;
            Invalidate();
            _releaseTimer.Start();
        }

        public void SetDarkTheme(bool darkTheme)
        {
            if (_darkTheme == darkTheme) return;
            _darkTheme = darkTheme;
            Invalidate();
        }

        protected override void OnPaintBackground(PaintEventArgs eventArgs)
        {
            eventArgs.Graphics.Clear(_transparentKeyColor);
        }

        protected override void OnPaint(PaintEventArgs eventArgs)
        {
            base.OnPaint(eventArgs);
            var graphics = eventArgs.Graphics;
            graphics.SmoothingMode = SmoothingMode.AntiAlias;
            graphics.PixelOffsetMode = PixelOffsetMode.HighQuality;

            var palette = _darkTheme ? SlimPalette.Dark : SlimPalette.Light;
            var offset = _pressed && !_dragged ? 4f : 0f;
            var shadowBounds = new RectangleF(3, 8, Width - 6, Height - 11);
            using (var shadowPath = RoundedRectangle(shadowBounds, 10))
            using (var shadowBrush = new SolidBrush(palette.Shadow))
            {
                graphics.FillPath(shadowBrush, shadowPath);
            }

            var keyBounds = new RectangleF(3, 2 + offset, Width - 6, Height - 12);
            using (var keyPath = RoundedRectangle(keyBounds, 10))
            using (var keyBrush = new LinearGradientBrush(
                keyBounds,
                palette.KeyTop,
                palette.KeyBottom,
                LinearGradientMode.Vertical))
            using (var borderPen = new Pen(palette.Border, 1.2f))
            {
                graphics.FillPath(keyBrush, keyPath);
                graphics.DrawPath(borderPen, keyPath);
            }

            DrawArrow(graphics, new PointF(Width / 2f, 27f + offset), palette.Icon);
        }

        protected override void OnMouseDown(MouseEventArgs eventArgs)
        {
            _dragged = false;
            if (eventArgs.Button == MouseButtons.Left)
            {
                _releaseTimer.Stop();
                _pressed = true;
                _mouseDownScreen = Cursor.Position;
                _targetStartLocation = _moveTarget.Location;
                Invalidate();
            }
            base.OnMouseDown(eventArgs);
        }

        protected override void OnMouseMove(MouseEventArgs eventArgs)
        {
            if (eventArgs.Button == MouseButtons.Left)
            {
                var delta = new Size(Cursor.Position.X - _mouseDownScreen.X, Cursor.Position.Y - _mouseDownScreen.Y);
                if (!_dragged && Math.Abs(delta.Width) + Math.Abs(delta.Height) >= 5)
                {
                    _dragged = true;
                    _pressed = false;
                    Invalidate();
                }
                if (_dragged) _moveTarget.Location = _targetStartLocation + delta;
            }
            base.OnMouseMove(eventArgs);
        }

        protected override void OnMouseUp(MouseEventArgs eventArgs)
        {
            if (!_dragged && eventArgs.Button == MouseButtons.Left) _releaseTimer.Start();
            base.OnMouseUp(eventArgs);
        }

        protected override void OnClick(EventArgs eventArgs)
        {
            if (!_dragged) base.OnClick(eventArgs);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) _releaseTimer.Dispose();
            base.Dispose(disposing);
        }

        private void DrawArrow(Graphics graphics, PointF center, Color color)
        {
            var direction = _pointsRight ? 1f : -1f;
            using var pen = new Pen(color, 3.5f)
            {
                StartCap = LineCap.Round,
                EndCap = LineCap.Round,
                LineJoin = LineJoin.Round,
            };
            graphics.DrawLine(pen, center.X - 12f * direction, center.Y, center.X + 12f * direction, center.Y);
            graphics.DrawLine(pen, center.X + 12f * direction, center.Y, center.X + 4f * direction, center.Y - 8f);
            graphics.DrawLine(pen, center.X + 12f * direction, center.Y, center.X + 4f * direction, center.Y + 8f);
        }

        private static GraphicsPath RoundedRectangle(RectangleF bounds, float radius)
        {
            var diameter = radius * 2f;
            var path = new GraphicsPath();
            path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
            path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
            path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
            path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
            return path;
        }

        private readonly record struct SlimPalette(
            Color KeyTop,
            Color KeyBottom,
            Color Border,
            Color Icon,
            Color Shadow)
        {
            public static SlimPalette Light => new(
                Color.FromArgb(255, 255, 255),
                Color.FromArgb(225, 226, 228),
                Color.FromArgb(126, 129, 134),
                Color.FromArgb(38, 40, 44),
                Color.FromArgb(48, 48, 48));

            public static SlimPalette Dark => new(
                Color.FromArgb(150, 174, 193),
                Color.FromArgb(101, 125, 145),
                Color.FromArgb(119, 143, 162),
                Color.FromArgb(246, 248, 250),
                Color.FromArgb(36, 39, 43));
        }
    }
}
