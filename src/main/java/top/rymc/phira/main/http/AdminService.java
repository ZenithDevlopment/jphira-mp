package top.rymc.phira.main.http;

import com.google.gson.JsonSyntaxException;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.util.GsonUtil;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理员名单。由独立配置文件 data/admins.json 决定（整数数组，即 Phira 用户 ID）。
 * 文件不存在时自动创建为空列表。
 */
public final class AdminService {

    private static final Path ADMIN_FILE = Path.of("data", "admins.json");
    private static final Set<Integer> ADMIN_IDS = ConcurrentHashMap.newKeySet();

    static {
        load();
    }

    private AdminService() {
    }

    public static boolean isAdmin(int userId) {
        return ADMIN_IDS.contains(userId);
    }

    public static synchronized List<Integer> getAdmins() {
        return ADMIN_IDS.stream().sorted().toList();
    }

    public static synchronized boolean addAdmin(int userId) {
        boolean added = ADMIN_IDS.add(userId);
        if (added) {
            save();
        }
        return added;
    }

    public static synchronized boolean removeAdmin(int userId) {
        boolean removed = ADMIN_IDS.remove(userId);
        if (removed) {
            save();
        }
        return removed;
    }

    /** Re-reads the file, used after an import replaces it. */
    public static synchronized void reload() {
        load();
    }

    private static synchronized void load() {
        if (!Files.exists(ADMIN_FILE)) {
            save();
            return;
        }

        // Parse into a temporary set first: a corrupt or half written file must not wipe the
        // list, otherwise every operator would be locked out of the panel at once.
        Set<Integer> loaded = ConcurrentHashMap.newKeySet();
        try (Reader reader = Files.newBufferedReader(ADMIN_FILE)) {
            Integer[] ids = GsonUtil.getGson().fromJson(reader, Integer[].class);
            if (ids != null) {
                for (int id : ids) {
                    loaded.add(id);
                }
            }
        } catch (JsonSyntaxException e) {
            Server.getLogger().error("admins.json is unreadable, keeping the current admin list", e);
            return;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load admin list", e);
        }

        ADMIN_IDS.clear();
        ADMIN_IDS.addAll(loaded);
    }

    private static synchronized void save() {
        try {
            Files.createDirectories(ADMIN_FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(ADMIN_FILE)) {
                GsonUtil.getGson().toJson(ADMIN_IDS.stream().sorted().toList(), writer);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save admin list", e);
        }
    }
}
