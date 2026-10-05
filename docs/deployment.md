# 部署指南

三个仓库各自独立，服务端是唯一有状态的部分。

| 仓库 | 产物 | 部署位置 |
|---|---|---|
| `jphira-mp` | `build/libs/jphira-mp-<version>.jar` | 服务器任意目录，例如 `/opt/jphira-mp` |
| `jphira-mp-zenith-webmanager` | `out/` 静态文件 | 上面那个目录的子目录 `frontend/dist` |
| `jphira-mp-zenith-apidoc` | 无 | 只是接口文档，不需要部署 |

服务端自己托管前端，所以不需要额外的 nginx。

## 1. 环境要求

| 项 | 要求 |
|---|---|
| JDK | 17 或更高（推荐 21） |
| Node.js | 20 或更高（只有构建前端时需要） |
| 磁盘 | 预留 2 GB（Gradle 缓存 + 依赖 + 构建产物） |
| 内存 | 至少 1 GB 可用给 JVM |

## 2. 构建服务端

```bash
git clone <jphira-mp 仓库> && cd jphira-mp
./gradlew shadowJar
```

产物在 `build/libs/jphira-mp-<version>.jar`（约 25 MB，含全部依赖）。

> 从 Windows 打包源码再上传到 Linux 时，注意转一下换行，否则 `gradlew` 会因 shebang 带 `\r` 而报
> `env: './gradlew': No such file or directory`：
> ```bash
> for f in gradlew build.gradle settings.gradle gradle/wrapper/gradle-wrapper.properties; do
>   tr -d '\r' < "$f" > "$f.tmp" && mv "$f.tmp" "$f"
> done
> ```

## 3. 构建前端

```bash
git clone <jphira-mp-zenith-webmanager 仓库> && cd jphira-mp-zenith-webmanager
npm install          # 或 pnpm install
npx next build       # 输出静态文件到 out/
```

`next.config.ts` 里 `output: "export"`，所以产物是纯静态文件。

## 4. 组装目录

服务端把前端挂在**运行目录**下的 `frontend/dist`（相对路径），所以两者要放在一起：

```
/opt/jphira-mp/
├── jphira-mp-1.0.0-dev-20260822-05.jar
├── frontend/
│   └── dist/              <- 把 webmanager 的 out/* 复制到这里
└── data/                  <- 运行时生成，见下
```

```bash
mkdir -p /opt/jphira-mp/frontend/dist
cp -r <webmanager>/out/* /opt/jphira-mp/frontend/dist/
cp <jphira-mp>/build/libs/jphira-mp-*.jar /opt/jphira-mp/
```

**注意**：是 `frontend/dist`，不是 `frontend`。放错层级的话根路径会一直返回 404。

## 5. 启动

```bash
cd /opt/jphira-mp
java -Xmx900m -jar jphira-mp-1.0.0-dev-20260822-05.jar \
  --port 12346 --http-port 8080
```

| 参数 | 默认 | 说明 |
|---|---|---|
| `--port` | 12346 | 游戏端口，Phira 客户端连这个 |
| `--host` | 0.0.0.0 | **游戏端口**的监听地址 |
| `--http-port` | 8080 | HTTP 控制面端口，浏览器访问这个 |
| `--http-host` | 0.0.0.0 | **HTTP 端口**的监听地址 |

`--host` 不影响 HTTP 端口，两者要分别指定。

启动后浏览器访问 `http://<服务器IP>:8080` 就是管理面板。

## 6. 用 systemd 守护（推荐）

裸跑的话进程崩了就永久停服，也没有开机自启。`/etc/systemd/system/jphira-mp.service`：

```ini
[Unit]
Description=JPhira MP Server
After=network.target

[Service]
Type=simple
WorkingDirectory=/opt/jphira-mp
ExecStart=/usr/bin/java -Xmx900m -jar /opt/jphira-mp/jphira-mp-1.0.0-dev-20260822-05.jar --port 12346 --http-port 8080
Restart=always
RestartSec=5
User=root

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl enable --now jphira-mp
systemctl status jphira-mp
journalctl -u jphira-mp -f
```

`WorkingDirectory` 必须是 jar 所在目录，否则 `data/` 和 `frontend/dist` 会找错地方。

## 7. data 目录

全部状态都在这里，**升级时不要删**：

