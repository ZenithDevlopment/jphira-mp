package top.rymc.phira.main.game.point;

import com.google.gson.reflect.TypeToken;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.main.util.ExecutorServiceManager;
import top.rymc.phira.main.util.GsonUtil;
import top.rymc.phira.main.util.ThreadFactoryCompat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Player score board.
 *
 * <p>Scoring happens on the shared room timer thread, so nothing here may block it: mutations
 * only touch memory and mark the state dirty, while a dedicated thread writes the file at most
 * once per interval. Rankings are cached and rebuilt at most once per batch of changes, which
 * keeps a round settlement at one sort instead of one per player.
 */
public final class PlayerPointService {

    private static final Path POINT_FILE = Path.of("data", "player-points.json");
    private static final Type POINT_DATA_TYPE = new TypeToken<Map<Integer, PointData>>() {
    }.getType();
    private static final long SAVE_INTERVAL_SECONDS = 5;
    private static final long TOP_LINES_CACHE_MILLIS = 60_000;

    private static final Map<Integer, PointData> POINTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean DIRTY = new AtomicBoolean(false);
    private static final AtomicLong VERSION = new AtomicLong();

    private static volatile List<Map.Entry<Integer, PointData>> rankingCache;
    private static volatile long rankingCacheVersion = -1;
    private static volatile List<String> topLinesCache = List.of();
    private static volatile long topLinesCacheTime;

    static {
        load();
        startAutoSave();
    }

    private PlayerPointService() {
    }

    public static PointSummary getSummary(Player player) {
        PointData data = touch(player);
        return new PointSummary(data.points, getRank(player.getId()));
    }

    public static int addPoints(Player player, int points) {
        PointData data = touch(player);
        data.points = Math.max(0, data.points + points);
        markDirty();
        return data.points;
    }

    /**
     * Awards a whole round at once.
     *
     * @return latest totals keyed by player id
     */
    public static Map<Integer, Integer> addPointsBatch(Map<Integer, Integer> gainedByPlayerId,
                                                       Map<Integer, String> nameByPlayerId) {
        Map<Integer, Integer> totals = new LinkedHashMap<>();
        gainedByPlayerId.forEach((playerId, gained) -> {
            PointData data = POINTS.computeIfAbsent(playerId, id -> new PointData());
            String name = nameByPlayerId.get(playerId);
            if (name != null) {
                data.name = name;
            }
            data.points = Math.max(0, data.points + gained);
            totals.put(playerId, data.points);
        });
        // One invalidation for the batch: the ranking is rebuilt on the next read only.
        markDirty();
        return totals;
    }

    public static List<String> getTopRankingLines(int limit) {
        long now = System.currentTimeMillis();
        if (now - topLinesCacheTime <= TOP_LINES_CACHE_MILLIS) {
            return topLinesCache;
        }

        List<String> lines = getRanking().stream()
                .limit(limit)
                .map(entry -> String.format("#%d %s：%d 分", getRank(entry.getKey()), entry.getValue().name, entry.getValue().points))
                .toList();
        topLinesCache = lines;
        topLinesCacheTime = now;
        return lines;
    }

    /** Ranking entry point for the HTTP layer: {@code [playerId, name, points, rank]}. */
    public static List<RankEntry> getRankingSnapshot(int limit) {
        List<Map.Entry<Integer, PointData>> ranking = getRanking();
        List<RankEntry> entries = new java.util.ArrayList<>(Math.min(limit, ranking.size()));
        for (int i = 0; i < ranking.size() && i < limit; i++) {
            Map.Entry<Integer, PointData> entry = ranking.get(i);
            entries.add(new RankEntry(entry.getKey(), entry.getValue().name, entry.getValue().points, i + 1));
        }
        return entries;
    }

    public static int getRank(int playerId) {
        List<Map.Entry<Integer, PointData>> ranking = getRanking();
        for (int i = 0; i < ranking.size(); i++) {
            if (ranking.get(i).getKey() == playerId) {
                return i + 1;
            }
        }
        return ranking.size() + 1;
    }

    /** Writes pending changes immediately, e.g. on shutdown. */
    public static void flush() {
        if (DIRTY.compareAndSet(true, false)) {
            save();
        }
    }

    private static void markDirty() {
        VERSION.incrementAndGet();
        DIRTY.set(true);
    }

    private static PointData touch(Player player) {
        PointData data = POINTS.computeIfAbsent(player.getId(), id -> new PointData());
        data.name = player.getName();
        return data;
    }

    /** Rebuilt at most once per mutation batch, guarded by the version counter. */
    private static List<Map.Entry<Integer, PointData>> getRanking() {
        long version = VERSION.get();
        List<Map.Entry<Integer, PointData>> cached = rankingCache;
        if (cached != null && rankingCacheVersion == version) {
            return cached;
        }

        synchronized (PlayerPointService.class) {
            // Read once and stamp with that same value: reading again after sorting would mark
            // the cache as current while the changes made during the sort are missing from it.
            long versionAtSort = VERSION.get();
            if (rankingCache == null || rankingCacheVersion != versionAtSort) {
                // Compare against a snapshot. Sorting over live fields while another thread
                // awards points breaks the comparator contract and throws mid settlement.
                Map<Integer, Integer> scores = new HashMap<>(POINTS.size());
                POINTS.forEach((id, data) -> scores.put(id, data.points));
                rankingCache = POINTS.entrySet().stream()
                        .sorted(Comparator.<Map.Entry<Integer, PointData>>comparingInt(
                                        entry -> scores.getOrDefault(entry.getKey(), 0)).reversed()
                                .thenComparingInt(Map.Entry::getKey))
                        .toList();
                rankingCacheVersion = versionAtSort;
            }
            return rankingCache;
        }
    }

    private static void startAutoSave() {
        ScheduledExecutorService saver = Executors.newSingleThreadScheduledExecutor(
                ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("Player-Points-Saver")
        );
        ExecutorServiceManager.registerService(saver);
        saver.scheduleWithFixedDelay(() -> {
            try {
                flush();
            } catch (Exception e) {
                Server.getLogger().error("Failed to persist player points", e);
            }
        }, SAVE_INTERVAL_SECONDS, SAVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private static void load() {
        if (!Files.exists(POINT_FILE)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(POINT_FILE)) {
            Map<Integer, PointData> data = GsonUtil.getGson().fromJson(reader, POINT_DATA_TYPE);
            if (data != null) {
                POINTS.putAll(data);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load player points", e);
        }
    }

    /** Always called off the room threads: from the saver or {@link #flush()}. */
    private static synchronized void save() {
        try {
            Files.createDirectories(POINT_FILE.getParent());
            Map<Integer, PointData> sorted = POINTS.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .collect(LinkedHashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), LinkedHashMap::putAll);
            try (Writer writer = Files.newBufferedWriter(POINT_FILE)) {
                GsonUtil.getGson().toJson(sorted, writer);
            }
        } catch (IOException e) {
            // Keep the dirty flag so the next cycle retries instead of losing the round.
            DIRTY.set(true);
            Server.getLogger().error("Failed to save player points", e);
        }
    }

    public record PointSummary(int points, int rank) {
    }

    public record RankEntry(int playerId, String name, int points, int rank) {
    }

    private static final class PointData {
        private volatile String name;
        private volatile int points;
    }
}
