package top.rymc.phira.main.game.room;

import com.google.gson.reflect.TypeToken;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.game.room.chart.ChartPool;
import top.rymc.phira.main.game.room.local.LocalRoom;
import top.rymc.phira.main.game.room.local.LocalRoomBuilder;
import top.rymc.phira.main.util.ExecutorServiceManager;
import top.rymc.phira.main.util.GsonUtil;
import top.rymc.phira.main.util.ThreadFactoryCompat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Persists room definitions so a restart does not wipe the configured rooms.
 *
 * <p>Players and in round state are deliberately not saved: rooms come back empty and idle, ready
 * for the next session. Only what an operator configured is kept, which is exactly what is painful
 * to recreate after a crash or a redeploy.
 */
public final class RoomStore {

    private static final Path FILE = Path.of("data", "rooms.json");
    private static final Type TYPE = new TypeToken<List<SavedRoom>>() {
    }.getType();
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);

    private RoomStore() {
    }

    /**
     * Recreates the stored rooms.
     *
     * <p>A room whose pools have since been deleted is skipped with a warning rather than
     * preventing the server from starting.
     */
    public static synchronized void restore() {
        if (!Files.exists(FILE)) {
            return;
        }
        List<SavedRoom> saved;
        try (Reader reader = Files.newBufferedReader(FILE)) {
            saved = GsonUtil.getGson().fromJson(reader, TYPE);
        } catch (Exception e) {
            Server.getLogger().warn("Room store is unreadable ({}), starting with no rooms", e.getMessage());
            return;
        }
        if (saved == null || saved.isEmpty()) {
            return;
        }

        int restored = 0;
        for (SavedRoom room : saved) {
            try {
                List<ChartPool.PoolSnapshot> pools = ChartPool.resolvePools(room.poolIds());
                if (pools.isEmpty()) {
                    Server.getLogger().warn("Skipping room {}: its pools no longer exist", room.roomId());
                    continue;
                }
                new LocalRoomBuilder()
                        .setting(room.setting().toSetting())
                        .pools(pools)
                        .build(room.roomId());
                restored++;
            } catch (Exception e) {
                Server.getLogger().warn("Skipping room {}: {}", room.roomId(), e.getMessage());
            }
        }
        Server.getLogger().info("Restored {} room(s) from {}", restored, FILE);
    }

    /** Marks the definitions dirty; the writer picks it up on its next pass. */
    public static void save() {
        DIRTY.set(true);
    }

    public static synchronized boolean flush() {
        if (!DIRTY.compareAndSet(true, false)) {
            return false;
        }
        try {
            List<SavedRoom> saved = new ArrayList<>();
            for (Room room : RoomManager.getAllRooms()) {
                if (!(room instanceof LocalRoom local)) {
                    continue;
                }
                List<Integer> poolIds = local.getChartPool().getPools().stream()
                        .map(ChartPool.PoolSnapshot::id)
                        .toList();
                if (poolIds.isEmpty()) {
                    continue;
                }
                saved.add(new SavedRoom(room.getRoomId(), poolIds, SavedSetting.of(local)));
            }
            saved.sort(Comparator.comparing(SavedRoom::roomId));

            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GsonUtil.getGson().toJson(saved, writer);
            }
            return true;
        } catch (IOException e) {
            // Keep the dirty flag so the next pass retries instead of losing the definitions.
            DIRTY.set(true);
            Server.getLogger().error("Failed to save rooms", e);
            return false;
        }
    }

    /** Periodic writer, so a kill still keeps whatever the operator configured. */
    public static void startAutoSave() {
        ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(
                ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("Room-Store-Saver")
        );
        ExecutorServiceManager.registerService(writer);
        writer.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Exception e) {
                Server.getLogger().error("Room store save failed", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private record SavedRoom(String roomId, List<Integer> poolIds, SavedSetting setting) {
    }

    /** Explicit shape rather than the live setting object, so field renames cannot break restore. */
    private record SavedSetting(boolean autoDestroy, boolean host, int maxPlayer, boolean locked,
                                boolean cycle, boolean live, boolean chat, int minPlayer,
                                int selectChartCountdownSeconds, int readyCountdownSeconds,
                                int forceFinishSeconds, int refreshIntervalRounds,
                                List<Integer> adminIds) {

        static SavedSetting of(LocalRoom room) {
            LocalRoom.RoomSetting setting = room.getSetting();
            return new SavedSetting(
                    setting.isAutoDestroy(), setting.isHost(), setting.getMaxPlayer(), setting.isLocked(),
                    setting.isCycle(), setting.isLive(), setting.isChat(), setting.getMinPlayer(),
                    setting.getSelectChartCountdownSeconds(), setting.getReadyCountdownSeconds(),
                    setting.getForceFinishSeconds(), setting.getRefreshIntervalRounds(),
                    setting.getAdminIds() == null ? List.of() : List.copyOf(setting.getAdminIds())
            );
        }

        /** Rebuilds the live setting through the builder so defaults stay in one place. */
        top.rymc.phira.main.game.room.local.LocalRoom.RoomSetting toSetting() {
            return new LocalRoomBuilder()
                    .autoDestroy(autoDestroy).host(host).maxPlayer(maxPlayer).locked(locked)
                    .cycle(cycle).live(live).chat(chat).minPlayer(minPlayer)
                    .selectChartCountdownSeconds(selectChartCountdownSeconds)
                    .readyCountdownSeconds(readyCountdownSeconds)
                    .forceFinishSeconds(forceFinishSeconds)
                    .refreshIntervalRounds(refreshIntervalRounds)
                    .adminIds(adminIds == null ? java.util.Set.of() : new java.util.LinkedHashSet<>(adminIds))
                    .buildSetting();
        }
    }
}
