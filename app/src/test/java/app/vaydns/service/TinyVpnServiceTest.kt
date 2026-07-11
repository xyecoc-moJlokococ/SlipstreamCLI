package app.vaydns.service

import java.net.InetSocketAddress
import java.net.ServerSocket
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * TinyVpnService itself is a >1300-line VpnService subclass tightly coupled to JNI/real threads/
 * network state, so these tests only cover the two small helpers on it that are pure (or
 * self-contained real-socket) logic -- not the recovery state machine. See BridgeFailureWatchTest
 * and ClassifyRecoveryReasonTest for the extracted decision-logic coverage.
 */
@RunWith(RobolectricTestRunner::class)
class TinyVpnServiceTest {
    private val service = Robolectric.buildService(TinyVpnService::class.java).create().get()

    // -- isAddressInUse --

    @Test
    fun recognizes_the_java_eaddrinuse_message() {
        assertTrue(service.isAddressInUse(Exception("Address already in use")))
    }

    @Test
    fun recognizes_the_native_os_error_98_message() {
        assertTrue(service.isAddressInUse(Exception("bind failed: os error 98")))
    }

    @Test
    fun does_not_flag_an_unrelated_error() {
        assertFalse(service.isAddressInUse(Exception("Connection refused")))
    }

    @Test
    fun does_not_flag_an_error_with_no_message() {
        assertFalse(service.isAddressInUse(Exception(null as String?)))
    }

    // -- findFreeLocalPort --

    @Test
    fun returns_a_different_port_when_the_fallback_is_occupied() {
        ServerSocket().use { occupied ->
            occupied.bind(InetSocketAddress("127.0.0.1", 0))
            val port = service.findFreeLocalPort(occupied.localPort)
            assertNotEquals(occupied.localPort, port)
            assertTrue(port > 0)
        }
    }

    @Test
    fun returns_a_valid_port_when_the_probe_bind_succeeds_trivially() {
        // Binding to :0 always succeeds in practice (the OS picks a free ephemeral port), so this
        // documents the common case: some valid port comes back, not necessarily the fallback.
        val port = service.findFreeLocalPort(1080)
        assertTrue(port in 1..65535)
    }
}
