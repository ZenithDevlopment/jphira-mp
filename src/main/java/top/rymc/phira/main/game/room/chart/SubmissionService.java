package top.rymc.phira.main.game.room.chart;

import com.google.gson.reflect.TypeToken;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.util.GsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player chart submissions for pools that opted in.
 *
 * <p>Entries are keyed by pool and chart, not by player: several people may back the
 * same chart, and the reviewers see one row listing everyone who asked for it. A player
 * may only back a given chart in a given pool once, which is what keeps the list clean.
 *
 * <p>State lives in {@code data/submissions.json}.
 */
public final class SubmissionService {

    private static final Path FILE = Path.of("data", "submissions.json");
    private static final Type TYPE = new TypeToken<Store>() {
    }.getType();
    /** Per pool and chart, so a flood of submissions cannot grow the file without bound. */
    private static final int MAX_SUBMITTERS = 200;

    public enum Status {
        PENDING, APPROVED, REJECTED
    }

    private static final Map<String, Submission> ENTRIES = new ConcurrentHashMap<>();

    private static String key(int poolId, int chartId) {
        return poolId + ":" + chartId;
    }

    /** One player backing a chart. Name is denormalised so review does not need Phira calls. */
    public record Submitter(int userId, String name, OffsetDateTime at) {
    }

    public static final class Submission {
        private int poolId;
        private int chartId;
        private Status status = Status.PENDING;
        private List<Submitter> submitters = new ArrayList<>();
        private OffsetDateTime createdAt;
        private OffsetDateTime reviewedAt;
        private Integer reviewerId;
        private String reason;
    }

    /** Snapshot handed to the API layer. */
    public record View(int poolId, int chartId, Status status, List<Submitter> submitters,
                       OffsetDateTime createdAt, OffsetDateTime reviewedAt, Integer reviewerId, String reason) {
    }

    private static final class Store {
        private List<Submission> submissions = new ArrayList<>();
    }

    private SubmissionService() {
    }

    public static synchronized void preload() {
        // Cleared first: an import may have removed rows, and stale ones would otherwise survive.
        ENTRIES.clear();
        if (!Files.exists(FILE)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(FILE)) {
            Store store = GsonUtil.getGson().fromJson(reader, TYPE);
            if (store != null && store.submissions != null) {
                for (Submission entry : store.submissions) {
                    if (entry != null && entry.submitters == null) {
                        entry.submitters = new ArrayList<>();
                    }
                    ENTRIES.put(key(entry.poolId, entry.chartId), entry);
                }
            }
        } catch (Exception e) {
            ENTRIES.clear();
            Server.getLogger().warn("Submission store is unreadable ({}), starting empty", e.getMessage());
        }
    }

    /**
     * Registers interest in several charts at once.
     *
     * @return per chart outcome, in the order they were requested
     */
    public static synchronized List<Result> submit(int poolId, List<Integer> chartIds, int userId, String name) {
        List<Result> results = new ArrayList<>();
        boolean changed = false;
        for (int chartId : chartIds) {
            String key = key(poolId, chartId);
            Submission entry = ENTRIES.get(key);
            if (entry == null) {
                entry = new Submission();
                entry.poolId = poolId;
                entry.chartId = chartId;
                entry.createdAt = OffsetDateTime.now();
                ENTRIES.put(key, entry);
            }
            if (entry.status == Status.APPROVED) {
                results.add(new Result(chartId, false, "已经在该谱池里了"));
                continue;
            }
            if (hasSubmitted(entry, userId)) {
                results.add(new Result(chartId, false, "你已经投过这张谱面"));
                continue;
            }
            if (entry.submitters.size() >= MAX_SUBMITTERS) {
                results.add(new Result(chartId, false, "这张谱面的投稿人数已达上限"));
                continue;
            }
            entry.submitters.add(new Submitter(userId, name, OffsetDateTime.now()));
            entry.status = Status.PENDING;
            results.add(new Result(chartId, true, null));
            changed = true;
        }
        if (changed) {
            save();
        }
        return results;
    }

    public record Result(int chartId, boolean accepted, String reason) {
    }

    /** Withdraws this player's support for a chart. Empty entries are dropped. */
    public static synchronized boolean withdraw(int poolId, int chartId, int userId) {
        Submission entry = ENTRIES.get(key(poolId, chartId));
        if (entry == null || entry.status == Status.APPROVED) {
            return false;
        }
        boolean removed = entry.submitters.removeIf(submitter -> submitter.userId() == userId);
        if (!removed) {
            return false;
        }
        if (entry.submitters.isEmpty()) {
            ENTRIES.remove(key(poolId, chartId));
        }
        save();
        return true;
    }

    /** Reviewers decide; approving also lands the chart in the pool. */
    public static synchronized boolean review(int poolId, int chartId, boolean approve, int reviewerId, String reason) {
        Submission entry = ENTRIES.get(key(poolId, chartId));
        if (entry == null) {
            return false;
        }
        entry.status = approve ? Status.APPROVED : Status.REJECTED;
        entry.reviewedAt = OffsetDateTime.now();
        entry.reviewerId = reviewerId;
        entry.reason = reason;
        save();
        return true;
    }

    public static synchronized List<View> listByPool(int poolId, Status status) {
        return ENTRIES.values().stream()
                .filter(entry -> entry.poolId == poolId)
                .filter(entry -> status == null || entry.status == status)
                .sorted(Comparator.comparingInt((Submission entry) -> entry.chartId))
                .map(SubmissionService::view)
                .toList();
    }

    /** Pending entries across every pool, newest backing first. */
    public static synchronized List<View> listPending() {
        return ENTRIES.values().stream()
                .filter(entry -> entry.status == Status.PENDING)
                .sorted(Comparator.comparing((Submission entry) -> entry.createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(SubmissionService::view)
                .toList();
    }

    /** Charts this player backed, across all pools. */
    public static synchronized List<View> listBySubmitter(int userId) {
        return ENTRIES.values().stream()
                .filter(entry -> hasSubmitted(entry, userId))
                .sorted(Comparator.comparing((Submission entry) -> entry.createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .map(SubmissionService::view)
                .toList();
    }

    private static boolean hasSubmitted(Submission entry, int userId) {
        return entry.submitters.stream().anyMatch(submitter -> submitter.userId() == userId);
    }

    private static View view(Submission entry) {
        return new View(entry.poolId, entry.chartId, entry.status, List.copyOf(entry.submitters),
                entry.createdAt, entry.reviewedAt, entry.reviewerId, entry.reason);
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Store store = new Store();
            store.submissions = ENTRIES.values().stream()
                    .sorted(Comparator.comparingInt((Submission entry) -> entry.poolId)
                            .thenComparingInt(entry -> entry.chartId))
                    .toList();
            Path temp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp)) {
                GsonUtil.getGson().toJson(store, writer);
            }
            try {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save submissions", e);
        }
    }
}
