# AerServerMonitor

> 一个bungee端的服务器监控插件, 融玩家管理、子服探活、聊天记录、Webhook 推送 以及自带 Dashboard 为一体！

- 编译使用 JDK 17

该插件使用AI进行了辅助开发. 本md由AI生成.

---

## 目录

- [功能一览](#功能一览)
- [架构总览](#架构总览)
- [编译与安装](#编译与安装)
- [初次配置](#初次配置)
- [配置文件详解](#配置文件详解)
    - [database.monitor](#databasemonitor-主数据库)
    - [database.skin](#databaseskin-皮肤数据库)
    - [redis](#redis-缓存与限流)
    - [postUrls](#posturls-webhook-推送)
    - [options](#options-核心选项)
    - [options.ssrf-protection](#optionsssrf-protection-ssrf-防护)
    - [options.async](#optionsasync-异步线程池)
    - [options.ws](#optionsws-websocket)
    - [options.dashboard](#optionsdashboard-控制面板)
    - [logs](#logs-日志)
- [命令与权限](#命令与权限)
- [HTTP API 一览](#http-api-一览)
- [Dashboard 使用](#dashboard-使用)
- [Webhook 事件类型](#webhook-事件类型)
- [数据库表结构](#数据库表结构)

---

## 功能一览

- **玩家管理** — 在线列表实时刷新, 按 UUID 或名字查玩家, 一键踢人
- **子服监控** — 把 BungeeCord 里配的子服全列出来, 可选 SLPing 探活, 每个服的在线玩家也拉得到
- **聊天记录** — 聊天先进队列再批量入库, 不卡主线程, 历史消息按时间区间翻
- **Webhook 推送** — 7 种事件 (玩家进/退/换服/聊天/执行命令/被踢/代理被 ping) 各自配 URL, 推失败自动重试, 实在不行进 webhook_log 表留着
- **自带 Dashboard** — 11 个页面的网页控制台, Overview / Players / Servers / Chat / Events / Console / System / Webhooks / Logs / Admins / Settings 全有
- **双鉴权** — 外部 API 走 HMAC (a/b 头), Dashboard 走 JWT (Authorization Bearer), 两套分开不混
- **限流与封禁** — 滑动窗口计数, 超阈值直接 ban IP, 重复犯的永久封
- **SSRF 防护** — `/server/info` 探活走白名单/黑名单, 默认拦云元数据接口
- **Redis 缓存** — 玩家数据缓存 + 限流计数器都在 Redis, Redis 挂了自动降级直连 DB, 不会让插件趴下
- **日志系统** — 请求/响应/操作/聊天/游戏事件按天分文件, 过期自动清

---

## 架构总览

```
                ┌─────────────────────────────────────────┐
                │            BungeeCord 代理端             │
                │  ┌────────────────────────────────────┐ │
   玩家事件 ────▶│  │       EventListener (BungeeCord)   │ │
                │  └─────────────┬──────────────────────┘ │
                │                │                         │
                │       ┌────────▼─────────┐              │
                │       │   AsyncExecutor  │ (线程池)      │
                │       └───┬───────┬──────┘              │
                │           │       │                      │
                │   ┌───────▼──┐ ┌──▼───────────┐         │
                │   │ PlayerDAO│ │  ChatDAO ...  │ ◀─── HikariCP ──▶ MySQL / H2
                │   └──────────┘ └──────────────┘         │
                │                │                         │
                │       ┌────────▼─────────┐              │
                │       │   RedisManager   │ ◀── Jedis ──▶ Redis
                │       └──────────────────┘              │
                │                │                         │
                │   ┌────────────▼───────────────────┐    │
                │   │      HttpApiServer (port 8009) │ ◀─── 外部 API / Dashboard 浏览器
                │   │  - AuthMiddleware              │    │
                │   │  - RateLimitMiddleware         │    │
                │   │  - PlayerHandler / ServerHandler... │
                │   └────────────────────────────────┘    │
                │                                         │
                │   ┌────────────────────────────────┐    │
                │   │  WebSocketServer (port 8010)   │ ───▶ 实时事件推送
                │   └────────────────────────────────┘    │
                │                                         │
                │   ┌────────────────────────────────┐    │
                │   │      PostManager (Webhook)     │ ───▶ 外部 HTTP 接收端
                │   └────────────────────────────────┘    │
                └─────────────────────────────────────────┘
```

核心组件:

| 组件 | 职责 |
|------|------|
| `AerServerMonitor` | 插件主类, 编排所有组件的初始化和关闭 |
| `PluginConfig` | 配置加载/读取, 包装 BungeeCord 的 Configuration |
| `DatabaseManager` | HikariCP 连接池, 自动建表 |
| `RedisManager` | Jedis 连接池, 自带健康检查和降级模式 |
| `SkinDatabaseManager` | 可选的皮肤数据库对接 (SkinsRestorer 等) |
| `EventListener` | 监听 BungeeCord 所有玩家/聊天/服务器事件 |
| `HttpApiServer` | `com.sun.net.httpserver` 内置 HTTP 服务, 端口 8009 |
| `WebSocketServer` | Netty 实现, 端口 8010, 实时事件推送 |
| `AuthMiddleware` | 双路径鉴权: Bearer JWT (Dashboard) 或 HMAC a/b 头 (外部 API) |
| `RateLimitMiddleware` | 滑动窗口限流, Redis 计数 |
| `WhitelistMiddleware` | SSRF 白/黑名单 |
| `LogManager` | 异步按天分文件写日志 |
| `PostManager` | Webhook 异步推送 + 失败重试 |

---

## 编译与安装

### 编译

```bash
git clone https://github.com/YanYuTingTeam/AerServerMonitor.git
cd AerServerMonitor
mvn clean package
```

产物在 `target/AerServerMonitor-1.0.jar`, shade 插件已经把 gson / HikariCP / jedis / bcrypt / jwt / h2 全部 relocate 进 jar 里, 不用再单独丢依赖

### 安装

把 jar 丢进 BungeeCord 的 `plugins/` 目录, 启动一次让插件生成 `plugins/AerServerMonitor/config.yml`, 然后关服改配置再启

### 依赖环境

| 项 | 要求 |
|----|------|
| BungeeCord | 1.20-R0.2 或更高 |
| JDK | 17+ |
| 数据库 | H2 (默认, 零配置) / MySQL 8+ / MariaDB 10+ |
| Redis | 可选, 但强烈推荐 (否则限流和缓存走 memory) |

---

## 初次配置

最简启动 (单机 H2, 不用 Redis):

```yaml
database:
  monitor:
    type: h2             # 不用装数据库
redis:
  host: "127.0.0.1"      # 没装 Redis 也会自动降级
options:
  http-port: 8009
  ws-port: 8010
  admin-username: "admin"
  admin-password: "改掉这个密码"
```

启服后访问:

- Dashboard: `http://你的代理IP:8009/dashboard`
- API 文档: `http://你的代理IP:8009/docs`

第一次启动会在 `admins` 表里插一条默认管理员 (用户名密码看 `options.admin-username` / `admin-password`), 拿这个账号登 Dashboard, 进去后改密码

---

## 配置文件详解

配置文件路径: `plugins/AerServerMonitor/config.yml`, 改完执行 `/asm reload` 热重载 (部分配置比如端口, 线程池大小还是得重启)

### database.monitor (主数据库)

存玩家, 聊天, 命令日志, Webhook 日志, 管理员账号这些核心数据

```yaml
database:
  monitor:
    type: h2                              # h2 或 mysql
    address: "localhost:3306"             # mysql 才用, 格式 host:port
    username: root
    password: pw
    database: db                          # mysql 库名
    table_prefix: asmonitor_              # 表前缀
    pool:
      maximum-pool-size: 10               # HikariCP 最大连接
      minimum-idle: 2                     # 最小空闲
      connection-timeout: 30000           # 拿连接超时 (ms)
      idle-timeout: 600000                # 空闲连接超时
      max-lifetime: 1800000               # 连接最大生命
      keepalive-time: 30000               # keepalive 间隔
```

| 字段 | type=h2 时 | type=mysql 时 |
|------|-----------|--------------|
| `address` | 忽略 | host:port |
| `username` / `password` / `database` | 忽略 | 必填 |
| `table_prefix` | 生效 | 生效 |

H2 模式数据文件在 `plugins/AerServerMonitor/monitor.db`, 自带 `MODE=MySQL` 兼容, 所以 SQL 写法和 MySQL 一样

### database.skin (皮肤数据库)

可选的, 对接 SkinsRestorer 这种皮肤插件的库, 让 Dashboard 显示玩家真实皮肤 UUID 而不是原 UUID

```yaml
database:
  skin:
    type: h2                  # h2 / mysql / monitor
    address: ""
    username: ""
    password: ""
    database: ""
    table: skinr_players      # 皮肤插件的表名
    pool:
      maximum-pool-size: 5
      minimum-idle: 1
```

- `type: monitor` — 复用主库的连接, 不再单独建池
- `type: h2` — 独立 H2 文件, 路径填在 `address`
- `type: mysql` — 独立 MySQL 库

不用皮肤系统的话, `type: monitor` + `table: skinr_players` 就行, 查不到会返回玩家原 UUID, 不报错

### redis (缓存与限流)

```yaml
redis:
  host: "127.0.0.1"
  port: 6379
  password: ""
  database: 0
  pool:
    max-total: 16
    max-idle: 8
    min-idle: 2
  retry:
    max-attempts: 3
    interval-ms: 5000
    health-check-interval-ms: 10000
    degraded-threshold: 3
```

- `degraded-threshold` — 连续失败这么多次就进降级模式, 玩家缓存直接走 DB, 限流走 memory
- 健康检查线程每 `health-check-interval-ms` ping 一次, 恢复自动退出降级

没装 Redis 也能跑, 插件启动时直接进降级模式, 控制台会打 `Redis connection failed. Running in degraded mode.`

### postUrls (Webhook 推送)

每种事件配一个独立 URL, 留空就是不推

```yaml
postUrls:
  playerJoinServer: ""
  playerChat: ""
  playerExecuteCmd: ""
  playerJoin: ""
  playerLeave: ""
  proxyPing: ""
  playerWasKicked: ""
  playerClickChat: ""
```

事件名 (key) 不能改, 必须和 `EventType` 枚举一一对应, 推送是 POST + JSON body, 失败按 `options.async.post-retry-interval-ms` 的间隔重试 N 次 (N = `post-retry-max`), 最终失败的进 `webhook_log` 表, 可以在 Dashboard Webhooks 页手动 retry

### options (核心选项)

```yaml
options:
  http-port: 8009                          # HTTP API + Dashboard 端口
  ws-port: 8010                            # WebSocket 端口
  hmac-salt: "AerServerMonitor"            # HMAC 盐, 外部 API 鉴权用
  hmac-origin: "AerServerMonitor"          # GET 请求 HMAC 的 data 字段
  hmac-algorithm: "HmacSHA256"             # HMAC 算法
  exec-cmd-as-playerChat: false            # true 时玩家命令也算聊天记录
  header-a-timeoffset: 15000               # a 头时间戳允许偏差 (ms)
  max-request-per-time: 3                  # 单 IP 在窗口内最大请求数
  per-time: 2000                           # 限流窗口长度 (ms)
  banned-time: 60000                       # 触发限流后封禁时长
```

**HMAC 鉴权说明** (外部 API 用):

外部调用方需要发两个头:

- `a`: 当前时间戳 (毫秒), 服务端允许 ±15000ms 偏差
- `b`: HMAC(算法, 盐, data), data 对 GET 是 `hmac-origin` 字符串, 对 POST 是请求 body

Dashboard 内部用 JWT, 不走这套, 看下面 dashboard 段

### options.ssrf-protection (SSRF 防护)

控制 `/server/info?ip=xxx` 接口能不能探活某个 IP

```yaml
options:
  ssrf-protection:
    enabled: true
    mode: "whitelist"                      # whitelist 或 blacklist
    whitelist:
      - "127.0.0.1"
      - "192.168.0.0/16"
      - "10.0.0.0/8"
      - "s:*"                              # s: 前缀 = 按 BungeeCord 已配置的子服名放行
    blacklist:
      - "169.254.169.254"                  # 云元数据接口
      - "0.0.0.0"
```

- `mode: whitelist` — 只允许 whitelist 里的 IP, blacklist 是补充禁用
- `mode: blacklist` — 默认全放, 只禁 blacklist 里的
- `s:子服名` — 直接放行 BungeeCord 配置里已有的子服地址, 省得重复写 IP

### options.async (异步线程池)

```yaml
options:
  async:
    event-pool-size: 4                     # 事件处理线程池
    event-pool-queue: 5000                 # 事件队列上限
    post-pool-size: 2                      # Webhook 推送线程池
    post-pool-queue: 2000
    post-retry-max: 3                      # 失败重试次数
    post-retry-interval-ms: [1000, 5000, 30000]  # 每次重试间隔
    log-flush-interval-ms: 100             # 日志刷盘间隔
    chat-batch-size: 50                    # 聊天批量入库一批多少条
    chat-batch-interval-ms: 100            # 聊天批量入库间隔
    webhook-queue-max: 5000                # webhook 队列上限
```

聊天是先丢进 BlockingQueue, 每 100ms 批量入库一次, 高并发下能显著降数据库压力

### options.ws (WebSocket)

```yaml
options:
  ws:
    max-connections: 500
    ping-interval-ms: 30000
    pong-timeout-ms: 10000                 # 10 秒没回 pong 就踢
```

WS 鉴权方式: 连接时 URL query 带 `?token=<jwt>`, 服务端校验通过才升级协议, 所有 Dashboard 实时事件 (玩家进出, 聊天, 换服) 都通过 WS 推送

### options.dashboard (控制面板)

```yaml
options:
  dashboard:
    enabled: true
    path: "/dashboard"                     # 访问路径
    session-expire: 86400000               # JWT 有效期 (ms), 默认 24 小时
    secret: "CHANGE_ME_TO_RANDOM_STRING"   # JWT 签名密钥, 务必改!
    login-security:
      max-attempts: 5                      # 窗口内最大登录失败次数
      window-ms: 300000                    # 5 分钟
      lockout-ms: 300000                   # 超限后锁定 5 分钟
      permanent-ban-threshold: 3           # 累计锁定这么多次后永久封 IP
      ban-scope: "ip-24"                   # 封禁粒度: ip / ip-24 / ip-16
      clean-ban-interval-ms: 86400000      # 永久封禁记录清理间隔
    command-whitelist: []                  # 控制台命令白名单, 空数组=不限
    command-blacklist:                     # 控制台命令黑名单
      - "stop"
      - "restart"
```

`secret` 务必改成随机字符串, 不然 JWT 签名可被伪造

`ban-scope` 三种粒度:

- `ip` — 封单个 IP
- `ip-24` — 封 /24 网段 (256 个 IP)
- `ip-16` — 封 /16 网段 (65536 个 IP)

控制台命令白/黑名单作用于 Dashboard Console 页执行的命令, 不影响游戏内命令

### logs (日志)

```yaml
logs:
  print-chat: false                        # 是否在控制台打印聊天
  print-request: false                     # 是否在控制台打印每个 HTTP 请求
  file-retention-days: 30                  # 日志文件保留天数
  file-max-size-mb: 100                    # 单文件最大 (此版本未实现 size 切割, 仅按天)
```

日志文件在 `plugins/AerServerMonitor/logs/monitor-YYYY-MM-DD.log`, 每天一个文件

---

## 命令与权限

| 命令 | 权限 | 说明 |
|------|------|------|
| `/asm` / `/asmonitor` / `/aerservermonitor` | `aermonitor.info` | 查看插件信息 |
| `/asm reload` | `aermonitor.admin` | 热重载 config.yml |

默认所有玩家都有 `aermonitor.info`, `aermonitor.admin` 需要手动给

---

## HTTP API 一览

所有接口都走 `http://代理IP:8009`, 详细字段请看 `/docs` 内置文档

| 路径 | 方法 | 鉴权 | 说明 |
|------|------|------|------|
| `/player/info?uuid=` 或 `?name=` | GET | HMAC 或 JWT | 查玩家信息, 支持批量 (逗号分隔) |
| `/player/kick?uuid=` 或 `?name=` | POST | HMAC 或 JWT | 踢玩家, body 是踢出原因 |
| `/server/list?ping=true\|false` | GET | HMAC 或 JWT | 列所有子服和在线玩家 |
| `/server/info?name=` 或 `?port=` 或 `?ip=` | GET | HMAC 或 JWT | 查单个子服 |
| `/chat/history?timefrom=&timeto=` | GET | HMAC 或 JWT | 聊天历史 |
| `/chat/command` | POST | HMAC 或 JWT | 以控制台身份执行命令 |
| `/system/status` | GET | HMAC 或 JWT | 系统状态 (DB/Redis/JVM/OS) |
| `/log/get` `/log/getlog` `/log/getmsg` `/log/getevent` | GET | HMAC 或 JWT | 日志查询 |
| `/api/auth/login` | POST | 无 | 管理员登录, 返回 JWT |
| `/api/auth/verify` | GET | JWT | 校验 JWT 是否有效 |
| `/api/auth/change-password` | POST | JWT | 改密码, 改完 token_version+1 让旧 JWT 失效 |
| `/api/admins` | GET | JWT | 管理员列表 |
| `/api/webhooks/history` | GET | JWT | Webhook 推送历史 |
| `/api/webhooks/retry?id=` | POST | JWT | 手动重试某条 webhook |
| `/dashboard` `/dashboard/*` | GET | 无 (静态资源) | Dashboard 网页 |
| `/docs` `/docs/*` | GET | 无 | API 文档网页 |

响应统一格式:

```json
{
  "code": 200,
  "errcode": 0,
  "message": "success",
  "time": 1720886400000,
  "logid": "20260714024600ABCD1234A1E5F8G2",
  "data": { ... }
}
```

业务码看 `/docs/errors.html`

---

## Dashboard 使用

打开 `http://代理IP:8009/dashboard`, 默认跳到登录页, 账号密码在 config.yml 的 `options.admin-username` / `admin-password`

11 个页面分别干这些事:

| 页面 | 干啥 |
|------|------|
| Overview | 在线人数, 子服状态, 近期事件概览 |
| Players | 在线玩家列表 + 搜索玩家 (支持名字和 UUID) |
| Servers | 子服列表, 可 SLPing 探活 |
| Chat | 实时聊天流 + 历史搜索 |
| Events | 玩家进出/换服/聊天/命令事件流 |
| Console | 在 BungeeCord 控制台执行命令 (受白/黑名单限制) |
| System | DB / Redis / JVM / OS 状态 |
| Webhooks | Webhook 推送历史 + 手动重试 |
| Logs | 请求/响应/操作/聊天/游戏日志查询 |
| Admins | 管理员账号管理 |
| Settings | 前端设置 (主题切换等) |

WS 实时事件通过 `ws://代理IP:8010?token=<jwt>` 推送, Dashboard 自动连

---

## Webhook 事件类型

| 事件 key | 触发时机 | body 核心字段 |
|----------|---------|---------------|
| `playerJoin` | 玩家登录代理端 | player |
| `playerLeave` | 玩家退出代理端 | player |
| `playerJoinServer` | 玩家进某子服 (含换服) | player, target, origin, success |
| `playerChat` | 玩家发聊天消息 | player, server, message |
| `playerExecuteCmd` | 玩家执行命令 | player, server, message |
| `playerWasKicked` | 玩家被子服踢出 | player, server, reason |
| `proxyPing` | 代理端被 ping | ip |
| `playerClickChat` | 玩家点击聊天组件 (插件通道触发) | — |

body 里都带 `eventid` (唯一 ID) 和 `time` (毫秒时间戳)

---

## 数据库表结构

表名都带 `table_prefix` 前缀 (默认 `asmonitor_`)

### players

| 字段 | 类型 | 说明 |
|------|------|------|
| uuid | VARCHAR(36) PK | 玩家 UUID |
| name | VARCHAR(16) | 玩家名 |
| ip | VARCHAR(45) | 最近登录 IP |
| current_server | VARCHAR(64) | 当前所在子服 |
| online | BOOLEAN | 是否在线 |
| skin | VARCHAR(64) | 皮肤 UUID (预留) |
| fjoin_time | BIGINT | 首次登录时间 |
| ljoin_time | BIGINT | 最近登录时间 |
| leave_time | BIGINT | 最近退出时间 |
| update_time | BIGINT | 记录更新时间 |

### chat

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK AUTO_INCREMENT | — |
| name | VARCHAR(16) | 玩家名 |
| uuid | VARCHAR(36) | 玩家 UUID |
| ip | VARCHAR(45) | IP |
| servername | VARCHAR(64) | 子服名 |
| time | BIGINT | 消息时间 |
| msgid | VARCHAR(64) | 消息唯一 ID |
| content | TEXT | 消息内容 |

cmd_log / webhook_log / admins 这几张表的字段看 `DatabaseManager.createTables()` 或 Dashboard System 页的表结构展示就行

---

## License & Contact

License `GPL-3.0` | wx/qq `AerMini` | mail `aermini@mcyyt.cn`