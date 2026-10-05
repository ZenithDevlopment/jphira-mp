package top.rymc.phira.main.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.game.point.PlayerPointService;
import top.rymc.phira.main.game.record.RoundRecordService;
import top.rymc.phira.main.game.room.chart.ChartPool;
import top.rymc.phira.main.game.room.chart.SubmissionService;
import top.rymc.phira.main.util.GsonUtil;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Import and export of every persisted data set.
 *
 * <p>Everything stored under {@code data/} is plain JSON, so files are moved as is rather than
 * through each service's own model: the exported shape is exactly what a restart would read.
 * Imports can either replace a file or merge into it, keyed by whatever identifies a row.
 */
public final class DataTransferService {

    private static final Path BACKUP_DIR = Path.of("data", "backups");
    /** Milliseconds, because two imports in the same second would otherwise collide. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    /** Reject absurd uploads before they reach the parser. */
    private static final int MAX_IMPORT_BYTES = 64 * 1024 * 1024;

    public enum Kind {
        ADMINS("admins"),
        POOLS("pools"),
        POINTS("points"),
        RECORDS("records"),
        SUBMISSIONS("submissions");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Kind of(String value) {
            for (Kind kind : values()) {
                if (kind.key.equals(value)) {
                    return kind;
                }
            }
            return null;
        }

        private Path file() {
            return switch (this) {
                case ADMINS -> Path.of("data", "admins.json");
                case POOLS -> Path.of("data", "chart-pools.json");
                case POINTS -> Path.of("data", "player-points.json");
                case RECORDS -> Path.of("data", "round-records.json");
                case SUBMISSIONS -> Path.of("data", "submissions.json");
            };
        }

        /** Empty document used when nothing has been written yet. */
        private String emptyDocument() {
            return switch (this) {
                case ADMINS -> "[]";
                case POOLS -> "{\"pools\":[]}";
                case POINTS -> "{}";
                case RECORDS -> "[]";
                case SUBMISSIONS -> "{\"submissions\":[]}";
            };
        }
    }

    /** @return the stored document, or an empty one when the file does not exist yet */
    public static String exportJson(Kind kind) throws IOException {
        Path file = kind.file();
        if (!Files.exists(file)) {
            return kind.emptyDocument();
        }
        return Files.readString(file);
    }

    public record ImportResult(int imported, int total, String backupFile) {
    }

    /**
     * Writes an imported document.
     *
     * @param merge keep existing rows and only add or overwrite the incoming ones
     */
    public static ImportResult importJson(Kind kind, String document, boolean merge) throws IOException {
        if (document == null || document.isBlank()) {
            throw new IllegalArgumentException("内容为空");
        }
        if (document.length() > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("内容过大");
        }

        // Validate the shape before touching anything on disk. Lenient parsing happily turns
        // "not-json" into a JsonPrimitive, so a null check alone is not enough.
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(document);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("不是有效的 JSON");
        }
        validate(kind, parsed);

        String backup = backup(kind);
        String target = merge ? mergeDocument(kind, parsed) : document;
        int count = countRows(kind, JsonParser.parseString(target));

        Files.createDirectories(kind.file().getParent());
        try (Writer writer = Files.newBufferedWriter(kind.file())) {
            writer.write(target);
        }
        reload(kind);

