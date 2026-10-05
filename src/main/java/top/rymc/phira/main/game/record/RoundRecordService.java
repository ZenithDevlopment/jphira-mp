package top.rymc.phira.main.game.record;

import com.google.gson.reflect.TypeToken;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.data.RoundRecord;
import top.rymc.phira.main.util.ExecutorServiceManager;
import top.rymc.phira.main.util.GsonUtil;
import top.rymc.phira.main.util.ThreadFactoryCompat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * History of finished rounds.
 *
 * <p>Appending happens on the shared room timer thread right after a round settles, so it only
 * touches memory; a dedicated thread persists the file. The log is capped, oldest rounds first.
 */
public final class RoundRecordService {

    private static final Path RECORD_FILE = Path.of("data", "round-records.json");
    /** Rounds kept on disk. A busy event night produces a few hundred. */
    private static final int MAX_RECORDS = 1000;
    private static final long SAVE_INTERVAL_SECONDS = 5;
    private static final Type RECORD_LIST_TYPE = new TypeToken<List<RoundRecord>>() {
    }.getType();

    /** Newest first, so trimming the tail drops the oldest round. */
    private static final Deque<RoundRecord> RECORDS = new ArrayDeque<>();
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    public static synchronized void preload() throws IOException {
        if (!Files.exists(RECORD_FILE)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(RECORD_FILE)) {
            List<RoundRecord> loaded = GsonUtil.getGson().fromJson(reader, RECORD_LIST_TYPE);
            if (loaded != null) {
                // Stored oldest first; the deque wants newest first.
                for (int i = loaded.size() - 1; i >= 0; i--) {
                    RECORDS.addLast(loaded.get(i));
                }
            }
        }
    }

    /** Re-reads the file after an import. */
    public static synchronized void reload() throws IOException {
        synchronized (RECORDS) {
            RECORDS.clear();
        }
        preload();
    }

    /** Unique, sortable id: a round finished later always compares greater. */
    public static String nextId(String roomId, long finishedAt) {
        return roomId + "-" + finishedAt + "-" + SEQUENCE.incrementAndGet();
    }

    public static void append(RoundRecord record) {
        synchronized (RECORDS) {
            RECORDS.addFirst(record);
            while (RECORDS.size() > MAX_RECORDS) {
                RECORDS.removeLast();
            }
        }
        markDirty();
    }

    /** Newest first. {@code roomId} and {@code playerId} are optional filters. */
    public static List<RoundRecord> query(int limit, int offset, String roomId, Integer playerId) {
        List<RoundRecord> snapshot;
        synchronized (RECORDS) {
            snapshot = new ArrayList<>(RECORDS);
        }

        List<RoundRecord> matched = new ArrayList<>();
        for (RoundRecord record : snapshot) {
            if (roomId != null && !roomId.equals(record.roomId())) {
                continue;
            }
            if (playerId != null && record.results().stream().noneMatch(r -> r.playerId() == playerId)) {
                continue;
            }
            if (offset > 0) {
                offset--;
                continue;
            }
            matched.add(record);
            if (matched.size() >= limit) {
                break;
            }
        }
        return matched;
    }

    public static Optional<RoundRecord> find(String id) {
        synchronized (RECORDS) {
            return RECORDS.stream().filter(record -> record.id().equals(id)).findFirst();
        }
    }

    /** One player's rounds, newest first. */
    public static List<RoundRecord.PlayerRound> byPlayer(int playerId, int limit) {
        List<RoundRecord> snapshot;
        synchronized (RECORDS) {
            snapshot = new ArrayList<>(RECORDS);
        }

        List<RoundRecord.PlayerRound> rounds = new ArrayList<>();
        for (RoundRecord record : snapshot) {
            record.results().stream()
                    .filter(result -> result.playerId() == playerId)
                    .findFirst()
                    .ifPresent(result -> rounds.add(new RoundRecord.PlayerRound(
                            record.id(), record.roomId(), record.chartId(), record.chartName(),
                            record.finishedAt(), result)));
            if (rounds.size() >= limit) {
                break;
            }
        }
        return rounds;
    }

    public static int size() {
        synchronized (RECORDS) {
            return RECORDS.size();
        }
    }

    public static void flush() {
        if (DIRTY.compareAndSet(true, false)) {
            save();
        }
    }

    private static void markDirty() {
        DIRTY.set(true);
    }

    private static void startAutoSave() {
        ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(
                ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("Round-Record-Saver")
        );
        ExecutorServiceManager.registerService(saver);
        saver.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Exception e) {
                Server.getLogger().error("Failed to persist round records", e);
            }
        }, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    /** Oldest first on disk, matching what {@link #preload()} expects. */
    private static synchronized void save() {
        try {
            Files.createDirectories(RECORD_FILE.getParent());
            List<RoundRecord> ordered;
            synchronized (RECORDS) {
                ordered = new ArrayList<>(RECORDS);
            }
            java.util.Collections.reverse(ordered);
            try (Writer writer = Files.newBufferedWriter(RECORD_FILE)) {
                GsonUtil.getGson().toJson(ordered, writer);
            }
        } catch (IOException e) {
            DIRTY.set(true);
            Server.getLogger().error("Failed to save round records", e);
        }
    }

    static {
        startAutoSave();
    }

    private RoundRecordService() {
    }
}
