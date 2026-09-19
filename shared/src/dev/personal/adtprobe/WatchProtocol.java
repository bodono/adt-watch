package dev.personal.adtprobe;

/** Shared node-identity rule and the phone's advertised capability. There is no ping protocol. */
public final class WatchProtocol {
    public static final String PHONE_CAPABILITY = "adt_probe_phone_v1";

    private WatchProtocol() { }

    public static boolean validNodeId(String value) {
        return value != null && !value.isEmpty() && value.length() <= 256
            && value.indexOf('\n') < 0 && value.indexOf('\r') < 0;
    }
}