| 文件 | 内容 | 删掉的后果 |
|---|---|---|
| `chart-pools.json` | 谱池定义（含类别、容量、轮次、顺序） | 谱池全丢，回到内置的 2 个默认池 |
| `chart-index.json` | 谱面索引（约 1 万条，含探测出的时长） | 需要重新拉取目录，时长探测结果丢失 |
| `chart-info-cache.json` | 池内谱面的元数据缓存 | 重新拉取即可 |
| `player-points.json` | 玩家积分 | 积分清零 |
| `submissions.json` | 玩家谱面投稿 | 投稿记录清空，玩家需重新投稿 |
| `admins.json` | 管理员用户 ID 数组 | 没人能管理，需手动写回 |
| `jwt-secret` | 签发 JWT 的密钥 | 所有登录态失效，用户需重新登录 |

`admins.json` 是热读的，改完立刻生效，不用重启。首次部署时直接写：

```bash
echo '[你的Phira用户ID]' > /opt/jphira-mp/data/admins.json
```

## 8. 首次使用

1. **登录**：面板上用 Phira 账号登录（服务端会代理到 Phira 官网校验，本机不存密码）。
2. **拉取谱面目录**：索引初始为空，在「谱池管理 → 按规则生成谱池」里点「刷新谱面目录」。
   服务端会增量拉取（默认最多 40 页），已缓存的不会重复拉。
3. **生成池**：选类别、填每池谱面数与停留轮数，先「预览匹配数」确认规模，再「生成」。
   TB 类别需要时长，会先探测，按预算控制数量（每首两次请求）。
4. **建房间**：房间在创建时固化池列表，之后不再跟随全局改动。要换池得重建房间。

## 9. 常见问题

**根路径 404**：前端没放到 `frontend/dist`，或者目录层级多了一层。

**面板能开但接口全 401**：`data/jwt-secret` 被清过，重新登录即可。

**接口 403**：当前账号不在 `data/admins.json` 里。

**启动时报 `HTTP API server failed to bind`**：8080 被占用。换个 `--http-port`，或
`ss -ltnp | grep 8080` 找出占用者。注意服务端在 HTTP 绑定失败时会直接退出（避免出现
「游戏能玩但面板不可用」）。

**重启后立刻启动失败**：旧进程还没释放端口。`kill -9` 之后等两三秒再起，
systemd 的 `Restart=always` 已经处理了这种情况。

**谱面筛选结果偏少或为 0**：确认索引已拉取（面板会显示「索引 N」）。纯配置谱面被 Phira
隐藏在默认列表之外，服务端会在索引里没有 `plain` 时才单独拉一次，所以第一次拉取耗时更长。

**Phira 开始返回空页**：目录拉取被限流了，等十几分钟。服务端已经做了节流（每页间隔
60ms、增量上限 40 页），正常使用不会触发。

## 10. 保护与限制

这些上限是为了防止一次误操作打爆上游接口或内存：

| 位置 | 限制 | 说明 |
|---|---|---|
| 谱面目录拉取 | 增量上限 40 页；索引为空时全量 | 每页 30 条、间隔 60ms |
| 时长探测 | 单次最多 500 首，4 线程 | 每首 2 次 Range 请求，并发过高会触发限流 |
| 批量加谱 | 单次最多 500 张 | 未缓存的谱面需要逐张拉取 |
| 生成池 | 单次最多 200 个池 | 超出会截断并警告，可调大 `sizeLimit` 或分次生成 |
| 批量新建空池 | 单次最多 50 个 | ID 由服务端分配 |
| 谱面投稿 | 单次最多 20 张 | 登录即可，但池需开启投稿 |
| 单张谱面的投稿人 | 最多 200 人 | 超出后不再接受新的投稿人 |
| 谱面筛选 | 单次最多返回 500 条 | `limit` 参数会被截断 |

其他行为：

- **`chart-index.json` 损坏不会阻止启动**：会记录警告并以空索引启动，重新拉取即可。
- **缓存写入是原子替换**（写临时文件再 rename），所以并发探测或中途崩溃都不会写坏文件。
- **管理员权限是实时判断的**：`requireAdmin` 每次都读 `data/admins.json`，不信任 JWT 里的 `isAdmin` 声明，所以移除某人后他的 token 立即失效。
- **`PUT /api/v1/pool/{id}` 的负数语义**：传 `-1` 表示清空该字段（恢复默认），省略表示不修改。
- **空池是允许的**：便于先建池再挑谱，房间创建时会跳过空池。

## 11. 升级

```bash
systemctl stop jphira-mp
# 替换 jar 和 frontend/dist，保留 data/
systemctl start jphira-mp
```

只替换 `frontend/dist` 时不用重启，刷新浏览器即可（静态文件按请求读取）。

**注意**：`chart-index.json` 在旧版本里若有字段命名差异（例如早期版本用下划线风格），
升级后建议删掉让它重建，否则部分字段会读成默认值。
