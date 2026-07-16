package com.netice.myapp.durumari.remote

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

enum class RemoteConnectionState {
    OFF,
    LISTENING,
    CONNECTED,
    ERROR,
}

class RemoteControlServer(
    private val onCommand: (RemoteCommand) -> Unit,
    private val onStateChanged: (RemoteConnectionState, String?) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    private val lock = Any()
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null

    fun start(port: Int) {
        stop()
        val runId = generation.incrementAndGet()
        running.set(true)
        updateState(RemoteConnectionState.LISTENING, null)
        thread(name = "durumari-remote-server", isDaemon = true) {
            var ownedServer: ServerSocket? = null
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                ownedServer = server
                synchronized(lock) { serverSocket = server }
                while (running.get() && generation.get() == runId) {
                    val client = server.accept()
                    synchronized(lock) {
                        clientSocket?.closeQuietly()
                        clientSocket = client
                    }
                    runCatching { handleClient(client, runId) }
                    client.closeQuietly()
                    synchronized(lock) {
                        if (clientSocket === client) clientSocket = null
                    }
                    if (running.get() && generation.get() == runId) updateState(RemoteConnectionState.LISTENING, null)
                }
            } catch (_: SocketException) {
                if (running.get() && generation.get() == runId) updateState(RemoteConnectionState.ERROR, "연결 소켓 오류")
            } catch (error: Exception) {
                if (running.get() && generation.get() == runId) updateState(RemoteConnectionState.ERROR, error.message ?: "서버 시작 실패")
            } finally {
                synchronized(lock) {
                    if (serverSocket === ownedServer) {
                        serverSocket?.closeQuietly()
                        serverSocket = null
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        generation.incrementAndGet()
        synchronized(lock) {
            clientSocket?.closeQuietly()
            clientSocket = null
            serverSocket?.closeQuietly()
            serverSocket = null
        }
        updateState(RemoteConnectionState.OFF, null)
    }

    private fun handleClient(client: Socket, runId: Long) {
        client.keepAlive = true
        client.tcpNoDelay = true
        client.soTimeout = CLIENT_READ_TIMEOUT_MS
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
        val hello = reader.readLine()
        if (hello != PROTOCOL_HELLO) {
            writer.writeLine("ERROR UNSUPPORTED_PROTOCOL")
            return
        }
        if (!running.get() || generation.get() != runId) return
        writer.writeLine("READY")
        updateState(RemoteConnectionState.CONNECTED, client.inetAddress.hostAddress)

        while (running.get() && generation.get() == runId && !client.isClosed) {
            when (reader.readLine() ?: break) {
                "PING" -> writer.writeLine("PONG")
                "LEFT" -> {
                    onCommand(RemoteCommand.PREVIOUS_PAGE)
                    writer.writeLine("OK")
                }
                "RIGHT" -> {
                    onCommand(RemoteCommand.NEXT_PAGE)
                    writer.writeLine("OK")
                }
                "DISCONNECT" -> {
                    writer.writeLine("BYE")
                    break
                }
                else -> writer.writeLine("ERROR UNKNOWN_COMMAND")
            }
        }
    }

    private fun BufferedWriter.writeLine(value: String) {
        write(value)
        newLine()
        flush()
    }

    private fun updateState(state: RemoteConnectionState, detail: String?) {
        onStateChanged(state, detail)
    }

    private fun AutoCloseable.closeQuietly() {
        runCatching { close() }
    }

    companion object {
        const val DEFAULT_PORT = 48484
        private const val PROTOCOL_HELLO = "DURUMARI_REMOTE/1"
        private const val CLIENT_READ_TIMEOUT_MS = 45_000
    }
}

enum class RemoteCommand {
    PREVIOUS_PAGE,
    NEXT_PAGE,
}
