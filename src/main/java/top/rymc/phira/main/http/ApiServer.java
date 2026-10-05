package top.rymc.phira.main.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HandlerType;
import io.javalin.json.JavalinGson;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.data.RoundRecord;
import top.rymc.phira.main.game.point.PlayerPointService;
import top.rymc.phira.main.game.record.RoundRecordService;
import top.rymc.phira.main.data.UserInfo;
import top.rymc.phira.main.game.player.PlayerManager;
import top.rymc.phira.main.game.room.Room;
import top.rymc.phira.main.game.room.RoomManager;
import top.rymc.phira.main.game.room.RoomStore;
import top.rymc.phira.main.game.room.RoomSnapshot;
import top.rymc.phira.main.game.room.chart.ChartIndex;
import top.rymc.phira.main.game.room.chart.ChartPool;
import top.rymc.phira.main.game.room.chart.PoolCategory;
import top.rymc.phira.main.game.room.chart.RoomChartPool;
import top.rymc.phira.main.game.room.chart.ScreeningRule;
import top.rymc.phira.main.game.room.chart.SubmissionService;
import top.rymc.phira.main.game.room.local.LocalRoom;
import top.rymc.phira.main.game.room.local.LocalRoomBuilder;
import top.rymc.phira.main.game.room.state.RoomGameState;
import top.rymc.phira.main.game.room.state.RoomPlaying;
import top.rymc.phira.main.game.room.state.RoomWaitForReady;
import top.rymc.phira.main.util.GsonUtil;
import top.rymc.phira.main.util.PhiraFetcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * HTTP 控制面 API 服务器（Javalin）。
 *
 * - 静态前端资源挂载 ./frontend/dist（Location.EXTERNAL）
 * - API 路径遵循 /api/v1/... 文档
 * - 除 /api/v1/login 外均需 Bearer Phira Token；写操作需管理员权限（data/admins.json）
 */
public final class ApiServer {

    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final CountDownLatch STARTED = new CountDownLatch(1);
    private static final int DEFAULT_POOL_SIZE = 15;
    /** Upper bound on a single chart search response. */
    private static final int MAX_SEARCH_LIMIT = 500;
    /** Upper bound on one submission call, so a player cannot flood the review queue. */
    private static final int MAX_SUBMIT_BATCH = 20;
    /** Sender id clients render as a system message rather than a player. */
    private static final int SYSTEM_SENDER_ID = -1;
    private static final int MAX_CHAT_LENGTH = 200;
    /** Upper bound for any page size, so one request cannot pull the whole history. */
    private static final int MAX_PAGE_SIZE = 200;
    /** Duration probes hit the network, so a single request may only ask for a few. */
    private static final int MAX_PROBE_BATCH = 20;
    private static volatile String startupFailure;

    private ApiServer() {
    }

