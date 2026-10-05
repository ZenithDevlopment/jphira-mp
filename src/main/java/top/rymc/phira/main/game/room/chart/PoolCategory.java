package top.rymc.phira.main.game.room.chart;

/** Pool kinds driving rotation rules and presentation. */
public enum PoolCategory {
    /** Charts tagged {@code regular}. */
    REGULAR,
    /** Charts tagged {@code plain}. */
    CONFIGURED,
    /** Long charts, regular or configured. */
    TB,
    /** Hand-picked, no screening rule attached. */
    MANUAL
}
