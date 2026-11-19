package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAccessClassificationTest {

    @Test
    fun validatedAndTlsReachableMeansInternationalReachable() {
        assertEquals(
            NetworkAccessClass.INTERNATIONAL_REACHABLE,
            SmartAuto.classifyAccess(
                internetCapability = true,
                validated = true,
                externalTcpOk = true,
                tlsSniOk = true,
            ),
        )
    }

    @Test
    fun tcpReachableButTlsFailsMeansPossibleDpiInterference() {
        assertEquals(
            NetworkAccessClass.DPI_OR_TLS_INTERFERENCE,
            SmartAuto.classifyAccess(true, false, true, false),
        )
    }

    @Test
    fun internetCapabilityWithoutValidationOrTcpSuggestsInternationalRestriction() {
        assertEquals(
            NetworkAccessClass.INTERNATIONAL_RESTRICTED,
            SmartAuto.classifyAccess(true, false, false, false),
        )
    }

    @Test
    fun noInternetCapabilityMeansLocalOnly() {
        assertEquals(
            NetworkAccessClass.LOCAL_ONLY,
            SmartAuto.classifyAccess(false, false, false, false),
        )
    }

    @Test
    fun conflictingSignalsRemainUnknown() {
        assertEquals(
            NetworkAccessClass.UNKNOWN,
            SmartAuto.classifyAccess(true, true, false, false),
        )
    }
}
