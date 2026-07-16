namespace Durumari.Remote;

internal sealed class StatusDot : Control
{
    private Color _dotColor = Color.Gray;

    public Color DotColor
    {
        get => _dotColor;
        set
        {
            _dotColor = value;
            Invalidate();
        }
    }

    public StatusDot()
    {
        DoubleBuffered = true;
        Size = new Size(18, 18);
        AccessibleName = "연결 상태";
    }

    protected override void OnPaint(PaintEventArgs e)
    {
        base.OnPaint(e);
        e.Graphics.SmoothingMode = System.Drawing.Drawing2D.SmoothingMode.AntiAlias;
        using var brush = new SolidBrush(DotColor);
        var size = Math.Min(ClientSize.Width, ClientSize.Height) - 2;
        e.Graphics.FillEllipse(brush, 1, 1, size, size);
    }
}
