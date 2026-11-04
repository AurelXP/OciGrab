package com.aurelien.ocigrab

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec

data class GeneratedKey(
    val privatePem: String,      // PKCS#1 "BEGIN RSA PRIVATE KEY"
    val publicPem: String,       // "BEGIN PUBLIC KEY"
    val openSshPublic: String,   // "ssh-rsa AAAA..."
    val fingerprint: String      // MD5 aa:bb:cc (format Oracle)
)

object KeyUtil {

    private fun derLen(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLen(content.size) + content

    private fun derInt(v: BigInteger): ByteArray = der(0x02, v.toByteArray())

    private fun pem(label: String, der: ByteArray): String {
        val b64 = Base64.encodeToString(der, Base64.NO_WRAP)
        return "-----BEGIN $label-----\n" + b64.chunked(64).joinToString("\n") + "\n-----END $label-----\n"
    }

    private fun sshString(b: ByteArray): ByteArray {
        val l = b.size
        return byteArrayOf((l shr 24).toByte(), (l shr 16).toByte(), (l shr 8).toByte(), l.toByte()) + b
    }

    private fun md5Hex(data: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(data).joinToString(":") { "%02x".format(it) }

    fun generate(bits: Int, comment: String): GeneratedKey {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(bits)
        val kp = kpg.generateKeyPair()
        val priv = (kp.private as? RSAPrivateCrtKey)
            ?: (KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kp.private.encoded)) as RSAPrivateCrtKey)
        val pub = kp.public as RSAPublicKey

        val body = derInt(BigInteger.ZERO) + derInt(priv.modulus) + derInt(priv.publicExponent) +
            derInt(priv.privateExponent) + derInt(priv.primeP) + derInt(priv.primeQ) +
            derInt(priv.primeExponentP) + derInt(priv.primeExponentQ) + derInt(priv.crtCoefficient)
        val privatePem = pem("RSA PRIVATE KEY", der(0x30, body))
        val publicPem = pem("PUBLIC KEY", pub.encoded)

        val blob = sshString("ssh-rsa".toByteArray(Charsets.US_ASCII)) +
            sshString(pub.publicExponent.toByteArray()) +
            sshString(pub.modulus.toByteArray())
        val ssh = "ssh-rsa " + Base64.encodeToString(blob, Base64.NO_WRAP) + " " + comment

        return GeneratedKey(privatePem, publicPem, ssh, md5Hex(pub.encoded))
    }

    // Convertit une clé PKCS#1 ("BEGIN RSA PRIVATE KEY") en PKCS#8 lisible par Android
    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        val algId = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(),
            0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00
        )
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val inner = version + algId + der(0x04, pkcs1)
        return der(0x30, inner)
    }

    fun loadPrivate(pem: String): PrivateKey {
        require(!pem.contains("ENCRYPTED")) { "clé protégée par un mot de passe : génère-en une sans mot de passe" }
        require(!pem.contains("BEGIN OPENSSH PRIVATE KEY")) {
            "format OpenSSH non géré : utilise une clé PEM (BEGIN PRIVATE KEY ou BEGIN RSA PRIVATE KEY)"
        }
        require(pem.contains("PRIVATE KEY")) {
            when {
                pem.contains("PUBLIC KEY") -> "c'est la clé PUBLIQUE ; il faut la clé PRIVÉE (celle qui contient « PRIVATE KEY »)"
                pem.contains("CERTIFICATE") -> "c'est un certificat, pas une clé privée"
                else -> "aucune clé privée trouvée (elle commence par -----BEGIN PRIVATE KEY-----)"
            }
        }
        // On ne garde que ce qui est entre BEGIN et END : Oracle ajoute parfois une ligne « OCI_API_KEY » après.
        val m = Regex("-----BEGIN (RSA )?PRIVATE KEY-----(.*?)-----END (RSA )?PRIVATE KEY-----", RegexOption.DOT_MATCHES_ALL)
            .find(pem)
        require(m != null) { "clé incomplète : il manque la ligne -----END PRIVATE KEY-----" }
        val isPkcs1 = m.groupValues[1].isNotEmpty()
        val b64 = m.groupValues[2].replace("\\s".toRegex(), "")
        var bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("clé abîmée (caractères invalides), recopie-la en entier")
        }
        if (isPkcs1) bytes = wrapPkcs1(bytes)
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }

    fun publicFromPrivate(priv: PrivateKey): PublicKey {
        val c = priv as RSAPrivateCrtKey
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(c.modulus, c.publicExponent))
    }

    fun publicPemOfPrivatePem(privPem: String): String =
        pem("PUBLIC KEY", publicFromPrivate(loadPrivate(privPem)).encoded)

    fun fingerprintOfPrivatePem(privPem: String): String =
        md5Hex(publicFromPrivate(loadPrivate(privPem)).encoded)
}
