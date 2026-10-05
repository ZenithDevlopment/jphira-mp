package top.rymc.phira.main.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.network.handler.AuthenticateHandler;
import top.rymc.phira.main.network.haproxy.HAProxyHandshakeHandler;
import top.rymc.phira.protocol.codec.decoder.FrameDecoder;
import top.rymc.phira.protocol.codec.decoder.HandshakeDecoder;
import top.rymc.phira.protocol.codec.decoder.ServerPacketDecoder;
import top.rymc.phira.protocol.codec.encoder.FrameEncoder;
import top.rymc.phira.protocol.codec.encoder.ServerPacketEncoder;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

public class ServerChannelInitializer extends ChannelInitializer<Channel> {

    /** Pipeline name of the read timeout, replaced with a longer one after login. */
    public static final String READ_TIMEOUT_HANDLER = "readTimeout";
    /**
     * Idle tolerance for a logged in player. Short timeouts are right for unknown peers, but a
     * real client that pauses (backgrounded window, brief network hiccup) would otherwise be
     * dropped mid round and forced into the five minute suspend.
     */
    public static final int AUTHENTICATED_READ_TIMEOUT_SECONDS = 120;

    private final ChannelGroup allChannels;

    public ServerChannelInitializer(ChannelGroup allChannels) {
        this.allChannels = allChannels;
    }

    @Override
    protected void initChannel(Channel channel) {
        allChannels.add(channel);

        InetSocketAddress originalRemoteAddress = (InetSocketAddress) channel.remoteAddress();
        if (!Server.getInstance().getArgs().isProxyProtocol()) {
            initChannel0(channel, originalRemoteAddress);
            return;
        }

        channel.pipeline().addLast(new HAProxyMessageDecoder());

        HAProxyHandshakeHandler haProxyHandler = new HAProxyHandshakeHandler();
        channel.pipeline().addLast(haProxyHandler);

        haProxyHandler.getRealAddress().whenComplete((remoteAddress, throwable) -> {
            if (throwable != null) {
                Server.getLogger().warn("Disconnecting {} on HAProxy handshaking: {}", originalRemoteAddress, throwable.getMessage());
                if (channel.isActive()) {
                    channel.close();
                }
                return;
            }

            initChannel0(channel, remoteAddress);
        });

    }

    private void initChannel0(Channel channel, InetSocketAddress remoteAddress) {
        String ipPort = remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort();

        String refused = ConnectionLimiter.accept(remoteAddress.getAddress());
        if (refused != null) {
            Server.getLogger().warn("Refusing connection from {}: {}", ipPort, refused);
            channel.close();
            return;
        }
        channel.closeFuture().addListener(future -> ConnectionLimiter.release(remoteAddress.getAddress()));

        Server.getLogger().info("Establishing a connection from {}", ipPort);

        HandshakeDecoder handshake = new HandshakeDecoder();
        channel.pipeline().addLast(handshake);

        handshake.getClientProtocolVersion().whenComplete((version,throwable) -> {
            if (throwable != null) {
                Server.getLogger().warn("Disconnecting {} on Phira handshaking: {}", ipPort, throwable.getMessage());
                if (channel.isActive()) {
                    channel.close();
                }
                return;
            }

            Server.getLogger().info("Receive client version {} from {}", version, ipPort);

            channel.pipeline()
                    .addLast(new FrameDecoder())
                    .addLast(new FrameEncoder())
                    // Named so it can be relaxed once the client has authenticated.
                    .addLast(READ_TIMEOUT_HANDLER,
                            new ReadTimeoutHandler(Server.getInstance().getArgs().getReadTimeoutSeconds(), TimeUnit.SECONDS))
                    .addLast(new ServerPacketDecoder())
                    .addLast(new ServerPacketEncoder());

            PlayerConnection connection = new PlayerConnection(channel, remoteAddress);
            connection.setPacketHandler(new AuthenticateHandler(connection));
            channel.pipeline().addLast(connection);
        });
    }
}