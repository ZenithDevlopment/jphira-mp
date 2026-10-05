package top.rymc.phira.main.data;

import com.google.gson.annotations.SerializedName;
import lombok.*;

import java.time.OffsetDateTime;

@Getter
@Setter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class GameRecord {
    private int id;
    private int player;
    private int chart;
    private int score;
    private float accuracy;
    private int perfect;
    private int good;
    private int bad;
    private int miss;
    private float speed;
    private int maxCombo;
    private boolean best;
    // alternate: the list endpoint returns snake_case while the single record one uses camelCase.
    @SerializedName(value = "bestStd", alternate = {"best_std"})
    private boolean bestStd;
    private int mods;
    @SerializedName(value = "fullCombo", alternate = {"full_combo"})
    private boolean fullCombo;
    private OffsetDateTime time;
    private float std;
    @SerializedName(value = "stdScore", alternate = {"std_score"})
    private float stdScore;
}

