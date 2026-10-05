package top.rymc.phira.main.game.room;

import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.protocol.data.monitor.judge.JudgeEvent;
import top.rymc.phira.protocol.data.monitor.touch.TouchFrame;

import java.util.List;

public interface Room {

    String getRoomId();

    default void join(Player player, boolean isMonitor){
        join(player, isMonitor, true);
    }

    void join(Player player, boolean isMonitor, boolean shouldBroadcastJoin);

    void leave(Player player);

    Operation getOperation();

    RoomSnapshot getView();

    boolean containsPlayer(Player player);

    boolean containsMonitor(Player player);

    /** True when neither players nor monitors remain. */
    boolean isEmpty();

    /**
     * Reclaims the room and cancels whatever its current state scheduled.
     * Safe to call more than once.
     */
    void destroy();

    interface Operation {

        void lockRoom(Player player);

        void cycleRoom(Player player);

        void selectChart(Player player, int id);

        void chat(Player player, String message);

        void touchSend(Player player, List<TouchFrame> touchFrames);

        void judgeSend(Player player, List<JudgeEvent> judgeEvents);

        void requireStart(Player player);

        void ready(Player player);

        void cancelReady(Player player);

        void abort(Player player);

        void played(Player player, int recordId);
    }
}
