package ru.rim.dd.core.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Восстановление «битого» сопряжения: один раз за подключение, подсказка — только при неудаче. */
class BondRecoveryTest {

    @Test
    fun firstFailure_removesBondAndDropsConnection() {
        val recovery = BondRecovery()
        var removed = 0
        var dropped = 0
        val started = recovery.onEncryptionFailure(removeBond = { removed++; true }, dropConnection = { dropped++ })
        assertTrue(started)
        assertEquals(1, removed)
        assertEquals(1, dropped)
        assertNull(recovery.hint)
    }

    @Test
    fun secondFailureInSameConnection_isIgnored() {
        val recovery = BondRecovery()
        var removed = 0
        recovery.onEncryptionFailure({ removed++; true }, {})
        val again = recovery.onEncryptionFailure({ removed++; true }, { error("не должно вызываться") })
        assertFalse(again)
        assertEquals(1, removed)
    }

    @Test
    fun bondNotRemoved_givesUserHint_andStillDropsConnection() {
        val recovery = BondRecovery()
        var dropped = false
        recovery.onEncryptionFailure(removeBond = { false }, dropConnection = { dropped = true })
        assertTrue(dropped)
        assertEquals(BondRecovery.STALE_PAIRING_HINT, recovery.hint)
    }

    @Test
    fun reset_allowsRecoveryAgain_andClearsHint() {
        val recovery = BondRecovery()
        recovery.onEncryptionFailure({ false }, {})
        recovery.reset()
        assertNull(recovery.hint)
        assertTrue(recovery.onEncryptionFailure({ true }, {}))
    }
}
