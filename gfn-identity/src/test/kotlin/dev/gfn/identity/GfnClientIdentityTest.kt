package dev.gfn.identity

import kotlin.test.Test
import kotlin.test.assertEquals

class GfnClientIdentityTest {
    @Test
    fun windowsIdentityMatchesProtocolBaseline() {
        val identity = GfnClientIdentity.WindowsDesktop
        assertEquals("GFN-PC", identity.clientIdentification)
        assertEquals("windows", identity.clientPlatformName)
        assertEquals("WINDOWS", identity.protocolHeaders().getValue("NV-Device-OS"))
        assertEquals("DESKTOP", identity.protocolHeaders().getValue("NV-Device-Type"))
    }
}
