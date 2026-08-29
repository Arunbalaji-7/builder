package com.walgreens.rawxmldatapuller.util;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for sensitive config values stored in MySQL.
 *
 * <p>Encrypted values are prefixed with {@code "ENC:"} so plaintext values
 * (e.g. from application.properties fallback) are never accidentally decrypted.</p>
 *
 * <p>Format on disk: {@code ENC:<base64(12-byte-IV || ciphertext+auth-tag)>}</p>
 *
 * <p>The 256-bit key is read from {@code app.encryption.key} in
 * {@code application.properties} as a 64-character hex string.
 * <strong>Do not change the key once passwords have been saved — they will
 * become unreadable.</strong></p>
 */
public final class CryptoUtil {

    public  static final String ENC_PREFIX = "ENC:";
    private static final String ALGORITHM  = "AES/GCM/NoPadding";
    private static final int    IV_BYTES   = 12;
    private static final int    TAG_BITS   = 128;

    private CryptoUtil() {}

    /**
     * Encrypts {@code plaintext} with AES-256-GCM using the supplied hex key.
     * Returns the value unchanged if it is already encrypted or blank.
     */
    public static String encrypt(String plaintext, String keyHex) {
        if (plaintext == null || plaintext.isBlank() || isEncrypted(plaintext)) return plaintext;
        try {
            byte[] iv = new byte[IV_BYTES];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(hexToBytes(keyHex), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct  = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Encode as IV || ciphertext
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0,       IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed for config value", e);
        }
    }

    /**
     * Decrypts an {@code "ENC:..."} value back to plaintext.
     * Returns the value unchanged if it is not prefixed with {@code "ENC:"}.
     */
    public static String decrypt(String value, String keyHex) {
        if (!isEncrypted(value)) return value;
        try {
            byte[] combined = Base64.getDecoder().decode(value.substring(ENC_PREFIX.length()));
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(combined, IV_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(hexToBytes(keyHex), "AES"),
                    new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed for config value", e);
        }
    }

    /** Returns {@code true} if {@code value} was produced by {@link #encrypt}. */
    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX);
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0)
            throw new IllegalArgumentException("Invalid encryption key format (expected 64-char hex)");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        return out;
    }
}

