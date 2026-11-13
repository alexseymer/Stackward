package dev.stackward.connection

import java.security.KeyPairGenerator
import java.security.PublicKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TofuHostKeyVerifierTest {

    private class FakePins : HostKeyPins {
        val pins = mutableMapOf<String, String>()
        override fun getPin(host: String, port: Int) = pins["$host:$port"]
        override fun savePin(host: String, port: Int, fingerprint: String) { pins["$host:$port"] = fingerprint }
    }

    private fun newKey(): PublicKey =
        KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public

    @Test
    fun firstConnect_pinsAndAccepts() {
        val pins = FakePins()
        val key = newKey()
        val verifier = TofuHostKeyVerifier(pins, "h", 22)

        assertTrue(verifier.verify("h", 22, key))
        assertEquals(HostKeyFingerprint.sha256Base64(key), pins.getPin("h", 22))
        assertEquals(pins.getPin("h", 22), verifier.lastFingerprint)
    }

    @Test
    fun sameKeyAgain_isAccepted() {
        val pins = FakePins()
        val key = newKey()
        assertTrue(TofuHostKeyVerifier(pins, "h", 22).verify("h", 22, key))
        assertTrue(TofuHostKeyVerifier(pins, "h", 22).verify("h", 22, key))
    }

    @Test
    fun changedKey_failsClosedAndKeepsTheOriginalPin() {
        val pins = FakePins()
        val original = newKey()
        TofuHostKeyVerifier(pins, "h", 22).verify("h", 22, original)
        val pinned = pins.getPin("h", 22)

        val error = assertThrows(SshException::class.java) {
            TofuHostKeyVerifier(pins, "h", 22).verify("h", 22, newKey())
        }

        assertTrue(error.message!!.contains("Possible MITM"))
        assertEquals(pinned, pins.getPin("h", 22))
    }

    @Test
    fun pinsAreKeyedPerHostAndPort() {
        val pins = FakePins()
        val key = newKey()
        TofuHostKeyVerifier(pins, "h", 22).verify("h", 22, key)

        assertNull(pins.getPin("h", 2222))
        assertNull(pins.getPin("other", 22))
        // A different port is a different trust anchor: first use there pins its own key.
        assertTrue(TofuHostKeyVerifier(pins, "h", 2222).verify("h", 2222, newKey()))
    }

    @Test
    fun fingerprint_isStandardBase64OfSha256_withoutWrapping() {
        val fp = HostKeyFingerprint.sha256Base64(newKey())
        assertEquals(44, fp.length) // 32 bytes -> 44 chars with padding
        assertTrue(!fp.contains("\n"))
        assertEquals("SHA256:$fp", HostKeyFingerprint.openSshLabel(fp))
    }
}
