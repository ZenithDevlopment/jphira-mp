package top.rymc.phira.main.network.handler;

import lombok.Getter;
import top.rymc.phira.main.game.exception.GameOperationException;
import top.rymc.phira.main.game.i18n.I18nService;
import top.rymc.phira.main.game.player.holder.PlayerHolder;
import top.rymc.phira.main.game.player.local.LocalPlayer;
import top.rymc.phira.main.game.point.PlayerPointService;
import top.rymc.phira.main.game.room.Room;
import top.rymc.phira.main.game.room.RoomManager;
import top.rymc.phira.main.game.room.RoomSnapshot;
import top.rymc.phira.main.game.room.chart.ChartPool;
import top.rymc.phira.main.game.room.local.LocalRoomBuilder;
import top.rymc.phira.main.network.PlayerConnection;
import top.rymc.phira.protocol.handler.server.SimpleServerBoundPacketHandler;
import top.rymc.phira.protocol.packet.ClientBoundPacket;
import top.rymc.phira.protocol.packet.ServerBoundPacket;
import top.rymc.phira.protocol.packet.clientbound.*;
import top.rymc.phira.protocol.packet.serverbound.ServerBoundCreateRoomPacket;
import top.rymc.phira.protocol.packet.serverbound.ServerBoundJoinRoomPacket;
import top.rymc.phira.protocol.packet.serverbound.ServerBoundPingPacket;

import java.util.List;
import java.util.regex.Pattern;

public class PlayHandler extends SimpleServerBoundPacketHandler implements PlayerHolder {

    private static final Pattern ROOM_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,20}");
    private static final String RANK_ROOM_ID = "rank";

    @Getter
    private final LocalPlayer player;

    protected void sendPacket(ClientBoundPacket packet) {
        player.getConnection().send(packet);
    }

    public static PlayHandler create(LocalPlayer player) {
        return new PlayHandler(player);
    }

    private PlayHandler(LocalPlayer player) {
        this.player = player;
    }

    @Override
    public void handle(ServerBoundCreateRoomPacket packet) {
        String roomId = packet.getRoomId();
        PlayerConnection connection = player.getConnection();
        if (RANK_ROOM_ID.equals(roomId)) {
            connection.send(ClientBoundCreateRoomPacket.failed("排行榜已发送"));
            sendPointRanking();
            return;
        }

        try {
            Room room = createRoom(roomId);
            connection.send(ClientBoundCreateRoomPacket.success());
            room.join(player, false, false);
            RoomHandler roomHandler = new RoomHandler(player, room, this);
            connection.setPacketHandler(roomHandler);
        } catch (GameOperationException e) {
            connection.send(ClientBoundCreateRoomPacket.failed(I18nService.INSTANCE.getMessage(player, e.getMessageKey())));
        } catch (Exception e) {
            connection.send(ClientBoundCreateRoomPacket.failed(e.getMessage()));
        }
    }

    private Room createRoom(String roomId) {
        if (!ROOM_ID_PATTERN.matcher(roomId).matches()) {
            throw new GameOperationException("房间名仅限字母、数字、-、_。");
        }
        if (RoomManager.findRoom(roomId) != null) {
            throw GameOperationException.roomAlreadyExists();
        }

        List<ChartPool.PoolSnapshot> pools = ChartPool.getDefaultPools();
        if (pools.isEmpty()) {
            throw new GameOperationException("当前没有默认启用的谱池，无法创建房间。");
        }

        return new LocalRoomBuilder()
                .host(false)
                .cycle(false)
                .chat(true)
                .autoDestroy(true)
                .pools(pools)
                .build(roomId);
    }

    @Override
    public void handle(ServerBoundJoinRoomPacket packet) {
        PlayerConnection connection = player.getConnection();

        try {
            Room room = RoomManager.findRoom(packet.getRoomId());
            if (room == null) {
                sendRoomNotFoundHint(packet.getRoomId());
                throw GameOperationException.roomNotFound();
            }

            room.join(player, packet.isMonitor());
            RoomHandler roomHandler = new RoomHandler(player, room, this);
            connection.setPacketHandler(roomHandler);

            RoomSnapshot.ProtocolHack hack = room.getView().getProtocolHack();
            connection.send(hack.buildJoinSuccessPacket());

            hack.fixClientRoomState(player, true);
            sendRoomGuide();
            sendPointSummary();

        } catch (GameOperationException e) {
            connection.send(ClientBoundJoinRoomPacket.failed(I18nService.INSTANCE.getMessage(player, e.getMessageKey())));
        } catch (Exception e) {
            connection.send(ClientBoundJoinRoomPacket.failed(e.getMessage()));
        }
    }

    private void sendRoomNotFoundHint(String roomId) {
        PlayerConnection connection = player.getConnection();
        connection.sendChat("房间 \"" + roomId + "\" 不存在。");
        List<Room> rooms = RoomManager.getAllRooms();
        if (rooms.isEmpty()) {
            connection.sendChat("当前没有房间，可创建一个。");
            return;
        }

        connection.sendChat("当前可用房间：");
        for (Room room : rooms) {
            RoomSnapshot view = room.getView();
            long online = view.getPlayers().stream().filter(p -> p.isOnline()).count();
            connection.sendChat("- " + view.getRoomId() + "（在线 " + online + "/" + view.getPlayers().size() + " 人）");
        }
    }

    private void sendRoomGuide() {
        PlayerConnection connection = player.getConnection();
        connection.sendChat("房间玩法：选谱阶段投票 → 准备阶段 → 游戏，票数最高者开局。");
        connection.sendChat("投票仅限当前谱池，重复投票会改票，观战者不能投票。");
    }

    private void sendPointSummary() {
        PlayerPointService.PointSummary point = PlayerPointService.getSummary(player);
        player.getConnection().sendChat("当前积分：" + point.points() + "，积分排名：#" + point.rank());
    }

    private void sendPointRanking() {
        PlayerPointService.PointSummary point = PlayerPointService.getSummary(player);
        player.getConnection().sendChat("————————————————————————————————————————");
        player.getConnection().sendChat("积分排行榜 TOP 10");
        player.getConnection().sendChat("排行榜每一分钟刷新一次，可能有滞后。");
        for (String line : PlayerPointService.getTopRankingLines(10)) {
            player.getConnection().sendChat(line);
        }
        player.getConnection().sendChat("你的积分：" + point.points() + "，积分排名：#" + point.rank());
        player.getConnection().sendChat("————————————————————————————————————————");
    }

    /**
     * Required by the packet handler interface, and the reason clients used to be kicked from the
     * lobby every heartbeat: ping used to fall through to {@link #onUnhandledPacket}. The reply
     * itself is sent by the connection layer, which answers pings in every state.
     */
    @Override
    public void handle(ServerBoundPingPacket packet) {
        player.getConnection().send(ClientBoundPongPacket.INSTANCE);
    }

    @Override
    protected void onUnhandledPacket(ServerBoundPacket packet) {
        player.kick();
    }
}