    public static void start(String host, int port) {
        Thread thread = new Thread(() -> {
            try {
                Javalin.create(ApiServer::configure).start(host, port);
                Server.getLogger().info("HTTP API server listening on {}:{}", host, port);
            } catch (Exception e) {
                // The web manager is the only way to configure pools, so a silent failure here
                // would leave the operator with a running game server and no control panel.
                startupFailure = "HTTP API server failed to bind " + host + ":" + port;
                Server.getLogger().error(startupFailure, e);
            } finally {
                STARTED.countDown();
            }
        }, "Http-ApiServer");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 阻塞直到 HTTP API 服务器启动完成（或启动失败），确保服务端 "Done" 日志在最后输出。
     */
    public static void awaitStarted() {
        try {
            STARTED.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (startupFailure != null) {
            throw new IllegalStateException(startupFailure
                    + "; the game port is up but the web manager is unreachable. "
                    + "Free the port or pass a different --http-port.");
        }
    }

    private static void configure(io.javalin.config.JavalinConfig config) {
        config.jsonMapper(new JavalinGson(GsonUtil.getCompactGson(), false));

        config.routes.before("/api/v1/*", ApiServer::authenticate);
        config.routes.after("/api/v1/*", ApiServer::corsHeaders);
        config.routes.options("/api/v1/*", ctx -> ctx.status(204));

        config.routes.post("/api/v1/login", ApiServer::handleLogin);

        config.routes.post("/api/v1/room/{id}/create", ApiServer::handleRoomCreate);
        config.routes.put("/api/v1/room/{id}/update", ApiServer::handleRoomUpdate);
        // 具体路径必须先于参数路径注册（Javalin 按注册顺序匹配，避免 /room/list 被 /room/{id} 捕获）
        config.routes.get("/api/v1/room/list", ApiServer::handleRoomList);
        config.routes.get("/api/v1/room/{id}/", ApiServer::handleRoomGet);
        config.routes.get("/api/v1/room/{id}", ApiServer::handleRoomGet);
        config.routes.delete("/api/v1/room/{id}", ApiServer::handleRoomDelete);
        config.routes.post("/api/v1/room/{id}/end", ApiServer::handleRoomEnd);
        config.routes.post("/api/v1/room/{id}/say", ApiServer::handleRoomSay);
        config.routes.post("/api/v1/broadcast", ApiServer::handleBroadcast);
        config.routes.get("/api/v1/room/{id}/pool", ApiServer::handleRoomPoolGet);
        config.routes.put("/api/v1/room/{id}/pool/switch", ApiServer::handleRoomPoolSwitch);
        config.routes.put("/api/v1/room/{id}/pool/favorite", ApiServer::handleRoomPoolFavorite);

        config.routes.get("/api/v1/pool/list", ApiServer::handlePoolList);
        config.routes.post("/api/v1/pool", ApiServer::handlePoolCreate);
        config.routes.post("/api/v1/pool/batch", ApiServer::handlePoolBatchCreate);
        config.routes.delete("/api/v1/pool/{id}", ApiServer::handlePoolRemove);
        config.routes.put("/api/v1/pool/{id}", ApiServer::handlePoolUpdate);
        config.routes.post("/api/v1/pool/{id}/chart", ApiServer::handlePoolChartAdd);
        config.routes.post("/api/v1/pool/{id}/charts", ApiServer::handlePoolChartAddBatch);
        config.routes.delete("/api/v1/pool/{id}/chart/{chartId}", ApiServer::handlePoolChartRemove);
        config.routes.put("/api/v1/pool/{id}/favorite", ApiServer::handlePoolFavorite);
        config.routes.put("/api/v1/pool/{id}/default", ApiServer::handlePoolDefault);
        // 投稿：玩家侧只需登录，审核侧需管理员
        config.routes.post("/api/v1/pool/{id}/submissions", ApiServer::handleSubmitCharts);
        config.routes.get("/api/v1/pool/{id}/submissions", ApiServer::handlePoolSubmissions);
        config.routes.delete("/api/v1/pool/{id}/submissions/{chartId}", ApiServer::handleSubmissionWithdraw);
        config.routes.get("/api/v1/submission/open-pools", ApiServer::handleOpenPools);
        config.routes.get("/api/v1/submission/mine", ApiServer::handleMySubmissions);
        config.routes.get("/api/v1/submission/pending", ApiServer::handlePendingSubmissions);
        config.routes.post("/api/v1/submission/{poolId}/{chartId}/approve", ApiServer::handleSubmissionApprove);
        config.routes.post("/api/v1/submission/{poolId}/{chartId}/reject", ApiServer::handleSubmissionReject);

        config.routes.get("/api/v1/chart/search", ApiServer::handleChartSearch);
        config.routes.post("/api/v1/pool/generate", ApiServer::handlePoolGenerate);
        config.routes.post("/api/v1/chart/duration", ApiServer::handleChartDurationProbe);
        // 数据导入导出
        config.routes.get("/api/v1/data/{kind}/export", ApiServer::handleDataExport);
        config.routes.post("/api/v1/data/{kind}/import", ApiServer::handleDataImport);
        config.routes.get("/api/v1/data/{kind}/backups", ApiServer::handleDataBackups);
        config.routes.post("/api/v1/data/{kind}/restore", ApiServer::handleDataRestore);

        config.routes.get("/api/v1/admin", ApiServer::handleAdminList);
        config.routes.post("/api/v1/admin", ApiServer::handleAdminAdd);
        config.routes.delete("/api/v1/admin/{userId}", ApiServer::handleAdminRemove);

        // 比赛记录：具体路径先于 /record/{id} 注册
        config.routes.get("/api/v1/record/list", ApiServer::handleRecordList);
        config.routes.get("/api/v1/record/player/{playerId}", ApiServer::handlePlayerRecords);
        config.routes.get("/api/v1/record/{id}", ApiServer::handleRecordGet);
        config.routes.get("/api/v1/point/ranking", ApiServer::handlePointRanking);

        // 前端静态资源与 SPA 路由 fallback（API 具体路由优先匹配，通配在此兜底）
        config.routes.get("/", ApiServer::serveFrontend);
        config.routes.get("/*", ApiServer::serveFrontend);

        config.routes.exception(ApiException.class, (e, ctx) ->
                ctx.status(e.status).json(Map.of("ok", false, "reason", e.reason)));
        config.routes.exception(Exception.class, (e, ctx) -> {
            Server.getLogger().error("HTTP API error on {}", ctx.path(), e);
            ctx.status(500).json(Map.of("ok", false, "reason", "服务器内部错误"));
        });
    }

    // ===== 前端静态资源与 SPA fallback =====

    private static final Path FRONTEND_DIR = Path.of("frontend", "dist").toAbsolutePath().normalize();

    private static final Map<String, String> STATIC_MIME = Map.ofEntries(
            entry("html", "text/html; charset=utf-8"),
            entry("js", "application/javascript"),
            entry("mjs", "application/javascript"),
            entry("css", "text/css"),
            entry("json", "application/json"),
            entry("map", "application/json"),
            entry("txt", "text/plain"),
            entry("png", "image/png"),
            entry("jpg", "image/jpeg"),
            entry("jpeg", "image/jpeg"),
            entry("gif", "image/gif"),
            entry("svg", "image/svg+xml"),
            entry("ico", "image/x-icon"),
            entry("webp", "image/webp"),
            entry("woff", "font/woff"),
            entry("woff2", "font/woff2"),
            entry("ttf", "font/ttf")
    );

    private static void serveFrontend(Context ctx) {
        String path = ctx.path();
        if (path.startsWith("/api/")) { // 未定义的 API 路径不参与前端 fallback
            ctx.status(404).result("Not Found");
            return;
        }
        String relative = path.startsWith("/") ? path.substring(1) : path;

        Path file = FRONTEND_DIR.resolve(relative).normalize();
        if (!file.startsWith(FRONTEND_DIR)) { // 路径穿越防护
            ctx.status(400).result("Bad Request");
            return;
        }

        if (Files.isRegularFile(file)) {
            serveStaticFile(ctx, file, false);
            return;
        }

        // 不存在的资源文件（含扩展名）直接 404，不 fallback 成 HTML
        String lastSegment = relative.substring(relative.lastIndexOf('/') + 1);
        if (lastSegment.contains(".")) {
            ctx.status(404).result("Not Found");
            return;
        }

        // 前端路由 fallback 到对应静态目录的 HTML
        Path html;
        if (relative.startsWith("room")) {
            html = FRONTEND_DIR.resolve("room/__fallback__/index.html");
        } else if (relative.startsWith("pool")) {
            html = FRONTEND_DIR.resolve("pool/__fallback__/index.html");
        } else {
            html = FRONTEND_DIR.resolve("index.html");
        }
        serveStaticFile(ctx, html, true);
    }

    private static void serveStaticFile(Context ctx, Path file, boolean html) {
        try {
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String ext = dot >= 0 ? name.substring(dot + 1) : "";
            String contentType = html ? "text/html; charset=utf-8" : STATIC_MIME.getOrDefault(ext, "application/octet-stream");
            ctx.contentType(contentType);
            if (!html) {
                ctx.header("Cache-Control", "public, max-age=31536000, immutable");
            }
            InputStream in = Files.newInputStream(file);
            ctx.result(in);
        } catch (IOException e) {
            ctx.status(404).result("Not Found");
        }
    }

    // ===== 鉴权 =====

    private static void corsHeaders(Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static void authenticate(Context ctx) {
        if (ctx.method() == HandlerType.OPTIONS) {
            return;
        }
        if (ctx.path().equals("/api/v1/login")) {
            return;
        }

        String auth = ctx.header("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw new ApiException(401, "需要登录");
        }

        Integer userId = JwtService.parseUserId(auth.substring("Bearer ".length()));
        if (userId == null) {
            throw new ApiException(401, "需要登录");
        }
        ctx.attribute("userId", userId);
    }

    private static int requireUser(Context ctx) {
        Integer userId = ctx.attribute("userId");
        if (userId == null) {
            throw new ApiException(401, "需要登录");
        }
        return userId;
    }

    private static int requireAdmin(Context ctx) {
        int userId = requireUser(ctx);
        if (!AdminService.isAdmin(userId)) {
            throw new ApiException(403, "需要权限");
        }
        return userId;
    }

    private static void requireInRoom(int userId, LocalRoom room) {
        if (AdminService.isAdmin(userId)) {
            return;
        }
        PlayerManager.getPlayer(userId)
                .flatMap(p -> p.getRoom())
                .filter(r -> r == room)
                .orElseThrow(() -> new ApiException(403, "需要权限"));
    }

    // ===== 登录 =====

    private static void handleLogin(Context ctx) {
        LoginBody body = bodyOrNull(ctx, LoginBody.class);
        if (body == null || body.email() == null || body.password() == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }

        try {
            PhiraFetcher.LoginResult result = PhiraFetcher.POST_LOGIN.apply(body.email(), body.password());
            boolean isAdmin = AdminService.isAdmin(result.id());
            String jwt = JwtService.issueToken(result.id(), isAdmin);
            Server.getLogger().info("HTTP login: user {} isAdmin={}", result.id(), isAdmin);
            ctx.json(Map.of(
                    "ok", true,
                    "token", jwt,
                    "phira_token", result.token(),
                    "isAdmin", isAdmin,
                    "userId", result.id()
            ));
        } catch (IOException e) {
            throw new ApiException(400, "登录失败，请检查邮箱与密码");
        }
    }

    // ===== 房间 =====

    private static void handleRoomCreate(Context ctx) {
        requireAdmin(ctx);
        String roomId = ctx.pathParam("id");
        if (!ROOM_ID_PATTERN.matcher(roomId).matches()) {
            throw new ApiException(400, "房间名仅限字母、数字、-、_。");
        }
        if (RoomManager.findRoom(roomId) != null) {
            throw new ApiException(400, "房间已存在");
        }

        CreateRoomBody body = bodyOrNull(ctx, CreateRoomBody.class);
        String type = body == null || body.type() == null ? "local" : body.type();
        if (!"local".equals(type)) {
            throw new ApiException(400, "不支持的房间类型：" + type);
        }

        List<ChartPool.PoolSnapshot> pools;
        if (body == null || body.pools() == null || body.pools().isEmpty()) {
            pools = ChartPool.getDefaultPools();
            if (pools.isEmpty()) {
                throw new ApiException(400, "当前没有默认启用的谱池");
            }
        } else {
            pools = ChartPool.resolvePools(body.pools());
            if (pools.isEmpty()) {
                throw new ApiException(400, "指定的谱池不存在或均为空池");
            }
        }

        new LocalRoomBuilder()
                .host(false)
                .cycle(false)
                .chat(true)
                .autoDestroy(false)
                .pools(pools)
                .build(roomId);
        Server.getLogger().info("HTTP create room: {} pools={}", roomId, pools.stream().map(ChartPool.PoolSnapshot::id).toList());
        RoomStore.save();
        ctx.json(Map.of("ok", true));
    }

    private static void handleRoomUpdate(Context ctx) {
        requireAdmin(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        UpdateRoomBody body = bodyOrNull(ctx, UpdateRoomBody.class);
        if (body == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }

        LocalRoom.RoomSetting setting = room.getSetting();
        if (body.live() != null) {
            setting.setLive(body.live());
        }
        if (body.adminIds() != null) {
            // Validated here: a typo would otherwise silently grant nobody control.
            List<Integer> clean = new ArrayList<>();
            for (int id : body.adminIds()) {
                if (id <= 0) {
                    throw new ApiException(400, "管理员 ID 必须是正整数");
                }
                if (!clean.contains(id)) {
                    clean.add(id);
                }
            }
            setting.setAdminIds(new LinkedHashSet<>(clean));
        }
        if (body.chatEnable() != null) {
            setting.setChat(body.chatEnable());
        }
        if (body.minPlayer() != null) {
            if (body.minPlayer() <= 0) {
                throw new ApiException(400, "minPlayer 必须大于 0");
            }
            setting.setMinPlayer(body.minPlayer());
        }
        if (body.maxPlayer() != null) {
            if (body.maxPlayer() <= 0) {
                throw new ApiException(400, "maxPlayer 必须大于 0");
            }
            setting.setMaxPlayer(body.maxPlayer());
        }
        if (body.selectCountdown() != null) {
            if (body.selectCountdown() < 10) {
                throw new ApiException(400, "selectCountdown 必须大于等于 10");
            }
            setting.setSelectChartCountdownSeconds(body.selectCountdown());
        }
        if (body.readyCountdown() != null) {
            if (body.readyCountdown() <= 0) {
                throw new ApiException(400, "readyCountdown 必须大于 0");
            }
            setting.setReadyCountdownSeconds(body.readyCountdown());
        }
        if (body.forceFinish() != null) {
            if (body.forceFinish() <= 0) {
                throw new ApiException(400, "forceFinish 必须大于 0");
            }
            setting.setForceFinishSeconds(body.forceFinish());
        }
        if (body.interval() != null) {
            if (body.interval() <= 0) {
                throw new ApiException(400, "interval 必须大于 0");
            }
            setting.setRefreshIntervalRounds(body.interval());
        }
        Server.getLogger().info("HTTP update room: {} fields updated", room.getRoomId());
        RoomStore.save();
        ctx.json(Map.of("ok", true));
    }

    private static void handleRoomGet(Context ctx) {
        int userId = requireUser(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        requireInRoom(userId, room);
        ctx.json(Map.of("ok", true, "info", roomSnapshot(room)));
    }

    private static void handleRoomList(Context ctx) {
        int userId = requireUser(ctx);
        if (AdminService.isAdmin(userId)) {
            List<Map<String, Object>> rooms = RoomManager.getAllRooms().stream()
                    .map(room -> roomSnapshot((LocalRoom) room))
                    .toList();
            ctx.json(Map.of("ok", true, "rooms", rooms));
            return;
        }

        List<Map<String, Object>> rooms = PlayerManager.getPlayer(userId)
                .flatMap(p -> p.getRoom())
                .map(room -> List.of(roomSnapshot((LocalRoom) room)))
                .orElseGet(List::of);
        ctx.json(Map.of("ok", true, "rooms", rooms));
    }

    private static void handleRoomDelete(Context ctx) {
        requireAdmin(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        if (!room.getView().getPlayers().isEmpty() || !room.getView().getMonitors().isEmpty()) {
            throw new ApiException(400, "房间非空，无法删除");
        }
        // destroy() and not just removeRoom(): the state's countdown tasks would keep
        // running against a room nobody can reach any more.
        room.destroy();
        Server.getLogger().info("HTTP delete room: {}", room.getRoomId());
        RoomStore.save();
        ctx.json(Map.of("ok", true));
    }

    private static void handleRoomEnd(Context ctx) {
        requireAdmin(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        if (!(room.getView().getState() instanceof RoomPlaying state)) {
            throw new ApiException(400, "房间不在游戏中");
        }
        state.forceFinishByServer();
        Server.getLogger().info("HTTP force end room: {}", room.getRoomId());
        ctx.json(Map.of("ok", true));
    }

    /** Pushes an operator message into one room; clients render it as a system message. */
    private static void handleRoomSay(Context ctx) {
        requireAdmin(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        String message = requireSayMessage(ctx);
        room.getPlayerManager().broadcast(op -> op.receiveChat(SYSTEM_SENDER_ID, message));
        Server.getLogger().info("HTTP room message to {}: {}", room.getRoomId(), message);
        ctx.json(Map.of("ok", true, "delivered", room.getPlayerManager().getPlayersCopy().size()));
    }

    /** Pushes an operator message to every online player. */
    private static void handleBroadcast(Context ctx) {
        requireAdmin(ctx);
        String message = requireSayMessage(ctx);
        PlayerManager.getOnlinePlayers()
                .forEach(player -> player.operations().ifPresent(op -> op.receiveChat(SYSTEM_SENDER_ID, message)));
        Server.getLogger().info("HTTP global message: {}", message);
        ctx.json(Map.of("ok", true, "delivered", PlayerManager.getOnlinePlayers().size()));
    }

    private static String requireSayMessage(Context ctx) {
        SayBody body = bodyOrNull(ctx, SayBody.class);
        if (body == null || body.message() == null || body.message().isBlank()) {
            throw new ApiException(400, "消息不能为空");
        }
        // Line breaks would let an operator message forge extra chat bubbles.
        String message = body.message().strip().replaceAll("[\\r\\n]+", " ");
        // Players see this unthrottled, so keep it to something a chat line can hold.
        return message.length() > MAX_CHAT_LENGTH ? message.substring(0, MAX_CHAT_LENGTH) : message;
    }

    // ===== 数据导入导出 =====

    /** Streams the stored document so it can be saved and moved to another server. */
    private static void handleDataExport(Context ctx) {
        requireAdmin(ctx);
        DataTransferService.Kind kind = requireDataKind(ctx);
        try {
            String document = DataTransferService.exportJson(kind);
            ctx.contentType("application/json");
            ctx.header("Content-Disposition", "attachment; filename=\"" + kind.key() + ".json\"");
            ctx.result(document);
            Server.getLogger().info("HTTP export {}", kind.key());
        } catch (IOException e) {
            throw new ApiException(500, "导出失败：" + e.getMessage());
        }
    }

    /** Accepts a document and either replaces the data set or merges into it. */
    private static void handleDataImport(Context ctx) {
        requireAdmin(ctx);
        DataTransferService.Kind kind = requireDataKind(ctx);
        boolean merge = "true".equalsIgnoreCase(blankToNull(ctx.queryParam("merge")))
                || "merge".equalsIgnoreCase(blankToNull(ctx.queryParam("mode")));

        // Text, not form encoded: the browser sends the file body verbatim.
        String document = ctx.body();
        if (document == null || document.isBlank()) {
            throw new ApiException(400, "内容为空");
        }
        try {
            DataTransferService.ImportResult result = DataTransferService.importJson(kind, document, merge);
            ctx.json(Map.of("ok", true, "imported", result.imported(),
                    "total", result.total(), "backup", result.backupFile()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (IOException e) {
            throw new ApiException(500, "导入失败：" + e.getMessage());
        }
    }

    private static void handleDataBackups(Context ctx) {
        requireAdmin(ctx);
        DataTransferService.Kind kind = requireDataKind(ctx);
        try {
            ctx.json(Map.of("ok", true, "backups", DataTransferService.listBackups(kind)));
        } catch (IOException e) {
            throw new ApiException(500, "无法列出备份");
        }
    }

    private static void handleDataRestore(Context ctx) {
        requireAdmin(ctx);
        DataTransferService.Kind kind = requireDataKind(ctx);
        RestoreBody body = bodyOrNull(ctx, RestoreBody.class);
        if (body == null || body.backup() == null || body.backup().isBlank()) {
            throw new ApiException(400, "请指定要恢复的备份");
        }
        try {
            DataTransferService.ImportResult result = DataTransferService.restore(kind, body.backup());
            ctx.json(Map.of("ok", true, "imported", result.imported()));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        } catch (IOException e) {
            throw new ApiException(500, "恢复失败：" + e.getMessage());
        }
    }

    private static DataTransferService.Kind requireDataKind(Context ctx) {
        DataTransferService.Kind kind = DataTransferService.Kind.of(ctx.pathParam("kind"));
        if (kind == null) {
            throw new ApiException(400, "未知数据类型：" + ctx.pathParam("kind"));
        }
        return kind;
    }

    // ===== 比赛记录 =====

    /** Newest first; {@code roomId} and {@code playerId} narrow the result. */
    private static void handleRecordList(Context ctx) {
        requireAdmin(ctx);
        int limit = queryLimit(ctx);
        int offset = Math.max(0, (int) queryDouble(ctx, "offset", 0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", RoundRecordService.size());
        result.put("records", RoundRecordService.query(limit, offset,
                        blankToNull(ctx.queryParam("roomId")), queryIntOrNull(ctx, "playerId"))
                .stream().map(ApiServer::recordJson).toList());
        ctx.json(Map.of("ok", true, "result", result));
    }

    private static void handleRecordGet(Context ctx) {
        requireAdmin(ctx);
        RoundRecord record = RoundRecordService.find(ctx.pathParam("id"))
                .orElseThrow(() -> new ApiException(404, "记录不存在"));
        ctx.json(Map.of("ok", true, "record", recordJson(record)));
    }

    private static void handlePlayerRecords(Context ctx) {
        requireAdmin(ctx);
        int playerId = pathInt(ctx, "playerId");
        ctx.json(Map.of("ok", true, "result", Map.of(
                "playerId", playerId,
                "rounds", RoundRecordService.byPlayer(playerId, queryLimit(ctx))
                        .stream().map(ApiServer::playerRoundJson).toList()
        )));
    }

    private static void handlePointRanking(Context ctx) {
        requireAdmin(ctx);
        ctx.json(Map.of("ok", true, "ranking", PlayerPointService.getRankingSnapshot(queryLimit(ctx))
                .stream().map(ApiServer::rankEntryJson).toList()));
    }

    // Stored as snake_case on disk, but the HTTP contract is camelCase like every other endpoint.

    private static Map<String, Object> recordJson(RoundRecord record) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", record.id());
        json.put("roomId", record.roomId());
        json.put("chartId", record.chartId());
        json.put("chartName", record.chartName());
        json.put("startedAt", record.startedAt());
        json.put("finishedAt", record.finishedAt());
        json.put("results", record.results().stream().map(ApiServer::playerResultJson).toList());
        return json;
    }

    private static Map<String, Object> playerRoundJson(RoundRecord.PlayerRound round) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("recordId", round.recordId());
        json.put("roomId", round.roomId());
        json.put("chartId", round.chartId());
        json.put("chartName", round.chartName());
        json.put("finishedAt", round.finishedAt());
        json.put("result", playerResultJson(round.result()));
        return json;
    }

    private static Map<String, Object> playerResultJson(RoundRecord.PlayerResult result) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("playerId", result.playerId());
        json.put("playerName", result.playerName());
        json.put("rank", result.rank());
        json.put("score", result.score());
        json.put("accuracy", result.accuracy());
        json.put("std", result.std());
        json.put("gainedPoints", result.gainedPoints());
        json.put("totalPoints", result.totalPoints());
        return json;
    }

    private static Map<String, Object> rankEntryJson(PlayerPointService.RankEntry entry) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("playerId", entry.playerId());
        json.put("name", entry.name());
        json.put("points", entry.points());
        json.put("rank", entry.rank());
        return json;
    }

    private static int queryLimit(Context ctx) {
        return Math.max(1, Math.min(MAX_PAGE_SIZE, (int) queryDouble(ctx, "limit", 50)));
    }

    private static Integer queryIntOrNull(Context ctx, String name) {
        Double value = queryNumber(ctx, name);
        return value == null ? null : value.intValue();
    }

    private static void handleRoomPoolGet(Context ctx) {
        int userId = requireUser(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        requireInRoom(userId, room);
        ctx.json(Map.of("ok", true, "pool", poolStatus(room)));
    }

    private static void handleRoomPoolSwitch(Context ctx) {
        requireAdmin(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        SwitchPoolBody body = bodyOrNull(ctx, SwitchPoolBody.class);
        if (body == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        try {
            room.getChartPool().switchPool(body.poolId());
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, e.getMessage());
        }
        Server.getLogger().info("HTTP switch room {} pending pool to {}", room.getRoomId(), body.poolId());
        ctx.json(Map.of("ok", true));
    }

    private static void handleRoomPoolFavorite(Context ctx) {
        requireAdmin(ctx);
        LocalRoom room = requireRoom(ctx.pathParam("id"));
        FavoriteBody body = requireFavoriteBody(ctx);
        room.getChartPool().setFavorite(body.favoriteId());
        Server.getLogger().info("HTTP room {} pool favorite set to {}", room.getRoomId(), body.favoriteId());
        ctx.json(Map.of("ok", true));
    }

    // ===== 全局谱池管理 =====

    private static void handlePoolList(Context ctx) {
        requireAdmin(ctx);
        List<Map<String, Object>> pools = ChartPool.listPools().stream()
                .map(ApiServer::poolSnapshot)
                .toList();
        ctx.json(Map.of("ok", true, "pools", pools));
    }

    private static void handlePoolCreate(Context ctx) {
        requireAdmin(ctx);
        PoolCreateBody body = bodyOrNull(ctx, PoolCreateBody.class);
        if (body == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        try {
            ChartPool.addPool(body.id(), body.chartIds() == null ? List.of() : body.chartIds(),
                    body.category(), body.sizeLimit(), body.roundsPerStay());
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
        Server.getLogger().info("HTTP create pool: {} charts={}", body.id(), body.chartIds());
        ctx.json(Map.of("ok", true));
    }

    private static void handlePoolUpdate(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        PoolUpdateBody body = bodyOrNull(ctx, PoolUpdateBody.class);
        if (body == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        try {
            ChartPool.updatePool(poolId, body.category(), body.sizeLimit(), body.roundsPerStay(),
                    body.order(), body.submissionOpen());
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
        Server.getLogger().info("HTTP update pool {}: category={} sizeLimit={} roundsPerStay={} order={} submissionOpen={}",
                poolId, body.category(), body.sizeLimit(), body.roundsPerStay(), body.order(), body.submissionOpen());
        ctx.json(Map.of("ok", true));
    }

    private static void handlePoolBatchCreate(Context ctx) {
        requireAdmin(ctx);
        PoolBatchBody body = bodyOrNull(ctx, PoolBatchBody.class);
        if (body == null || body.count() < 1) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        try {
            List<Integer> created = ChartPool.createEmptyPools(body.count(), body.category(),
                    body.sizeLimit(), body.roundsPerStay());
            ctx.json(Map.of("ok", true, "poolIds", created));
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private static void handlePoolRemove(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        try {
            ChartPool.removePool(poolId);
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
        Server.getLogger().info("HTTP remove pool: {}", poolId);
        ctx.json(Map.of("ok", true));
    }

    private static void handlePoolChartAdd(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        ChartAddBody body = bodyOrNull(ctx, ChartAddBody.class);
        if (body == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        try {
            ChartPool.addChart(poolId, body.chartId());
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
        Server.getLogger().info("HTTP add chart {} to pool {}", body.chartId(), poolId);
        ctx.json(Map.of("ok", true));
    }

    private static void handlePoolChartAddBatch(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        ChartAddBatchBody body = bodyOrNull(ctx, ChartAddBatchBody.class);
        if (body == null || body.chartIds() == null || body.chartIds().isEmpty()) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        try {
            int added = ChartPool.addCharts(poolId, body.chartIds());
            Server.getLogger().info("HTTP add {} charts to pool {} (requested {})", added, poolId, body.chartIds().size());
            ctx.json(Map.of("ok", true, "added", added));
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private static void handlePoolChartRemove(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        int chartId = pathInt(ctx, "chartId");
        try {
            ChartPool.removeChart(poolId, chartId);
        } catch (Exception e) {
            throw new ApiException(400, e.getMessage());
        }
        Server.getLogger().info("HTTP remove chart {} from pool {}", chartId, poolId);
        ctx.json(Map.of("ok", true));
    }

    private static void handlePoolFavorite(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        FavoriteBody body = requireFavoriteBody(ctx);
        ChartPool.setFavoriteId(poolId, body.favoriteId());
        Server.getLogger().info("HTTP pool {} favorite set to {}", poolId, body.favoriteId());
        ctx.json(Map.of("ok", true));
    }

    private static void handlePoolDefault(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        PoolDefaultBody body = bodyOrNull(ctx, PoolDefaultBody.class);
        if (body == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        ChartPool.setDefaultFlag(poolId, body.enabled());
        Server.getLogger().info("HTTP pool {} default set to {}", poolId, body.enabled());
        ctx.json(Map.of("ok", true));
    }

    // ===== 谱面筛选与池生成 =====

    private static void handleChartSearch(Context ctx) {
        requireAdmin(ctx);
        if (ctx.queryParam("refresh") != null) {
            ChartIndex.refreshAsync(blankToNull(ctx.queryParam("division")));
        }
        ScreeningRule rule = ruleFromQuery(ctx);
        int limit = (int) Math.min(Math.max(1, queryDouble(ctx, "limit", 50)), MAX_SEARCH_LIMIT);
        Double probeBudget = queryNumber(ctx, "probeDuration");
        if (probeBudget != null && probeBudget > 0) {
            probeDurationsFor(rule, probeBudget.intValue());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("indexed", ChartIndex.indexedCount());
        result.put("remoteTotal", ChartIndex.remoteCount());
        result.put("refreshing", ChartIndex.isRefreshing());
        result.put("matched", ChartIndex.countMatches(rule));
        // TB gates on a duration that is only known after probing, so an empty result
        // usually means "not probed yet" rather than "nothing qualifies".
        result.put("pendingDuration", rule.minDurationSeconds() != null
                && ChartIndex.countMatches(withoutDuration(rule)) > 0);
        result.put("charts", ChartIndex.search(rule, limit).stream().map(ApiServer::chartInfo).toList());
        ctx.json(Map.of("ok", true, "result", result));
    }

    /** Same rule with the duration bound dropped, used to report what probing would unlock. */
    private static ScreeningRule withoutDuration(ScreeningRule rule) {
        return new ScreeningRule(rule.category(), rule.anyTags(), rule.minRating(),
                rule.minRatingCount(), rule.minDifficulty(), null);
    }

    private static void handlePoolGenerate(Context ctx) {
        requireAdmin(ctx);
        PoolGenerateBody body = bodyOrNull(ctx, PoolGenerateBody.class);
        if (body == null || body.category() == null) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        if (body.probeDuration() != null && body.probeDuration() > MAX_PROBE_BATCH) {
            throw new ApiException(400, "一次最多探测 " + MAX_PROBE_BATCH + " 张谱面");
        }
        ScreeningRule rule = ScreeningRule.of(body.category());
        if (rule == null) {
            throw new ApiException(400, "该类别不支持规则生成");
        }
        if (body.probeDuration() != null && body.probeDuration() > 0) {
            probeDurationsFor(rule, body.probeDuration());
        }
        try {
            List<Integer> created = ChartPool.generatePools(rule,
                    body.sizeLimit() == null ? DEFAULT_POOL_SIZE : body.sizeLimit(), body.roundsPerStay());
            ctx.json(Map.of("ok", true, "poolIds", created));
        } catch (IOException e) {
            throw new ApiException(400, e.getMessage());
        }
    }

    private static void handleChartDurationProbe(Context ctx) {
        requireAdmin(ctx);
        ChartDurationBody body = bodyOrNull(ctx, ChartDurationBody.class);
        if (body == null || body.chartIds() == null || body.chartIds().isEmpty()) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        // Each probe costs a couple of network round trips, so cap the batch instead of
        // letting one request hold the HTTP thread for minutes.
        if (body.chartIds().size() > MAX_PROBE_BATCH) {
            throw new ApiException(400, "一次最多探测 " + MAX_PROBE_BATCH + " 张谱面");
        }
        List<ChartInfo> charts = new ArrayList<>();
        for (int chartId : body.chartIds()) {
            ChartInfo chart = ChartIndex.get(chartId);
            if (chart != null) {
                charts.add(chart);
            }
        }
        ctx.json(Map.of("ok", true, "probed", ChartIndex.probeDurations(charts, charts.size()),
                "charts", charts.stream().map(ApiServer::chartInfo).toList()));
    }

    /**
     * Durations are unknown until probed, so the duration bound is dropped while
     * candidates are collected and only then applied.
     */
    private static void probeDurationsFor(ScreeningRule rule, int budget) {
        if (rule.minDurationSeconds() == null) {
            return;
        }
        ScreeningRule relaxed = withoutDuration(rule);
        int capped = Math.min(budget, ChartIndex.MAX_PROBE_BUDGET);
        // Candidates outnumber probes: long charts are a minority of the high rated set.
        int probed = ChartIndex.probeDurations(ChartIndex.search(relaxed, capped * 4), capped);
        Server.getLogger().info("Probed {} durations for {} (budget {})", probed, rule.category(), capped);
    }

    private static ScreeningRule ruleFromQuery(Context ctx) {
        PoolCategory category = queryCategory(ctx);
        ScreeningRule preset = category == null ? null : ScreeningRule.of(category);
        String tag = ctx.queryParam("tag");
        Double minDifficulty = queryNumber(ctx, "minDifficulty");
        Double minDuration = queryNumber(ctx, "minDuration");

        // Kept as explicit branches: mixing a primitive with a nullable field would unbox null.
        Double difficultyBound = minDifficulty != null ? minDifficulty
                : preset == null ? null : preset.minDifficulty();
        Integer durationBound = null;
        if (minDuration != null) {
            durationBound = minDuration.intValue();
        } else if (preset != null) {
            durationBound = preset.minDurationSeconds();
        }

        return new ScreeningRule(
                category == null ? PoolCategory.MANUAL : category,
                tag == null || tag.isBlank()
                        ? (preset == null ? Set.of() : preset.anyTags())
                        : Set.of(tag.split(",")),
                (float) queryDouble(ctx, "minRating", preset == null ? 0 : preset.minRating()),
                (int) queryDouble(ctx, "minRatingCount", preset == null ? 0 : preset.minRatingCount()),
                difficultyBound,
                durationBound);
    }

    // ===== 谱面投稿 =====

    /** Pools players may submit into. Any signed-in user may call this. */
    private static void handleOpenPools(Context ctx) {
        requireUser(ctx);
        ctx.json(Map.of("ok", true, "pools", ChartPool.listOpenForSubmission().stream()
                .map(ApiServer::poolSnapshot).toList()));
    }

    /** Backs one or more charts in a single pool. Login is enough; the pool must be open. */
    private static void handleSubmitCharts(Context ctx) {
        int userId = requireUser(ctx);
        int poolId = pathInt(ctx, "id");
        SubmitBody body = bodyOrNull(ctx, SubmitBody.class);
        if (body == null || body.chartIds() == null || body.chartIds().isEmpty()) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        if (body.chartIds().size() > MAX_SUBMIT_BATCH) {
            throw new ApiException(400, "一次最多投稿 " + MAX_SUBMIT_BATCH + " 张谱面");
        }

        ChartPool.PoolSnapshot pool = ChartPool.findPool(poolId);
        if (pool == null) {
            throw new ApiException(404, "谱池不存在");
        }
        if (!pool.submissionOpen()) {
            throw new ApiException(400, "该谱池未开启投稿");
        }
        // Reject unknown charts here rather than letting approval fail later.
        List<Integer> unknown = body.chartIds().stream().filter(id -> ChartIndex.get(id) == null).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(400, "这些谱面不在索引里，无法投稿：" + unknown);
        }

        List<Map<String, Object>> results = SubmissionService
                .submit(poolId, body.chartIds(), userId, resolvePlayerName(userId)).stream()
                .map(result -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chartId", result.chartId());
                    item.put("accepted", result.accepted());
                    item.put("reason", result.reason());
                    return item;
                })
                .toList();
        long accepted = results.stream().filter(item -> Boolean.TRUE.equals(item.get("accepted"))).count();
        Server.getLogger().info("HTTP submission: user {} backed {} chart(s) in pool {} (accepted {})",
                userId, body.chartIds().size(), poolId, accepted);
        ctx.json(Map.of("ok", true, "accepted", accepted, "results", results));
    }

    /** Reviewers see every submission of a pool; supports an optional status filter. */
    private static void handlePoolSubmissions(Context ctx) {
        requireAdmin(ctx);
        int poolId = pathInt(ctx, "id");
        SubmissionService.Status filter = parseSubmissionStatus(ctx.queryParam("status"));
        ctx.json(Map.of("ok", true, "submissions",
                SubmissionService.listByPool(poolId, filter).stream().map(ApiServer::submissionView).toList()));
    }

    /** Charts this player backed, across every pool. */
    private static void handleMySubmissions(Context ctx) {
        int userId = requireUser(ctx);
        ctx.json(Map.of("ok", true, "submissions",
                SubmissionService.listBySubmitter(userId).stream().map(ApiServer::submissionView).toList()));
    }

    /** Cross-pool review queue. */
    private static void handlePendingSubmissions(Context ctx) {
        requireAdmin(ctx);
        ctx.json(Map.of("ok", true, "submissions",
                SubmissionService.listPending().stream().map(ApiServer::submissionView).toList()));
    }

    /** Approving also lands the chart in the pool, which is idempotent. */
    private static void handleSubmissionApprove(Context ctx) {
        int reviewerId = requireAdmin(ctx);
        int poolId = pathInt(ctx, "poolId");
        int chartId = pathInt(ctx, "chartId");
        if (!SubmissionService.review(poolId, chartId, true, reviewerId, null)) {
            throw new ApiException(404, "投稿不存在");
        }
        try {
            ChartPool.addChart(poolId, chartId);
        } catch (Exception e) {
            throw new ApiException(400, "已标记通过，但加入谱池失败：" + e.getMessage());
        }
        Server.getLogger().info("HTTP submission approved: pool {} chart {} by {}", poolId, chartId, reviewerId);
        ctx.json(Map.of("ok", true));
    }

    private static void handleSubmissionReject(Context ctx) {
        int reviewerId = requireAdmin(ctx);
        int poolId = pathInt(ctx, "poolId");
        int chartId = pathInt(ctx, "chartId");
        RejectBody body = bodyOrNull(ctx, RejectBody.class);
        if (!SubmissionService.review(poolId, chartId, false, reviewerId, body == null ? null : body.reason())) {
            throw new ApiException(404, "投稿不存在");
        }
        Server.getLogger().info("HTTP submission rejected: pool {} chart {} by {}", poolId, chartId, reviewerId);
        ctx.json(Map.of("ok", true));
    }

    /** A player withdraws their own backing; approved entries cannot be pulled back. */
    private static void handleSubmissionWithdraw(Context ctx) {
        int userId = requireUser(ctx);
        int poolId = pathInt(ctx, "id");
        int chartId = pathInt(ctx, "chartId");
        if (!SubmissionService.withdraw(poolId, chartId, userId)) {
            throw new ApiException(400, "无法撤回：没有投过这张谱面，或它已经通过审核");
        }
        ctx.json(Map.of("ok", true));
    }

    private static SubmissionService.Status parseSubmissionStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SubmissionService.Status.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "未知状态：" + raw);
        }
    }

    /** Falls back to a placeholder when Phira cannot be reached, so submission never blocks on it. */
    private static String resolvePlayerName(int userId) {
        try {
            UserInfo info = PhiraFetcher.GET_USER_INFO_BY_ID.apply(userId);
            return info != null && info.getName() != null ? info.getName() : ("用户 " + userId);
        } catch (IOException e) {
            return "用户 " + userId;
        }
    }

    private static Map<String, Object> submissionView(SubmissionService.View view) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("poolId", view.poolId());
        info.put("chartId", view.chartId());
        info.put("status", view.status().name());
        ChartInfo chart = ChartIndex.get(view.chartId());
        info.put("chart", chart == null ? null : chartInfo(chart));
        info.put("submitterCount", view.submitters().size());
        info.put("submitters", view.submitters().stream().map(submitter -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("userId", submitter.userId());
            item.put("name", submitter.name() == null ? "" : submitter.name());
            item.put("at", submitter.at() == null ? null : submitter.at().toString());
            return item;
        }).toList());
        info.put("createdAt", view.createdAt() == null ? null : view.createdAt().toString());
        info.put("reviewedAt", view.reviewedAt() == null ? null : view.reviewedAt().toString());
        info.put("reviewerId", view.reviewerId());
        info.put("reason", view.reason());
        return info;
    }

    // ===== 管理员名单 =====

    private static void handleAdminList(Context ctx) {
        requireAdmin(ctx);
        ctx.json(Map.of("ok", true, "admins", AdminService.getAdmins()));
    }

    private static void handleAdminAdd(Context ctx) {
        requireAdmin(ctx);
        AdminBody body = bodyOrNull(ctx, AdminBody.class);
        if (body == null || body.userId() <= 0) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        boolean added = AdminService.addAdmin(body.userId());
        Server.getLogger().info("HTTP admin add: {} ({} now)", body.userId(), AdminService.getAdmins().size());
        ctx.json(Map.of("ok", true, "added", added, "admins", AdminService.getAdmins()));
    }

    private static void handleAdminRemove(Context ctx) {
        requireAdmin(ctx);
        int userId = pathInt(ctx, "userId");
        if (AdminService.getAdmins().size() <= 1) {
            throw new ApiException(400, "至少保留一名管理员");
        }
        boolean removed = AdminService.removeAdmin(userId);
        Server.getLogger().info("HTTP admin remove: {} ({} left)", userId, AdminService.getAdmins().size());
        ctx.json(Map.of("ok", true, "removed", removed, "admins", AdminService.getAdmins()));
    }

    private static PoolCategory queryCategory(Context ctx) {
        String raw = ctx.queryParam("category");
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PoolCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "未知类别：" + raw);
        }
    }

    private static double queryDouble(Context ctx, String name, double fallback) {
        Double parsed = queryNumber(ctx, name);
        return parsed == null ? fallback : parsed;
    }

    private static Double queryNumber(Context ctx, String name) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "参数格式错误：" + name);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    // ===== 辅助 =====

    private static LocalRoom requireRoom(String roomId) {
        Room room = RoomManager.findRoom(roomId);
        if (room == null) {
            throw new ApiException(404, "欲请求的内容不存在");
        }
        return (LocalRoom) room;
    }

    private static FavoriteBody requireFavoriteBody(Context ctx) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("favoriteId")) {
                throw new ApiException(400, "请检查是否传入了错误的格式");
            }
            Integer favoriteId = json.get("favoriteId").isJsonNull() ? null : json.get("favoriteId").getAsInt();
            return new FavoriteBody(favoriteId);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
    }

    private static int pathInt(Context ctx, String param) {
        try {
            return Integer.parseInt(ctx.pathParam(param));
        } catch (NumberFormatException e) {
            throw new ApiException(400, "参数格式错误");
        }
    }

    private static <T> T bodyOrNull(Context ctx, Class<T> clazz) {
        String body = ctx.body();
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return ctx.bodyAsClass(clazz);
        } catch (Exception e) {
            throw new ApiException(400, "请检查是否传入了错误的格式");
        }
    }

    private static Map<String, Object> roomSnapshot(LocalRoom room) {
        RoomSnapshot view = room.getView();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("roomId", view.getRoomId());
        info.put("state", stateName(view.getState()));
        info.put("live", view.isLive());
        info.put("locked", view.isLocked());
        info.put("cycle", view.isCycle());
        info.put("host", view.getHost());
        info.put("chart", chartInfo(view.getState().getChart()));
        info.put("type", "local");
        info.put("config", roomConfig(room.getSetting()));
        info.put("pool", poolStatus(room));
        info.put("players", view.getPlayers().stream()
                .map(p -> Map.of("id", p.getId(), "name", p.getName()))
                .toList());
        info.put("monitors", view.getMonitors().stream()
                .map(p -> Map.of("id", p.getId(), "name", p.getName()))
                .toList());
        return info;
    }

    private static Map<String, Object> chartInfo(ChartInfo chart) {
        if (chart == null) {
            return null;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", chart.getId());
        info.put("name", chart.getName());
        info.put("level", chart.getLevel());
        info.put("difficulty", chart.getDifficulty());
        info.put("charter", chart.getCharter());
        info.put("composer", chart.getComposer());
        info.put("illustrator", chart.getIllustrator());
        info.put("description", chart.getDescription());
        info.put("ranked", chart.isRanked());
        info.put("reviewed", chart.isReviewed());
        info.put("stable", chart.isStable());
        info.put("stableRequest", chart.isStableRequest());
        info.put("illustration", chart.getIllustration());
        info.put("preview", chart.getPreview());
        info.put("file", chart.getFile());
        info.put("uploader", chart.getUploader());
        info.put("tags", chart.getTags() == null ? List.of() : List.of(chart.getTags()));
        info.put("rating", chart.getRating());
        info.put("ratingCount", chart.getRatingCount());
        info.put("created", chart.getCreated());
        info.put("updated", chart.getUpdated());
        info.put("chartUpdated", chart.getChartUpdated());
        info.put("durationSeconds", chart.getDurationSeconds());
        return info;
    }

    private static String stateName(RoomGameState state) {
        if (state instanceof RoomPlaying) {
            return "Playing";
        }
        if (state instanceof RoomWaitForReady) {
            return "WaitForReady";
        }
        return "SelectChart";
    }

    private static Map<String, Object> roomConfig(LocalRoom.RoomSetting setting) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("minPlayer", setting.getMinPlayer());
        config.put("maxPlayer", setting.getMaxPlayer());
        config.put("selectCountdown", setting.getSelectChartCountdownSeconds());
        config.put("readyCountdown", setting.getReadyCountdownSeconds());
        config.put("forceFinish", setting.getForceFinishSeconds());
        config.put("interval", setting.getRefreshIntervalRounds());
        config.put("adminIds", setting.getAdminIds() == null ? List.of() : setting.getAdminIds());
        return config;
    }

    private static Map<String, Object> poolStatus(LocalRoom room) {
        RoomChartPool pool = room.getChartPool();
        ChartPool.PoolSnapshot current = pool.getCurrentPoolSnapshot();
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("currentPool", poolSnapshot(current));
        status.put("pools", pool.getPools().stream().map(ApiServer::poolSnapshot).toList());
        status.put("pendingPoolId", pool.getPendingPoolId());
        status.put("favoriteId", current.favoriteId());
        status.put("finishedRoundsSinceRefresh", pool.getFinishedRoundsSinceRefresh());
        status.put("refreshIntervalRounds", room.getSetting().getRefreshIntervalRounds());
        status.put("effectiveRoundsPerStay", pool.resolveRoundsPerStay(room.getSetting().getRefreshIntervalRounds()));
        return status;
    }

    private static Map<String, Object> poolSnapshot(ChartPool.PoolSnapshot pool) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", pool.id());
        snapshot.put("favoriteId", pool.favoriteId());
        snapshot.put("default", pool.defaultFlag());
        snapshot.put("chartIds", pool.chartIds());
        snapshot.put("category", pool.category() == null ? null : pool.category().name());
        snapshot.put("sizeLimit", pool.sizeLimit());
        snapshot.put("roundsPerStay", pool.roundsPerStay());
        snapshot.put("order", pool.order());
        snapshot.put("submissionOpen", pool.submissionOpen());
        return snapshot;
    }

    private static final class ApiException extends RuntimeException {
        private final int status;
        private final String reason;

        private ApiException(int status, String reason) {
            super(reason);
            this.status = status;
            this.reason = reason;
        }
    }

    // ===== DTO =====
    // 注意：Gson 使用 LOWER_CASE_WITH_UNDERSCORES 命名策略，record 组件名会被转换为下划线键，
    // 因此驼峰字段必须用 @SerializedName 显式声明文档约定的 JSON 键名。

    public record LoginBody(String email, String password) {
    }

    public record CreateRoomBody(String type, List<Integer> pools) {
    }

    public record UpdateRoomBody(
            Boolean live, Boolean lock,
            @SerializedName("chatEnable") Boolean chatEnable,
            @SerializedName("minPlayer") Integer minPlayer,
            @SerializedName("maxPlayer") Integer maxPlayer,
            @SerializedName("selectCountdown") Integer selectCountdown,
            @SerializedName("readyCountdown") Integer readyCountdown,
            @SerializedName("forceFinish") Integer forceFinish,
            Integer interval,
            @SerializedName("adminIds") List<Integer> adminIds
    ) {
    }

    public record SwitchPoolBody(@SerializedName("poolId") int poolId) {
    }

    public record FavoriteBody(@SerializedName("favoriteId") Integer favoriteId) {
    }

    public record PoolCreateBody(int id, @SerializedName("chartIds") List<Integer> chartIds,
                                 PoolCategory category, @SerializedName("sizeLimit") Integer sizeLimit,
                                 @SerializedName("roundsPerStay") Integer roundsPerStay) {
    }

    public record ChartAddBody(@SerializedName("chartId") int chartId) {
    }

    public record ChartAddBatchBody(@SerializedName("chartIds") List<Integer> chartIds) {
    }

    public record PoolUpdateBody(PoolCategory category, @SerializedName("sizeLimit") Integer sizeLimit,
                                 @SerializedName("roundsPerStay") Integer roundsPerStay, Integer order,
                                 @SerializedName("submissionOpen") Boolean submissionOpen) {
    }

    public record PoolDefaultBody(boolean enabled) {
    }

    public record PoolGenerateBody(PoolCategory category, @SerializedName("sizeLimit") Integer sizeLimit,
                                   @SerializedName("roundsPerStay") Integer roundsPerStay,
                                   @SerializedName("probeDuration") Integer probeDuration) {
    }

    public record ChartDurationBody(@SerializedName("chartIds") List<Integer> chartIds) {
    }

    public record AdminBody(@SerializedName("userId") int userId) {
    }

    public record PoolBatchBody(int count, PoolCategory category,
                                @SerializedName("sizeLimit") Integer sizeLimit,
                                @SerializedName("roundsPerStay") Integer roundsPerStay) {
    }

    public record SubmitBody(@SerializedName("chartIds") List<Integer> chartIds) {
    }

    public record RejectBody(String reason) {
    }

    public record SayBody(String message) {
    }

    public record RestoreBody(String backup) {
    }
}
