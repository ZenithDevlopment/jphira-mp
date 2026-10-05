package top.rymc.phira.main.game.room.state;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.main.game.player.operations.PlayerOperations;
import top.rymc.phira.main.game.room.local.LocalRoom;
import top.rymc.phira.main.network.ProtocolConvertible;
import top.rymc.phira.main.util.ExecutorServiceManager;
import top.rymc.phira.main.util.ThreadFactoryCompat;
import top.rymc.phira.protocol.data.monitor.judge.JudgeEvent;
import top.rymc.phira.protocol.data.monitor.touch.TouchFrame;
import top.rymc.phira.protocol.data.state.GameState;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public abstract sealed class RoomGameState implements ProtocolConvertible<GameState> permits RoomPlaying, RoomWaitForReady, RoomSelectChart {

    protected static final int SYSTEM_PLAYER_ID = -1;
    protected static final String MESSAGE_SEPARATOR = "————————————————————————————————————————";
    protected static final ScheduledExecutorService TIMER = initTimer();

    protected final Consumer<RoomGameState> stateUpdater;
    @Getter
    protected final LocalRoom room;
    @Setter
    @Getter
    protected ChartInfo chart;

    private static ScheduledExecutorService initTimer() {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(
                ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("Room-State-Timer")
        );
        ExecutorServiceManager.registerService(timer);
        return timer;
    }

    public RoomGameState(LocalRoom room, Consumer<RoomGameState> stateUpdater) {
        this(room, stateUpdater, null);
    }

    public RoomGameState(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart) {
        this.room = room;
        this.stateUpdater = stateUpdater;
        this.chart = chart;
    }

    protected void updateGameState(RoomGameState newRoomGameState) {
        updateGameState(newRoomGameState, true);
    }

    protected void updateGameState(RoomGameState newRoomGameState, boolean broadcastState) {
        stateUpdater.accept(newRoomGameState);
        if (broadcastState) {
            broadcast(op -> op.enterState(newRoomGameState.toProtocol()));
        }
    }

    /**
     * Cancels everything this state scheduled. Called when the room is destroyed, so that
     * pending countdown tasks do not keep the room alive until they fire.
     */
    public void dispose() {
    }

    protected void broadcast(Consumer<PlayerOperations> action) {
        room.getPlayerManager().broadcast(action);
    }

    protected void broadcastSystemMessage(String message) {
        broadcast(op -> op.receiveChat(SYSTEM_PLAYER_ID, message));
    }

    protected void sendSystemMessage(Player player, String message) {
        player.operations().ifPresent(op -> op.receiveChat(SYSTEM_PLAYER_ID, message));
    }

    protected long countOnlinePlayers() {
        return room.getPlayerManager().getPlayers().stream()
                .filter(Player::isOnline)
                .count();
    }

    protected Set<Player> getOnlinePlayers() {
        return room.getPlayerManager().getPlayers().stream()
                .filter(Player::isOnline)
                .collect(java.util.stream.Collectors.toSet());
    }

    public abstract void handleJoin(Player player);

    public abstract void handleLeave(Player player);

    public abstract void requireStart(Player player);

    public abstract void ready(Player player);

    public abstract void cancelReady(Player player);

    public abstract void touchSend(Player player, List<TouchFrame> touchFrames);

    public abstract void judgeSend(Player player, List<JudgeEvent> judgeEvents);

    public abstract void abort(Player player);

    public abstract void played(Player player, int recordId);

    @RequiredArgsConstructor
    public enum Type {
        Playing(RoomPlaying::new),
        SelectChart(RoomSelectChart::new),
        WaitForReady(RoomWaitForReady::new);

        private final Builder builder;

        private interface Builder {
            RoomGameState build(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart);
        }

        public RoomGameState build(LocalRoom room, Consumer<RoomGameState> stateUpdater, ChartInfo chart){
            return builder.build(room, stateUpdater, chart);
        }
    }

}
