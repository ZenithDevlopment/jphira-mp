package top.rymc.phira.main.game.room.chart;

import top.rymc.phira.main.Server;

import java.util.List;

/**
 * Frozen chart pool state inside a room.
 *
 * Pools are copied from the global definitions at creation time and never change afterwards.
 * Each room owns one instance, tracking:
 * - the frozen pool list, in rotation order
 * - current pool, pending pool, finished round counter
 * - rotation driven by the pool's own {@code roundsPerStay}, falling back to the room-level interval
 *
 * State is not persisted; rooms do not survive a restart.
 */
public final class RoomChartPool {

    private final List<ChartPool.PoolSnapshot> pools;
    private ChartPool.PoolSnapshot currentPool;
    private Integer pendingPoolId;
    private int finishedRoundsSinceRefresh;

    public RoomChartPool(List<ChartPool.PoolSnapshot> pools) {
        List<ChartPool.PoolSnapshot> usable = pools == null ? List.of()
                : pools.stream().filter(pool -> pool != null && !pool.chartIds().isEmpty()).toList();
        if (usable.isEmpty()) {
            throw new IllegalArgumentException("Room chart pool list cannot be empty");
        }
        this.pools = List.copyOf(usable);
        this.currentPool = this.pools.get(0);
    }

    /**
     * Frozen pool list in rotation order, read-only.
     */
    public synchronized List<ChartPool.PoolSnapshot> getPools() {
        return pools;
    }

    public synchronized ChartPool.PoolSnapshot getCurrentPoolSnapshot() {
        return currentPool;
    }

    public synchronized Integer getPendingPoolId() {
        return pendingPoolId;
    }

    public synchronized int getFinishedRoundsSinceRefresh() {
        return finishedRoundsSinceRefresh;
    }

    /**
     * Set the pending pool, taking effect on the next refresh. The target must belong to the frozen list.
     */
    public synchronized void switchPool(int poolId) {
        if (poolById(poolId) == null) {
            throw new IllegalArgumentException("Pool not in room chart pool list: " + poolId);
        }
        pendingPoolId = poolId;
    }

    /**
     * Override the current pool's favorite display, without touching the global definition.
     * Takes effect on the next SelectChart snapshot.
     */
    public synchronized void setFavorite(Integer favoriteId) {
        currentPool = new ChartPool.PoolSnapshot(currentPool.id(), favoriteId, currentPool.defaultFlag(), currentPool.chartIds(),
                currentPool.category(), currentPool.sizeLimit(), currentPool.roundsPerStay(), currentPool.order(),
                currentPool.submissionOpen());
    }

    /**
     * Called when a Playing round ends. Rotates once the pool's own round quota is met.
     *
     * @param roomIntervalRounds fallback used when the pool defines no quota
     */
    public synchronized void finishPlayingRound(int roomIntervalRounds) {
        finishedRoundsSinceRefresh++;
        if (finishedRoundsSinceRefresh >= resolveRoundsPerStay(roomIntervalRounds)) {
            refreshCurrentPool();
        }
    }

    /** Quota of the current pool, falling back to the room-level interval. */
    public synchronized int resolveRoundsPerStay(int roomIntervalRounds) {
        Integer quota = currentPool.roundsPerStay();
        return quota == null || quota < 1 ? roomIntervalRounds : quota;
    }

    private void refreshCurrentPool() {
        if (pendingPoolId != null) {
            currentPool = poolById(pendingPoolId);
            pendingPoolId = null;
        } else {
            int index = poolIndex(currentPool.id());
            currentPool = pools.get((index + 1) % pools.size());
        }
        finishedRoundsSinceRefresh = 0;
        Server.getLogger().info("Room chart pool rotated to {} ({} rounds finished)", currentPool.id(), currentPool.category());
    }

    private ChartPool.PoolSnapshot poolById(int poolId) {
        return pools.stream()
                .filter(pool -> pool.id() == poolId)
                .findFirst()
                .orElse(null);
    }

    private int poolIndex(int poolId) {
        for (int i = 0; i < pools.size(); i++) {
            if (pools.get(i).id() == poolId) {
                return i;
            }
        }
        throw new IllegalStateException("Current pool not in room chart pool list: " + poolId);
    }
}
