package top.rymc.phira.main.game.room;

import top.rymc.phira.main.Server;
import top.rymc.phira.main.game.exception.GameOperationException;
import top.rymc.phira.main.util.ExecutorServiceManager;
import top.rymc.phira.main.util.ThreadFactoryCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public class RoomManager {
    private static final Map<String, Room> ROOMS = new ConcurrentHashMap<>();
    /** Interval between sweeps; the threshold itself comes from {@code --empty-room-ttl}. */
    private static final long WATCHDOG_INTERVAL_SECONDS = 30;
    /** When the room was first seen empty; only meaningful while it stays empty. */
    private static final Map<String, Long> EMPTY_SINCE = new ConcurrentHashMap<>();

    public static <T extends Room> T resolveRoom(String roomId, Function<Runnable, T> constructor) {
        int maxRooms = Server.getInstance().getArgs().getMaxRooms();
        if (ROOMS.size() >= maxRooms) {
            throw GameOperationException.roomLimitReached(maxRooms);
        }

        AtomicReference<T> reference = new AtomicReference<>();
        ROOMS.compute(roomId, (id, exist) -> {
            if (exist != null) {
                throw GameOperationException.roomAlreadyExists();
            }

            T room = constructor.apply(() -> forget(roomId));
            reference.set(room);
            return room;
        });
        T room = reference.get();
        if (room == null) {
            throw new AssertionError();
        }

        return room;
    }

    public static Room findRoom(String roomId) {
        return ROOMS.get(roomId);
    }

    public static void removeRoom(String roomId) {
        ROOMS.remove(roomId);
        EMPTY_SINCE.remove(roomId);
    }

    public static List<Room> getAllRooms() {
        return new ArrayList<>(ROOMS.values());
    }

    /** Reclaims rooms nobody is in, so abandoned ones cannot pile up for the process lifetime. */
    public static void startWatchdog() {
        ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
                ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("Room-Watchdog")
        );
        ExecutorServiceManager.registerService(watchdog);
        watchdog.scheduleWithFixedDelay(RoomManager::reclaimEmptyRooms,
                WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void reclaimEmptyRooms() {
        try {
            int ttlMinutes = Server.getInstance().getArgs().getEmptyRoomTtlMinutes();
            if (ttlMinutes <= 0) {
                EMPTY_SINCE.clear();
                return;
            }
            long ttlMillis = TimeUnit.MINUTES.toMillis(ttlMinutes);
            long now = System.currentTimeMillis();
            for (Room room : ROOMS.values()) {
                String roomId = room.getRoomId();
                if (!room.isEmpty()) {
                    EMPTY_SINCE.remove(roomId);
                    continue;
                }
                Long since = EMPTY_SINCE.putIfAbsent(roomId, now);
                if (since != null && now - since >= ttlMillis) {
                    Server.getLogger().info("Reclaiming room {} after {} minutes with nobody in it",
                            roomId, (now - since) / 60_000);
                    room.destroy();
                }
            }
        } catch (Exception e) {
            // A failed sweep must not kill the scheduled task.
            Server.getLogger().warn("Room watchdog sweep failed: {}", e.getMessage());
        }
    }

    private static void forget(String roomId) {
        ROOMS.remove(roomId);
        EMPTY_SINCE.remove(roomId);
    }
}
