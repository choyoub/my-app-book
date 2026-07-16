using System.Net;
using System.Net.Sockets;

namespace Durumari.Remote;

internal static class Program
{
    private static async Task Main()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        var received = new List<string>();

        var serverTask = Task.Run(async () =>
        {
            using var socket = await listener.AcceptTcpClientAsync();
            await using var stream = socket.GetStream();
            using var reader = new StreamReader(stream);
            await using var writer = new StreamWriter(stream) { AutoFlush = true };

            while (await reader.ReadLineAsync() is { } line)
            {
                received.Add(line);
                await writer.WriteLineAsync(line switch
                {
                    "DURUMARI_REMOTE/1" => "READY",
                    "PING" => "PONG",
                    "LEFT" or "RIGHT" => "OK",
                    "DISCONNECT" => "BYE",
                    _ => "ERROR UNKNOWN_COMMAND",
                });
                if (line == "DISCONNECT") break;
            }
        });

        await using var client = new RemoteClient();
        await client.ConnectAsync("127.0.0.1", port);
        Ensure(client.State == ConnectionState.Connected, "연결 상태 전환 실패");
        await client.PreviousPageAsync();
        await client.NextPageAsync();
        await client.DisconnectAsync();
        await serverTask;
        listener.Stop();

        var expected = new[] { "DURUMARI_REMOTE/1", "LEFT", "RIGHT", "DISCONNECT" };
        Ensure(received.SequenceEqual(expected), $"명령 순서 불일치: {string.Join(", ", received)}");
        Ensure(client.State == ConnectionState.Disconnected, "연결 종료 상태 전환 실패");
        Console.WriteLine("프로토콜 스모크 테스트 성공");
    }

    private static void Ensure(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException(message);
    }
}
