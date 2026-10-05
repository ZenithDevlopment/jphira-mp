# ZENITH Chart Pool Screening

Reference for the three-category pool rotation and the Phira API behaviour it relies on.

## Pool categories

| Category | Tag on Phira | Rule | Rounds per pool |
|---|---|---|---|
| `REGULAR` | `regular` | rating > 4.2 **and** difficulty >= AT17 | 3 |
| `CONFIGURED` | `plain` | rating > 4.0 | 3 |
| `TB` | `regular` or `plain` | rating > 4.2 **and** duration > 6 min | 1 |
| `MANUAL` | — | hand-picked, no rule attached | room default |

`TB` draws from both kinds, so it is a category of its own rather than a variant of `REGULAR`.

## Measured Phira API behaviour

Verified against `https://phira.5wyxi.com` on 2026-10-04, 9689 charts total.

### Data fields

| Field | Reality |
|---|---|
| `rating` | normalised to 0..1. A 4.2/5 rating is `rating > 0.84`, 4.0/5 is `rating > 0.80` |
| `ratingCount` | must be bounded from below; single-vote charts reach 0.94 |
| `difficulty` | reliable numeric constant. **Use this, not `level`** |
| `level` | free-form text, values seen: `AT Lv.17`, `AT16`, `AT 15`, `AT Lv. 17`, `AT Lv.19+`, `AT Lv.Random`, `AT`, `EZ`, `HD`, `4K`, `whatsthepointlv.18` |
| `tags` | carries `regular` or `plain`. The single reliable kind marker |
| duration | **absent** from every response. Must be derived from the audio file |
| `division` | **absent** from response bodies, query parameter only |

### Endpoints

```
GET /chart?page=N              30 per page, `limit` is ignored -> 324 pages for everything
GET /chart?division=plain      works, 3362 charts
GET /chart?division=regular    IGNORED, identical to calling without it
GET /chart?division=inchi      0 results
GET /chart/{id}                24 fields, no division, no duration
```

Default ordering is `updated desc`, so the first pages contain only freshly uploaded
`regular` charts. Fetch `plain` charts explicitly with `division=plain`.

### Duration

Phira exposes no duration field anywhere, so it is derived from the audio inside the chart
archive (`file`). Verified 2026-10-05 against 20 real charts: 18 resolved, 2 failed.

The `preview` audio is useless for this: it is a 15 second excerpt
(`info.yml` carries `previewStart: 15.0`, `previewEnd: 31.0`).

Archive probing, all via `Range` requests that follow the `303` to the signed CDN URL:

1. Read the archive tail and walk the central directory, which sits directly before the EOCD
   record, so its size is enough to locate it without knowing the total length.
2. Fetch the first 32 KB of the audio entry and inflate just that much of the deflate stream.
   A second 512 KB window covers archives carrying a large illustrated ID3 tag.
3. MP3: read the frame header, then prefer the Xing/Info frame count (`frames * 1152 / rate` for
   MPEG-1), otherwise fall back to the file size divided by the frame bitrate.
4. Ogg Vorbis: the exact length lives in the granule of the final page, which cannot be reached
   without inflating the whole entry, so the nominal bitrate from the identification header is
   used instead. Accurate to a few percent, good enough for a 6 minute threshold.

Sample results: 66s, 90s, 97s, 103s, 120s, 123s, 133s, 142s, 148s, 188s, 191s, 192s, 249s, 274s,
324s, 521s.

### Archive format

20 of 20 sampled archives are ZIP (`PK\003\004`) holding `info.txt`, `info.yml`, the chart JSON,
an illustration and the audio. The audio is `.mp3` or `.ogg`, so both are handled. Rare older
charts ship a bare audio file instead of an archive; WAV is recognised by its `RIFF` header.

`/files` endpoints ignore `Range` unless redirects are followed, and the CDN URL carries a
short-lived `sign`/`t` signature, so URLs cannot be cached.

## Pool file format

`data/chart-pools.json`, keys lower_snake_case. New fields are optional, so existing
files load unchanged and pools default to `category=MANUAL`, `order=0`.

```json
{
  "pools": [
    {
      "id": 10,
      "favorite_id": null,
      "default_flag": true,
      "chart_ids": [7039, 44992],
      "category": "REGULAR",
      "size_limit": 15,
      "rounds_per_stay": 3,
      "order": 10
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `category` | drives rotation and presentation, never membership |
| `size_limit` | intended pool size, set when auto-generating pools |
| `rounds_per_stay` | rounds before rotating; absent falls back to the room `interval` |
| `order` | rotation order, then `id`. Appended pools get `max(order) + 10` |

## Behaviour

- Rotation happens in `RoomPlaying.finishRound` -> `RoomChartPool.finishPlayingRound`.
- A pool's own `rounds_per_stay` wins over the room `interval`, giving 3/3/1 across categories.
- Empty pools are allowed and skipped: `getDefaultPools` filters them out and
  `RoomChartPool` rejects a list that is entirely empty, so a staged pool never reaches a room.
- `GET /api/v1/room/{id}/pool` reports `effectiveRoundsPerStay` for the current pool.

## API

| Method | Path | Notes |
|---|---|---|
| `POST` | `/api/v1/pool/{id}/charts` | batch add, idempotent, single write. `{chartIds:[...]}` -> `{ok, added}` |
| `PUT` | `/api/v1/pool/{id}` | update `category` / `sizeLimit` / `roundsPerStay` / `order`. Omitted keys are kept |
| `POST` | `/api/v1/pool` | accepts empty `chartIds`, plus optional category metadata |
| `GET` | `/api/v1/pool/list` | now reports `category`, `sizeLimit`, `roundsPerStay`, `order` |
| `GET` | `/api/v1/chart/search` | screen the catalogue. `category`, `tag`, `minRating`, `minRatingCount`, `minDifficulty`, `minDuration`, `limit`, `refresh`, `division` |
| `POST` | `/api/v1/pool/generate` | cut every match into pools. `{category, sizeLimit, roundsPerStay, probeDuration}` |
| `POST` | `/api/v1/chart/duration` | probe specific charts. `{chartIds:[...]}` |

`GET /api/v1/chart/search` returns `indexed`, `remoteTotal`, `refreshing`, `matched` and
`charts`. Passing `refresh` starts a background sweep, since a full catalogue pull costs about
320 requests at 30 charts per page.

`POST /api/v1/pool/generate` probes durations first when the category needs them, because the
duration bound cannot be applied while it is still unknown.

Pool snapshots gained `category`, `sizeLimit`, `roundsPerStay`, `order`, and chart payloads
report `durationSeconds`.

## Capacity

Estimates from a 150-chart sample (~5.3% are AT17+, ~4% also clear rating > 0.84).

| Category | Estimated charts | Pools of 15 |
|---|---|---|
| `REGULAR` | ~390 | ~26 |
| `CONFIGURED` | ~3055 | ~200 |
| `TB` | pending duration probing | — |

`CONFIGURED` rating data is healthier than expected: 90.8% of sampled `plain` charts
exceed 0.80, and 88.5% still do with `ratingCount >= 5`.
