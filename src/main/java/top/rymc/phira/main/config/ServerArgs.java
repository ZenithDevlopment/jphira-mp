package top.rymc.phira.main.config;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import lombok.Getter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.io.IoBuilder;
import top.rymc.phira.main.Server;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;

@Getter
public class ServerArgs {

    private static final Logger logger = Server.getLogger();

    private final int port;
    private final String host;
    private final String httpHost;
    private final int httpPort;
    private final Path pluginsDir;
    private final boolean proxyProtocol;
    private final String defaultLanguage;
    private final int readTimeoutSeconds;
    private final int maxConnections;
    private final int maxRooms;
    private final int emptyRoomTtlMinutes;

    public ServerArgs(String[] args) {
        OptionParser parser = new OptionParser();

        OptionSpec<Integer> portSpec = parser.accepts("port", "Server listening port")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(12346);

        OptionSpec<String> hostSpec = parser.accepts("host", "Bind address (IP to listen on)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("0.0.0.0");

        OptionSpec<String> pluginsSpec = parser.accepts("plugins", "Plugins directory path")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("plugins");

        OptionSpec<Boolean> proxyProtocol = parser.accepts("proxy-protocol", "Enable proxy protocol support")
                .withOptionalArg()
                .ofType(Boolean.class)
                .defaultsTo(false);

        OptionSpec<String> languageSpec = parser.accepts("language", "Default server language (e.g., zh-CN, en-US)")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("zh-CN");

        OptionSpec<String> httpHostSpec = parser.accepts("http-host", "HTTP API bind address")
                .withRequiredArg()
                .ofType(String.class)
                .defaultsTo("0.0.0.0");

        OptionSpec<Integer> httpPortSpec = parser.accepts("http-port", "HTTP API listening port")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(8080);

        // 5 秒太紧：选手弱网时会反复掉线并进入 5 分钟挂起，每次都打断当前回合。
        OptionSpec<Integer> readTimeoutSpec = parser.accepts("read-timeout", "Seconds before an idle client is dropped")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(20);

        OptionSpec<Integer> maxConnectionsSpec = parser.accepts("max-connections", "Maximum simultaneous connections")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(1000);

        OptionSpec<Integer> maxRoomsSpec = parser.accepts("max-rooms", "Maximum rooms alive at once")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(200);

        // Rooms created by operators use autoDestroy=false, meaning players may come and go.
        // Reclaiming those too eagerly would delete the room out from under a live event.
        OptionSpec<Integer> emptyRoomTtlSpec = parser.accepts("empty-room-ttl",
                        "Minutes a room may stay empty before being reclaimed (0 disables reclaiming)")
                .withRequiredArg()
                .ofType(Integer.class)
                .defaultsTo(120);

        parser.accepts("help", "Show this help message").forHelp();

        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (Exception e) {
            logger.error("Failed to parse arguments: {}", e.getMessage());
            printHelp(parser);
            LogManager.shutdown();
            System.exit(1);
            throw new AssertionError();
        }

        if (options.has("help")) {
            printHelp(parser);
            LogManager.shutdown();
            System.exit(0);
            throw new AssertionError();
        }

        this.port = clampPort(options.valueOf(portSpec));
        this.host = options.valueOf(hostSpec);
        this.httpHost = options.valueOf(httpHostSpec);
        this.httpPort = clampPort(options.valueOf(httpPortSpec));
        this.pluginsDir = Paths.get(options.valueOf(pluginsSpec));
        this.proxyProtocol = options.valueOf(proxyProtocol);
        this.defaultLanguage = options.valueOf(languageSpec);
        this.readTimeoutSeconds = clampPositive(options.valueOf(readTimeoutSpec), 5);
        this.maxConnections = clampPositive(options.valueOf(maxConnectionsSpec), 1);
        this.maxRooms = clampPositive(options.valueOf(maxRoomsSpec), 1);
        this.emptyRoomTtlMinutes = Math.max(0, options.valueOf(emptyRoomTtlSpec));
    }

    /** Keeps hand-edited values from turning the server into a no-op or a self-inflicted DoS. */
    private static int clampPositive(int value, int minimum) {
        if (value < minimum) {
            logger.warn("Value {} is below the minimum {}, using {}", value, minimum, minimum);
            return minimum;
        }
        return value;
    }

    private void printHelp(OptionParser parser) {
        try {
            logger.info("Phira Server Usage:");
            PrintStream logStream = IoBuilder.forLogger(logger).setLevel(Level.INFO).buildPrintStream();
            parser.printHelpOn(logStream);
        } catch (IOException e) {
            logger.error("Failed to print help: {}", e.getMessage());
        }
    }

    private static int clampPort(int value) {
        return Math.max(1, Math.min(value, 65535));
    }

}
