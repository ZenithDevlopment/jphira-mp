package top.rymc.phira.main.network.handler;

import top.rymc.phira.main.Server;
import top.rymc.phira.main.data.UserInfo;
import top.rymc.phira.main.game.exception.GameOperationException;
import top.rymc.phira.main.game.player.local.LocalPlayer;
import top.rymc.phira.main.game.point.PlayerPointService;
import top.rymc.phira.main.game.player.PlayerManager;
import top.rymc.phira.main.game.i18n.I18nService;
import top.rymc.phira.main.game.room.RoomSnapshot;
import top.rymc.phira.main.game.session.LocalSessionManager;
import top.rymc.phira.main.game.exception.session.ResumeFailedException;
import top.rymc.phira.main.game.exception.session.SuspendFailedException;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.ReadTimeoutHandler;

import java.util.concurrent.TimeUnit;
import top.rymc.phira.main.network.ConnectionReference;
import top.rymc.phira.main.network.PlayerConnection;
import top.rymc.phira.main.network.ServerChannelInitializer;
import top.rymc.phira.main.util.PhiraFetcher;
import top.rymc.phira.protocol.data.FullUserProfile;
import top.rymc.phira.protocol.data.RoomInfo;
import top.rymc.phira.protocol.handler.server.SimpleServerBoundPacketHandler;
import top.rymc.phira.protocol.packet.ClientBoundPacket;
import top.rymc.phira.protocol.packet.ServerBoundPacket;
import top.rymc.phira.protocol.packet.clientbound.ClientBoundAuthenticatePacket;
import top.rymc.phira.protocol.packet.clientbound.ClientBoundPongPacket;
import top.rymc.phira.protocol.packet.serverbound.*;

public class AuthenticateHandler extends SimpleServerBoundPacketHandler {

    private final PlayerConnection connection;

    protected void sendPacket(ClientBoundPacket packet) {
        connection.send(packet);
    }

    public AuthenticateHandler(PlayerConnection connection) {
        this.connection = connection;
    }

    /**
     * A heartbeat can legitimately arrive before authentication, so it is answered rather than
     * treated as an unexpected packet. The connection layer already replies to pings; this override
     * only exists so the packet is not counted as unhandled here.
     */
    @Override
    public void handle(ServerBoundPingPacket packet) {
        connection.send(ClientBoundPongPacket.INSTANCE);
    }

    @Override
    public void handle(ServerBoundAuthenticatePacket packet) {
        try {
            String token = packet.getToken();
            Server.getLogger().info("{} sent his token [{}]", connection.getRemoteAddressAsString(), token);

            UserInfo userInfo = PhiraFetcher.GET_USER_INFO.apply(token);

            PlayerManager.ResolveResult<LocalPlayer> result = PlayerManager.resolvePlayer(
                    userInfo.getId(),
                    LocalPlayer.class,
                    () -> new LocalPlayer(userInfo, new ConnectionReference(connection)),
                    (player) -> LocalSessionManager.resume(player, connection),
                    (remover, player) -> connection.onClose((ctx) -> {
                        if (player.getConnection() != connection) {
                            return;
                        }

                        try {
                            LocalSessionManager.suspend(player, remover);
                        } catch (SuspendFailedException e) {
                            remover.run();
                        }

                        })
            );

            LocalPlayer player = result.player();
            RoomSnapshot view = player.getRoomView().orElse(null);
            RoomInfo roomInfo = view == null ? null : view.asProtocolConvertible(player).toProtocol();

            if (result.type() == PlayerManager.ResolveResult.Type.Create) {
                connection.setPacketHandler(PlayHandler.create(result.player()));
            }

            connection.send(ClientBoundAuthenticatePacket.success(new FullUserProfile(userInfo.getId(), userInfo.getName(), false), roomInfo));
            relaxReadTimeout(connection);
            sendWelcomeMessages(player);

            if (view != null) {
                view.getProtocolHack().fixClientRoomState(player, true);
            }

            Server.getLogger().info("{} has logged in as [{}] {}", connection.getRemoteAddressAsString(), userInfo.getId(), userInfo.getName());

        } catch (GameOperationException e) {
            connection.send(ClientBoundAuthenticatePacket.failed(I18nService.INSTANCE.getMessage(e.getMessageKey())));
            connection.close();
        } catch (ResumeFailedException e) {
            connection.send(ClientBoundAuthenticatePacket.failed(I18nService.INSTANCE.getMessage("error.player_already_online")));
            connection.close();
        } catch (Exception e) {
            connection.send(ClientBoundAuthenticatePacket.failed(e.getMessage()));
            connection.close();
        }
    }

    private void sendWelcomeMessages(LocalPlayer player) {
        PlayerPointService.PointSummary point = PlayerPointService.getSummary(player);
        connection.sendChat(MESSAGE_SEPARATOR);
        connection.sendChat("欢迎加入 Zenith 音游战队 服务器！");
        connection.sendChat("玩家：" + player.getName() + "（#" + player.getId() + "）");
        connection.sendChat("当前积分：" + point.points() + "，积分排名：#" + point.rank());
        connection.sendChat("输入房间名可创建或加入房间，房间名仅限字母、数字、-、_。");
        connection.sendChat(MESSAGE_SEPARATOR);
    }

    /** Kept in sync with the separator the room states use. */
    private static final String MESSAGE_SEPARATOR = "————————————————————————————————————————";

    @Override
    protected void onUnhandledPacket(ServerBoundPacket packet) {
        connection.close();
    }

    /**
     * Widens the idle timeout now that the peer is a known player.
     *
     * <p>The handshake timeout exists to drop scanners quickly. Keeping it would instead drop
     * players whose client simply paused for a while, which costs them the round in progress.
     */
    private static void relaxReadTimeout(PlayerConnection connection) {
        ChannelPipeline pipeline = connection.getChannel().pipeline();
        if (pipeline.get(ServerChannelInitializer.READ_TIMEOUT_HANDLER) == null) {
            return;
        }
        pipeline.replace(ServerChannelInitializer.READ_TIMEOUT_HANDLER,
                ServerChannelInitializer.READ_TIMEOUT_HANDLER,
                new ReadTimeoutHandler(ServerChannelInitializer.AUTHENTICATED_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

}
