package com.triger.poc

import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.Incoming
import computer.iroh.RecvStream
import computer.iroh.SendStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

object IrohIo {
    private const val MAX_MESSAGE_BYTES = 1 shl 20

    @JvmStatic
    fun acceptNext(endpoint: Endpoint): Incoming? = runBlocking {
        endpoint.acceptNext()
    }

    @JvmStatic
    fun acceptIncoming(incoming: Incoming): Connection = runBlocking {
        val accepting = incoming.accept()
        accepting.connect()
    }

    @JvmStatic
    fun connectWithTimeout(
        endpoint: Endpoint,
        addr: EndpointAddr,
        alpn: ByteArray,
        timeoutMs: Long
    ): Connection = runBlocking {
        withTimeout(timeoutMs) {
            endpoint.connect(addr, alpn)
        }
    }

    @JvmStatic
    fun openBi(connection: Connection): BiStream = runBlocking {
        connection.openBi()
    }

    @JvmStatic
    fun acceptBi(connection: Connection): BiStream = runBlocking {
        connection.acceptBi()
    }

    @JvmStatic
    fun sendText(stream: SendStream, text: String) {
        val payload = text.toByteArray(StandardCharsets.UTF_8)
        val frame = ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
        runBlocking {
            stream.writeAll(frame)
            stream.finish()
        }
    }

    @JvmStatic
    fun receiveText(stream: RecvStream): String = runBlocking {
        val header = stream.readExact(4u)
        val length = ByteBuffer.wrap(header).int
        require(length in 0..MAX_MESSAGE_BYTES) { "invalid frame length: $length" }
        val payload = if (length == 0) ByteArray(0) else stream.readExact(length.toUInt())
        String(payload, StandardCharsets.UTF_8)
    }
}
