package com.aaai.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.security.MessageDigest

class DeterministicKeyTest {

    private fun hash(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun sameSeedGivesIdenticalCertificateAndKey() {
        val a = DeterministicKey.generate(DeterministicKey.seedFor("0123456789abcdef"))
        val b = DeterministicKey.generate(DeterministicKey.seedFor("0123456789abcdef"))
        assertArrayEquals(a.certificate.encoded, b.certificate.encoded)
        assertArrayEquals(a.privateKey.encoded, b.privateKey.encoded)
    }

    @Test
    fun differentAndroidIdGivesDifferentCertificate() {
        val a = DeterministicKey.generate(DeterministicKey.seedFor("0123456789abcdef"))
        val b = DeterministicKey.generate(DeterministicKey.seedFor("fedcba9876543210"))
        assertFalse(a.certificate.encoded.contentEquals(b.certificate.encoded))
    }

    @Test
    fun missingAndroidIdStillWorksAndIsStable() {
        val a = DeterministicKey.generate(DeterministicKey.seedFor(null))
        val b = DeterministicKey.generate(DeterministicKey.seedFor("  "))
        assertArrayEquals(a.certificate.encoded, b.certificate.encoded)
    }

    /** Prints the reference hash to pin in [referenceCertificateIsPinned]. */
    @Test
    fun printReferenceHash() {
        val cert = DeterministicKey.generate(DeterministicKey.seedFor("0123456789abcdef")).certificate
        println("REFERENCE_CERT_SHA256=" + hash(cert.encoded))
    }

    /** Fails if anything that shapes the key (salt, BouncyCastle version, parameters...) changes. */
    @Test
    fun referenceCertificateIsPinned() {
        if (REFERENCE.isEmpty()) return
        val cert = DeterministicKey.generate(DeterministicKey.seedFor("0123456789abcdef")).certificate
        assertEquals(REFERENCE, hash(cert.encoded))
    }

    companion object {
        private const val REFERENCE = "49c1f845e808c7b94f7808499a38ec25abac22036c36f7cb1fc1b0109646b563"
    }
}