        Server.getLogger().info("Imported {} ({} rows, merge={}), previous copy at {}",
                kind.key(), count, merge, backup);
        return new ImportResult(count, count, backup);
    }

    /** Rejects a document whose shape does not match what the data set expects. */
    private static void validate(Kind kind, JsonElement root) {
        if (root == null || root.isJsonNull()) {
            throw new IllegalArgumentException("不是有效的 JSON");
        }
        if (!root.isJsonArray() && !root.isJsonObject()) {
            throw new IllegalArgumentException("JSON 顶层必须是对象或数组");
        }

        switch (kind) {
            case ADMINS, RECORDS -> {
                if (!root.isJsonArray()) {
                    throw new IllegalArgumentException(kind.key() + " 应为数组");
                }
            }
            case POOLS -> requireArrayField(root, "pools");
            case SUBMISSIONS -> requireArrayField(root, "submissions");
            case POINTS -> {
                if (!root.isJsonObject()) {
                    throw new IllegalArgumentException("玩家积分应为对象（用户 ID 到积分）");
                }
            }
        }
    }

    private static void requireArrayField(JsonElement root, String field) {
        if (!root.isJsonObject() || !root.getAsJsonObject().has(field)
                || !root.getAsJsonObject().get(field).isJsonArray()) {
            throw new IllegalArgumentException("缺少数组字段 " + field);
        }
    }

    /** Copies the current file aside so a bad import can be undone. */
    private static String backup(Kind kind) throws IOException {
        Path file = kind.file();
        if (!Files.exists(file)) {
            return "";
        }
        Files.createDirectories(BACKUP_DIR);
        // Never overwrite an earlier backup: collisions would silently lose the older copy.
        Path target;
        do {
            target = BACKUP_DIR.resolve(kind.key() + "-" + LocalDateTime.now().format(STAMP) + ".json");
        } while (Files.exists(target));
        Files.copy(file, target);
        return target.toString();
    }

    private static String mergeDocument(Kind kind, JsonElement incoming) {
        JsonElement current;
        try {
            current = JsonParser.parseString(exportJson(kind));
        } catch (IOException e) {
            current = JsonParser.parseString(kind.emptyDocument());
        }

        JsonElement merged = switch (kind) {
            case ADMINS -> mergeArrays(current, incoming, null);
            case POOLS -> wrapPools(mergeArrays(rows(current, "pools"), rows(incoming, "pools"), "id"));
            case SUBMISSIONS -> wrapSubmissions(mergeArrays(rows(current, "submissions"),
                    rows(incoming, "submissions"), null));
            case POINTS -> mergeObjects(current, incoming);
            case RECORDS -> mergeArrays(current, incoming, "id");
        };
        return GsonUtil.getGson().toJson(merged);
    }

    private static JsonArray rows(JsonElement document, String field) {
        if (document == null || !document.isJsonObject()) {
            return new JsonArray();
        }
        JsonElement rows = document.getAsJsonObject().get(field);
        return rows != null && rows.isJsonArray() ? rows.getAsJsonArray() : new JsonArray();
    }

    private static JsonObject wrapPools(JsonArray pools) {
        JsonObject wrapped = new JsonObject();
        wrapped.add("pools", pools);
        return wrapped;
    }

    private static JsonObject wrapSubmissions(JsonArray submissions) {
        JsonObject wrapped = new JsonObject();
        wrapped.add("submissions", submissions);
        return wrapped;
    }

    /** Later rows win on key collision; without a key the arrays are concatenated. */
    private static JsonArray mergeArrays(JsonElement current, JsonElement incoming, String key) {
        JsonArray currentRows = current != null && current.isJsonArray()
                ? current.getAsJsonArray() : rows(current, "pools");
        JsonArray incomingRows = incoming != null && incoming.isJsonArray()
                ? incoming.getAsJsonArray() : rows(incoming, "pools");

        JsonArray merged = new JsonArray();
        if (key == null) {
            Set<String> seen = new LinkedHashSet<>();
            for (JsonElement row : incomingRows) {
                merged.add(row);
                seen.add(row.toString());
            }
            for (JsonElement row : currentRows) {
                if (seen.add(row.toString())) {
                    merged.add(row);
                }
            }
            return merged;
        }

        Set<String> seenKeys = new LinkedHashSet<>();
        for (JsonElement row : incomingRows) {
            if (row.isJsonObject()) {
                seenKeys.add(keyOf(row.getAsJsonObject(), key));
            }
            merged.add(row);
        }
        for (JsonElement row : currentRows) {
            if (!row.isJsonObject()) {
                merged.add(row);
                continue;
            }
            if (seenKeys.add(keyOf(row.getAsJsonObject(), key))) {
                merged.add(row);
            }
        }
        return merged;
    }

    private static String keyOf(JsonObject row, String key) {
        JsonElement value = row.get(key);
        return value == null || value.isJsonNull() ? row.toString() : value.getAsString();
    }

    private static JsonObject mergeObjects(JsonElement current, JsonElement incoming) {
        JsonObject merged = new JsonObject();
        if (current != null && current.isJsonObject()) {
            current.getAsJsonObject().entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
        }
        if (incoming != null && incoming.isJsonObject()) {
            incoming.getAsJsonObject().entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
        }
        return merged;
    }

    private static int countRows(Kind kind, JsonElement document) {
        return switch (kind) {
            case ADMINS, RECORDS -> document.isJsonArray() ? document.getAsJsonArray().size() : 0;
            case POOLS -> rows(document, "pools").size();
            case SUBMISSIONS -> rows(document, "submissions").size();
            case POINTS -> document.isJsonObject() ? document.getAsJsonObject().size() : 0;
        };
    }

    /** Pushes the file back into memory; without this the running server keeps the old state. */
    private static void reload(Kind kind) throws IOException {
        switch (kind) {
            case ADMINS -> AdminService.reload();
            case POOLS -> ChartPool.preload();
            case POINTS -> PlayerPointService.reload();
            case RECORDS -> RoundRecordService.reload();
            case SUBMISSIONS -> SubmissionService.preload();
        }
    }

    /** Files available to restore, newest first. */
    public static java.util.List<String> listBackups(Kind kind) throws IOException {
        if (!Files.exists(BACKUP_DIR)) {
            return java.util.List.of();
        }
        try (java.util.stream.Stream<Path> files = Files.list(BACKUP_DIR)) {
            return files
                    .filter(path -> path.getFileName().toString().startsWith(kind.key() + "-"))
                    .map(path -> path.getFileName().toString())
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
        }
    }

    /** Restores a previously written backup. */
    public static ImportResult restore(Kind kind, String backupName) throws IOException {
        Path source = BACKUP_DIR.resolve(Path.of(backupName).getFileName().toString());
        if (!Files.exists(source) || !backupName.startsWith(kind.key() + "-")) {
            throw new IllegalArgumentException("备份不存在");
        }
        return importJson(kind, Files.readString(source), false);
    }

    private DataTransferService() {
    }
}
