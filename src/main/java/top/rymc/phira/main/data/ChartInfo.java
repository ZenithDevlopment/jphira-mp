package top.rymc.phira.main.data;

import com.google.gson.annotations.SerializedName;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.OffsetDateTime;
import java.util.Arrays;

@Getter
@ToString
@EqualsAndHashCode
@SuppressWarnings("unused")
public final class ChartInfo {
    private int id;
    private String name;
    private String level;
    private float difficulty;
    private String charter;
    private String composer;
    private String illustrator;
    private String description;
    private boolean ranked;
    private boolean reviewed;
    private boolean stable;
    @SerializedName("stableRequest")
    private boolean stableRequest;
    private String illustration;
    private String preview;
    private String file;
    private int uploader;
    private String[] tags;
    private float rating;
    @SerializedName("ratingCount")
    private int ratingCount;
    private OffsetDateTime created;
    private OffsetDateTime updated;
    @SerializedName("chartUpdated")
    private OffsetDateTime chartUpdated;
    /** Audio length in seconds, {@code null} until probed from the preview file. */
    private Integer durationSeconds;

    /** Phira encodes the chart kind in {@code tags} rather than a dedicated field. */
    public boolean hasTag(String tag) {
        return tags != null && Arrays.stream(tags).anyMatch(tag::equals);
    }

    /** Only the probed duration is mutable; everything else comes from the remote catalogue. */
    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }
}

