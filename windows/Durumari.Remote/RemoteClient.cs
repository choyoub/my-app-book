using System.Net.Sockets;
using System.Text;

namespace Durumari.Remote;

internal enum ConnectionState
{
    Disconnected,
    Connecting,
    Connected,
    Error,
}

internal sealed class RemoteClient : IAsyncDisposable
{
    public const int DefaultPort = 48484;

    private readonly SemaphoreSlim _operationLock = new(1, 1);
    private readonly System.Threading.Timer _heartbeatTimer;
    private TcpClient? _client;
    private StreamReader? _reader;
    private StreamWriter? _writer;
    private bool _disposed;

    public RemoteClient()
    {
        _heartbeatTimer = new System.Threading.Timer(
            async _ => await HeartbeatAsync(),
            null,
            Timeout.Infinite,
            Timeout.Infinite);
    }

    public ConnectionState State { get; private set; } = ConnectionState.Disconnected;
    public string? StateDetail { get; private set; }
    public event EventHandler? StateChanged;

    public async Task ConnectAsync(string host, int port)
    {
        host = host.Trim();
        if (string.IsNullOrWhiteSpace(host))
        {
            SetState(ConnectionState.Error, "Android Tailscale 도메인을 입력하세요.");
            return;
        }

        await DisconnectAsync(sendGoodbye: false);
        SetState(ConnectionState.Connecting, $"{host}:{port}");

        await _operationLock.WaitAsync();
        try
        {
            var client = new TcpClient { NoDelay = true };
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
            await client.ConnectAsync(host, port, timeout.Token);
            var stream = client.GetStream();
            var reader = new StreamReader(stream, new UTF8Encoding(false), leaveOpen: true);
            var writer = new StreamWriter(stream, new UTF8Encoding(false), leaveOpen: true) { AutoFlush = true };
            await writer.WriteLineAsync("DURUMARI_REMOTE/1");
            var response = await reader.ReadLineAsync(timeout.Token);
            if (response != "READY")
            {
                client.Dispose();
                throw new IOException("Android 앱이 연결을 승인하지 않았습니다.");
            }

            _client = client;
            _reader = reader;
            _writer = writer;
            SetState(ConnectionState.Connected, $"{host}:{port}");
            _heartbeatTimer.Change(TimeSpan.FromSeconds(10), TimeSpan.FromSeconds(10));
        }
        catch (Exception error)
        {
            CloseTransport();
            SetState(ConnectionState.Error, FriendlyMessage(error));
        }
        finally
        {
            _operationLock.Release();
        }
    }

    public Task PreviousPageAsync() => SendCommandAsync("LEFT");
    public Task NextPageAsync() => SendCommandAsync("RIGHT");

    public async Task DisconnectAsync(bool sendGoodbye = true)
    {
        _heartbeatTimer.Change(Timeout.Infinite, Timeout.Infinite);
        await _operationLock.WaitAsync();
        try
        {
            if (sendGoodbye && State == ConnectionState.Connected && _writer is not null && _reader is not null)
            {
                try
                {
                    await _writer.WriteLineAsync("DISCONNECT");
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                    await _reader.ReadLineAsync(timeout.Token);
                }
                catch
                {
                    // 연결 종료 중의 네트워크 오류는 무시합니다.
                }
            }
            CloseTransport();
            SetState(ConnectionState.Disconnected, null);
        }
        finally
        {
            _operationLock.Release();
        }
    }

    private async Task SendCommandAsync(string command)
    {
        if (State != ConnectionState.Connected) return;
        await _operationLock.WaitAsync();
        try
        {
            if (_writer is null || _reader is null) return;
            await _writer.WriteLineAsync(command);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(4));
            var response = await _reader.ReadLineAsync(timeout.Token);
            if (response != "OK") throw new IOException("Android 앱의 응답이 올바르지 않습니다.");
        }
        catch (Exception error)
        {
            CloseTransport();
            SetState(ConnectionState.Error, FriendlyMessage(error));
        }
        finally
        {
            _operationLock.Release();
        }
    }

    private async Task HeartbeatAsync()
    {
        if (State != ConnectionState.Connected || !_operationLock.Wait(0)) return;
        try
        {
            if (_writer is null || _reader is null) return;
            await _writer.WriteLineAsync("PING");
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(4));
            var response = await _reader.ReadLineAsync(timeout.Token);
            if (response != "PONG") throw new IOException("연결 확인 응답이 없습니다.");
        }
        catch (Exception error)
        {
            CloseTransport();
            SetState(ConnectionState.Error, FriendlyMessage(error));
        }
        finally
        {
            _operationLock.Release();
        }
    }

    private void CloseTransport()
    {
        _heartbeatTimer.Change(Timeout.Infinite, Timeout.Infinite);
        _reader?.Dispose();
        _writer?.Dispose();
        _client?.Dispose();
        _reader = null;
        _writer = null;
        _client = null;
    }

    private void SetState(ConnectionState state, string? detail)
    {
        State = state;
        StateDetail = detail;
        StateChanged?.Invoke(this, EventArgs.Empty);
    }

    private static string FriendlyMessage(Exception error) => error switch
    {
        OperationCanceledException => "연결 시간이 초과되었습니다.",
        SocketException socketError => $"네트워크 오류: {socketError.SocketErrorCode}",
        _ => error.Message,
    };

    public async ValueTask DisposeAsync()
    {
        if (_disposed) return;
        _disposed = true;
        await DisconnectAsync(sendGoodbye: false);
        _heartbeatTimer.Dispose();
        _operationLock.Dispose();
    }
}
