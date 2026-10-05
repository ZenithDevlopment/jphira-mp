package top.rymc.phira.main.game.room.state;

import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.game.exception.GameOperationException;
import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.main.game.point.PlayerPointService;
import top.rymc.phira.main.game.room.chart.ChartPool;
import top.rymc.phira.main.game.room.local.LocalRoom;
import top.rymc.phira.protocol.data.monitor.judge.JudgeEvent;
import top.rymc.phira.protocol.data.monitor.touch.TouchFrame;
import top.rymc.phira.protocol.data.state.GameState;
import top.rymc.phira.protocol.data.state.SelectChart;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class RoomSelectChart extends RoomGameState {

    private static final List<Integer> NOTICE_SECONDS = List.of(150, 120, 90, 60, 30, 10, 5, 3, 2, 1);
    private static final Random RANDOM = new Random();

    private final Map<Player, Integer> voteByPlayer = new ConcurrentHashMap<>();
    private final Set<ScheduledFuture<?>> countdownTasks = ConcurrentHashMap.newKeySet();
    private final ChartPool.PoolSnapshot currentPoolInfo;
    private final List<ChartInfo> currentPool;
    private final int countdownSeconds;
    private final AtomicBoolean countdownRunning = new AtomicBoolean();
    /** Ready pressed while still voting; honoured when the ready phase starts. */
    private final Set<Integer> readyIntents = ConcurrentHashMap.newKeySet();
    private volatile ChartInfo lockedChart;

    public RoomSelectChart(LocalRoom room, Consumer<RoomGameState> stateUpdater) {
        this(room, stateUpdater, null);
    }

    public RoomSelectChart(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart) {
        super(room, stateUpdater, chart);
        this.currentPoolInfo = room.getChartPool().getCurrentPoolSnapshot();
        this.currentPool = currentPoolInfo.chartIds().stream()
                .map(ChartPool::getChartInfo)
                .toList();
        this.countdownSeconds = room.getSetting().getSelectChartCountdownSeconds();
    }

    @Override
    public void handleJoin(Player player) {
        // Charts are not part of the protocol state, so a newcomer has no way to vote
        // until the pool is pushed to them.
        sendVoteBoard(player);
        updateCountdownState();
    }

    @Override
    public void handleLeave(Player player) {
        voteByPlayer.remove(player);
        broadcastVoteBoardHint();
        updateCountdownState();
    }

    /**
     * Host shortcut: skip the rest of the countdown and start now.
     *
     * <p>Unlike the automatic path this ignores {@code minPlayer}, so an operator can get a
     * round going (or unstick one) without waiting for more players to arrive.
     */
    @Override
    public void requireStart(Player player) {
        if (countOnlinePlayers() == 0) {
            throw GameOperationException.permissionDenied();
        }

        stopCountdown();
        ChartInfo selectedChart = lockedChart != null ? lockedChart : selectWinningChart();
        broadcastSystemMessage(player.getName() + " 提前开始本轮，曲目：" + formatChartName(selectedChart));

        RoomWaitForReady state = new RoomWaitForReady(room, stateUpdater, selectedChart, readyIntents);
        updateGameState(state);
        state.startCountdown();
    }

    /**
     * Ready pressed during voting.
     *
     * <p>Clients flip their button to "cancel" as soon as the player presses it, so refusing
     * here outright would leave the UI claiming a readiness the server never recorded. The
     * intent is kept and honoured once the ready phase actually starts.
     */
    @Override
    public void ready(Player player) {
        readyIntents.add(player.getId());
        broadcast(op -> op.memberReady(player.getId()));
        updateCountdownState();
    }

    @Override
    public void cancelReady(Player player) {
        throw GameOperationException.invalidState();
    }

    @Override
    public void touchSend(Player player, List<TouchFrame> touchFrames) {

    }

    @Override
    public void judgeSend(Player player, List<JudgeEvent> judgeEvents) {

    }

    /**
     * The round already moved on, so a late abort is dropped instead of reported.
     *
     * <p>These arrive routinely right after a round is settled: the client sends them based on
     * its own state, which lags behind the server by one packet.
     */
    @Override
    public void abort(Player player) {
    }

    /** Same as {@link #abort}: a score for a round that has already been settled. */
    @Override
    public void played(Player player, int recordId) {
    }

    public void vote(Player player, int chartId) {
        if (room.containsMonitor(player)) {
            throw GameOperationException.permissionDenied();
        }

        if (lockedChart != null) {
            sendSystemMessage(player, "本轮曲目已锁定，无法继续改票。");
            throw GameOperationException.invalidState();
        }

        if (!ChartPool.contains(chartId, currentPool)) {
            throw GameOperationException.chartNotFound();
        }

        ChartInfo chart = ChartPool.getChartInfo(chartId);
        voteByPlayer.put(player, chartId);
        broadcastSystemMessage(player.getName() + " 已投票：" + formatChartName(chart));
        broadcastLeadingChart();
        broadcastVoteBoardHint();
    }

    /**
     * Publishes the leading chart so clients see a selection.
     *
     * <p>Without this the client never receives a selected chart, and pressing start is rejected
     * locally before the packet ever reaches the server.
     */
    private void broadcastLeadingChart() {
        Map<Integer, Long> votes = voteByPlayer.values().stream()
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        ChartInfo leading = currentPool.stream()
                .filter(chart -> votes.containsKey(chart.getId()))
                .max(Comparator.comparingLong(chart -> votes.get(chart.getId())))
                .orElse(null);
        if (leading == null) {
            return;
        }

        int firstVoter = voteByPlayer.entrySet().stream()
                .filter(entry -> entry.getValue().equals(leading.getId()))
                .map(entry -> entry.getKey().getId())
                .min(Integer::compareTo)
                .orElse(SYSTEM_PLAYER_ID);

        broadcast(op -> op.selectChart(leading.getId(), leading.getName(), firstVoter));
    }

    public void broadcastVoteBoard() {
        broadcast(op -> {
            op.receiveChat(SYSTEM_PLAYER_ID, MESSAGE_SEPARATOR);
            op.receiveChat(SYSTEM_PLAYER_ID, room.getRoomId() + " 本轮谱池 #" + currentPoolInfo.id());
            if (currentPoolInfo.favoriteId() != null) {
                op.receiveChat(SYSTEM_PLAYER_ID, "谱面收藏夹 ID：" + currentPoolInfo.favoriteId());
                op.receiveChat(SYSTEM_PLAYER_ID, "你可以通过导入收藏夹来一键导入谱池");
            }
            op.receiveChat(SYSTEM_PLAYER_ID, "使用选谱操作投票，只能选择下列歌曲。票数最高者开局。");
            for (String line : buildVoteBoardLines()) {
                op.receiveChat(SYSTEM_PLAYER_ID, line);
            }
            op.receiveChat(SYSTEM_PLAYER_ID, MESSAGE_SEPARATOR);
        });
    }

    public void sendVoteBoard(Player player) {
        PlayerPointService.PointSummary point = PlayerPointService.getSummary(player);
        sendSystemMessage(player, MESSAGE_SEPARATOR);
        sendSystemMessage(player, "当前积分：" + point.points() + "，积分排名：#" + point.rank());
        sendSystemMessage(player, room.getRoomId() + " 本轮谱池 #" + currentPoolInfo.id());
        if (currentPoolInfo.favoriteId() != null) {
            sendSystemMessage(player, "谱面收藏夹 ID：" + currentPoolInfo.favoriteId());
            sendSystemMessage(player, "你可以通过导入收藏夹来一键导入谱池");
        }
        sendSystemMessage(player, "使用选谱操作投票，只能选择下列歌曲。票数最高者开局。");
        for (String line : buildVoteBoardLines()) {
            sendSystemMessage(player, line);
        }
        sendSystemMessage(player, MESSAGE_SEPARATOR);
    }

    public void broadcastVoteBoardHint() {
        broadcast(op -> op.receiveChat(SYSTEM_PLAYER_ID, "点击锁定房间按钮查看当前谱池状态。"));
    }

    private List<String> buildVoteBoardLines() {
        Map<Integer, Long> votes = voteByPlayer.values().stream()
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        return currentPool.stream()
                .map(chart -> String.format("[%d票] %s  |  Lv.%s  |  ID:%d",
                        votes.getOrDefault(chart.getId(), 0L), chart.getName(), chart.getLevel(), chart.getId()))
                .toList();
    }

    private String formatChartName(ChartInfo chart) {
        return chart.getName() + " | Lv." + chart.getLevel() + " | ID:" + chart.getId();
    }

    public void activate() {
        updateCountdownState();
    }

    private void updateCountdownState() {
        if (countOnlinePlayers() >= room.getSetting().getMinPlayer()) {
            startCountdown();
        } else {
            cancelCountdown();
        }
    }

    private void startCountdown() {
        // Compare-and-set: two players joining at once must not schedule a second full countdown.
        if (!countdownRunning.compareAndSet(false, true)) {
            return;
        }

        broadcastSystemMessage("已达到开局人数，" + countdownSeconds + " 秒后锁定投票并进入准备阶段。人数不足会取消倒计时。");

        NOTICE_SECONDS.stream()
                .filter(seconds -> seconds <= countdownSeconds)
                .forEach(seconds -> countdownTasks.add(TIMER.schedule(
                        () -> noticeCountdown(seconds),
                        countdownSeconds - seconds,
                        TimeUnit.SECONDS
                )));
        countdownTasks.add(TIMER.schedule(this::finishCountdown, countdownSeconds, TimeUnit.SECONDS));
    }

    private void stopCountdown() {
        countdownRunning.set(false);
        countdownTasks.forEach(task -> task.cancel(false));
        countdownTasks.clear();
    }

    private void cancelCountdown() {
        if (!countdownRunning.get()) {
            return;
        }

        stopCountdown();
        broadcastSystemMessage("在线玩家不足，开局倒计时已取消。");
    }

    @Override
    public void dispose() {
        countdownTasks.forEach(task -> task.cancel(false));
        countdownTasks.clear();
    }

    private void noticeCountdown(int seconds) {
        if (countdownRunning.get() && countOnlinePlayers() >= room.getSetting().getMinPlayer()) {
            broadcastSystemMessage("投票锁定倒计时：" + seconds + " 秒");
            if (seconds == 1) {
                lockedChart = selectWinningChart();
                broadcastSystemMessage("本轮曲目已锁定，无法继续改票。");
                broadcastSystemMessage(MESSAGE_SEPARATOR);
                broadcastSelectedChartState(lockedChart);
            }
        }
    }

    private void broadcastSelectedChartState(ChartInfo selectedChart) {
        broadcast(operations -> operations.updateHostStatus(false));
        broadcast(operations -> operations.enterState(new SelectChart(selectedChart.getId())));
    }

    private void finishCountdown() {
        if (!countdownRunning.get()) {
            return;
        }

        countdownRunning.set(false);
        countdownTasks.clear();
        if (countOnlinePlayers() < room.getSetting().getMinPlayer()) {
            broadcastSystemMessage("在线玩家不足，本轮取消。");
            return;
        }

        ChartInfo selectedChart = lockedChart != null ? lockedChart : selectWinningChart();
        broadcastSystemMessage("投票结束，本轮曲目：" + formatChartName(selectedChart));

        RoomWaitForReady state = new RoomWaitForReady(room, stateUpdater, selectedChart, readyIntents);
        updateGameState(state);
        state.startCountdown();
    }

    private ChartInfo selectWinningChart() {
        Map<Integer, Long> votes = voteByPlayer.values().stream()
                .collect(Collectors.groupingBy(id -> id, Collectors.counting()));

        long highestVotes = currentPool.stream()
                .map(chart -> votes.getOrDefault(chart.getId(), 0L))
                .max(Comparator.naturalOrder())
                .orElse(0L);

        List<ChartInfo> candidates = currentPool.stream()
                .filter(chart -> votes.getOrDefault(chart.getId(), 0L) == highestVotes)
                .collect(Collectors.toCollection(ArrayList::new));

        if (candidates.size() > 1) {
            broadcastSystemMessage("最高票出现并列，将随机抽选本轮曲目。");
        }

        return candidates.get(RANDOM.nextInt(candidates.size()));
    }

    @Override
    public GameState toProtocol() {
        Integer id = chart == null ? null : chart.getId();
        return new SelectChart(id);
    }
}
