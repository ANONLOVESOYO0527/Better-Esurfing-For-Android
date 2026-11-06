package dev.echoping.betteresurfing.service

import org.junit.Assert.*
import org.junit.Test

class KeepAliveLeaseTest {
    @Test fun disconnectAndStopReleaseBothLocks() {
        var cpu = false
        var wifi = false
        var acquisitions = 0
        val lease = KeepAliveLease(
            { cpu = true; acquisitions++ }, { cpu = false },
            { wifi = true }, { wifi = false },
        )
        lease.update(true)
        assertTrue(cpu && wifi)
        lease.update(true)
        assertEquals(1, acquisitions)
        lease.update(false)
        assertFalse(cpu || wifi)
        lease.update(true)
        assertTrue(cpu && wifi)
        lease.close()
        lease.close()
        assertFalse(cpu || wifi)
    }

    @Test fun failedWifiAcquireReleasesCpu() {
        var cpu = false
        val lease = KeepAliveLease(
            { cpu = true }, { cpu = false },
            { throw SecurityException("denied") }, {},
        )
        try { lease.update(true); fail("acquisition should fail") }
        catch (_: SecurityException) { }
        assertFalse(cpu)
    }
}
