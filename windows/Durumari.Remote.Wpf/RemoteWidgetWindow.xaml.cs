using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Controls;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace Durumari.Remote.Wpf;

public partial class RemoteWidgetWindow : Window
{
    private readonly Random _random = new();
    private string _resolvedTheme = "dark";
    private Color _effectColor = Color.FromRgb(0x00, 0x78, 0xD4);
    private double _compactHandleHeight = 10;
    private bool _effectsEnabled = true;
    private bool _soundEnabled = true;

    public Func<Task>? PreviousRequested { get; set; }
    public Func<Task>? NextRequested { get; set; }

    public RemoteWidgetWindow()
    {
        InitializeComponent();
        Left = SystemParameters.WorkArea.Right - 190;
        Top = SystemParameters.WorkArea.Top + 90;
    }

    public void SetSize(string size)
    {
        var profile = size switch
        {
            "small" => new WidgetSizeProfile(36, 16, 6, 10, 8),
            "large" => new WidgetSizeProfile(64, 24, 8, 20, 12),
            _ => new WidgetSizeProfile(48, 20, 7, 14, 10)
        };

        LeftKeycap.Width = RightKeycap.Width = profile.Keycap;
        LeftKeycap.Height = RightKeycap.Height = profile.Keycap;
        LeftArrow.Width = LeftArrow.Height = profile.Arrow;
        RightArrow.Width = RightArrow.Height = profile.Arrow;
        LeftLabel.FontSize = RightLabel.FontSize = profile.Label;
        DragLabel.FontSize = profile.Label;
        KeycapGap.Width = profile.Gap;
        _compactHandleHeight = profile.Handle;
        DragHandle.BeginAnimation(HeightProperty, null);
        DragHandle.Height = _compactHandleHeight;
    }

    public void SetTheme(string theme, bool systemIsDark, Color windowsAccent)
    {
        _resolvedTheme = theme == "system" ? (systemIsDark ? "dark" : "light") : theme;
        _effectColor = windowsAccent;
        switch (_resolvedTheme)
        {
            case "light":
                ApplyKeycapTheme(
                    CreateGradient("#FFFFFFFF", "#FFECEEF3"),
                    "#FFE2E8F0",
                    "#FF1E293B",
                    "#70FFFFFF");
                break;
            case "acrylic":
                if (systemIsDark)
                {
                    ApplyKeycapTheme(
                        CreateGradient("#78495E73", "#4A2F4052"),
                        "#8CA9BED2",
                        "#FFFFFFFF",
                        "#70FFFFFF");
                }
                else
                {
                    ApplyKeycapTheme(
                        CreateGradient("#96F5F8FC", "#5EC8D5E2"),
                        "#8A788DA2",
                        "#FF203247",
                        "#A8FFFFFF");
                }
                break;
            default:
                ApplyKeycapTheme(
                    CreateGradient("#FF383838", "#FF222222"),
                    "#FF4A4A4A",
                    "#FFFFFFFF",
                    "#35FFFFFF");
                break;
        }
    }

    public void SetFeedbackOptions(bool effectsEnabled, bool soundEnabled)
    {
        _effectsEnabled = effectsEnabled;
        _soundEnabled = soundEnabled;
    }

    public void SetWidgetOpacity(int percent) =>
        Opacity = Math.Clamp(percent, 20, 100) / 100d;

    private void ApplyKeycapTheme(
        Brush background,
        string borderHex,
        string foregroundHex,
        string highlightHex)
    {
        Resources["KeycapHighlightBrush"] =
            new SolidColorBrush((Color)ColorConverter.ConvertFromString(highlightHex));
        foreach (var keycap in new[] { LeftKeycap, RightKeycap })
        {
            keycap.Background = background;
            keycap.BorderBrush = new SolidColorBrush((Color)ColorConverter.ConvertFromString(borderHex));
            keycap.Foreground = new SolidColorBrush((Color)ColorConverter.ConvertFromString(foregroundHex));
        }
    }

    private static Brush CreateGradient(string first, string second) => new LinearGradientBrush(
        (Color)ColorConverter.ConvertFromString(first),
        (Color)ColorConverter.ConvertFromString(second),
        new Point(0, 0), new Point(1, 1));

