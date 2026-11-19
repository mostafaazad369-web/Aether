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
    @Test
    fun captivePortalIsNotMisreportedAsAnInternationalShutdown() {
        assertEquals(
            NetworkAccessClass.CAPTIVE_PORTAL,
            SmartAuto.classifyAccess(
                internetCapability = true,
                validated = false,
                externalTcpOk = false,
                tlsSniOk = false,
                captivePortal = true,
            ),
        )
    }

    @Test
    fun localResolverWithoutPublicTcpIsClassifiedAsLocalNetworkOnly() {
        assertEquals(
            NetworkAccessClass.LOCAL_NETWORK_ONLY,
            SmartAuto.classifyAccess(
                internetCapability = true,
                validated = false,
                externalTcpOk = false,
                tlsSniOk = false,
                localResolverOk = true,
            ),
        )
    }

    @Test
    fun missingActiveNetworkIsOfflineNotLocalOnly() {
        assertEquals(
            NetworkAccessClass.OFFLINE,
            SmartAuto.classifyAccess(
                internetCapability = false,
                validated = false,
                externalTcpOk = false,
                tlsSniOk = false,
                networkAvailable = false,
            ),
        )
    }

    @Test
    fun localResolverWithValidatedButNoExternalTcpRemainsUnknown() {
        assertEquals(
            NetworkAccessClass.UNKNOWN,
            SmartAuto.classifyAccess(
                internetCapability = true,
                validated = true,
                externalTcpOk = false,
                tlsSniOk = false,
                localResolverOk = true,
            ),
        )
    }

}
