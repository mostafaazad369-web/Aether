package studio.cluvex.aether.custom

import org.junit.Assert.*
import org.junit.Test
import studio.cluvex.aether.custom.ChainBuilderPolicy.Core
import studio.cluvex.aether.model.TransportBackend

class ChainBuilderPolicyTest {
    @Test fun everyOfferedChainMapsToOneRunnableConfiguration() {
        assertEquals(TransportBackend.entries.size + ChainMode.entries.size, ChainBuilderPolicy.all.size)
        for (chain in ChainBuilderPolicy.all) {
            val target = requireNotNull(ChainBuilderPolicy.target(chain)) { "$chain" }
            if (Core.CONFIG in chain) {
                assertTrue(target.customEnabled)
                assertEquals(chain, ChainBuilderPolicy.coresOf(requireNotNull(target.mode)))
            } else {
                assertFalse(target.customEnabled)
                assertEquals(chain, ChainBuilderPolicy.coresOf(requireNotNull(target.backend)))
            }
        }
        assertNull(ChainBuilderPolicy.target(listOf(Core.PSIPHON)))
        assertNull(ChainBuilderPolicy.target(listOf(Core.AETHER, Core.CONFIG, Core.TOR)))
    }

    @Test fun nodeOptionsOnlyOfferSupportedChains() {
        assertEquals(setOf(Core.AETHER, Core.TOR, Core.CONFIG),
            ChainBuilderPolicy.options(emptyList(), 1, 0).toSet())
        assertEquals(setOf(Core.AETHER, Core.PSIPHON, Core.TOR),
            ChainBuilderPolicy.options(listOf(Core.CONFIG), 2, 1).toSet())
    }

    @Test fun growingAChainDoesNotSilentlyAddConfig() {
        assertEquals(listOf(Core.AETHER, Core.PSIPHON),
            ChainBuilderPolicy.complete(listOf(Core.AETHER), 2, listOf(Core.AETHER)))
        assertEquals(listOf(Core.TOR, Core.AETHER),
            ChainBuilderPolicy.complete(listOf(Core.TOR), 2, listOf(Core.TOR)))
        assertEquals(listOf(Core.CONFIG, Core.AETHER),
            ChainBuilderPolicy.complete(listOf(Core.CONFIG), 2, listOf(Core.CONFIG)))
        assertTrue(ChainBuilderPolicy.lengthNeedsConfig(3))
        assertFalse(ChainBuilderPolicy.lengthNeedsConfig(2))
    }
}
