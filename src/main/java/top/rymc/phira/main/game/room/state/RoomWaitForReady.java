package top.rymc.phira.main.game.room.state;

import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.game.exception.GameOperationException;
import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.main.game.player.operations.PlayerOperations;
import top.rymc.phira.main.game.room.local.LocalRoom;
import top.rymc.phira.protocol.data.monitor.judge.JudgeEvent;
import top.rymc.phira.protocol.data.monitor.touch.TouchFrame;
import top.rymc.phira.protocol.data.state.GameState;
import top.rymc.phira.protocol.data.state.SelectChart;
import top.rymc.phira.protocol.data.state.WaitForReady;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class RoomWaitForReady extends RoomGameState {

    private static final List<Integer> NOTICE_SECONDS = List.of(60, 30, 10, 5, 3, 2, 1);

    /**
     * Ready players, keyed by id rather than by object.
     *
     * <p>A reconnect replaces the connection and can replace the player instance too, which
     * would silently drop someone who had already pressed ready.
     */
    private final Set<Integer> readyPlayerIds = ConcurrentHashMap.newKeySet();
    private final Set<ScheduledFuture<?>> countdownTasks = ConcurrentHashMap.newKeySet();
    private volatile boolean countdownRunning;

    public RoomWaitForReady(LocalRoom room, Consumer<RoomGameState> stateUpdater) {
        super(room, stateUpdater);
    }

    public RoomWaitForReady(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart, Player initiator) {
        this(room, stateUpdater, chart);
    }

    public RoomWaitForReady(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart) {
        super(room, stateUpdater, chart);
    }

    /**
     * Carries over players who already pressed ready while voting.
     *
     * <p>Those clients show a "cancel" button, so the server has to agree with them; otherwise the
     * round ends with nobody ready even though everybody believes they were.
     */
    public RoomWaitForReady(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart,
                            Set<Integer> initiallyReady) {
        super(room, stateUpdater, chart);
        if (initiallyReady == null || initiallyReady.isEmpty()) {
            return;
        }
        readyPlayerIds.addAll(initiallyReady);
        room.getPlayerManager().getPlayers().stream()
                .filter(player -> initiallyReady.contains(player.getId()))
                .forEach(player -> player.operations().ifPresent(op -> op.memberReady(player.getId())));
    }

    @Override
    public void handleJoin(Player player) {
        player.operations().ifPresent(op -> {
            op.enterState(new SelectChart(chart.getId()));
            op.enterState(new WaitForReady());
        });
        updateState();
    }

    @Override
    public void handleLeave(Player player) {
        readyPlayerIds.remove(player.getId());
        updateState();
    }

    @Override
    public void requireStart(Player player) {
        throw GameOperationException.permissionDenied();
    }

    @Override
    public void ready(Player player) {
        readyPlayerIds.add(player.getId());
        broadcast(op -> op.memberReady(player.getId()));
        updateState();
    }

    @Override
    public void cancelReady(Player player) {
        readyPlayerIds.remove(player.getId());
        broadcast(op -> op.memberCancelReady(player.getId()));
    }

    private boolean isReady(Player player) {
        return readyPlayerIds.contains(player.getId());
    }

    @Override
    public void touchSend(Player player, List<TouchFrame> touchFrames) {

    }

    @Override
    public void judgeSend(Player player, List<JudgeEvent> judgeEvents) {

    }

    @Override
    public void abort(Player player) {
        throw GameOperationException.invalidState();
    }

    @Override
    public void played(Player player, int recordId) {
        throw GameOperationException.invalidState();
    }

    public void startCountdown() {
        if (countdownRunning) {
            return;
        }

        countdownRunning = true;
        int countdownSeconds = room.getSetting().getReadyCountdownSeconds();
        broadcastSystemMessage("进入准备阶段：请在 " + countdownSeconds + " 秒内准备。未准备玩家将作为观战跳过本轮。");

        for (int seconds : NOTICE_SECONDS) {
            if (seconds <= countdownSeconds) {
                countdownTasks.add(TIMER.schedule(() -> noticeCountdown(seconds), countdownSeconds - seconds, TimeUnit.SECONDS));
            }
        }
        countdownTasks.add(TIMER.schedule(this::finishCountdown, countdownSeconds, TimeUnit.SECONDS));
    }

    private void noticeCountdown(int seconds) {
        if (countdownRunning) {
            broadcastSystemMessage("准备倒计时：" + seconds + " 秒。");
        }
    }

    private void updateState() {
        if (countOnlinePlayers() == 0) {
            cancelCountdown();
            broadcastSystemMessage("当前没有在线玩家，本轮准备已取消。");
            RoomSelectChart state = new RoomSelectChart(room, stateUpdater, chart);
            updateGameState(state);
            state.activate();
            return;
        }

        if (isAllOnlinePlayersReady()) {
            cancelCountdown();
            enterPlaying();
        }
    }

    private boolean isAllOnlinePlayersReady() {
        Set<Player> allPlayers = new HashSet<>(room.getPlayerManager().getPlayers());
        allPlayers.addAll(room.getPlayerManager().getMonitors());

        Set<Player> onlinePlayers = allPlayers.stream()
                .filter(Player::isOnline)
                .collect(Collectors.toSet());

        return !onlinePlayers.isEmpty() && onlinePlayers.stream().allMatch(this::isReady);
    }

    private void finishCountdown() {
        if (!countdownRunning) {
            return;
        }

        countdownRunning = false;
        countdownTasks.clear();
        enterPlaying();
    }

    private void enterPlaying() {
        Set<Player> activePlayers = room.getPlayerManager().getPlayers().stream()
                .filter(Player::isOnline)
                .filter(this::isReady)
                .collect(Collectors.toSet());

        room.getPlayerManager().getPlayers().stream()
                .filter(Player::isOnline)
                .filter(player -> !isReady(player))
                .forEach(player -> player.operations().ifPresent(op -> {
                    op.updateHostStatus(true);
                    op.enterState(new SelectChart(chart.getId()));
                }));
        room.getPlayerManager().getMonitors().stream()
                .filter(Player::isOnline)
                .filter(player -> !isReady(player))
                .forEach(player -> player.operations().ifPresent(op -> op.enterState(new SelectChart(chart.getId()))));

        if (activePlayers.isEmpty()) {
            broadcastSystemMessage("没有已准备的在线玩家，本轮取消。");
            RoomSelectChart state = new RoomSelectChart(room, stateUpdater, chart);
            updateGameState(state);
            state.activate();
            return;
        }

        RoomPlaying state = new RoomPlaying(room, stateUpdater, chart, activePlayers);
        updateGameState(state, false);
        room.getPlayerManager().getPlayers().stream()
                .filter(Player::isOnline)
                .filter(this::isReady)
                .forEach(player -> player.operations().ifPresent(op -> op.enterState(state.toProtocol())));
        room.getPlayerManager().getMonitors().stream()
                .filter(Player::isOnline)
                .filter(this::isReady)
                .forEach(player -> player.operations().ifPresent(op -> op.enterState(state.toProtocol())));
        activePlayers.forEach(player -> player.operations().ifPresent(PlayerOperations::gameStartPlaying));
        room.getPlayerManager().getMonitors().stream()
                .filter(Player::isOnline)
                .filter(this::isReady)
                .forEach(player -> player.operations().ifPresent(PlayerOperations::gameStartPlaying));
    }

    private void cancelCountdown() {
        countdownRunning = false;
        countdownTasks.forEach(task -> task.cancel(false));
        countdownTasks.clear();
    }

    @Override
    public void dispose() {
        countdownTasks.forEach(task -> task.cancel(false));
        countdownTasks.clear();
    }

    @Override
    public GameState toProtocol() {
        return new WaitForReady();
    }
}
