package studio.cluvex.aether.custom

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class ConfigImporterSecurityTest {

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
