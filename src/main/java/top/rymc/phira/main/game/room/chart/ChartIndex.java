package top.rymc.phira.main.game.room.chart;

import com.google.gson.reflect.TypeToken;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.util.ChartDuration;
import top.rymc.phira.main.util.ExecutorServiceManager;
import top.rymc.phira.main.util.GsonUtil;
import top.rymc.phira.main.util.PhiraFetcher;
import top.rymc.phira.main.util.ThreadFactoryCompat;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Screenable view of the whole Phira catalogue.
 *
 * <p>The upstream list endpoint returns 30 charts per page and ignores limit, so a
 * full sweep costs roughly 320 requests. Results are cached on disk and refreshed
 * only on demand, keeping the cost off the startup path.
 */
public final class ChartIndex {

    private static final Path INDEX_FILE = Path.of("data", "chart-index.json");
    /** Phira starts answering with empty pages when a sweep hammers it. */
    private static final long REQUEST_INTERVAL_MS = 60;
    /** Cool-down between the configured and default passes of a full refresh. */
    private static final long SWEEP_PAUSE_MS = 4000;
    /** Upper bound for an incremental pass; a full sweep would trip the upstream rate limit. */
    private static final int INCREMENTAL_PAGES = 40;
    private static final String TAG_PLAIN = "plain";
    /** Kept low on purpose: each probe costs two range requests and Phira rate limits bursts. */
    private static final int PROBE_THREADS = 4;
    private static final int PROBE_TIMEOUT_SECONDS = 300;
    /** Upper bound on a single probe request, so a typo cannot queue thousands of downloads. */
    public static final int MAX_PROBE_BUDGET = 500;
    private static final Type INDEX_TYPE = new TypeToken<Map<Integer, ChartInfo>>() {
    }.getType();

    private static final Map<Integer, ChartInfo> CHARTS = new ConcurrentHashMap<>();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();
    private static volatile int totalOnRemote = -1;

    /**
     * Loads the cached catalogue. A damaged cache is discarded rather than fatal: the
     * index is rebuildable, so it must never keep the server from starting.
     */
    public static synchronized void preload() {
        if (!Files.exists(INDEX_FILE)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(INDEX_FILE)) {
            Map<Integer, ChartInfo> cached = GsonUtil.getGson().fromJson(reader, INDEX_TYPE);
            if (cached != null) {
                CHARTS.putAll(cached);
            }
        } catch (Exception e) {
            CHARTS.clear();
            Server.getLogger().warn("Chart index cache is unreadable ({}), starting empty; "
                    + "run a refresh to rebuild it", e.getMessage());
        }
    }

    /**
     * Refreshes the index, pulling only what is missing.
     *
     * <p>A full sweep costs roughly 320 requests and Phira starts refusing them, so the default
     * pass walks {@code updated desc} and stops as soon as a page yields nothing new. Configured
     * charts are only fetched when the index has none, because upstream hides them from the
     * default listing entirely.
     *
     * @param division {@code plain} to sweep configured charts explicitly, {@code null} for both
     * @return how many charts the index holds afterwards
     */
    public static synchronized int refresh(String division) throws IOException {
        if (!REFRESHING.compareAndSet(false, true)) {
            return CHARTS.size();
        }
        try {
            if (division == null) {
                if (countTagged(TAG_PLAIN) == 0) {
                    sweep(TAG_PLAIN, Integer.MAX_VALUE);
                    pause(SWEEP_PAUSE_MS);
                }
                // The very first sweep has to walk the whole listing, otherwise a fresh
                // deployment would only ever see the newest page cap worth of charts.
                // Later sweeps stop as soon as a page yields nothing new.
                boolean empty = CHARTS.isEmpty();
                if (empty) {
                    Server.getLogger().info("Chart index is empty, running a full sweep (slow on first run)");
                }
                sweep(null, empty ? Integer.MAX_VALUE : INCREMENTAL_PAGES);
            } else {
                sweep(division, Integer.MAX_VALUE);
            }
            save();
            Server.getLogger().info("Chart index refreshed: {} charts (division={})", CHARTS.size(),
                    division == null ? "all" : division);
            return CHARTS.size();
        } finally {
            REFRESHING.set(false);
        }
    }