    private void DragHandleArea_MouseEnter(object sender, MouseEventArgs e)
    {
        DragHandle.Background = new SolidColorBrush(Color.FromRgb(0xA3, 0xA3, 0xA3));
        DragLabel.Visibility = Visibility.Visible;
        var expandedHeight = Math.Max(_compactHandleHeight, LeftKeycap.ActualHeight - 20);
        DragHandle.BeginAnimation(HeightProperty, new DoubleAnimation(
            expandedHeight,
            TimeSpan.FromMilliseconds(140))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        });
        DragHandle.BeginAnimation(OpacityProperty, new DoubleAnimation(
            0.82,
            TimeSpan.FromMilliseconds(120)));
    }

    private void DragHandleArea_MouseLeave(object sender, MouseEventArgs e)
    {
        DragHandle.Background = new SolidColorBrush(Color.FromRgb(0x66, 0x66, 0x66));
        DragLabel.Visibility = Visibility.Collapsed;
        DragHandle.BeginAnimation(HeightProperty, new DoubleAnimation(
            _compactHandleHeight,
            TimeSpan.FromMilliseconds(120))
        {
            EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
        });
        DragHandle.BeginAnimation(OpacityProperty, new DoubleAnimation(
            0.36,
            TimeSpan.FromMilliseconds(100)));
    }

    private void DragHandleArea_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.LeftButton == MouseButtonState.Pressed) DragMove();
    }

    private void Keycap_PreviewMouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (sender is not Button keycap) return;
        if (_soundEnabled) PlayKeycapClickSound();
        if (_effectsEnabled) CreateKeycapParticles(keycap);
    }

    private static void PlayKeycapClickSound()
    {
        _ = Task.Run(static () =>
        {
            try { Console.Beep(900, 45); }
            catch { /* 오디오 출력이 없는 환경에서는 시각 효과만 유지합니다. */ }
        });
    }

    private void CreateKeycapParticles(Button keycap)
    {
        var center = keycap.TranslatePoint(
            new Point(keycap.ActualWidth / 2, keycap.ActualHeight / 2),
            KeycapEffects);
        var color = _effectColor;

        for (var index = 0; index < 8; index++)
        {
            var radius = _random.NextDouble() * 1.8 + 1.2;
            var particle = new Ellipse
            {
                Width = radius * 2,
                Height = radius * 2,
                Fill = new SolidColorBrush(color),
                Opacity = 0.9
            };
            var angle = _random.NextDouble() * Math.PI * 2;
            var distance = _random.NextDouble() * 13 + 9;
            var targetX = center.X + Math.Cos(angle) * distance - radius;
            var targetY = center.Y + Math.Sin(angle) * distance - radius;
            var duration = TimeSpan.FromMilliseconds(_random.Next(180, 271));

            Canvas.SetLeft(particle, center.X - radius);
            Canvas.SetTop(particle, center.Y - radius);
            KeycapEffects.Children.Add(particle);

            particle.BeginAnimation(Canvas.LeftProperty, new DoubleAnimation(targetX, duration)
            {
                EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
            });
            particle.BeginAnimation(Canvas.TopProperty, new DoubleAnimation(targetY, duration)
            {
                EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
            });
            var fade = new DoubleAnimation(0, duration);
            fade.Completed += (_, _) => KeycapEffects.Children.Remove(particle);
            particle.BeginAnimation(OpacityProperty, fade);
        }
    }

    private async void Left_Click(object sender, RoutedEventArgs e)
    {
        if (PreviousRequested is { } action) await action();
    }

    private async void Right_Click(object sender, RoutedEventArgs e)
    {
        if (NextRequested is { } action) await action();
    }

    private async void RemoteWidgetWindow_PreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Left)
        {
            e.Handled = true;
            PlayKeyboardFeedback(LeftKeycap);
            if (PreviousRequested is { } previous) await previous();
        }
        else if (e.Key == Key.Right)
        {
            e.Handled = true;
            PlayKeyboardFeedback(RightKeycap);
            if (NextRequested is { } next) await next();
        }
    }

    private void PlayKeyboardFeedback(Button keycap)
    {
        if (_soundEnabled) PlayKeycapClickSound();
        if (_effectsEnabled) CreateKeycapParticles(keycap);
    }

    private readonly record struct WidgetSizeProfile(
        double Keycap,
        double Arrow,
        double Label,
        double Gap,
        double Handle);
}
