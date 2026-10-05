# 关于该分支

该分支用于 zenith 战队活动服务器的定制化改造。

房间不再依赖房主推进流程，而是由服务端自动完成投票、倒计时、准备、开局、结算和下一轮检查。

核心玩法围绕谱池投票展开：玩家在 `SelectChart` 阶段通过选谱操作为当前谱池内曲目投票，达到开局人数后进入可配置倒计时，倒计时结束后锁定票数最高的曲目。谱池、当前池快照、刷新轮次、收藏夹 id 和 ChartInfo 缓存均通过 `data` 目录下的 JSON 文件持久化。

该分支还增加了活动运营相关能力，包括控制台强制结束当前局、控制台管理谱池、通过锁房按钮查看当前谱池状态、积分系统、结算排名和通过创建 `rank` 房间查看积分排行榜。

这些改造面向固定活动服场景，不再以完整兼容原始通用房间逻辑为目标。

# jphira-mp
Java 实现的 [phira-mp](https://github.com/TeamFlos/phira-mp) 服务端，为性能与扩展性的平衡而生

## ⚙️ 特性
* Java 实现
* 基于 [netty](https://github.com/netty/netty)
* 拥有可扩展的插件系统（当前分支不支持）
* 正确实现原始逻辑

## 🚀 使用方法

运行 jphira-mp 与运行 Minecraft 服务端类似。

1. 安装 Java 17 或更高版本的 JDK（推荐 **Java 21** 以上）
2. 前往 [Release 页面](https://github.com/lRENyaaa/jphira-mp/releases) 下载最新版本
3. 在命令行中运行：

``` bash
java -jar jphira-mp-<version>.jar --port 12346
```

当前 jphira-mp 可用的命令行参数:
* `--help`: 显示帮助信息
* `--port <port>`: 指定游戏服务器监听端口，默认为 `12346`
* `--host <host>`: 指定游戏服务器监听地址，默认为 `0.0.0.0`
* `--http-host <host>`: 指定 HTTP 控制面监听地址，默认为 `0.0.0.0`
* `--http-port <port>`: 指定 HTTP 控制面监听端口，默认为 `8080`
* `--plugin <folder>`: 指定插件目录，默认为 `plugins`
* `--proxy-protocol`: 启用 Proxy Protocol 支持（用于代理等，如: [此内容](https://doc.natfrp.com/bestpractice/realip.html)），默认为 `false`
* `--language`: 设置服务器默认的玩家语言，默认为 `zh-CN`

`--host` 只影响游戏端口，HTTP 控制面需要单独用 `--http-host` / `--http-port`。
HTTP 端口绑定失败时服务端会直接启动失败并提示，避免出现「游戏能玩但管理面板不可用」的情况。

关闭 jphira-mp 同样与 Minecraft 服务端类似，在控制台输入 `stop` 命令即可关闭服务器。

## 🎛️ 控制台命令

| 命令 | 说明 |
|---|---|
| `stop` | 关闭服务器 |
| `say <text>` | 向所有在线玩家广播 |
| `admin list` / `admin add <userId>` / `admin remove <userId>` | 管理员名单 |
| `room list` / `room create <id> [poolId...]` / `room remove <id>` | 房间管理 |
| `room <id> end` | 强制结束当前 Playing |
| `room <id> config <key> <value>` | 房间配置，含 `interval`（池未单独设置轮次时的兜底间隔） |
| `room <id> pool switch <poolId>` | 设置下一轮生效的池 |
| `room <id> pool favorite <favoriteId\|none>` | 覆盖当前池展示收藏夹 |
| `pool list` | 列出全部谱池及其类别、顺序、容量与轮次配额 |
| `pool add <id> <chartIds...>` / `pool remove <id>` | 增删谱池（允许建空池后再挑谱） |
| `pool chart add\|remove <poolId> <chartId>` | 增删池内谱面 |
| `pool meta <poolId> <category> [sizeLimit] [roundsPerStay] [order]` | 设置类别与轮换参数 |
| `pool generate <category> [sizeLimit] [roundsPerStay] [probeBudget]` | 按规则批量切池 |
| `chart index` | 查看谱面索引状态 |
| `chart refresh [plain]` | 后台拉取 Phira 谱面目录 |
| `chart search <category> [limit]` | 按规则筛选并列出候选谱面 |
| `chart duration <chartIds...>` | 探测谱面时长 |
| `submission list` | 查看全部待审投稿 |
| `submission list <poolId>` | 查看某个池的投稿（含已审核） |
| `submission approve <poolId> <chartId>` | 通过投稿并把谱面加入该池 |
| `submission reject <poolId> <chartId> [reason]` | 拒绝投稿，可附理由 |

## 📮 谱面投稿

管理员在池详情页打开「开启玩家投稿」后，任何用 Phira 账号登录的玩家都可以在
「谱面投稿」页搜索并勾选谱面投进该池（一次最多 20 张）。

- 同一玩家对同一张谱面在同一池只能投一次；**不同玩家投同一张会合并成一条**，
  审核界面点「N 人」可以看到每个投稿人的头像、ID 和时间。
- 管理员通过后谱面**直接加入该池**；拒绝只记录状态和理由，不动池内容。
- 待审的投稿玩家可以自己撤回；已通过的不能撤回。
- 玩家的投稿记录存在 `data/submissions.json`。

## 🗂️ 谱池模型

`data/chart-pools.json` 中每个池除 `id` / `chart_ids` 外还带有元数据：

| 字段 | 含义 |
|---|---|
| `category` | `REGULAR` / `CONFIGURED` / `TB` / `MANUAL`，决定轮换与展示 |
| `size_limit` | 目标容量，批量生成时按此切分 |
| `rounds_per_stay` | 停留轮数，缺省时回退到房间的 `interval` |
| `order` | 轮换顺序，其次按 `id` |

类别对应的准入规则见 [docs/pool-screening.md](docs/pool-screening.md)，其中也记录了
Phira 接口的实测行为。首次使用需要先 `chart refresh` 拉取谱面目录，
或调用 `GET /api/v1/chart/search?refresh=1`。
控制台管理、批量切池与人工挑谱的接口说明见 [docs/integration.md](docs/integration.md)。

## 🔌 插件开发（当前分支不支持）
[![](https://jitpack.io/v/lRENyaaa/jphira-mp.svg)](https://jitpack.io/#lRENyaaa/jphira-mp)  
jphira-mp 在 [JitPack](https://jitpack.io/) 上可用

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>
```

```xml
<dependency>
    <groupId>com.github.lRENyaaa</groupId>
    <artifactId>jphira-mp</artifactId>
    <version>1.0.0-dev-20260328-01</version>
</dependency>
```

通过 [jphira-mp-example-plugin](https://github.com/lRENyaaa/jphira-mp-example-plugin) 了解API基本用法。

**请注意: jphira-mp 的尚未稳定，当前插件API可能会频繁变更**

**当前插件系统正在独立项目重构中，请查看 [PluginSystem-Prototype](https://github.com/lRENyaaa/PluginSystem-Prototype)**

## 📜 致谢
jphira-mp 基于如下项目:
* [jphira-mp-protocol](https://github.com/lRENyaaa/jphira-mp-protocol) - 基础协议实现
* [log4j2](https://github.com/apache/logging-log4j2) - 日志框架
* [netty](https://github.com/netty/netty) - 网络框架
* [orbit](https://github.com/MeteorDevelopment/orbit) - 事件系统

## 💬 开源协议
项目使用 LGPL v3 协议开源，见 [LICENSE](./LICENSE)  

Copyright (C) 2026 lRENyaaa
