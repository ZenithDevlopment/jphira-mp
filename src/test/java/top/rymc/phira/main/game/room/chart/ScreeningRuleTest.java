package top.rymc.phira.main.game.room.chart;

import org.junit.jupiter.api.Test;
import top.rymc.phira.main.data.ChartInfo;
import top.rymc.phira.main.util.GsonUtil;

import static org.assertj.core.api.Assertions.assertThat;

class ScreeningRuleTest {

    private static ChartInfo chart(String tags, float rating, int votes, float difficulty, Integer duration) {
        String json = "{\"tags\":[" + tags + "],\"rating\":" + rating
                + ",\"ratingCount\":" + votes + ",\"difficulty\":" + difficulty
                + (duration == null ? "" : ",\"duration_seconds\":" + duration) + "}";
        return GsonUtil.getGson().fromJson(json, ChartInfo.class);
    }

    @Test
    void presetsMatchTheActivityRules() {
        ScreeningRule regular = ScreeningRule.of(PoolCategory.REGULAR);
        assertThat(regular.minRating()).isEqualTo(0.84f);
        assertThat(regular.minDifficulty()).isEqualTo(17.0);
        assertThat(regular.anyTags()).containsExactly("regular");

        ScreeningRule configured = ScreeningRule.of(PoolCategory.CONFIGURED);
        assertThat(configured.minRating()).isEqualTo(0.80f);
        assertThat(configured.minDifficulty()).isNull();
        assertThat(configured.anyTags()).containsExactly("plain");

        ScreeningRule tb = ScreeningRule.of(PoolCategory.TB);
        assertThat(tb.minRating()).isEqualTo(0.84f);
        assertThat(tb.minDurationSeconds()).isEqualTo(360);
        assertThat(tb.anyTags()).containsExactlyInAnyOrder("regular", "plain");

        assertThat(ScreeningRule.of(PoolCategory.MANUAL)).isNull();
    }

    @Test
    void regularNeedsBothRatingAndDifficulty() {
        ScreeningRule rule = ScreeningRule.of(PoolCategory.REGULAR);
        assertThat(rule.matches(chart("\"regular\"", 0.90f, 10, 17.5f, null))).isTrue();
        assertThat(rule.matches(chart("\"regular\"", 0.90f, 10, 16.9f, null))).isFalse();
        assertThat(rule.matches(chart("\"regular\"", 0.80f, 10, 17.5f, null))).isFalse();
        assertThat(rule.matches(chart("\"plain\"", 0.90f, 10, 17.5f, null))).isFalse();
    }

    @Test
    void ratingBoundaryIsExclusive() {
        ScreeningRule rule = ScreeningRule.of(PoolCategory.CONFIGURED);
        assertThat(rule.matches(chart("\"plain\"", 0.80f, 10, 0f, null))).isFalse();
        assertThat(rule.matches(chart("\"plain\"", 0.81f, 10, 0f, null))).isTrue();
    }

    @Test
    void difficultyBoundaryIsInclusive() {
        ScreeningRule rule = ScreeningRule.of(PoolCategory.REGULAR);
        assertThat(rule.matches(chart("\"regular\"", 0.90f, 10, 17.0f, null))).isTrue();
        assertThat(rule.matches(chart("\"regular\"", 0.90f, 10, 16.9f, null))).isFalse();
    }

    @Test
    void aSingleVoteCannotQualify() {
        ScreeningRule rule = ScreeningRule.of(PoolCategory.CONFIGURED);
        assertThat(rule.matches(chart("\"plain\"", 0.99f, 4, 0f, null))).isFalse();
        assertThat(rule.matches(chart("\"plain\"", 0.99f, 5, 0f, null))).isTrue();
    }

    @Test
    void tbAcceptsBothKindsButNeedsAProbedDuration() {
        ScreeningRule rule = ScreeningRule.of(PoolCategory.TB);
        assertThat(rule.matches(chart("\"regular\"", 0.90f, 10, 0f, 400))).isTrue();
        assertThat(rule.matches(chart("\"plain\"", 0.90f, 10, 0f, 400))).isTrue();
        assertThat(rule.matches(chart("\"regular\"", 0.90f, 10, 0f, 300))).isFalse();
        // An unprobed chart must not sneak into a long-chart pool.
        assertThat(rule.matches(chart("\"regular\"", 0.90f, 10, 0f, null))).isFalse();
        assertThat(rule.matches(chart("\"other\"", 0.90f, 10, 0f, 400))).isFalse();
    }
}
