package com.abhayshah.volumecontrol;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceKeyTest {
    @Test public void sameModelKeepsIdentityAcrossReconnectsAndPorts() {
        assertEquals(DeviceKey.of("usb", "1234:5678:1.0"), DeviceKey.of(" USB ", " 1234:5678:1.0 "));
    }
    @Test public void differentModelsAndTransportFamiliesStaySeparate() {
        assertNotEquals(DeviceKey.of("usb", "1234:5678:1.0"), DeviceKey.of("usb", "1234:9999:1.0"));
        assertNotEquals(DeviceKey.of("usb", "headphones"), DeviceKey.of("bluetooth", "headphones"));
    }
    @Test public void rawBluetoothAddressIsNeverStoredAsThePreferenceKey() {
        String key = DeviceKey.of("bluetooth", "AA:BB:CC:DD:EE:FF");
        assertFalse(key.contains("AA:BB")); assertEquals(39, key.length());
        assertEquals(key, DeviceKey.of("Bluetooth", "aa:bb:cc:dd:ee:ff"));
    }
}
