package net.sterling.automod.connection;

public final class AutoModConnectionState {
    private static volatile String lastServerAddress;

    private AutoModConnectionState() {
    }

    public static void rememberServerAddress(String address) {
        lastServerAddress = address;
    }

    public static String getLastServerAddress() {
        return lastServerAddress;
    }
}
