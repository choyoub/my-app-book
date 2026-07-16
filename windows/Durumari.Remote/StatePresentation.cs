namespace Durumari.Remote;

internal static class StatePresentation
{
    public static (Color Color, string Text) For(ConnectionState state) => state switch
    {
        ConnectionState.Connected => (Color.FromArgb(42, 166, 92), "연결됨"),
        ConnectionState.Connecting => (Color.FromArgb(230, 150, 45), "연결 중"),
        ConnectionState.Error => (Color.FromArgb(210, 66, 66), "연결 오류"),
        _ => (Color.FromArgb(148, 148, 148), "연결 안 됨"),
    };
}
