package top.rymc.phira.main.game.room.chart;

import com.google.gson.reflect.TypeToken;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.util.GsonUtil;
import top.rymc.phira.main.util.PhiraFetcher;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 全局谱面池定义与 ChartInfo 缓存。
 *
 * 只负责：
 * - pool 定义（chart-pools.json，存 id / favorite_id / chart_ids / default）
 * - ChartInfo 持久化缓存（chart-info-cache.json）与预拉取
 * - 池定义的新增/删除/修改
 *
 * 运行时状态（当前池、pending、轮次计数、favorite 覆盖等）由每个房间自己的 {@link RoomChartPool} 维护，
 * 房间创建时固化池列表，之后不受全局定义变化影响；服务重启后房间不保留，运行时状态不持久化。
 */
public final class ChartPool {

    private static final Path POOL_FILE = Path.of("data", "chart-pools.json");
    private static final Path CACHE_FILE = Path.of("data", "chart-info-cache.json");
    private static final int[][] DEFAULT_POOL_CHART_IDS = {
            {30474, 42058},
            {33530, 52087}
    };
    /** Order step for auto-appended pools, leaving room for manual insertion. */
    private static final int ORDER_STEP = 10;
    /** A single generate call will not create more pools than this. */
    private static final int MAX_GENERATED_POOLS = 200;
    /** A single batch add will not fetch more charts than this. */
    public static final int MAX_BATCH_CHARTS = 500;
    /** A single batch create will not make more empty pools than this. */
    private static final int MAX_BATCH_POOLS = 50;

    private static final Type CHART_CACHE_TYPE = new TypeToken<Map<Integer, ChartInfo>>() {
    }.getType();

    private static final Map<Integer, ChartInfo> CHART_INFOS = new ConcurrentHashMap<>();
    private static List<PoolDefinition> pools = new ArrayList<>();

    private ChartPool() {
    }

    public static synchronized void preload() throws IOException {
        Files.createDirectories(POOL_FILE.getParent());
        loadCache();
        loadOrCreatePools();
        normalizePools();
        validatePools();
        for (int id : getAllChartIds()) {
            loadChartInfo(id);
        }
        saveCache();
        savePools();
    }

    public static synchronized List<PoolSnapshot> listPools() {
        return sortedPools().stream()
                .map(ChartPool::snapshotOf)
                .toList();
    }

    /**
     * 所有默认启用（default=true）的 pool 快照，按 id 排序。用于玩家创建房间时的固化池列表。
     */
    public static synchronized List<PoolSnapshot> getDefaultPools() {
        return sortedPools().stream()
                .filter(pool -> pool.defaultFlag && !pool.chartIds.isEmpty())
                .map(ChartPool::snapshotOf)
                .toList();
    }

    /**
     * 查找全局 pool 定义快照，不存在返回 null。
     */
    public static synchronized PoolSnapshot findPool(int poolId) {
        PoolDefinition pool = findPoolDefinition(poolId);
        return pool == null ? null : snapshotOf(pool);
    }

    /**
     * Resolve pool ids into non-empty snapshots, preserving the requested order.
     * Unknown and empty pools are skipped so that staged pools never reach a room.
     */
    public static synchronized List<PoolSnapshot> resolvePools(List<Integer> poolIds) {
        List<PoolSnapshot> resolved = new ArrayList<>();
        for (int poolId : distinct(poolIds)) {
            PoolDefinition pool = findPoolDefinition(poolId);
            if (pool == null || pool.chartIds.isEmpty()) {
                continue;
            }
            resolved.add(snapshotOf(pool));
        }
        return List.copyOf(resolved);
    }

    public static synchronized void addPool(int poolId, List<Integer> chartIds) throws IOException {
        addPool(poolId, chartIds, PoolCategory.MANUAL, null, null);
    }

    /**
     * Create a pool. Empty {@code chartIds} is allowed so that pools can be staged
     * before charts are picked in the web manager.
     */
    public static synchronized void addPool(int poolId, List<Integer> chartIds, PoolCategory category,
                                            Integer sizeLimit, Integer roundsPerStay) throws IOException {
        if (findPoolDefinition(poolId) != null) {
            throw new IllegalArgumentException("Pool already exists: " + poolId);
        }

        List<Integer> distinctChartIds = distinct(chartIds);
        for (int chartId : distinctChartIds) {
            loadChartInfo(chartId);
        }

        PoolDefinition pool = new PoolDefinition(poolId, null, false, distinctChartIds);
        pool.category = category == null ? PoolCategory.MANUAL : category;
        pool.sizeLimit = sizeLimit;
        pool.roundsPerStay = roundsPerStay;
        pool.order = nextOrder();
        pools.add(pool);
        saveCache();
        savePools();
    }

