package studio.cluvex.aether.core

import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityVaultLeaseTest {
    @Test
    fun eachLeaseCanOnlyReleaseItsOwnEngineCountOnce() {
        val dir = Files.createTempDirectory("aether-vault-lease").toFile()
        val first = IdentityVault.acquireEngine(dir)
        val second = IdentityVault.acquireEngine(dir)
        try {
            assertTrue(IdentityVault.running)
            first.release()
            assertTrue("second engine must retain identity ownership", IdentityVault.running)
            assertTrue("duplicate release must be ignored", first.release() == null)
            assertTrue(IdentityVault.running)
        } finally {
            second.release()
            dir.deleteRecursively()
        }
        assertFalse("last lease release must return the vault to idle", IdentityVault.running)
    }

    @Test
    fun closeIsAnIdempotentAliasForRelease() {
        val dir = Files.createTempDirectory("aether-vault-close").toFile()
        val lease = IdentityVault.acquireEngine(dir)
        try {
            lease.close()
            lease.close()
            assertFalse(IdentityVault.running)
            assertTrue(lease.release() == null)
        } finally {
            dir.deleteRecursively()
        }
    }
}
