package top.rymc.phira.main.game.room;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.game.i18n.I18nService;
import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.main.game.room.state.RoomGameState;
import top.rymc.phira.main.game.room.state.RoomPlaying;
import top.rymc.phira.main.game.room.state.RoomSelectChart;
import top.rymc.phira.main.game.room.state.RoomWaitForReady;
import top.rymc.phira.main.network.ProtocolConvertible;
import top.rymc.phira.protocol.data.RoomInfo;
import top.rymc.phira.protocol.data.state.GameState;
import top.rymc.phira.protocol.data.state.Playing;
import top.rymc.phira.protocol.data.state.SelectChart;
import top.rymc.phira.protocol.data.state.WaitForReady;
import top.rymc.phira.protocol.packet.clientbound.ClientBoundJoinRoomPacket;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
@Getter
public class RoomSnapshot {
    private final String roomId;
    private final RoomGameState state;
    private final boolean live;
    private final boolean locked;
    private final boolean cycle;
    private final Integer host;
    private final Set<Player> players;
    private final Set<Player> monitors;

    public ProtocolConvertible<RoomInfo> asProtocolConvertible(Player viewer) {
        return () -> new RoomInfo(
                roomId,
                state instanceof RoomPlaying ? new SelectChart() : state instanceof RoomWaitForReady ? new SelectChart(state.getChart().getId()) : state.toProtocol(),
                live, locked, cycle,
                true,
                false,
                players.stream().map(Player::toProtocol).toList(),
                monitors.stream().map(Player::toProtocol).toList()
        );
    }

    public boolean isHost(Player player) {
        return host != null && player.getId() == host;
    }

    @Getter
    private final ProtocolHack protocolHack = new ProtocolHack();

    public class ProtocolHack {

        private boolean isNotInSnapshot(Player player) {
            return !players.contains(player) && !monitors.contains(player);
        }

        public ClientBoundJoinRoomPacket buildJoinSuccessPacket() {

            GameState protocolState;
            ChartInfo chart = state.getChart();
            if (state instanceof RoomPlaying) {
                protocolState = new SelectChart();
            } else if (!(state instanceof RoomSelectChart) && chart != null) {
                protocolState = new SelectChart(chart.getId());
            } else {
                protocolState = state.toProtocol();
            }

            return ClientBoundJoinRoomPacket.success(
                    protocolState,
                    players.stream().map(Player::toProtocol).toList(),
                    monitors.stream().map(Player::toProtocol).toList(),
                    live
            );
        }

        private static final Executor executor = CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS);


        public void fixClientRoomState(Player player, boolean delay) {
            if (isNotInSnapshot(player)) return;

            if (state instanceof RoomWaitForReady) {
                runTask(() -> player.operations().ifPresent(operations -> operations.enterState(new WaitForReady())), delay);
            }

            setHost(player, isHost(player));
        }
        public void setHost(Player player, boolean value) {
            runTask(() -> player.operations().ifPresent(operations -> operations.updateHostStatus(value)), true);
        }

        private void runTask(Runnable task, boolean delay) {
            if (delay) {
                executor.execute(task);
            } else {
                task.run();
            }
        }
    }
}
