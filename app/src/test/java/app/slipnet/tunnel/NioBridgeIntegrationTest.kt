package app.slipnet.tunnel

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * End-to-end test of the NIO bridge over real localhost sockets: a fake "slipstream" SOCKS server
 * (handshake then echo) stands in for the native client, a real SOCKS client drives tun2socks's
 * side. Exercises the full state machine (client handshake -> upstream connect+handshake -> relay)
 * and the leak fix (connections must be reaped, not accumulate). Robolectric only stubs AppLog's
 * android.util.Log; the sockets are real JVM sockets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NioBridgeIntegrationTest {

    private var upstream: ServerSocket? = null

    @After
    fun tearDown() {
        MiniSlipstreamSocksBridge.stop()
        runCatching { upstream?.close() }
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun readFully(i: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = i.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException("eof at $off/${buf.size}")
            off += n
        }
    }

    /** Fake upstream: SOCKS5 no-auth (or user/pass) handshake, then echoes everything. */
    private fun startFakeUpstream(requireAuth: Boolean = false): Int {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress("127.0.0.1", 0))
        upstream = ss
        Thread {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        s.use {
                            val i = it.getInputStream(); val o = it.getOutputStream()
                            // greeting: ver, nmethods, methods...
                            i.read(); val n = i.read(); repeat(n) { i.read() }
                            if (requireAuth) {
                                o.write(byteArrayOf(0x05, 0x02)); o.flush()
                                // auth: ver, ulen, user, plen, pass
                                i.read(); val ul = i.read(); repeat(ul) { i.read() }
                                val pl = i.read(); repeat(pl) { i.read() }
                                o.write(byteArrayOf(0x01, 0x00)); o.flush()
                            } else {
                                o.write(byteArrayOf(0x05, 0x00)); o.flush()
                            }
                            // command: ver, cmd, rsv, atyp, addr, port
                            i.read(); i.read(); i.read(); val atyp = i.read()
                            val addrLen = when (atyp) { 1 -> 4; 4 -> 16; 3 -> i.read(); else -> 0 }
                            repeat(addrLen) { i.read() }
                            i.read(); i.read() // port
                            o.write(byteArrayOf(0x05, 0, 0, 0x01, 0, 0, 0, 0, 0, 0)); o.flush()
                            // echo
                            val buf = ByteArray(8192)
                            while (true) {
                                val r = i.read(buf)
                                if (r <= 0) break
                                o.write(buf, 0, r); o.flush()
                            }
                        }
                    } catch (_: Exception) {}
                }.also { it.isDaemon = true }.start()
            }
        }.also { it.isDaemon = true }.start()
        return ss.localPort
    }

    private fun startBridge(upstreamPort: Int, localUser: String? = null, localPass: String? = null): Int {
        val listen = freePort()
        val r = MiniSlipstreamSocksBridge.start(
            listenHost = "127.0.0.1", listenPort = listen,
            slipstreamHost = "127.0.0.1", slipstreamPort = upstreamPort,
            dnsHost = "", username = null, password = null,
            localUsername = localUser, localPassword = localPass
        )
        assertTrue("bridge start failed: ${r.exceptionOrNull()}", r.isSuccess)
        // give the selector thread a moment to bind/register
        Thread.sleep(100)
        return listen
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    @Test
    fun connect_and_relay_round_trips_payload() {
        val up = startFakeUpstream()
        val port = startBridge(up)
        val payload = ByteArray(50_000) { (it % 251).toByte() } // larger than one buffer's worth of writes

        Socket("127.0.0.1", port).use { s ->
            s.tcpNoDelay = true
            val i = s.getInputStream(); val o = s.getOutputStream()
            o.write(byteArrayOf(0x05, 0x01, 0x00)); o.flush() // greeting no-auth
            val g = ByteArray(2); readFully(i, g)
            assertArrayEquals(byteArrayOf(0x05, 0x00), g)
            // CONNECT 1.2.3.4:443
            o.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x01, 0xBB.toByte())); o.flush()
            val rep = ByteArray(10); readFully(i, rep)
            assertEquals(0x00, rep[1].toInt())
            o.write(payload); o.flush()
            val echo = ByteArray(payload.size); readFully(i, echo)
            assertArrayEquals(payload, echo)
        }

        val st = MiniSlipstreamSocksBridge.stats()
        assertEquals(1, st.connectOk)
        assertTrue("tx should be >= payload", st.txBytes >= payload.size)
        assertTrue("rx should be >= payload", st.rxBytes >= payload.size)
    }

    @Test
    fun local_password_auth_is_enforced() {
        val up = startFakeUpstream()
        val port = startBridge(up, localUser = "u", localPass = "p")

        // Wrong password -> auth failure (status 0x01), connection closed.
        Socket("127.0.0.1", port).use { s ->
            val i = s.getInputStream(); val o = s.getOutputStream()
            o.write(byteArrayOf(0x05, 0x01, 0x02)); o.flush() // offer user/pass
            val g = ByteArray(2); readFully(i, g)
            assertArrayEquals(byteArrayOf(0x05, 0x02), g)
            o.write(byteArrayOf(0x01, 1, 'u'.code.toByte(), 1, 'X'.code.toByte())); o.flush() // wrong pass
            val a = ByteArray(2); readFully(i, a)
            assertEquals(0x01, a[1].toInt()) // auth failed
        }

        // Correct password -> relay works.
        Socket("127.0.0.1", port).use { s ->
            val i = s.getInputStream(); val o = s.getOutputStream()
            o.write(byteArrayOf(0x05, 0x01, 0x02)); o.flush()
            readFully(i, ByteArray(2))
            o.write(byteArrayOf(0x01, 1, 'u'.code.toByte(), 1, 'p'.code.toByte())); o.flush()
            val a = ByteArray(2); readFully(i, a)
            assertEquals(0x00, a[1].toInt())
            o.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x01, 0xBB.toByte())); o.flush()
            val rep = ByteArray(10); readFully(i, rep)
            assertEquals(0x00, rep[1].toInt())
            o.write("ping".toByteArray()); o.flush()
            val echo = ByteArray(4); readFully(i, echo)
            assertArrayEquals("ping".toByteArray(), echo)
        }
    }

    @Test
    fun connections_do_not_leak_after_close() {
        val up = startFakeUpstream()
        val port = startBridge(up)

        // Open, use, and close 25 connections sequentially.
        repeat(25) {
            Socket("127.0.0.1", port).use { s ->
                val i = s.getInputStream(); val o = s.getOutputStream()
                o.write(byteArrayOf(0x05, 0x01, 0x00)); o.flush()
                readFully(i, ByteArray(2))
                o.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 1, 2, 3, 4, 0x01, 0xBB.toByte())); o.flush()
                readFully(i, ByteArray(10))
                o.write("x".toByteArray()); o.flush()
                readFully(i, ByteArray(1))
            }
        }

        // After the clients close, every connection must be reaped back to zero -- the whole point
        // of the rewrite (the old bridge leaked these as CLOSE-WAIT + parked threads).
        val drained = waitUntil(5_000) { MiniSlipstreamSocksBridge.stats().activeClients == 0 }
        assertTrue("active connections did not drain to 0: ${MiniSlipstreamSocksBridge.stats().activeClients}", drained)
        assertEquals(25, MiniSlipstreamSocksBridge.stats().connectOk)
    }
}
