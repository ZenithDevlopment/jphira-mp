package top.rymc.phira.main.game.room.chart;

import org.junit.jupiter.api.Test;
import top.rymc.phira.main.game.room.chart.ChartPool.PoolSnapshot;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoomChartPoolTest {

    private static PoolSnapshot pool(int id, Integer roundsPerStay) {
        return new PoolSnapshot(id, null, false, List.of(1, 2), PoolCategory.REGULAR, 15, roundsPerStay, id * 10, false);
    }

    @Test
    void rotatesOnTheRoomsIntervalWhenThePoolHasNoQuota() {
        RoomChartPool pool = new RoomChartPool(List.of(pool(1, null), pool(2, null)));
        assertThat(pool.resolveRoundsPerStay(3)).isEqualTo(3);

        pool.finishPlayingRound(3);
        pool.finishPlayingRound(3);
        assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(1);
        pool.finishPlayingRound(3);
        assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(2);
    }

    @Test
    void thePoolQuotaOverridesTheRoomsInterval() {
        RoomChartPool pool = new RoomChartPool(List.of(pool(1, 1), pool(2, 1)));
        assertThat(pool.resolveRoundsPerStay(9)).isEqualTo(1);

        pool.finishPlayingRound(9);
        assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(2);
    }

    @Test
    void aZeroQuotaFallsBackInsteadOfRotatingEveryRound() {
        RoomChartPool pool = new RoomChartPool(List.of(pool(1, 0), pool(2, null)));
        assertThat(pool.resolveRoundsPerStay(4)).isEqualTo(4);
    }

    @Test
    void rotationWrapsAround() {
        RoomChartPool pool = new RoomChartPool(List.of(pool(1, 1), pool(2, 1), pool(3, 1)));
        for (int expected : List.of(2, 3, 1, 2)) {
            pool.finishPlayingRound(9);
            assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(expected);
        }
    }

    @Test
    void aManualSwitchIsAppliedOnTheNextRefresh() {
        RoomChartPool pool = new RoomChartPool(List.of(pool(1, 2), pool(2, 2)));
        pool.switchPool(2);
        assertThat(pool.getPendingPoolId()).isEqualTo(2);
        assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(1);

        // The pending target only takes over once the quota is actually met.
        pool.finishPlayingRound(9);
        assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(1);
        pool.finishPlayingRound(9);
        assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(2);
        assertThat(pool.getPendingPoolId()).isNull();
    }

    @Test
    void switchingOutsideTheFrozenListIsRefused() {
        RoomChartPool pool = new RoomChartPool(List.of(pool(1, 5)));
        assertThatThrownBy(() -> pool.switchPool(99)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyPoolsNeverReachARoom() {
        PoolSnapshot empty = new PoolSnapshot(9, null, false, List.of(), PoolCategory.TB, 15, 1, 90, false);
        RoomChartPool pool = new RoomChartPool(List.of(empty, pool(1, 1)));
        assertThat(pool.getPools()).hasSize(1);
        assertThat(pool.getCurrentPoolSnapshot().id()).isEqualTo(1);

        assertThatThrownBy(() -> new RoomChartPool(List.of(empty)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theFrozenListKeepsTheGivenRotationOrder() {
        RoomChartPool pool = new RoomChartPool(List.of(pool(3, 1), pool(1, 1), pool(2, 1)));
        assertThat(pool.getPools()).extracting(PoolSnapshot::id).containsExactly(3, 1, 2);
    }
}
