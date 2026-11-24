package studio.cluvex.aether.custom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Test
import java.net.URL

class ConfigImporterSecurityTest {

    @Test
    fun `public IPv4 and IPv6 subscription destinations are accepted`() {
        assertTrue(ConfigImporter.isPublicInternetAddress(InetAddress.getByName("1.1.1.1")))
        assertTrue(ConfigImporter.isPublicInternetAddress(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun `private loopback link-local and shared IPv4 destinations are rejected`() {
        listOf("10.0.0.1", "127.0.0.1", "169.254.10.20", "192.168.1.1", "100.64.0.1").forEach {
            assertFalse("$it must not be routable", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `documentation and reserved IPv4 destinations are rejected`() {
        listOf("192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1").forEach {
            assertFalse("$it must not be routable", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `IANA PCP and TURN anycast exceptions are accepted`() {
        assertTrue(
            "192.0.0.9 is the globally reachable PCP Anycast address",
            ConfigImporter.isPublicInternetAddress(InetAddress.getByName("192.0.0.9")),
        )
        assertTrue(
            "192.0.0.10 is the globally reachable TURN Anycast address",
            ConfigImporter.isPublicInternetAddress(InetAddress.getByName("192.0.0.10")),
        )
    }

    @Test
    fun `other non-global IANA addresses in reserved range remain rejected`() {
        listOf("192.0.0.8", "192.0.0.170", "192.0.0.171", "192.0.0.11", "192.0.0.255").forEach {
            assertFalse("$it must remain non-global per IANA", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `IPv6 unique local link local special purpose and documentation destinations are rejected`() {
        listOf(
            "fc00::1",
            "fe80::1",
            "2001:db8::1",
            "2001:0000:ffff::1",
            "2001:0001:ffff::1",
            "2002:0808:0808::1",
            "3fff::1",
            "3fff:0fff::1",
            "5f00::1",
        ).forEach {
            assertFalse("$it must not be routable", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `IPv6 documentation prefix is limited to its exact IANA slash 20`() {
        assertFalse(ConfigImporter.isPublicInternetAddress(InetAddress.getByName("3fff:0fff::1")))
        assertTrue(
            "3fff:1000::1 is outside the 3fff::/20 documentation block",
            ConfigImporter.isPublicInternetAddress(InetAddress.getByName("3fff:1000::1")),
        )
    }

    @Test
    fun `NAT64 local-use is rejected and well-known NAT64 checks its embedded IPv4`() {
        assertFalse(ConfigImporter.isPublicInternetAddress(InetAddress.getByName("64:ff9b:1::808:808")))
        assertTrue(ConfigImporter.isPublicInternetAddress(InetAddress.getByName("64:ff9b::101:101")))
        assertFalse(ConfigImporter.isPublicInternetAddress(InetAddress.getByName("64:ff9b::7f00:1")))
    }

    @Test
    fun `IPv4-mapped IPv6 addresses inherit the embedded IPv4 policy`() {
        fun mapped(a: Int, b: Int, c: Int, d: Int): Inet6Address {
            val bytes = ByteArray(16).apply {
                this[10] = 0xff.toByte()
                this[11] = 0xff.toByte()
                this[12] = a.toByte()
                this[13] = b.toByte()
                this[14] = c.toByte()
                this[15] = d.toByte()
            }
            return Inet6Address.getByAddress(null, bytes, -1)
        }
        assertTrue(ConfigImporter.isPublicInternetAddress(mapped(1, 1, 1, 1)))
        assertFalse(ConfigImporter.isPublicInternetAddress(mapped(127, 0, 0, 1)))
    }

    @Test
    fun `IPv6 IANA non-global special-purpose ranges are rejected`() {
        listOf(
            "2001:2::1",       // benchmarking
            "2001:10::1",      // deprecated ORCHID
            "2001:10:ffff::1", // end of deprecated ORCHID /28
        ).forEach {
            assertFalse("$it must not be routable", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `registered globally reachable IANA anycast hosts remain accepted`() {
        listOf("2001:1::1", "2001:1::2", "2001:1::3").forEach {
            assertTrue("$it is globally reachable per IANA", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
        assertFalse(
            "2001:1::4 is not one of the registered global anycast hosts",
            ConfigImporter.isPublicInternetAddress(InetAddress.getByName("2001:1::4")),
        )
    }

    @Test
    fun `globally reachable IANA assignments are accepted`() {
        listOf(
            "2001:1::1", "2001:1::2", "2001:1::3",
            "2001:3::1", "2001:4:112::1",
            "2001:20::1", "2001:2f::1",
            "2001:30::1", "2001:3f::1",
        ).forEach {
            assertTrue("$it is globally reachable per IANA", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `unassigned IETF protocol addresses stay rejected`() {
        listOf(
            "2001::1", "2001:1::4", "2001:1:1::1",
            "2001:10::1", "2001:2::1",
        ).forEach {
            assertFalse("$it is not a globally reachable IANA exception", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
        }
    }

    @Test
    fun `a mixed public and private DNS answer is rejected as a whole`() {
        val addresses = listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("127.0.0.1"))
        assertThrows(ConfigImportException::class.java) {
            ConfigImporter.validateDirectDnsResults(addresses)
        }
    }

    @Test
    fun `an empty DNS answer is rejected`() {
        assertThrows(ConfigImportException::class.java) {
            ConfigImporter.validateDirectDnsResults(emptyList())
        }
    }

    @Test
    fun `validated public DNS answers are returned unchanged for connection pinning`() {
        val addresses = listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("8.8.8.8"))
        assertTrue(ConfigImporter.validateDirectDnsResults(addresses) === addresses)
    }

    @Test
    fun `HTTPS subscription URLs without embedded credentials are accepted`() {
        ConfigImporter.validateSubscriptionUrl(URL("https://subscriptions.example.net/config"))
    }

    @Test
    fun `cleartext HTTP subscription URLs are rejected`() {
        val error = assertThrows(ConfigImportException::class.java) {
            ConfigImporter.validateSubscriptionUrl(URL("http://subscriptions.example.net/config"))
        }
        assertTrue(error.message.orEmpty().contains("HTTPS"))
    }

    @Test
    fun `HTTPS URLs with embedded user info are rejected`() {
        assertThrows(ConfigImportException::class.java) {
            ConfigImporter.validateSubscriptionUrl(URL("https://user:secret@subscriptions.example.net/config"))
        }
    }

    @Test
    fun `relative and absolute HTTPS redirect targets are accepted`() {
        val base = URL("https://subscriptions.example.net/config")
        val relative = URL(base, "/v2/config")
        val absolute = URL(base, "https://cdn.example.net/v2/config")

        ConfigImporter.validateSubscriptionRedirect(relative, 1)
        ConfigImporter.validateSubscriptionRedirect(absolute, 2)
    }

    @Test
    fun `redirect targets cannot downgrade to HTTP`() {
        val base = URL("https://subscriptions.example.net/config")
        val downgrade = URL(base, "http://subscriptions.example.net/cleartext")

        assertThrows(ConfigImportException::class.java) {
            ConfigImporter.validateSubscriptionRedirect(downgrade, 1)
        }
    }

    @Test
    fun `five redirects are allowed`() {
        val target = URL("https://subscriptions.example.net/config")
        ConfigImporter.validateSubscriptionRedirect(target, 5)
    }

    @Test
    fun `sixth redirect is rejected`() {
        val target = URL("https://subscriptions.example.net/config")
        val error = assertThrows(ConfigImportException::class.java) {
            ConfigImporter.validateSubscriptionRedirect(target, 6)
        }
        assertTrue(error.message.orEmpty().contains("redirected too many times"))
    }
}
