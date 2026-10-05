package top.rymc.phira.main.game.room.local;

import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.game.room.RoomManager;
import top.rymc.phira.main.game.room.chart.ChartPool;
import top.rymc.phira.main.game.room.chart.RoomChartPool;
import top.rymc.phira.main.game.room.state.RoomGameState;

import java.util.List;
import java.util.Set;

public class LocalRoomBuilder {

    private boolean autoDestroy = true;
    private boolean host = true;
    private int maxPlayer = 1000;
    private boolean locked = false;
    private boolean cycle = false;
    private boolean live = false;
    private boolean chat = true;
    private int minPlayer = 2;
    private int selectChartCountdownSeconds = 150;
    private int readyCountdownSeconds = 60;
    private int forceFinishSeconds = 120;
    private int refreshIntervalRounds = 5;
    private Set<Integer> adminIds = Set.of();

    public LocalRoomBuilder adminIds(Set<Integer> adminIds) {
        this.adminIds = adminIds == null ? Set.of() : Set.copyOf(adminIds);
        return this;
    }
    private List<ChartPool.PoolSnapshot> pools;
    private RoomGameState.Type state = RoomGameState.Type.SelectChart;
    private ChartInfo chart;

    public LocalRoomBuilder autoDestroy(boolean autoDestroy) {
        this.autoDestroy = autoDestroy;
        return this;
    }

    public LocalRoomBuilder host(boolean host) {
        this.host = host;
        return this;
    }

    public LocalRoomBuilder maxPlayer(int maxPlayer) {
        this.maxPlayer = maxPlayer;
        return this;
    }

    public LocalRoomBuilder locked(boolean locked) {
        this.locked = locked;
        return this;
    }

    public LocalRoomBuilder cycle(boolean cycle) {
        this.cycle = cycle;
        return this;
    }

    public LocalRoomBuilder live(boolean live) {
        this.live = live;
        return this;
    }

    public LocalRoomBuilder chat(boolean chat) {
        this.chat = chat;
        return this;
    }

    public LocalRoomBuilder state(RoomGameState.Type state) {
        this.state = state;
        return this;
    }

    public LocalRoomBuilder chart(ChartInfo chart) {
        this.chart = chart;
        return this;
    }

    public LocalRoomBuilder minPlayer(int minPlayer) {
        this.minPlayer = minPlayer;
        return this;
    }

    public LocalRoomBuilder selectChartCountdownSeconds(int selectChartCountdownSeconds) {
        this.selectChartCountdownSeconds = selectChartCountdownSeconds;
        return this;
    }

    public LocalRoomBuilder readyCountdownSeconds(int readyCountdownSeconds) {
        this.readyCountdownSeconds = readyCountdownSeconds;
        return this;
    }

    public LocalRoomBuilder forceFinishSeconds(int forceFinishSeconds) {
        this.forceFinishSeconds = forceFinishSeconds;
        return this;
    }

    public LocalRoomBuilder refreshIntervalRounds(int refreshIntervalRounds) {
        this.refreshIntervalRounds = refreshIntervalRounds;
        return this;
    }

    public LocalRoomBuilder pools(List<ChartPool.PoolSnapshot> pools) {
        this.pools = pools;
        return this;
    }

    public LocalRoomBuilder setting(LocalRoom.RoomSetting setting) {
        this.autoDestroy = setting.isAutoDestroy();
        this.host = setting.isHost();
        this.maxPlayer = setting.getMaxPlayer();
        this.locked = setting.isLocked();
        this.cycle = setting.isCycle();
        this.live = setting.isLive();
        this.chat = setting.isChat();
        this.minPlayer = setting.getMinPlayer();
        this.selectChartCountdownSeconds = setting.getSelectChartCountdownSeconds();
        this.readyCountdownSeconds = setting.getReadyCountdownSeconds();
        this.forceFinishSeconds = setting.getForceFinishSeconds();
        this.refreshIntervalRounds = setting.getRefreshIntervalRounds();
        this.adminIds = setting.getAdminIds() == null ? Set.of() : Set.copyOf(setting.getAdminIds());
        return this;
    }

    public LocalRoom.RoomSetting buildSetting() {
        return new LocalRoom.RoomSetting(
            autoDestroy, host, maxPlayer, locked, cycle, live, chat,
            minPlayer, selectChartCountdownSeconds, readyCountdownSeconds, forceFinishSeconds, refreshIntervalRounds,
            adminIds
        );
    }

    public LocalRoom build(String roomId) {
        if (pools == null || pools.isEmpty()) {
            throw new IllegalStateException("Room chart pools must be provided");
        }

        return RoomManager.resolveRoom(roomId, (onDestroy) ->
                new LocalRoom(onDestroy, roomId, buildSetting(), state, chart, new RoomChartPool(pools))
        );
    }
}
