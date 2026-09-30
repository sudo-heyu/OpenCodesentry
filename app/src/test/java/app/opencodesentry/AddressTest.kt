package app.opencodesentry

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Address handling: how a typed host becomes a request URL, and how the app
 * recognises its own tailnet address (100.64.0.0/10).
 */
class AddressTest {

    @Test
    fun `bare host gets http and the port`() {
        assertEquals(
            "http://100.101.102.103:4096",
            Settings.buildServerUrl("100.101.102.103", 4096),
        )
        assertEquals(
            "http://my-mac:4096",
            Settings.buildServerUrl("my-mac", 4096),
        )
        assertEquals(
            "http://100.101.102.103:8080",
            Settings.buildServerUrl("100.101.102.103", 8080),
        )
    }

    @Test
    fun `explicit scheme is used verbatim`() {
        assertEquals(
            "https://my-mac.tailnet.ts.net",
            Settings.buildServerUrl("https://my-mac.tailnet.ts.net", 4096),
        )
        assertEquals(
            "http://100.101.102.103:5000",
            Settings.buildServerUrl("http://100.101.102.103:5000", 4096),
        )
    }

    @Test
    fun `whitespace and trailing slashes are tolerated`() {
        assertEquals(
            "http://my-mac:4096",
            Settings.buildServerUrl("  my-mac/  ", 4096),
        )
        assertEquals(
            "https://host.ts.net",
            Settings.buildServerUrl("https://host.ts.net/", 4096),
        )
    }

    @Test
    fun `blank host means not configured`() {
        assertEquals("", Settings.buildServerUrl("", 4096))
        assertEquals("", Settings.buildServerUrl("   ", 4096))
    }

    @Test
    fun `out of range ports are clamped`() {
        assertEquals("http://host:1", Settings.buildServerUrl("host", 0))
        assertEquals("http://host:65535", Settings.buildServerUrl("host", 99999))
    }

    @Test
    fun `tailnet range is 100_64_0_0 slash 10`() {
        // Inside the range Tailscale hands out.
        assertEquals(true, TailnetIp.isTailnet("100.64.0.1"))
        assertEquals(true, TailnetIp.isTailnet("100.104.105.106"))
        assertEquals(true, TailnetIp.isTailnet("100.127.255.254"))
        // Just outside it.
        assertEquals(false, TailnetIp.isTailnet("100.63.255.255"))
        assertEquals(false, TailnetIp.isTailnet("100.128.0.1"))
        assertEquals(false, TailnetIp.isTailnet("100.1.1.1"))
    }

    @Test
    fun `ordinary and malformed addresses are not tailnet`() {
        for (address in listOf(
            "192.168.0.10",
            "10.0.2.16",
            "127.0.0.1",
            "8.8.8.8",
            "100.64.0",
            "100.64.0.1.5",
            "not-an-ip",
            "",
            "fd7a:115c:a1e0::c42e:c74e",
        )) {
            assertEquals("$address should not match", false, TailnetIp.isTailnet(address))
        }
    }
}
