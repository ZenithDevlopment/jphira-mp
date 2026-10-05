package top.rymc.phira.main.network;

import top.rymc.phira.main.Server;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Caps how many sockets the server accepts, in total and per source address.
 *
 * <p>Without this a single host can open thousands of connections and starve everyone else's
 * workers, and a reconnect storm after a network hiccup inflates the count unexpectedly.
 */
public final class ConnectionLimiter {

    /**
     * Generous on purpose: an event venue usually shares one egress address, so a tight per
     * address cap would lock out the whole room rather than a single abusive client.
     */
    private static final int MAX_PER_ADDRESS = 64;

    private static final AtomicInteger TOTAL = new AtomicInteger();
    private static final Map<String, AtomicInteger> PER_ADDRESS = new ConcurrentHashMap<>();

    private ConnectionLimiter() {
    }

    /** Reserves a slot. Returns {@code null} when accepted, otherwise the reason to refuse. */
    public static String accept(InetAddress address) {
        int max = Server.getInstance().getArgs().getMaxConnections();
        if (TOTAL.get() >= max) {
            return "connection limit reached (" + max + ")";
        }

        String key = address.getHostAddress();
        AtomicInteger perAddress = PER_ADDRESS.computeIfAbsent(key, ignored -> new AtomicInteger());
        if (perAddress.get() >= MAX_PER_ADDRESS) {
            return "too many connections from " + key;
        }

        TOTAL.incrementAndGet();
        perAddress.incrementAndGet();
        return null;
    }

    public static void release(InetAddress address) {
        TOTAL.decrementAndGet();
        if (address == null) {
            return;
        }
        AtomicInteger perAddress = PER_ADDRESS.get(address.getHostAddress());
        if (perAddress != null && perAddress.decrementAndGet() <= 0) {
            PER_ADDRESS.remove(address.getHostAddress(), perAddress);
        }
    }

    public static int count() {
        return TOTAL.get();
    }
}
