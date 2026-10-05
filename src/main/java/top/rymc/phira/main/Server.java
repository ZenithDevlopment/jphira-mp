package top.rymc.phira.main;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.Getter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.io.IoBuilder;
import top.rymc.phira.main.command.CommandService;
import top.rymc.phira.main.config.ServerArgs;
import top.rymc.phira.main.game.player.Player;
import top.rymc.phira.main.game.player.PlayerManager;
import top.rymc.phira.main.game.i18n.I18nService;
import top.rymc.phira.main.game.point.PlayerPointService;
import top.rymc.phira.main.game.record.RoundRecordService;
import top.rymc.phira.main.game.room.RoomManager;
import top.rymc.phira.main.game.room.chart.ChartIndex;
import top.rymc.phira.main.game.room.chart.ChartPool;
import top.rymc.phira.main.game.room.chart.SubmissionService;
import top.rymc.phira.main.http.ApiServer;
import top.rymc.phira.main.network.ServerChannelInitializer;
import top.rymc.phira.main.util.ExecutorServiceManager;

import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class Server {

    private static class Holder {
        private static final Server INSTANCE = new Server();
    }

    public static Server getInstance() {
        return Holder.INSTANCE;
    }

    static {
        System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");
        System.setOut(IoBuilder.forLogger(LogManager.getLogger("STDOUT")).setLevel(Level.INFO).buildPrintStream());
        System.setErr(IoBuilder.forLogger(LogManager.getLogger("STDERR")).setLevel(Level.ERROR).buildPrintStream());
    }

    @Getter
    private static final Logger logger = LogManager.getLogger("Server");
    @Getter
    private final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong startTime = new AtomicLong(0);

    @Getter
    private ServerArgs args;

    public static void main(String[] args) {
        long bootStart = System.nanoTime();

        ServerArgs serverArgs = new ServerArgs(args);
        Server server = Server.getInstance();

        try {
            server.start(serverArgs, bootStart);
        } catch (Exception e) {
            getLogger().error("Failed to start server", e);
            System.exit(1);
        }

        server.awaitShutdown();
    }

    public void start(ServerArgs args, long bootStart) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server is already running");
        }

        this.args = args;
        startTime.set(System.currentTimeMillis());

        logger.info("Booting up Phira Server...");

        I18nService.INSTANCE.setDefaultLanguage(args.getDefaultLanguage());
        logger.info("Default language: {}", args.getDefaultLanguage());

        logger.info("Preloading chart pool...");
        ChartPool.preload();
        logger.info("Chart pool preloaded.");

        // Cached catalogue only; a full sweep is triggered on demand from the web manager.
        ChartIndex.preload();
        logger.info("Chart index loaded: {} charts", ChartIndex.indexedCount());

        SubmissionService.preload();
        RoundRecordService.preload();
        logger.info("Round records loaded: {}", RoundRecordService.size());

        // Rooms created from the console or web manager never self destroy, so reap the idle ones.
        RoomManager.startWatchdog();

        ApiServer.start(args.getHttpHost(), args.getHttpPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Thread.currentThread().setName("ShutdownThread");
            if (isRunning()) {
                shutdown();
            }
        }));

        logger.info("Initializing network...");

        bossGroup = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("Netty-Boss", true), NioIoHandler.newFactory());
        workerGroup = new MultiThreadIoEventLoopGroup(0, new DefaultThreadFactory("Netty-Worker", true), NioIoHandler.newFactory());

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // Pause reading from a client whose outbound buffer fills up, instead of growing it.
                .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(512 * 1024, 2 * 1024 * 1024))
                .childHandler(new ServerChannelInitializer(allChannels));

        ChannelFuture future = bootstrap.bind(InetAddress.getByName(args.getHost()), args.getPort()).sync();
        serverChannel = future.channel();
        allChannels.add(serverChannel);

        logger.info("Listening on {}:{}", args.getHost(), args.getPort());

        ApiServer.awaitStarted();

        new CommandService(logger).start();

        long totalTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bootStart);
        logger.info("Done ({}s)!", String.format("%.3f", totalTime / 1000.0));

    }

    public void awaitShutdown() {
        if (serverChannel == null) return;

        try {
            logger.info("Server is running. Type 'stop' to stop.");
            serverChannel.closeFuture().await();
        } catch (InterruptedException e) {
            logger.info("Server thread interrupted, shutting down...");
            Thread.currentThread().interrupt();
        }
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;

        long shutdownStart = System.nanoTime();
        int onlineCount = PlayerManager.getOnlinePlayers().size();
        int channelCount = allChannels.size();

        logger.info("Shutting down...");

        // Points and round records are batched, so write them before anything else stops.
        PlayerPointService.flush();
        RoundRecordService.flush();

        if (onlineCount > 0) {
            logger.info("Kicking {} player(s)...", onlineCount);
            PlayerManager.getOnlinePlayers().forEach(Player::kick);
        }

        if (channelCount > 0) {
            logger.info("Closing {} channel(s)...", channelCount);
            allChannels.close().awaitUninterruptibly(10, TimeUnit.SECONDS);
            logger.info("Channels closed.");
        }

        if (workerGroup != null) {
            logger.info("Shutting down worker group...");
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
        if (bossGroup != null) {
            logger.info("Shutting down boss group...");
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).awaitUninterruptibly(5, TimeUnit.SECONDS);
        }

        long uptime = System.currentTimeMillis() - startTime.get();
        long shutdownTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - shutdownStart);

        logger.info("Uptime: {}m {}s",
                TimeUnit.MILLISECONDS.toMinutes(uptime),
                TimeUnit.MILLISECONDS.toSeconds(uptime) % 60);
        logger.info("Shutdown completed in {}ms. Goodbye!", shutdownTime);

        LogManager.shutdown();
        ExecutorServiceManager.shutdown();
        System.exit(0);
    }

    public boolean isRunning() {
        return running.get();
    }

}