package top.rymc.phira.main.network;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.ReadTimeoutException;
import lombok.Getter;
import lombok.Setter;
import org.apache.logging.log4j.Logger;
import top.rymc.phira.main.Server;
import top.rymc.phira.main.util.ExecutorServiceManager;
import top.rymc.phira.main.util.ThreadFactoryCompat;
import top.rymc.phira.protocol.data.message.ChatMessage;
import top.rymc.phira.protocol.handler.server.ServerBoundPacketHandler;
import top.rymc.phira.protocol.packet.ClientBoundPacket;
import top.rymc.phira.protocol.packet.ServerBoundPacket;
import top.rymc.phira.protocol.packet.clientbound.ClientBoundMessagePacket;
import top.rymc.phira.protocol.packet.clientbound.ClientBoundPongPacket;
import top.rymc.phira.protocol.packet.serverbound.ServerBoundPingPacket;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Getter
public class PlayerConnection extends ChannelInboundHandlerAdapter {

    /**
     * Shared pool instead of one thread per connection. Packets of a single connection are still
     * handled strictly one at a time, but idle connections cost no thread.
     */
    private static final ExecutorService PACKET_POOL = ExecutorServiceManager.registerService(
            Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
                    ThreadFactoryCompat.THREAD_FACTORY_CREATOR.apply("Packet-Worker")));

    /** Guards against a client flooding the server with tiny packets. */
    private static final int PACKETS_PER_SECOND = 200;

    private final ConcurrentLinkedQueue<Runnable> pendingPackets = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger();

    private final Channel channel;
    private final InetSocketAddress remoteAddress;

    private long rateWindowStart = System.currentTimeMillis();
    private int rateWindowCount;

    @Setter
    private volatile ServerBoundPacketHandler packetHandler;
    private volatile ConnectState connectState = ConnectState.ACTIVE;

    private final List<Consumer<ChannelHandlerContext>> closeHandlers = new CopyOnWriteArrayList<>();

    public PlayerConnection(Channel channel, InetSocketAddress remoteAddress) {
        this.channel = channel;
        this.remoteAddress = remoteAddress;
    }

    /**
     * Queues a packet, keeping the order it arrived in.
     *
     * <p>{@code pendingCount} doubles as the scheduling token: only the caller that raises it from
     * zero submits a drain, and the drain keeps running while more packets arrive.
     */
    private void submitPacket(Runnable task) {
        pendingPackets.add(task);
        if (pendingCount.getAndIncrement() == 0) {
            PACKET_POOL.execute(this::drainPackets);
        }
    }

    private void drainPackets() {
        do {
            Runnable task = pendingPackets.poll();
            if (task != null) {
                // Must not escape: the token would never return to zero and every later
                // packet on this connection would be stranded in the queue forever.
                try {
                    task.run();
                } catch (Throwable t) {
                    Server.getLogger().error("Packet handling failed for {}", getRemoteAddressAsString(), t);
                }
            }
        } while (pendingCount.decrementAndGet() > 0);
    }

    /** Drops the packet instead of disconnecting: a brief burst is normal, a flood is not. */
    private boolean rateLimited() {
        long now = System.currentTimeMillis();
        if (now - rateWindowStart >= 1000) {
            rateWindowStart = now;
            rateWindowCount = 0;
        }
        if (rateWindowCount >= PACKETS_PER_SECOND) {
            return true;
        }
        rateWindowCount++;
        return false;
    }

    public void onClose(Consumer<ChannelHandlerContext> handler) {
        closeHandlers.add(handler);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (packetHandler == null) {
            ctx.close();
            return;
        }

        if (this.isClosed()) {
            return;
        }

        ServerBoundPacket packet = (ServerBoundPacket) msg;

        if (rateLimited()) {
            Server.getLogger().warn("Closing {}, packet rate exceeded {} per second", getRemoteAddressAsString(), PACKETS_PER_SECOND);
            ctx.close();
            return;
        }

        submitPacket(() -> handle(ctx, packet));
    }

    @SuppressWarnings("resource")
    private void handle(ChannelHandlerContext ctx, ServerBoundPacket packet) {
        // The connection may have gone away while this packet was queued behind an earlier one.
        if (isClosed()) {
            return;
        }

        // Keepalive is answered here rather than in the packet handlers: ping is transport level,
        // and a handler that does not know it treats it as an unexpected packet and kicks. That
        // made clients heartbeat from the lobby and reconnect every few seconds, because only
        // RoomHandler knew how to answer.
        if (packet instanceof ServerBoundPingPacket) {
            send(ClientBoundPongPacket.INSTANCE);
            return;
        }

        try {
            packet.handle(packetHandler);
        } catch (Throwable t) {
            ctx.channel().eventLoop().execute(() -> ctx.fireExceptionCaught(t));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (!ctx.channel().isActive()) {
            // The peer went away first. Logged at debug rather than dropped, because a silent
            // close is indistinguishable from a server-side kick when reading the logs after the
            // fact.
            Server.getLogger().debug("{}: connection already closed ({})",
                    getRemoteAddressAsString(), cause.toString());
            return;
        }

        Logger logger = Server.getLogger();

        if (cause instanceof ReadTimeoutException) {
            logger.error("{}: read timed out", getRemoteAddressAsString());
            connectState = ConnectState.TIMEOUT;
        } else if (cause instanceof SocketException) {
            logger.info("{}: {}", getRemoteAddressAsString(), cause.getMessage());
            connectState = ConnectState.ERROR;
        } else {
            logger.atError().withThrowable(cause).log("{}: exception encountered", getRemoteAddressAsString());
            connectState = ConnectState.ERROR;
        }

        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // Nothing to stop here: the packet pool is shared, and pending work is discarded by the
        // isClosed check the handlers perform before touching the channel.
        Server.getLogger().info("Client disconnected: {} ({})",
                getRemoteAddressAsString(), connectState);

        for (Consumer<ChannelHandlerContext> handler : closeHandlers) {
            try {
                handler.accept(ctx);
            } catch (Exception e) {
                Server.getLogger().error("Exception in close handler for connection {}", getRemoteAddressAsString(), e);
            }
        }

        super.channelInactive(ctx);
    }

    public Optional<ChannelFuture> send(ClientBoundPacket packet) {
        if (this.isClosed()) {
            return Optional.empty();
        }

        // A client that cannot keep up would otherwise grow the outbound buffer without bound.
        if (!channel.isWritable()) {
            Server.getLogger().warn("Dropping outbound packet to {}, buffer is backed up", getRemoteAddressAsString());
            return Optional.empty();
        }

        return Optional.ofNullable(channel.writeAndFlush(packet));
    }

    public void sendChat(String message) {
        this.send(ClientBoundMessagePacket.create(new ChatMessage(-1,message)));
    }

    public boolean isClosed() {
        return !channel.isActive();
    }

    public void close() {
        if (!this.isClosed()) {
            channel.close();
        }
    }

    public String getRemoteAddressAsString() {
        return remoteAddress.getAddress().getHostAddress() + ":" + remoteAddress.getPort();
    }

    public void markDuplicateLogin() {
        this.connectState = ConnectState.DUPLICATE;
        this.close();
    }

    public void markAsKicked() {
        this.connectState = ConnectState.KICK;
        this.close();
    }

    private enum ConnectState {
        ACTIVE,
        KICK,
        TIMEOUT,
        DUPLICATE,
        ERROR
    }
}
