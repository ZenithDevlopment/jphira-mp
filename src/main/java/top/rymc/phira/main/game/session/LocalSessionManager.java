package top.rymc.phira.main.game.session;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.game.i18n.I18nService;
import top.rymc.phira.main.game.player.local.LocalPlayer;
import top.rymc.phira.main.game.room.Room;
import top.rymc.phira.main.game.room.holder.SuspendableRoomHolder;
import top.rymc.phira.main.game.exception.session.ResumeFailedException;
import top.rymc.phira.main.game.exception.session.SuspendFailedException;
import top.rymc.phira.main.game.room.state.RoomGameState;
import top.rymc.phira.main.game.room.state.RoomPlaying;
import top.rymc.phira.main.game.room.state.RoomWaitForReady;
import top.rymc.phira.main.network.PlayerConnection;
import top.rymc.phira.protocol.handler.server.ServerBoundPacketHandler;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class LocalSessionManager {

    private static final Map<Integer, SuspendedRoomSession> SUSPENDED = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService TIMER = Executors.newScheduledThreadPool(1);

    @Getter
    @Setter
    private static long suspendTimeoutMillis = TimeUnit.HOURS.toMillis(1);

    public static void setSuspendTimeout(long timeout, TimeUnit unit) {
        suspendTimeoutMillis = unit.toMillis(timeout);
    }

    public static void resume(LocalPlayer player, PlayerConnection newConn) throws ResumeFailedException {
        SuspendedRoomSession session = SUSPENDED.remove(player.getId());
        if (session == null) {
            if (player.isOnline()) {
                postResume(player, newConn);
                return;
            }

            throw new ResumeFailedException();
        }

        ScheduledFuture<?> timeout = session.timeout;
        if (timeout != null) {
            timeout.cancel(false);
        }

        Optional<Room> optionalRoom = player.getRoom();
        if (optionalRoom.isPresent()) {
            Room room = optionalRoom.get();
            if (!room.containsPlayer(player)) {
                throw new ResumeFailedException();
            }
        }

        postResume(player, newConn);
    }

    private static void postResume(LocalPlayer player, PlayerConnection newConn) {
        ServerBoundPacketHandler handler = player.getConnection().getPacketHandler();
        newConn.setPacketHandler(handler);

        player.getConnectionRef().resume(newConn, (oldConn) ->
                oldConn.sendChat(I18nService.INSTANCE.getMessage(player.getLanguage(), "error.logged_in_elsewhere"))
        );
    }

    public static void suspend(LocalPlayer player, Runnable remover) throws SuspendFailedException {
        ServerBoundPacketHandler handler = player.getConnection().getPacketHandler();
        if (!(handler instanceof SuspendableRoomHolder roomHolder)) {
            throw new SuspendFailedException();
        }

        Room room = roomHolder.getRoom();
        if (room.containsMonitor(player)) {
            room.leave(player);
        }

        if (!room.containsPlayer(player)) {
            throw new SuspendFailedException();
        }

        SUSPENDED.compute(player.getId(), (id, oldSession) -> {

            RoomGameState state = room.getView().getState();
            if (state instanceof RoomWaitForReady) {
                room.getOperation().cancelReady(player);
            } else if (state instanceof RoomPlaying) {
                room.getOperation().abort(player);
            }

            if (oldSession != null && oldSession.timeout != null) {
                Server.getLogger().warn("Player {} already has a suspended session, cancelling old timeout", player.getId());
                oldSession.timeout.cancel(false);
            }

            SuspendedRoomSession newSession = new SuspendedRoomSession(player);

            newSession.timeout = TIMER.schedule(
                    () -> forceLeave(id, newSession, remover),
                    suspendTimeoutMillis,
                    TimeUnit.MILLISECONDS
            );

            return newSession;
        });
    }

    private static void forceLeave(int playerId, SuspendedRoomSession session, Runnable remover) {
        remover.run();

        if (!SUSPENDED.remove(playerId, session)) {
            return;
        }

        session.player.getRoom().ifPresent((room) -> {
            if (room.containsPlayer(session.player)) {
                room.leave(session.player);
            }
        });

    }

    @RequiredArgsConstructor
    private static final class SuspendedRoomSession {
        private final LocalPlayer player;
        private volatile ScheduledFuture<?> timeout;
    }
}