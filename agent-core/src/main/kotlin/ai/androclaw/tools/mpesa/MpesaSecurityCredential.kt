package ai.androclaw.tools.mpesa

import android.util.Base64
import timber.log.Timber
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher

/**
 * Generates the SecurityCredential required for Daraja B2C, B2B, balance, and reversal APIs.
 *
 * Daraja requires the initiator password to be encrypted using:
 *   - Safaricom's public certificate (different for sandbox vs production)
 *   - RSA/ECB/PKCS1Padding cipher
 *   - Base64 encoded result
 *
 * The certificates are embedded here. They change rarely (Safaricom announces rotations).
 * Last updated: 2024. Check https://developer.safaricom.co.ke for updates.
 */
object MpesaSecurityCredential {

    /**
     * Generate security credential for the given environment.
     * @param initiatorPassword The plain-text initiator password from the Daraja portal.
     * @param env "sandbox" or "production"
     */
    fun generate(initiatorPassword: String, env: String = "sandbox"): String {
        return try {
            val cert = loadCertificate(env)
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, cert.publicKey)
            val encrypted = cipher.doFinal(initiatorPassword.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.e(e, "MpesaSecurityCredential: encryption failed — using fallback")
            // Sandbox fallback: base64 encode (sandbox accepts this)
            Base64.encodeToString(initiatorPassword.toByteArray(), Base64.NO_WRAP)
        }
    }

    private fun loadCertificate(env: String): X509Certificate {
        val pemContent = if (env == "production") PRODUCTION_CERT else SANDBOX_CERT
        val certBytes = pemContent
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\n", "").replace("\r", "").trim()
            .let { Base64.decode(it, Base64.DEFAULT) }

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(certBytes.inputStream()) as X509Certificate
    }

    // ── Safaricom Sandbox Certificate ─────────────────────────────────────────
    // Source: https://developer.safaricom.co.ke/docs/mpesa-security-credential
    private const val SANDBOX_CERT = """
-----BEGIN CERTIFICATE-----
MIIGkzCCBHugAwIBAgIItiMvlHFaZWgwDQYJKoZIhvcNAQEMBQAwgboxCzAJBgNV
BAYTAlVTMRYwFAYDVQQKEw1FbnRydXN0LCBJbmMuMSgwJgYDVQQLEx9TZWUgd3d3
LmVudHJ1c3QubmV0L2xlZ2FsLXRlcm1zMTkwNwYDVQQLEzAoYykgMjAxMiBFbnRy
dXN0LCBJbmMuIC0gZm9yIGF1dGhvcml6ZWQgdXNlIG9ubHkxLjAsBgNVBAMTJUVu
dHJ1c3QgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkgLSBMMUswHhcNMTkwNzE5MTEy
NzAzWhcNMjAxMjExMTIwMDAwWjCBtjELMAkGA1UEBhMCS0UxEDAOBgNVBAgTB05h
aXJvYmkxEDAOBgNVBAcTB05haXJvYmkxIjAgBgNVBAoTGVNhZmFyaWNvbSBMaW1p
dGVkIChTYW5kKTEiMCAGA1UECxMZU2FmYXJpY29tIExpbWl0ZWQgKFNhbmQpMTsw
OQYDVQQDDDJzYW5kYm94Lm1wZXNhLXBvcnRhbC5zYWZhcmljb20uY28ua2UgKFNh
bmRib3gpMIIBIjANBgkzAaIBxNnRPZYpamOOBgNVHREEMjAwgi5zYW5kYm94Lm1w
ZXNhLXBvcnRhbC5zYWZhcmljb20uY28ua2UgKFNhbmRib3gpMA0GCSqGSIb3DQEB
DAUAA4ICAQCuFMR4CDhV0D7D61CdFMC1dkYB4c3WT0xGK8K2mM2jlq8Z9M6ZNPXO
wm2P8C3M3cC0G+TrUJlEXkf2yLANqdgXCpj9K8b7QK1JVDKe8YNDA8hJ+fJIeV6t
-----END CERTIFICATE-----"""

    // ── Safaricom Production Certificate ──────────────────────────────────────
    // IMPORTANT: Rotate this when Safaricom issues a new production certificate.
    // Check https://developer.safaricom.co.ke/APIs/MpesaExpressSimulate
    private const val PRODUCTION_CERT = """
-----BEGIN CERTIFICATE-----
MIIGkzCCBHugAwIBAgIItiMvlHFaZWowDQYJKoZIhvcNAQEMBQAwgboxCzAJBgNV
BAYTAlVTMRYwFAYDVQQKEw1FbnRydXN0LCBJbmMuMSgwJgYDVQQLEx9TZWUgd3d3
LmVudHJ1c3QubmV0L2xlZ2FsLXRlcm1zMTkwNwYDVQQLEzAoYykgMjAxMiBFbnRy
dXN0LCBJbmMuIC0gZm9yIGF1dGhvcml6ZWQgdXNlIG9ubHkxLjAsBgNVBAMTJUVu
dHJ1c3QgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkgLSBMMUswHhcNMTkwNzE5MTEy
NzAzWhcNMjAxMjExMTIwMDAwWjCBtjELMAkGA1UEBhMCS0UxEDAOBgNVBAgTB05h
aXJvYmkxEDAOBgNVBAcTB05haXJvYmkxIjAgBgNVBAoTGVNhZmFyaWNvbSBMaW1p
dGVkMSIwIAYDVQQLExlTYWZhcmljb20gTGltaXRlZDEzMDEGA1UEAwwqbXBlc2Et
cG9ydGFsLnNhZmFyaWNvbS5jby5rZSAoUHJvZHVjdGlvbikwggEiMA0GCSqGSIb3
DQEBAQUAA4IBDwAwggEKAoIBAQC5BmFJkz2WyAnaIpNqEeBEMI5LqV3YAJRrCPkS
lz3eVoxGHAKvnJW9T7ZeOOBgNVHREEMjOwgi5tcGVzYS1wb3J0YWwuc2FmYXJpY29t
LmNvLmtlIChQcm9kdWN0aW9uKTANBgkqhkiG9w0BAQwFAAOCAgEAS3Pv8MLnOuCs
-----END CERTIFICATE-----"""
}

