package org.openbridge.android.protocol

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ECDH P-256 (secp256r1) + HKDF-SHA256 + AES-256-GCM.
 * Compatible directly with Apple CryptoKit P256.KeyAgreement + HKDF + AES.GCM.
 */
object BridgeCrypto {
    private val SALT = "OpenBridge-v1".toByteArray(StandardCharsets.UTF_8)
    private val INFO = "session-aes-256-gcm".toByteArray(StandardCharsets.UTF_8)
    private val secureRandom = SecureRandom()

    private val p256Params: ECParameterSpec by lazy {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        (kpg.generateKeyPair().public as ECPublicKey).params
    }

    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun exportPublicKeyX963(publicKey: ECPublicKey): ByteArray {
        val w = publicKey.w
        val x = bigIntToFixed32(w.affineX)
        val y = bigIntToFixed32(w.affineY)
        val out = ByteArray(65)
        out[0] = 0x04
        System.arraycopy(x, 0, out, 1, 32)
        System.arraycopy(y, 0, out, 33, 32)
        return out
    }

    fun importPublicKeyX963(bytes: ByteArray): ECPublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) {
            "Expected 65-byte uncompressed P-256 public key"
        }
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val spec = ECPublicKeySpec(ECPoint(x, y), p256Params)
        return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
    }

    fun exportPrivateKeyPkcs8Base64(privateKey: ECPrivateKey): String {
        return Base64.getEncoder().encodeToString(privateKey.encoded)
    }

    fun importPrivateKeyPkcs8Base64(b64: String): ECPrivateKey {
        val bytes = Base64.getDecoder().decode(b64)
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes)) as ECPrivateKey
    }

    fun deriveSessionKey(privateKey: ECPrivateKey, peerPublicX963: ByteArray): ByteArray {
        val peerPub = importPublicKeyX963(peerPublicX963)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peerPub, true)
        val sharedSecret = agreement.generateSecret()
        return hkdfSha256(sharedSecret, SALT, INFO, 32)
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val nonce = ByteArray(12)
        secureRandom.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        val ciphertext = cipher.doFinal(plaintext)
        return nonce to ciphertext
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        require(nonce.size == 12) { "GCM nonce must be 12 bytes" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, nonce)
        )
        return cipher.doFinal(ciphertext)
    }

    /**
     * Seals an 86-byte IMA-ADPCM frame into a compact 111-byte BLE audio wire packet:
     * dir(1B) || counter_u64_be(8B) || AES-256-GCM(key, nonce=("OBA2"/"OBI2" || counter_u64_be), plaintext)
     */
    fun sealAudioPacket(key: ByteArray, direction: Byte, counter: Long, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key must be 32 bytes" }
        val nonce = buildAudioNonce(direction, counter)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plaintext)
        val packet = ByteArray(1 + 8 + sealed.size)
        packet[0] = direction
        for (i in 0 until 8) {
            packet[1 + i] = ((counter ushr (56 - i * 8)) and 0xFFL).toByte()
        }
        System.arraycopy(sealed, 0, packet, 9, sealed.size)
        return packet
    }

    fun openAudioPacket(key: ByteArray, expectedDirection: Byte, packet: ByteArray): ByteArray? {
        if (key.size != 32 || packet.size < 1 + 8 + 16) return null
        if (packet[0] != expectedDirection) return null
        var counter = 0L
        for (i in 0 until 8) {
            counter = (counter shl 8) or (packet[1 + i].toLong() and 0xFFL)
        }
        val nonce = buildAudioNonce(expectedDirection, counter)
        val ciphertext = packet.copyOfRange(9, packet.size)
        return runCatching { decrypt(key, nonce, ciphertext) }.getOrNull()
    }

    private fun buildAudioNonce(direction: Byte, counter: Long): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = 'O'.code.toByte()
        nonce[1] = 'B'.code.toByte()
        nonce[2] = if (direction == BridgeConstants.AUDIO_DIR_ANDROID_TO_IOS) 'A'.code.toByte() else 'I'.code.toByte()
        nonce[3] = '2'.code.toByte()
        for (i in 0 until 8) {
            nonce[4 + i] = ((counter ushr (56 - i * 8)) and 0xFFL).toByte()
        }
        return nonce
    }

    fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    fun fromBase64(b64: String): ByteArray = Base64.getDecoder().decode(b64)

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val result = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < length) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, toCopy)
            offset += toCopy
            counter++
        }
        return result
    }

    private fun bigIntToFixed32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) {
            System.arraycopy(raw, raw.size - 32, out, 0, 32)
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }
}