    private static long countTagged(String tag) {
        return CHARTS.values().stream().filter(chart -> chart.hasTag(tag)).count();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sweep(String division, int maxPages) throws IOException {
        int seen = 0;
        for (int page = 1; page <= maxPages; page++) {
            PhiraFetcher.ChartListPage result = PhiraFetcher.fetchChartPage(page, division);
            totalOnRemote = result.count();
            List<ChartInfo> charts = result.results();
            if (charts == null || charts.isEmpty()) {
                return;
            }
            int fresh = 0;
            for (ChartInfo chart : charts) {
                if (merge(chart)) {
                    fresh++;
                }
            }
            // The listing is ordered by updated desc, so a page with nothing new means the
            // local copy already covers everything upstream has published since.
            if (fresh == 0) {
                return;
            }
            seen += charts.size();
            if (seen >= result.count()) {
                return;
            }
            // A full sweep is hundreds of requests; pace them or the later pages come back empty.
            pause(REQUEST_INTERVAL_MS);
        }
    }

    /** Refreshes in the background so callers never block on a full sweep. */
    public static void refreshAsync(String division) {
        Thread thread = new Thread(() -> {
            try {
                refresh(division);
            } catch (IOException e) {
                Server.getLogger().error("Chart index refresh failed", e);
            }
        }, "chart-index-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    public static boolean isRefreshing() {
        return REFRESHING.get();
    }

    public static int indexedCount() {
        return CHARTS.size();
    }

    public static int remoteCount() {
        return totalOnRemote;
    }

    public static List<ChartInfo> all() {
        return List.copyOf(CHARTS.values());
    }

    public static ChartInfo get(int chartId) {
        return CHARTS.get(chartId);
    }

    /** Charts clearing the rule, best rated first. */
    public static List<ChartInfo> search(ScreeningRule rule, int limit) {
        return CHARTS.values().stream()
                .filter(rule::matches)
                .sorted(Comparator.comparingDouble(ChartInfo::getRating).reversed()
                        .thenComparingInt(ChartInfo::getId))
                .limit(limit)
                .toList();
    }

    /** Counts matches without materialising the whole list. */
    public static long countMatches(ScreeningRule rule) {
        return CHARTS.values().stream().filter(rule::matches).count();
    }

    /**
     * Fills in missing durations for the given charts, best rated first so that the
     * quota is spent on the most likely candidates.
     *
     * <p>Each probe costs two range requests, so they run concurrently to keep the
     * operator waiting on seconds rather than minutes.
     *
     * @return how many durations were newly resolved
     */
    public static int probeDurations(List<ChartInfo> charts, int limit) {
        int budget = Math.min(limit, MAX_PROBE_BUDGET);
        List<ChartInfo> pending = charts.stream()
                .filter(chart -> chart.getDurationSeconds() == null)
                .limit(budget)
                .toList();
        if (pending.isEmpty()) {
            return 0;
        }

        AtomicInteger resolved = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PROBE_THREADS, pending.size()),
                ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("ChartDurationProbe"));
        try {
            for (ChartInfo chart : pending) {
                pool.execute(() -> {
                    try {
                        chart.setDurationSeconds(ChartDuration.PROBE.apply(chart.getId()));
                        resolved.incrementAndGet();
                    } catch (IOException e) {
                        failed.incrementAndGet();
                        Server.getLogger().warn("Duration probe failed for chart {}: {}", chart.getId(), e.getMessage());
                    }
                });
            }
        } finally {
            pool.shutdown();
        }
        try {
            // Probes are network bound; the timeout only guards against a hung archive read.
            if (!pool.awaitTermination(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }

        if (resolved.get() > 0) {
            saveUnchecked();
        }
        Server.getLogger().info("Duration probe finished: {}/{} resolved, {} failed",
                resolved.get(), pending.size(), failed.get());
        return resolved.get();
    }

    /**
     * Probes one chart in the background, for the case where a round is about to start on a chart
     * whose length is still unknown.
     *
     * <p>Never blocks the caller: a room that has already picked its chart cannot wait on two
     * range requests, and the forced-end deadline falls back to the floor when this misses.
     */
    public static void probeDurationAsync(ChartInfo chart) {
        if (chart == null || chart.getDurationSeconds() != null) {
            return;
        }
        ExecutorServiceManager.registerService(Executors.newSingleThreadExecutor(
                ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("ChartDurationProbe-Once")
        )).execute(() -> {
            try {
                chart.setDurationSeconds(ChartDuration.PROBE.apply(chart.getId()));
                saveUnchecked();
                Server.getLogger().info("Probed duration of chart {}: {}s", chart.getId(), chart.getDurationSeconds());
            } catch (IOException e) {
                Server.getLogger().warn("Duration probe failed for chart {}: {}", chart.getId(), e.getMessage());
            }
        });
    }

    /** @return true when the chart was not indexed before */
    private static boolean merge(ChartInfo fresh) {
        ChartInfo cached = CHARTS.putIfAbsent(fresh.getId(), fresh);
        if (cached == null) {
            return true;
        }
        if (cached.getDurationSeconds() != null) {
            // Keep the expensive probe result across catalogue refreshes.
            fresh.setDurationSeconds(cached.getDurationSeconds());
        }
        CHARTS.put(fresh.getId(), fresh);
        return false;
    }

    /**
     * Writes through a temporary file so a crash mid-write cannot truncate the cache,
     * which matters because probing saves from several threads.
     */
    private static synchronized void save() throws IOException {
        Files.createDirectories(INDEX_FILE.getParent());
        Map<Integer, ChartInfo> sorted = CHARTS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
        Path temp = INDEX_FILE.resolveSibling(INDEX_FILE.getFileName() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temp)) {
            GsonUtil.getGson().toJson(sorted, writer);
        }
        try {
            Files.move(temp, INDEX_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, INDEX_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void saveUnchecked() {
        try {
            save();
        } catch (IOException e) {
            Server.getLogger().error("Failed to save chart index", e);
        }
    }

    private ChartIndex() {
    }
}