    public static synchronized void removePool(int poolId) {
        PoolDefinition pool = requirePool(poolId);
        if (pools.size() == 1) {
            throw new IllegalArgumentException("Cannot remove the last pool");
        }
        if (pool.defaultFlag && countDefaultPools() == 1) {
            throw new IllegalArgumentException("Cannot remove the last default pool");
        }

        pools.remove(pool);
        validatePools();
        savePoolsUnchecked();
    }

    public static synchronized void addChart(int poolId, int chartId) throws IOException {
        addCharts(poolId, List.of(chartId));
    }

    /**
     * Add many charts at once, persisting a single time.
     *
     * @return how many charts were actually appended
     */
    public static synchronized int addCharts(int poolId, List<Integer> chartIds) throws IOException {
        PoolDefinition pool = requirePool(poolId);
        List<Integer> distinctIds = distinct(chartIds);
        // Each unknown chart costs a network fetch, so a single batch is bounded.
        if (distinctIds.size() > MAX_BATCH_CHARTS) {
            throw new IllegalArgumentException("Too many charts in one batch: " + distinctIds.size()
                    + " (max " + MAX_BATCH_CHARTS + ")");
        }
        int added = 0;
        for (int chartId : distinctIds) {
            ChartInfo info = loadChartInfo(chartId);
            if (pool.chartIds.contains(info.getId())) {
                continue;
            }
            pool.chartIds.add(info.getId());
            added++;
        }
        if (added > 0) {
            saveCache();
            savePools();
        }
        return added;
    }

    public static synchronized void removeChart(int poolId, int chartId) {
        PoolDefinition pool = requirePool(poolId);
        if (!pool.chartIds.contains(chartId)) {
            return;
        }

        pool.chartIds.remove(Integer.valueOf(chartId));
        savePoolsUnchecked();
    }

    public static synchronized void setFavoriteId(int poolId, Integer favoriteId) {
        PoolDefinition pool = requirePool(poolId);
        pool.favoriteId = favoriteId;
        savePoolsUnchecked();
    }

    public static synchronized void setDefaultFlag(int poolId, boolean defaultFlag) {
        PoolDefinition pool = requirePool(poolId);
        if (pool.defaultFlag == defaultFlag) {
            return;
        }
        pool.defaultFlag = defaultFlag;
        savePoolsUnchecked();
    }

    /**
     * Update pool metadata. A null argument keeps the current value, while a negative
     * number resets that field to its default.
     */
    public static synchronized void updatePool(int poolId, PoolCategory category, Integer sizeLimit,
                                                Integer roundsPerStay, Integer order, Boolean submissionOpen) {
        PoolDefinition pool = requirePool(poolId);
        if (category != null) {
            pool.category = category;
        }
        pool.sizeLimit = sizeLimit == null ? pool.sizeLimit : resetIfNegative(sizeLimit);
        pool.roundsPerStay = roundsPerStay == null ? pool.roundsPerStay : resetIfNegative(roundsPerStay);
        if (order != null) {
            pool.order = Math.max(0, order);
        }
        if (submissionOpen != null) {
            pool.submissionOpen = submissionOpen;
        }
        savePoolsUnchecked();
    }

    private static Integer resetIfNegative(int value) {
        return value < 0 ? null : value;
    }

    public static ChartInfo getChartInfo(int chartId) {
        return requireChartInfo(chartId);
    }

    public static boolean contains(int chartId, List<ChartInfo> pool) {
        return pool.stream().anyMatch(chart -> chart.getId() == chartId);
    }

    private static void loadOrCreatePools() throws IOException {
        if (Files.exists(POOL_FILE)) {
            try (Reader reader = Files.newBufferedReader(POOL_FILE)) {
                PoolConfig config = GsonUtil.getGson().fromJson(reader, PoolConfig.class);
                if (config != null && config.pools != null) {
                    pools = config.pools;
                    return;
                }
            }
        }

        pools = new ArrayList<>();
        for (int i = 0; i < DEFAULT_POOL_CHART_IDS.length; i++) {
            List<Integer> chartIds = new ArrayList<>();
            for (int id : DEFAULT_POOL_CHART_IDS[i]) {
                chartIds.add(id);
            }
            // 默认配置中第一个池默认启用，保证玩家开箱即可创建房间。
            pools.add(new PoolDefinition(i, null, i == 0, chartIds));
        }
        savePools();
    }

