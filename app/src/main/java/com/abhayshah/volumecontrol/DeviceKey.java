package com.abhayshah.volumecontrol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class DeviceKey {
    private DeviceKey() {}
    static String of(String family, String identity) {
        String normalized = family.trim().toLowerCase(Locale.ROOT) + ":" + identity.trim().toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder("device_");
            for (int i = 0; i < 16; i++) key.append(String.format(Locale.ROOT, "%02x", digest[i] & 255));
            return key.toString();
        } catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
}
