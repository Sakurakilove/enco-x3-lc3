package local.enco.lc3;

import java.util.Locale;

/** Validate configuration before any BluetoothDevice is obtained. */
final class TargetAddress {
    private TargetAddress() {}
    static boolean isEncoX3Name(String value) {
        if (value == null) return false;
        String name = value.replaceAll("\\s+", "").toLowerCase(Locale.US);
        return "oppoencox3".equals(name) || "encox3".equals(name);
    }
    static String normalize(String value) {
        if (value == null) throw new IllegalArgumentException("Missing target address");
        String address = value.trim().toUpperCase(Locale.US);
        if (!address.matches("(?:[0-9A-F]{2}:){5}[0-9A-F]{2}")
            || "00:00:00:00:00:00".equals(address)
            || "FF:FF:FF:FF:FF:FF".equals(address))
            throw new IllegalArgumentException("Enter the paired main earbud Bluetooth address, e.g. AA:BB:CC:DD:EE:FF");
        return address;
    }
}
