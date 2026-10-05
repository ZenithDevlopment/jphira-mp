package top.rymc.phira.main.game.room.state;

import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.data.GameRecord;
import top.rymc.phira.main.data.RoundRecord;
import top.rymc.phira.main.game.exception.GameOperationException;
import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.main.game.player.operations.PlayerOperations;
import top.rymc.phira.main.game.point.PlayerPointService;
import top.rymc.phira.main.game.record.PhiraRecord;
import top.rymc.phira.main.game.record.RoundRecordService;
import top.rymc.phira.main.game.room.local.LocalRoom;
import top.rymc.phira.main.util.PhiraFetcher;
import top.rymc.phira.protocol.data.monitor.judge.JudgeEvent;
import top.rymc.phira.protocol.data.monitor.touch.TouchFrame;
import top.rymc.phira.protocol.data.state.GameState;
import top.rymc.phira.protocol.data.state.Playing;
import top.rymc.phira.protocol.data.state.SelectChart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class RoomPlaying extends RoomGameState {

    private static final int FORCE_FINISH_NOTICE_SECONDS = 10;

    /**
     * Everything here is keyed by player id, never by {@link Player}.
     *
     * <p>A reconnect swaps the player instance, so object keys would silently split one player
     * into two: duplicated ranking rows, a lost readiness, and truncated replay data.
     */
    private final Set<Integer> activePlayerIds = ConcurrentHashMap.newKeySet();
    private final Set<Integer> donePlayerIds = ConcurrentHashMap.newKeySet();

    private final Map<Integer, GameRecord> gameRecords = new ConcurrentHashMap<>();
    private final Map<Integer, String> playerNames = new ConcurrentHashMap<>();
    private final Map<Integer, PhiraRecord> playerRecords = new ConcurrentHashMap<>();

    private final Map<Integer, List<TouchFrame>> touchFrames = new ConcurrentHashMap<>();
    private final Map<Integer, List<JudgeEvent>> judgeEvents = new ConcurrentHashMap<>();
    private final Set<ScheduledFuture<?>> forceFinishTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean roundFinished = new AtomicBoolean(false);
    private final AtomicBoolean forceFinishCountdownStarted = new AtomicBoolean(false);
    /** When the round went live; stamped into the round record. */
    private final long startedAt = System.currentTimeMillis();

    public RoomPlaying(LocalRoom room, Consumer<RoomGameState> stateUpdater) {
        super(room, stateUpdater);
    }

    public RoomPlaying(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart) {
        super(room, stateUpdater, chart);
    }

    public RoomPlaying(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart,
                       Set<Integer> activePlayerIds) {
        super(room, stateUpdater, chart);
        this.activePlayerIds.addAll(activePlayerIds);
        // Registered here rather than on the first submitted record: if every player stalls on
        // the loading screen without submitting or disconnecting, the round would never end.
        startForceFinishCountdown();
    }

    @Override
    public void handleJoin(Player player) {
        player.operations().ifPresent(op -> op.receiveChat(SYSTEM_PLAYER_ID, "房间当前正在游戏中，请静待游戏结束"));
    }

    @Override
    public void handleLeave(Player player) {
        if (activePlayerIds.contains(player.getId())) {
            finishPlayer(player, false);
        }
    }

    @Override
    public void requireStart(Player player) {
        throw GameOperationException.permissionDenied();
    }

    @Override
    public void ready(Player player) {
        throw GameOperationException.invalidState();
    }

    @Override
    public void cancelReady(Player player) {
        throw GameOperationException.invalidState();
    }

    @Override
    public void touchSend(Player player, List<TouchFrame> touchFrames) {
        this.touchFrames.computeIfAbsent(player.getId(), p -> new CopyOnWriteArrayList<>()).addAll(touchFrames);
    }

    @Override
    public void judgeSend(Player player, List<JudgeEvent> judgeEvents) {
        this.judgeEvents.computeIfAbsent(player.getId(), p -> new CopyOnWriteArrayList<>()).addAll(judgeEvents);
    }

    @Override
    public void abort(Player player) {
        int id = player.getId();
        if (!activePlayerIds.contains(id) || donePlayerIds.contains(id)) {
            return;
        }

        try {
            broadcast(op -> op.gameAbort(id));
        } finally {
            finishPlayer(player, true);
        }
    }

    @Override
    public void played(Player player, int recordId) {
        int id = player.getId();
        if (!activePlayerIds.contains(id) || donePlayerIds.contains(id)) {
            return;
        }

        try {
            GameRecord record = PhiraFetcher.GET_RECORD_INFO.toIntFunction(e -> {
                throw GameOperationException.recordNotFound();
            }).apply(recordId);

            playerNames.putIfAbsent(id, player.getName());
            gameRecords.put(id, record);
            savePhiraRecord(player, record);

            String message = String.format(
                    """
                    [%s] %s 的分数: %s, 准度: %s%%, 误差: ±%sms, 无暇度分数: %s
                        Perfect: %s, Good: %s, Bad: %s, Miss: %s
                    """,
                    player.getId(),
                    player.getName(),
                    record.getScore(),
                    record.getAccuracy() * 100,
                    record.getStd() * 1000,
                    record.getStdScore(),
                    record.getPerfect(),
                    record.getGood(),
                    record.getBad(),
                    record.getMiss()
            );

            broadcastSystemMessage(message);
            startForceFinishCountdown();
        } catch (GameOperationException e) {
            player.operations().ifPresent(op -> op.receiveChat(SYSTEM_PLAYER_ID, "网络错误导致成绩提交失败，本轮将视为放弃。"));
            broadcastSystemMessage("因网络错误导致 " + player.getName() + " 成绩提交失败，已视为放弃本轮。");
            broadcast(op -> op.gameAbort(player.getId()));
            throw GameOperationException.recordSubmitFailed();
        } finally {
            finishPlayer(player, true);
        }
    }

    private void savePhiraRecord(Player player, GameRecord record) {
        int id = player.getId();
        List<TouchFrame> playerTouchFrames = touchFrames.getOrDefault(id, List.of());
        List<JudgeEvent> playerJudgeEvents = judgeEvents.getOrDefault(id, List.of());

        if (playerTouchFrames.isEmpty() && playerJudgeEvents.isEmpty()) {
            return;
        }

        PhiraRecord phiraRecord = new PhiraRecord(
                record.getId(),
                record.getTime().toInstant().toEpochMilli(),
                chart.getId(),
                chart.getName(),
                id,
                player.getName(),
                playerTouchFrames,
                playerJudgeEvents
        );

        playerRecords.put(id, phiraRecord);
    }

    private void startForceFinishCountdown() {
        // Compare-and-set: two players submitting at once must not schedule the task twice.
        if (!forceFinishCountdownStarted.compareAndSet(false, true)) {
            return;
        }

        int forceFinishSeconds = room.getSetting().getForceFinishSeconds();
        if (forceFinishSeconds > FORCE_FINISH_NOTICE_SECONDS) {
            forceFinishTasks.add(TIMER.schedule(
                    () -> broadcastSystemMessage("本轮游戏将在 " + FORCE_FINISH_NOTICE_SECONDS + " 秒后强制结束。"),
                    forceFinishSeconds - FORCE_FINISH_NOTICE_SECONDS,
                    TimeUnit.SECONDS
            ));
        }
        forceFinishTasks.add(TIMER.schedule(this::forceFinishGame, forceFinishSeconds, TimeUnit.SECONDS));
    }

    public void forceFinishByServer() {
        forceFinishGame();
    }

    @Override
    public void dispose() {
        forceFinishTasks.forEach(task -> task.cancel(false));
        forceFinishTasks.clear();
    }

    private void forceFinishGame() {
        finishRound();
    }

    private void cancelForceFinishCountdown() {
        forceFinishTasks.forEach(task -> task.cancel(false));
        forceFinishTasks.clear();
    }

    private void finishPlayer(Player player, boolean updateClientState) {
        donePlayerIds.add(player.getId());

        if (updateClientState && player.isOnline()) {
            player.operations().ifPresent(op -> {
                op.updateHostStatus(room.canControl(player));
                op.enterState(new SelectChart(chart.getId()));
            });
        }

        if (isAllOnlineActivePlayersDone()) {
            finishRound();
        }
    }

    private void finishRound() {
        if (!roundFinished.compareAndSet(false, true)) {
            return;
        }

        room.getChartPool().finishPlayingRound(room.getSetting().getRefreshIntervalRounds());
        cancelForceFinishCountdown();
        broadcastRanking();
        RoomSelectChart state = new RoomSelectChart(room, stateUpdater, chart);
        for (Player player : room.getPlayerManager().getPlayers()) {
            if (player.isOnline()) {
                player.operations().ifPresent(op -> op.updateHostStatus(room.canControl(player)));
            }
        }
        broadcast(PlayerOperations::gameEnd);
        updateGameState(state);
        state.broadcastVoteBoardHint();
        state.activate();
    }

    private void broadcastRanking() {
        if (gameRecords.isEmpty()) {
            return;
        }

        List<Map.Entry<Integer, GameRecord>> ranking = gameRecords.entrySet().stream()
                .sorted(Map.Entry.<Integer, GameRecord>comparingByValue(
                                Comparator.comparingInt(GameRecord::getScore).reversed()
                                        .thenComparing(Comparator.comparingDouble(GameRecord::getAccuracy).reversed())
                                        .thenComparingDouble(GameRecord::getStd))
                        .thenComparingInt(Map.Entry::getKey)
                )
                .toList();

        // Phase 1: decide the ranks and award the whole round in one batch, so the shared
        // ranking cache is invalidated once instead of once per player.
        List<RankedPlayer> ranked = new ArrayList<>(ranking.size());
        int rank = 0;
        GameRecord previous = null;
        for (int i = 0; i < ranking.size(); i++) {
            Map.Entry<Integer, GameRecord> entry = ranking.get(i);
            GameRecord record = entry.getValue();
            if (previous == null || compareRecord(record, previous) != 0) {
                rank = i + 1;
            }
            ranked.add(new RankedPlayer(entry.getKey(), nameOf(entry.getKey()), record, rank,
                    getPointsByRank(rank)));
            previous = record;
        }

        Map<Integer, Integer> gainedByPlayerId = new LinkedHashMap<>();
        Map<Integer, String> nameByPlayerId = new LinkedHashMap<>();
        ranked.forEach(entry -> {
            gainedByPlayerId.put(entry.playerId(), entry.gainedPoints());
            nameByPlayerId.put(entry.playerId(), entry.name());
        });
        Map<Integer, Integer> totals = PlayerPointService.addPointsBatch(gainedByPlayerId, nameByPlayerId);

        // Phase 2: publish, then store the round once everyone's total is final.
        long finishedAt = System.currentTimeMillis();
        List<RoundRecord.PlayerResult> results = new ArrayList<>(ranked.size());
        broadcastSystemMessage(MESSAGE_SEPARATOR);
        broadcastSystemMessage("本轮排名");
        for (RankedPlayer entry : ranked) {
            int totalPoints = totals.getOrDefault(entry.playerId(), 0);
            broadcastSystemMessage(String.format(
                    "%d. %s - 分数: %s, 准度: %s%%, 误差: ±%sms, 积分: +%s, 总积分: %s",
                    entry.rank(),
                    entry.name(),
                    entry.record().getScore(),
                    entry.record().getAccuracy() * 100,
                    entry.record().getStd() * 1000,
                    entry.gainedPoints(),
                    totalPoints
            ));
            results.add(new RoundRecord.PlayerResult(
                    entry.playerId(),
                    entry.name(),
                    entry.rank(),
                    entry.record().getScore(),
                    entry.record().getAccuracy(),
                    entry.record().getStd(),
                    entry.gainedPoints(),
                    totalPoints
            ));
        }
        broadcastSystemMessage(MESSAGE_SEPARATOR);

        RoundRecordService.append(new RoundRecord(
                RoundRecordService.nextId(room.getRoomId(), finishedAt),
                room.getRoomId(),
                chart.getId(),
                chart.getName(),
                startedAt,
                finishedAt,
                List.copyOf(results)
        ));
    }

    /** Falls back to the id when the player already left, so the ranking row is never blank. */
    private String nameOf(int playerId) {
        String name = playerNames.get(playerId);
        if (name != null) {
            return name;
        }
        return room.getPlayerManager().getPlayers().stream()
                .filter(player -> player.getId() == playerId)
                .map(Player::getName)
                .findFirst()
                .orElse("#" + playerId);
    }

    private record RankedPlayer(int playerId, String name, GameRecord record, int rank, int gainedPoints) {
    }

    private int compareRecord(GameRecord a, GameRecord b) {
        return Comparator.comparingInt(GameRecord::getScore).reversed()
                .thenComparing(Comparator.comparingDouble(GameRecord::getAccuracy).reversed())
                .thenComparingDouble(GameRecord::getStd)
                .compare(a, b);
    }

    private int getPointsByRank(int rank) {
        return switch (rank) {
            case 1 -> 100;
            case 2 -> 75;
            case 3 -> 50;
            case 4 -> 25;
            default -> 10;
        };
    }

    private boolean isAllOnlineActivePlayersDone() {
        // Looked up per id so a player who reconnected mid round still counts as active.
        Set<Integer> onlineActive = activePlayerIds.stream()
                .filter(id -> room.getPlayerManager().getPlayers().stream()
                        .anyMatch(player -> player.getId() == id && player.isOnline()))
                .collect(Collectors.toSet());

        return donePlayerIds.containsAll(onlineActive);
    }

    @Override
    public GameState toProtocol() {
        return new Playing();
    }
}
