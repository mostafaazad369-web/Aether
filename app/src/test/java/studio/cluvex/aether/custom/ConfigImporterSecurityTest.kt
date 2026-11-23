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
    fun `redirect targets are subject to the same HTTPS policy`() {
        val base = URL("https://subscriptions.example.net/config")
        val secure = URL(base, "/v2/config")
        ConfigImporter.validateSubscriptionUrl(secure)

        val downgrade = URL(base, "http://subscriptions.example.net/cleartext")
        assertThrows(ConfigImportException::class.java) {
            ConfigImporter.validateSubscriptionUrl(downgrade)
        }
    }
}
