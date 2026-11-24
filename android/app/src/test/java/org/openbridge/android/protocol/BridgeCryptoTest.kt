package org.openbridge.android.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

class BridgeCryptoTest {
    @Test
    fun ecdhP256AndAesGcmRoundTrip() {
        val androidKp = BridgeCrypto.generateKeyPair()
        val iosKp = BridgeCrypto.generateKeyPair()

        val androidPub = BridgeCrypto.exportPublicKeyX963(androidKp.public as ECPublicKey)
        val iosPub = BridgeCrypto.exportPublicKeyX963(iosKp.public as ECPublicKey)

        assertEquals(65, androidPub.size)
        assertEquals(65, iosPub.size)

        val androidKey = BridgeCrypto.deriveSessionKey(androidKp.private as ECPrivateKey, iosPub)
        val iosKey = BridgeCrypto.deriveSessionKey(iosKp.private as ECPrivateKey, androidPub)

        assertArrayEquals(androidKey, iosKey)

        val secretMsg = "رمز پویا: 482910".toByteArray(StandardCharsets.UTF_8)
        val (nonce, cipher) = BridgeCrypto.encrypt(androidKey, secretMsg)
        val decrypted = BridgeCrypto.decrypt(iosKey, nonce, cipher)

        assertArrayEquals(secretMsg, decrypted)
    }
}
