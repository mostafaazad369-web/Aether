package studio.cluvex.aether.custom

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import java.net.InetAddress
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
    fun `IPv6 unique local link local and documentation destinations are rejected`() {
        listOf("fc00::1", "fe80::1", "2001:db8::1", "2002:0808:0808::1").forEach {
            assertFalse("$it must not be routable", ConfigImporter.isPublicInternetAddress(InetAddress.getByName(it)))
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
