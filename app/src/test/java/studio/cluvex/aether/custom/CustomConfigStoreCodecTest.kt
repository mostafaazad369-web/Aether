package studio.cluvex.aether.custom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Pure JVM coverage for the persisted custom-config payload.
 *
 * These tests deliberately exercise the codec only: Android Keystore encryption
 * is covered separately by device/instrumentation testing, not mocked here.
 */
class CustomConfigStoreCodecTest {

    @Test
    fun `encoded state round trips credentials and ownership metadata`() {
        val config = CustomConfig(
            id = "config-1",
            name = "Private test server",
            protocol = ConfigProtocol.VLESS,
            format = ConfigFormat.URI,
            source = ConfigSource.LINK,
            outbound = """{"protocol":"vless","settings":{"id":"synthetic-secret"}}""",
            raw = "vless://synthetic-user@example.invalid:443",
            server = "example.invalid:443",
            locked = false,
            note = "synthetic note",
            expiresAt = 1_900_000_000_000L,
            mobileOnly = true,
            addedAt = 1_700_000_000_000L,
            groupId = "group-1",
            subscriptionId = "subscription-1",
        )
        val state = CustomConfigStore.State(
            configs = listOf(config),
            activeId = config.id,
            mode = ChainMode.CONFIG_AETHER,
            enabled = true,
            groups = listOf(CustomConfigStore.Group("group-1", "Private")),
            subscriptions = listOf(
                CustomConfigStore.Subscription(
                    id = "subscription-1",
                    name = "Synthetic feed",
                    url = "https://subscriptions.example.invalid/config",
                    updatedAt = 1_700_000_000_100L,
                    groupId = "group-1",
                ),
            ),
            verifyConnection = false,
            autoSelect = true,
            healthIntervalSeconds = 900,
            tabNames = mapOf(ConfigCatalog.ALL to "All servers"),
            activeTab = ConfigCatalog.customKey("group-1"),
        )

        val decoded = CustomConfigStore.decode(CustomConfigStore.encode(state))

        assertEquals(state, decoded)
        assertNotNull(decoded?.selection)
        assertEquals(config, decoded?.selection?.config)
        assertEquals(ChainMode.CONFIG_AETHER, decoded?.selection?.mode)
    }

    @Test
    fun `malformed or non object payloads are rejected`() {
        assertNull(CustomConfigStore.decode("{not-json"))
        assertNull(CustomConfigStore.decode("""["not", "an", "object"]"""))
        assertNull(CustomConfigStore.decode("null"))
    }

    @Test
    fun `unknown stored enum names fall back to safe defaults`() {
        val decoded = CustomConfigStore.decode(
            """{"v":4,"mode":"FUTURE_MODE","configs":[{"id":"future","name":"Future","protocol":"FUTURE_PROTOCOL","format":"FUTURE_FORMAT","source":"FUTURE_SOURCE","outbound":"{}"}],"active":"future","enabled":true}""",
        )

        assertNotNull(decoded)
        assertEquals(ChainMode.SOLO, decoded?.mode)
        assertEquals(ConfigProtocol.OTHER, decoded?.configs?.single()?.protocol)
        assertEquals(ConfigFormat.URI, decoded?.configs?.single()?.format)
        assertEquals(ConfigSource.LINK, decoded?.configs?.single()?.source)
    }
}