    private static void loadCache() throws IOException {
        if (!Files.exists(CACHE_FILE)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CACHE_FILE)) {
            Map<Integer, ChartInfo> cache = GsonUtil.getGson().fromJson(reader, CHART_CACHE_TYPE);
            if (cache != null) {
                CHART_INFOS.putAll(cache);
            }
        }
    }

    private static ChartInfo loadChartInfo(int chartId) throws IOException {
        ChartInfo info = CHART_INFOS.get(chartId);
        if (info != null) {
            return info;
        }

        info = PhiraFetcher.GET_CHART_INFO.apply(chartId);
        CHART_INFOS.put(chartId, info);
        return info;
    }

    private static ChartInfo requireChartInfo(int chartId) {
        ChartInfo info = CHART_INFOS.get(chartId);
        if (info == null) {
            throw new IllegalStateException("Chart info is not loaded: " + chartId);
        }
        return info;
    }

    private static PoolDefinition requirePool(int poolId) {
        PoolDefinition pool = findPoolDefinition(poolId);
        if (pool == null) {
            throw new IllegalArgumentException("Pool not found: " + poolId);
        }
        return pool;
    }

    private static PoolDefinition findPoolDefinition(int poolId) {
        return pools.stream()
                .filter(pool -> pool.id == poolId)
                .findFirst()
                .orElse(null);
    }

    private static long countDefaultPools() {
        return pools.stream().filter(pool -> pool.defaultFlag).count();
    }

    private static List<PoolDefinition> sortedPools() {
        return pools.stream()
                .sorted(Comparator.comparingInt((PoolDefinition pool) -> pool.order).thenComparingInt(pool -> pool.id))
                .toList();
    }

    private static int nextOrder() {
        return pools.stream()
                .mapToInt(pool -> pool.order)
                .max()
                .orElse(0) + ORDER_STEP;
    }

    /**
     * Cuts every chart matching the rule into pools of at most {@code sizeLimit} charts.
     *
     * <p>Existing pools of the same category are left untouched, so regeneration is
     * additive and can be repeated safely. The whole batch is persisted once, because
     * a large catalogue easily produces hundreds of pools.
     *
     * @return ids of the pools that were created
     */
    public static synchronized List<Integer> generatePools(ScreeningRule rule, int sizeLimit, Integer roundsPerStay)
            throws IOException {
        if (sizeLimit < 1) {
            throw new IllegalArgumentException("Pool size limit must be positive");
        }
        List<ChartInfo> matched = ChartIndex.search(rule, Integer.MAX_VALUE);
        if (matched.isEmpty()) {
            return List.of();
        }

        List<Integer> created = new ArrayList<>();
        int nextId = pools.stream().mapToInt(pool -> pool.id).max().orElse(0) + 1;
        // Order advances monotonically, so it is computed once instead of rescanning every pool.
        int order = nextOrder();

        for (int start = 0; start < matched.size(); start += sizeLimit) {
            if (created.size() >= MAX_GENERATED_POOLS) {
                Server.getLogger().warn("Stopped at {} generated pools; {} charts were left over. "
                                + "Raise sizeLimit or generate in several passes.",
                        MAX_GENERATED_POOLS, matched.size() - start);
                break;
            }
            List<ChartInfo> slice = matched.subList(start, Math.min(start + sizeLimit, matched.size()));
            List<Integer> chartIds = slice.stream().map(ChartInfo::getId).toList();

            PoolDefinition pool = new PoolDefinition(nextId, null, false, chartIds);
            pool.category = rule.category();
            pool.sizeLimit = sizeLimit;
            pool.roundsPerStay = roundsPerStay;
            pool.order = order;
            order += ORDER_STEP;
            pools.add(pool);
            created.add(nextId++);

            // Reuse the already fetched metadata instead of hitting the network again.
            slice.forEach(chart -> CHART_INFOS.putIfAbsent(chart.getId(), chart));
        }

        saveCache();
        savePools();
        Server.getLogger().info("Generated {} {} pools from {} charts (sizeLimit={})", created.size(),
                rule.category(), matched.size(), sizeLimit);
        return created;
    }

    /**
     * Creates several empty pools at once, so an operator can stage a batch and then fill
     * each one by hand. Ids are assigned server side to avoid races between clients.
     *
     * @return ids of the pools that were created
     */
    public static synchronized List<Integer> createEmptyPools(int count, PoolCategory category,
                                                             Integer sizeLimit, Integer roundsPerStay) {
        if (count < 1 || count > MAX_BATCH_POOLS) {
            throw new IllegalArgumentException("Pool count must be between 1 and " + MAX_BATCH_POOLS);
        }
        List<Integer> created = new ArrayList<>();
        int nextId = pools.stream().mapToInt(pool -> pool.id).max().orElse(0) + 1;
        int order = nextOrder();

        for (int index = 0; index < count; index++) {
            PoolDefinition pool = new PoolDefinition(nextId, null, false, List.of());
            pool.category = category == null ? PoolCategory.MANUAL : category;
            pool.sizeLimit = sizeLimit;
            pool.roundsPerStay = roundsPerStay;
            pool.order = order;
            order += ORDER_STEP;
            pools.add(pool);
            created.add(nextId++);
        }

        savePoolsUnchecked();
        Server.getLogger().info("Created {} empty {} pools: {}", created.size(), category, created);
        return created;
    }

    /** @return ids of pools already generated for the category */
    public static synchronized List<Integer> poolIdsByCategory(PoolCategory category) {
        return sortedPools().stream()
                .filter(pool -> pool.category == category)
                .map(pool -> pool.id)
                .toList();
    }

    /** Drop unusable entries and backfill defaults. Gson bypasses field initializers, so absent keys land as null. */
    private static void normalizePools() {
        if (pools == null) {
            pools = new ArrayList<>();
            return;
        }
        pools = new ArrayList<>(pools);
        pools.removeIf(Objects::isNull);
        for (PoolDefinition pool : pools) {
            if (pool.chartIds == null) {
                pool.chartIds = new ArrayList<>();
            }
            if (pool.category == null) {
                pool.category = PoolCategory.MANUAL;
            }
            pool.chartIds = distinct(pool.chartIds);
        }
    }

    private static void validatePools() {
        if (pools.isEmpty()) {
            throw new IllegalStateException("No chart pools configured");
        }

        for (PoolDefinition pool : pools) {
            if (pool.chartIds.isEmpty()) {
                Server.getLogger().warn("Pool {} is empty, rooms will skip it", pool.id);
            }
        }
    }

    private static PoolSnapshot snapshotOf(PoolDefinition pool) {
        return new PoolSnapshot(pool.id, pool.favoriteId, pool.defaultFlag, List.copyOf(pool.chartIds),
                pool.category, pool.sizeLimit, pool.roundsPerStay, pool.order, pool.submissionOpen);
    }

    /** Whether players may submit charts into this pool. */
    public static synchronized boolean isSubmissionOpen(int poolId) {
        return requirePool(poolId).submissionOpen;
    }

    /** Pool ids that currently accept submissions, used by the player facing listing. */
    public static synchronized List<PoolSnapshot> listOpenForSubmission() {
        return sortedPools().stream()
                .filter(pool -> pool.submissionOpen)
                .map(ChartPool::snapshotOf)
                .toList();
    }

    private static List<Integer> distinct(List<Integer> chartIds) {
        if (chartIds == null || chartIds.isEmpty()) {
            return new ArrayList<>();
        }
        return chartIds.stream()
                .distinct()
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<Integer> getAllChartIds() {
        return pools.stream()
                .flatMap(pool -> pool.chartIds.stream())
                .distinct()
                .toList();
    }

    private static void savePools() throws IOException {
        Files.createDirectories(POOL_FILE.getParent());
        PoolConfig config = new PoolConfig();
        config.pools = pools;
        try (Writer writer = Files.newBufferedWriter(POOL_FILE)) {
            GsonUtil.getGson().toJson(config, writer);
        }
    }

    private static void savePoolsUnchecked() {
        try {
            savePools();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save chart pools", e);
        }
    }

    private static void saveCache() throws IOException {
        Files.createDirectories(CACHE_FILE.getParent());
        Map<Integer, ChartInfo> sortedCache = CHART_INFOS.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
        try (Writer writer = Files.newBufferedWriter(CACHE_FILE)) {
            GsonUtil.getGson().toJson(sortedCache, writer);
        }
    }

    /**
     * Immutable view of a pool definition.
     *
     * @param roundsPerStay rounds to play before rotating, {@code null} to use the room-level interval
     * @param sizeLimit     intended pool size, {@code null} when unbounded
     */
    public record PoolSnapshot(int id, Integer favoriteId, boolean defaultFlag, List<Integer> chartIds,
                               PoolCategory category, Integer sizeLimit, Integer roundsPerStay, int order,
                               boolean submissionOpen) {
    }

    private static final class PoolConfig {
        private List<PoolDefinition> pools = new ArrayList<>();
    }

    private static final class PoolDefinition {
        private int id;
        private Integer favoriteId;
        private boolean defaultFlag;
        private List<Integer> chartIds = new ArrayList<>();
        private PoolCategory category = PoolCategory.MANUAL;
        private Integer sizeLimit;
        private Integer roundsPerStay;
        private int order;
        /** Whether players may submit charts into this pool. */
        private boolean submissionOpen;

        private PoolDefinition(int id, Integer favoriteId, boolean defaultFlag, List<Integer> chartIds) {
            this.id = id;
            this.favoriteId = favoriteId;
            this.defaultFlag = defaultFlag;
            this.chartIds = new ArrayList<>(chartIds);
        }
    }
}
