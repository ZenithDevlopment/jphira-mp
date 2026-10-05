# Integration notes for apidoc and webmanager

The server side is complete and verified. Two companion repositories still need updating:
neither can be pushed from this workspace, so the exact changes are spelled out below.

## 1. jphira-mp-zenith-apidoc

`API.apifox.json` gains three endpoints and one schema change.

### New endpoints

| Method | Path | Auth |
|---|---|---|
| `PUT` | `/api/v1/pool/{id}` | admin |
| `POST` | `/api/v1/pool/{id}/charts` | admin |
| `GET` | `/api/v1/chart/search` | admin |
| `POST` | `/api/v1/pool/generate` | admin |
| `POST` | `/api/v1/chart/duration` | admin |
| `GET` | `/api/v1/admin` | admin |
| `POST` | `/api/v1/admin` | admin |
| `DELETE` | `/api/v1/admin/{userId}` | admin |

所有请求体的驼峰字段**必须**带 `@SerializedName` 对应实现，`AdminBody` 早期漏了注解
导致 `userId` 恒为 0，添加管理员完全不可用（已在服务端修复并验证）。

`GET /api/v1/chart/search` query parameters:

| Name | Type | Notes |
|---|---|---|
| `category` | string | `REGULAR` / `CONFIGURED` / `TB` / `MANUAL`, case-insensitive |
| `tag` | string | comma separated override, e.g. `plain` |
| `minRating` | number | defaults to the category rule, e.g. `0.84` |
| `minRatingCount` | number | defaults to the category rule, e.g. `5` |
| `minDifficulty` | number | e.g. `17` |
| `minDuration` | number | seconds, e.g. `360` |
| `limit` | number | default 50, capped at 500 |
| `refresh` | any | present triggers a background catalogue sweep |
| `division` | string | `plain` restricts the sweep to configured charts |
| `probeDuration` | number | **TB 必传**：先按预算探测时长再筛选。不带它时 TB 永远返回 0，因为时长是筛选前提 |

`sweep` 是增量式的：按 `updated desc` 翻页，某页没有新谱面就停止（上限 40 页）。
索引为空时才会全量拉取。`division=regular` 被上游忽略，所以纯配置谱面单独拉一次，
但只在索引里还没有 `plain` 时。

响应里的 `pendingDuration` 为 true 表示「放宽时长条件后仍有候选」，
也就是「调大 `probeDuration` 还能筛出更多」。

Response body:

```json
{
  "ok": true,
  "result": {
    "indexed": 3367,
    "remoteTotal": 3367,
    "refreshing": false,
    "matched": 2996,
    "charts": [ { "id": 49526, "name": "Sargasso", "rating": 0.97461474, "durationSeconds": null } ]
  }
}
```

`POST /api/v1/pool/generate` request body:

```json
{ "category": "CONFIGURED", "sizeLimit": 15, "roundsPerStay": 3, "probeDuration": 200 }
```

Only `category` is required. `probeDuration` is the duration probe budget and is only
meaningful for `TB`, whose rule needs a duration the index does not have yet. Response:
`{ "ok": true, "poolIds": [1, 2, 3] }`.

`POST /api/v1/chart/duration` request body: `{ "chartIds": [7039, 44992] }`.
Response: `{ "ok": true, "probed": 2, "charts": [ ... ] }`.

### Schema changes

`谱面信息` (`#/definitions/303664780`) keeps all 22 required fields and adds:

| Field | Type | Notes |
|---|---|---|
| `durationSeconds` | integer | nullable, absent until probed |

`谱池快照` (`#/definitions/500285002`) keeps `id`, `favoriteId`, `default`, `chartIds` and adds:

| Field | Type | Notes |
|---|---|---|
| `category` | string | nullable, `REGULAR` / `CONFIGURED` / `TB` / `MANUAL` |
| `sizeLimit` | integer | nullable |
| `roundsPerStay` | integer | nullable, falls back to the room `interval` |
| `order` | integer | rotation order, then `id` |

`房间谱池状态` (`#/definitions/500285003`) adds `effectiveRoundsPerStay` (integer): the quota
actually in force for the current pool, which is the pool's `roundsPerStay` when set and the
room `interval` otherwise.

### Documentation corrections

- 房间定义 item 8 still says pools rotate "按 id 排序循环切换". Rotation now follows `order`
  first and `id` second, and is driven by the pool's own quota.
- 房间定义 item 7 should mention that `interval` only applies to pools without `roundsPerStay`.
- Item 5 should list the three new admin endpoints.
- The login example still contains a real email and password in plaintext. Replace with
  placeholders and rotate that account.

## 2. jphira-mp-zenith-webmanager

### `lib/api.ts`

`Pool` and the room pool status gain the new fields, and three calls are added:

```ts
export type PoolCategory = "REGULAR" | "CONFIGURED" | "TB" | "MANUAL";

export interface Pool {
  id: number;
  favoriteId: number | null;
  default: boolean;
  chartIds: number[];
  category: PoolCategory | null;
  sizeLimit: number | null;
  roundsPerStay: number | null;
  order: number;
}

export interface RoomPool {
  currentPool: Pool;
  pools: Pool[];
  pendingPoolId: number | null;
  favoriteId: number | null;
  finishedRoundsSinceRefresh: number;
  refreshIntervalRounds: number;
  effectiveRoundsPerStay: number;
}

export interface ChartSearchResult {
  indexed: number;
  remoteTotal: number | null;
  refreshing: boolean;
  matched: number;
  charts: PhiraChart[];
}
```

`PhiraChart` gains `durationSeconds: number | null`.

```ts
export const searchCharts = (params: {
  category?: PoolCategory; tag?: string; minRating?: number; minRatingCount?: number;
  minDifficulty?: number; minDuration?: number; limit?: number; refresh?: boolean;
  division?: string;
}) => request<ChartSearchResult>(`/chart/search?${qs(params)}`);

export const generatePools = (body: {
  category: PoolCategory; sizeLimit?: number; roundsPerStay?: number; probeDuration?: number;
}) => request<{ poolIds: number[] }>("/pool/generate", { method: "POST", body });

export const probeDurations = (chartIds: number[]) =>
  request<{ probed: number; charts: PhiraChart[] }>("/chart/duration", {
    method: "POST", body: { chartIds },
  });
```

`addCharts` already exists in `lib/api.ts` and now resolves, since the server finally
implements `POST /api/v1/pool/{id}/charts`.

### `app/pool/[id]/PoolClient.tsx`

Add an editable metadata block above the chart list:

- category select, defaulting to `MANUAL`
- number inputs for `sizeLimit` and `roundsPerStay`, sent through `PUT /api/v1/pool/{id}`
- an order input, same call
- the pool chart count next to `sizeLimit` so the operator can see the mismatch

### Suggested new page: `app/pool/generate/page.tsx`

1. category select
2. size limit and rounds per stay inputs
3. for `TB` only, a duration probe budget input
4. a button calling `generatePools`, then a link to each created pool

Pair it with a chart picker that calls `searchCharts` and lets the operator tick charts and
send them through `addCharts`. This replaces typing chart IDs by hand, which is currently the
only way to fill a pool.

### `scripts/mock-server.mjs`

Needs mocks for the three endpoints, otherwise `NEXT_PUBLIC_MOCK_API="true"` development
breaks as soon as the UI calls them.
