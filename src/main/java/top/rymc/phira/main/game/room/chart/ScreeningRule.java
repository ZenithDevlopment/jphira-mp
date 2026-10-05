package top.rymc.phira.main.game.room.chart;

import top.rymc.phira.main.data.ChartInfo;

import java.util.Set;

/**
 * Screening criteria for one pool category.
 *
 * <p>Phira stores ratings normalised to 0..1, so a 4.2/5 threshold is 0.84.
 * The rating bound is a {@code float} on purpose: it is compared against
 * {@link ChartInfo#getRating()}, which is also a float, and widening both sides to
 * double would let a chart sitting exactly on the threshold slip past it.
 *
 * <p>A null numeric bound or an empty tag set means that dimension is not checked.
 */
public record ScreeningRule(PoolCategory category, Set<String> anyTags, float minRating, int minRatingCount,
                            Double minDifficulty, Integer minDurationSeconds) {

    /** 常规: regular charts rated above 4.2, difficulty AT17 or higher. */
    public static ScreeningRule regular() {
        return new ScreeningRule(PoolCategory.REGULAR, Set.of("regular"), 0.84f, 5, 17.0, null);
    }

    /** 纯配置: plain charts rated above 4.0, any difficulty. */
    public static ScreeningRule configured() {
        return new ScreeningRule(PoolCategory.CONFIGURED, Set.of("plain"), 0.80f, 5, null, null);
    }

    /** TB: either kind, rated above 4.2, longer than 6 minutes. */
    public static ScreeningRule tb() {
        return new ScreeningRule(PoolCategory.TB, Set.of("regular", "plain"), 0.84f, 5, null, 360);
    }

    public static ScreeningRule of(PoolCategory category) {
        return switch (category) {
            case REGULAR -> regular();
            case CONFIGURED -> configured();
            case TB -> tb();
            case MANUAL -> null;
        };
    }

    /** A chart qualifies when it carries a matching tag and clears every configured bound. */
    public boolean matches(ChartInfo chart) {
        if (!anyTags.isEmpty() && !matchesTag(chart)) {
            return false;
        }
        // The activity rules say "above", so a chart sitting exactly on the rating bound is out.
        if (chart.getRating() <= minRating || chart.getRatingCount() < minRatingCount) {
            return false;
        }
        // Difficulty is "AT17 or higher", so the bound itself passes.
        if (minDifficulty != null && chart.getDifficulty() < minDifficulty) {
            return false;
        }
        return testDuration(chart);
    }

    /** True when the duration is already known and long enough; unknown durations never pass. */
    public boolean testDuration(ChartInfo chart) {
        return minDurationSeconds == null
                || chart.getDurationSeconds() != null && chart.getDurationSeconds() >= minDurationSeconds;
    }

    private boolean matchesTag(ChartInfo chart) {
        return anyTags.stream().anyMatch(chart::hasTag);
    }
}
