package com.abhayshah.volumecontrol;

import android.content.Context;
import android.hardware.usb.*;
import android.media.*;
import java.util.ArrayList;

/** Connected-device memory, not a claim to know the currently routed app's output. */
final class OutputProfile {
    final String key, label;
    OutputProfile(String family, String identity, String label) {
        this.key = DeviceKey.of(family, identity); this.label = label;
    }
    static OutputProfile read(Context context, AudioManager audio) {
        String family = VolumeService.connectedProfile(audio);
        String label = switch (family) {
            case "usb" -> "USB audio"; case "wired" -> "Wired headphones";
            case "bluetooth" -> "Bluetooth"; case "multiple" -> "Multiple outputs connected";
            default -> "Phone speaker";
        };
        try {
            if ("usb".equals(family)) {
                UsbManager manager = context.getSystemService(UsbManager.class);
                ArrayList<UsbDevice> candidates = new ArrayList<>();
                if (manager != null) for (UsbDevice d : manager.getDeviceList().values()) {
                    boolean sound = d.getDeviceClass() == UsbConstants.USB_CLASS_AUDIO;
                    for (int i = 0; i < d.getInterfaceCount(); i++) sound |= d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_AUDIO;
                    if (sound) candidates.add(d);
                }
                if (candidates.size() == 1) {
                    UsbDevice d = candidates.get(0);
                    String identity = d.getVendorId() + ":" + d.getProductId() + ":" + d.getVersion();
                    String product = d.getProductName();
                    // No permission prompt or serial-number access. Identical USB models share a profile.
                    return new OutputProfile("usb", identity, product == null || product.trim().isEmpty() ? "USB headphones" : product);
                }
            }
            if ("usb".equals(family) || "bluetooth".equals(family)) {
                ArrayList<AudioDeviceInfo> candidates = new ArrayList<>();
                for (AudioDeviceInfo d : audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                    int type = d.getType();
                    boolean match = "usb".equals(family)
                            ? type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
                            : type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLE_HEADSET;
                    if (match) candidates.add(d);
                }
                if (candidates.size() == 1) {
                    AudioDeviceInfo d = candidates.get(0);
                    String name = String.valueOf(d.getProductName());
                    String address = d.getAddress();
                    // Use a Bluetooth address only if Android exposes it; never persist the raw address.
                    if ("bluetooth".equals(family) && address != null
                            && address.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
                            && !address.equals("00:00:00:00:00:00") && !address.equals("02:00:00:00:00:00")) {
                        return new OutputProfile(family, address, name);
                    }
                    return new OutputProfile(family, "model:" + name, name);
                }
            }
        } catch (RuntimeException unavailable) { /* Hardware enumeration must not stop volume control. */ }
        return new OutputProfile(family, "default", label);
    }
}
