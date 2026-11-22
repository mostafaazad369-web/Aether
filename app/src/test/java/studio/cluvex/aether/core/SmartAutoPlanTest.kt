package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.cluvex.aether.model.ConnectionProfile
import studio.cluvex.aether.model.EndpointMode
import studio.cluvex.aether.model.Noize
import studio.cluvex.aether.model.ScanMode

class SmartAutoPlanTest {

    private fun fingerprint() = NetworkFingerprint(
        dpiClass = DpiClass.OPEN,
        udpOk = true,
        tlsSniOk = true,
        operatorName = "test",
        iranCellular = false,
        edgeLatencyMs = mapOf("162.159.192.0/24" to 24L),
        externalTcpOk = true,
        internetCapability = true,
        validated = true,
        accessClass = NetworkAccessClass.INTERNATIONAL_REACHABLE,
    )

    @Test
    fun pinnedPeerIsPreservedThroughEveryCandidateAndFallbackLabelIsHonest() {
        val user = ConnectionProfile(
            endpointMode = EndpointMode.MANUAL_PEER,
            manualPeer = "203.0.113.7:2408",
            noize = Noize.AGGRESSIVE,
            scanMode = ScanMode.THOROUGH,
        )

        val plan = SmartAuto.buildPlan(user, fingerprint())

        assertTrue(plan.isNotEmpty())
        assertTrue(plan.all { it.profile.endpointMode == EndpointMode.MANUAL_PEER })
        assertTrue(plan.all { it.profile.manualPeer == user.manualPeer })
        assertTrue(plan.all { it.profile.noize == Noize.AGGRESSIVE })
        assertTrue(plan.all { it.profile.scanMode == ScanMode.TURBO } ||
            plan.last().profile.scanMode == ScanMode.THOROUGH)

        val fallback = plan.last()
        assertEquals(ScanMode.THOROUGH, fallback.profile.scanMode)
        assertFalse(fallback.label.contains("full built-in ranges"))
        assertTrue(fallback.label.contains("user-pinned endpoint"))
    }

    @Test
    fun automaticEndpointUsesProbeNarrowingThenRestoresFullRangeFallback() {
        val plan = SmartAuto.buildPlan(ConnectionProfile(), fingerprint())

        assertEquals(EndpointMode.MANUAL_RANGE, plan.first().profile.endpointMode)
        assertEquals("162.159.192.0/24", plan.first().profile.manualRange)
        assertEquals(EndpointMode.AUTO, plan.last().profile.endpointMode)
        assertTrue(plan.last().label.contains("full built-in ranges"))
    }
}
