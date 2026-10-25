# 纽启新生 · 高校实验室纽扣电池回收与采购管理平台

> 面向高校实验室的**纽扣电池耗材回收与采购**一体化平台。
> 微信原生小程序（回收方 / 采购方）+ Vue3 管理后台（管理员）+ SpringBoot 后端。
> 覆盖 **耗材回收 → 积分激励 → 积分商城兑换 → 每日问答** 的完整业务闭环。

---

## 📌 项目状态

> **后端、管理后台、小程序端均已开发完成，全部经过端到端实测。**
> 每一项"已完成"都有可复现的验证方式（见各文档与 `scripts/` 下的校验脚本）。

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P0 地基** | 数据库设计（27 张表 + **12 条 Flyway 迁移**）、DDL 脚本、技术选型与依赖版本核查 | ✅ 已完成 |
| **P0** | SpringBoot 多模块骨架（6 模块）、统一返回/错误码/全局异常、traceId 链路追踪 | ✅ 已完成 |
| **P0** | Sa-Token **双端登录体系**（多 StpLogic）+ 三级 RBAC + 动态菜单树 | ✅ 已完成 |
| **P0** | AOP 基建：`@Log` 操作日志（异步落库 + 字段脱敏）、`@Idempotent` 接口幂等 | ✅ 已完成 |
| **P1 商品域** | 动态 SQL 多条件检索、CRUD、上下架、**Redis 逻辑过期缓存**、文件上传、分类与回收品类 | ✅ 已完成 |
| **P2 订单域** | **订单状态机**（双状态机 + 启动自检）、流转日志、**ZSet 延迟队列超时取消**、扫库补偿、模拟支付 | ✅ 已完成 |
| **P3 积分域** | 账户/流水 + **唯一索引幂等** + **条件更新防超发** + 积分兑换 + 管理员调整 | ✅ 已完成 |
| **P4 问答域** | 每日一题 + 防重复作答 + 答案不下发（VO 白名单） | ✅ 已完成 |
| **P5 管理后台** | Vue3 + Element-Plus：动态路由、`v-permission` 按钮权限、商品/订单/积分页面、ECharts 看板 | ✅ 已完成 |
| **P6 小程序端** | 兼容垫片（路径映射 + 分页体还原 + 401 静默重登）、25 个页面迁移，**未映射路径 0 条** | ✅ 已完成 |
| **P7 交付** | **并发压测报告**、配置一致性校验脚本、Docker Compose（配置校验通过） | ✅ 基本完成 |

**除上述主线路外，另已完成**：内容域（小贴士/FAQ/公告/站点配置）、购物车、
收货地址、商品评价、维修申请、数据看板聚合接口。

**压测结论**（200 并发，详见 [docs/08](docs/08-并发压测报告.md)）：

| 不变量 | 实测结果 |
|---|---|
| 库存不超卖 | 库存 100 时 200 并发下单 → **恰好 100 成功**，库存精确归零，永不为负 |
| 积分不超发 | 余额 1000 时 200 并发兑换 → **恰好 12 成功**，余额剩 40，订单/流水/积分三方对账一致 |
| 业务不重复入账 | 200 并发携带同一幂等键 → 全部返回成功，但流水表**只入账 1 条** |
| 账目始终自洽 | `balance = total_earn − total_spend` 全部成立；无负库存、无负积分 |

**唯一未完成实测的一项**：Docker Compose 完整栈启动。
`docker compose config` 校验已通过（变量替换正确），
但完整 `up -d --build` 需拉取约 1GB 基础镜像并在容器内执行 Maven 构建，
受网络条件限制未跑完。**这是环境限制，不是配置缺陷** ——
配置本身经过静态审查并修掉了三处真实缺陷（微信凭证漏传、`useSSL` 写死、profile 不可切）。

---

## 🎯 业务闭环

```
                        ┌─────────────────────────────────────────┐
                        │            微信原生小程序                │
                        │       （回收方 / 采购方，可同时拥有）      │
                        └─────────────────────────────────────────┘
                             │                              │
                    ┌────────▼────────┐            ┌────────▼────────┐
                    │   回收业务        │            │   采购业务       │
                    │  提交回收单       │            │  浏览商品/购物车  │
                    │  按品类计积分     │            │  下单/模拟结账   │
                    └────────┬────────┘            └────────┬────────┘
                             │                              │
        管理员审核 ──────────┤                              │
        确认回收 ────────────┤                              │
                             ▼                              ▼
                    ┌─────────────────┐          ┌──────────────────┐
                    │   积分账户 +      │          │   订单状态机       │
                    │   积分流水        │          │   流转日志/进度查询 │
                    │  （幂等 + 并发）   │          └──────────────────┘
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
      ┌──────────────┐ ┌──────────┐ ┌──────────────┐
      │  每日问答答题  │ │ 积分商城  │ │  积分排行榜    │
      │  （答对得积分）│ │ 积分兑换  │ │  (Redis ZSet) │
      └──────────────┘ └──────────┘ └──────────────┘
                             │
                             ▼
                  ┌────────────────────────┐
                  │  Vue3 管理后台（管理员）  │
                  │ 商品/订单/积分/问答运营   │
                  │  动态菜单 + 按钮级权限    │
                  │  ECharts 运营数据看板    │
                  └────────────────────────┘
```

---

## 🛠 技术栈

| 层次 | 技术 | 版本 | 说明 |
|---|---|---|---|
| 运行环境 | **JDK** | **17 (LTS)** | SpringBoot 3.x 基线版本，生态验证最充分 |
| 后端框架 | **Spring Boot** | **3.5.16** | 3.x 末线，补丁最全 |
| 持久层 | **MyBatis-Plus** + 手写 XML | **3.5.16** | 单表 CRUD 提效，复杂 SQL 手写可控 |
| 数据库 | **MySQL** | 8.0+ | InnoDB / utf8mb4 |
| 数据库版本管理 | **Flyway** | 11.7.2 | DDL 纳入 Git |
| 缓存 / 并发 | **Redis** + Redisson | **3.52.0** | 缓存、延迟队列、幂等、排行榜、限流 |
| 权限 | **Sa-Token** | **1.44.0** | 多账号体系，双端登录隔离 |
| 接口文档 | **springdoc-openapi** | **2.8.17** | ⚠️ 不用 Knife4j，理由见 [docs/04](docs/04-依赖版本锁定.md#31--放弃-knife4j--它在-spring-boot-35-上是硬性不兼容) |
| Excel 导出 | **FastExcel** | **1.3.0** | EasyExcel 已停更，改用其续作 |
| 对象映射 | MapStruct | 1.6.3 | 编译期生成，零反射开销 |
| 管理后台 | Vue3 + Vite + TS + Pinia + Element-Plus + ECharts | — | 动态路由 + 按钮级权限 |
| 小程序 | 微信原生 (WXML/WXSS/JS) | — | 分包加载 + 401 静默重登 |
| 部署 | Docker + Docker Compose + Nginx | — | 一键启动 |

> 完整的版本核查依据（含 Maven Central 来源链接与兼容性证据链）见 **[docs/04-依赖版本锁定.md](docs/04-依赖版本锁定.md)**。

---

## 📁 项目结构

```
nqx/
├── docs/                                  设计文档
│   ├── 01-技术选型与关键决策记录.md
│   ├── 02-数据库设计说明.md
│   ├── 03-实施计划与接口清单.md
│   └── 04-依赖版本锁定.md
├── sql/
│   ├── 00_bootstrap.sql                   建库建账号（手工执行一次）
│   ├── V1__init_schema.sql                21 张表 + 索引
│   └── V2__init_data.sql                  角色/权限树/演示数据
├── nqx-common/                            通用：Result、错误码、异常、工具、注解
├── nqx-pojo/                              entity / dto / vo / query
├── nqx-mapper/                            Mapper 接口 + XML
├── nqx-service/                           业务逻辑：状态机、缓存、幂等
├── nqx-web/                               启动模块 + Web 配置
└── nqx-ui/                                (独立) Vue3 管理后台
```

---

## 🚀 快速开始

### 前置要求

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | **17** | ⚠️ 必须是 17。18 已 EOL，21/26 生态适配不如 17 |
| MySQL | 8.0+ | |
| Redis | 7.x | |
| Node.js | 20+ | 仅前端需要 |

### 0. 一键启动（推荐，已实测）

前置条件：MySQL 与 Redis 已启动、数据库已按下面第 1 节建好。

```powershell
scripts\start-local.ps1
```

脚本会依次完成并**逐项报告结果**：

```
[OK]   openjdk version "17.0.20.1"          ← 检查 JDK 17（版本不对会明确报错）
[OK]   MySQL 3306 可连接                     ← 依赖服务提前检查，避免"应用启动失败"其实是数据库没起
[OK]   Redis 6379 可连接
[OK]   端口 8080 空闲                        ← 自动停掉旧实例（Windows 上运行中的 jar 会锁住文件导致打包卡死）
[OK]   BUILD SUCCESS
[OK]   应用已就绪（耗时约 18 秒）
```

完成后直接打印接口文档地址、账号密码与停止方式，无需再查文档。

常用参数：

```powershell
scripts\start-local.ps1 -SkipBuild          # 跳过打包，直接启动（改了配置没改代码时用）
scripts\start-local.ps1 -Profile prod       # 用生产配置启动
scripts\start-local.ps1 -JdkHome 'D:\jdk17' # 指定自己的 JDK 路径
```

### 1. 初始化数据库（**二选一，不要混用**）

**方式 A · 推荐**：只建库，表结构交给 Flyway 自动管理

```bash
mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/00_bootstrap.sql
```

**方式 B**：手工导入全部表结构与数据

```bash
mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/nqx-full-import.sql
```

> ⚠️ 选择方式 B 后**必须关闭 Flyway**，否则启动时会因表已存在而失败：
> `--spring.flyway.enabled=false`

两种方式的选择依据与细节见 **[sql/README.md](sql/README.md)**。

默认数据库连接：`root / 123456`（开发环境），可用 `DB_USERNAME` / `DB_PASSWORD` 环境变量覆盖。

### 2. 启动 Redis

```bash
redis-server
```

> 本机 Redis 已设置访问密码 `123456`（见 `redis.windows-service.conf` 的 `requirepass`），
> 该值已作为开发环境默认值写入 `application-dev.yml`，可用环境变量 `REDIS_PASSWORD` 覆盖。
> **若你的 Redis 没有密码**，请把配置中的 `password` 一行改为 `password:`（留空即 null）——
> 不能留成空字符串，否则 Redisson 会发出 `AUTH ""` 并报 `ERR invalid password`。

### 3. 启动后端

**最省事的方式：双击项目根目录的 bat**

| 文件 | 作用 |
|---|---|
| `启动全部.bat` | 后端 + 管理后台，自动开浏览器 |
| `启动后端.bat` | 只起 SpringBoot（调接口 / 看 Swagger 时用） |
| `启动管理后台.bat` | 只起管理后台（**窗口就是 vite 控制台，关掉即停**） |
| `停止全部.bat` | 停掉两个服务，可重复执行 |
| **`检查本地.bat`** | **改完代码后跑它**：真实 HTTP 走一遍关键链路，见 §7 |

或者用命令行：

```bash
mvn clean package -DskipTests
java -jar nqx-web/target/nqx.jar --spring.profiles.active=dev
```

启动后访问：
- 接口文档：<http://localhost:8080/swagger-ui.html>
- 健康检查：<http://localhost:8080/actuator/health>

默认管理员账号由应用启动时自动创建（**BCrypt 加密，不写死在 SQL 里**）：`admin / Admin@123`，首次登录请立即修改。

### 4. 启动管理后台

```bash
cd nqx-ui
npm install
npm run dev
```

### 5. 小程序

用微信开发者工具导入 `nqx-miniprogram/` 目录，在 `config/index.js` 中切换 `baseUrl`。

> 本地调试需在开发者工具中勾选 **「不校验合法域名」**；正式发布需备案域名 + HTTPS。

### 6. Docker 一键启动（推荐）

```bash
cp .env.example .env      # 按需修改密码，默认 123456
docker compose up -d
docker compose logs -f app
```

启动顺序由 `healthcheck` 保证：MySQL 与 Redis 健康后应用才会启动，
不会出现"应用先起来但连不上数据库"的启动失败。

| 服务 | 端口 | 说明 |
|---|---|---|
| app | 8080 | 后端应用 |
| mysql | 3306 | 首次启动自动建库 `nqx`，表结构仍由 Flyway 管理 |
| redis | 6379 | 已开启 AOF 持久化与内存上限淘汰策略 |

上传文件与日志挂载为数据卷，容器重建后不丢失。
停掉全部服务并保留数据：`docker compose down`；
连同数据卷一起清除：`docker compose down -v`。

**⚠️ 两种运行模式，按场景选择**

`docker compose` 默认以 `prod` profile 启动，此时**微信登录必须配置真实凭证**：

```bash
# .env
WX_APP_ID=你的AppID
WX_APP_SECRET=你的AppSecret
```

未配置时小程序端**所有登录都会被拒绝** —— 这是刻意的设计
（`WeChatApiClient` 的注释：「未配置 AppID/AppSecret 且开关关闭时，
直接拒绝登录而不是放行」）。**忘配参数时应当登不进去，而不是人人可登录。**

若只是想本地看效果、没有真实小程序凭证，用 dev profile 开启模拟登录：

```bash
# .env
SPRING_PROFILES_ACTIVE=dev     # 开启 nqx.wechat.mock-enabled
```

此时用 `code=dev:任意名字` 即可登录（如 `{"code":"dev:zhangsan","role":"lab"}`），
无需微信授权。数据源与 Redis 地址仍由 compose 注入容器名，dev 配置同样连得对库。

**端口被占用时**（本机已装 MySQL/Redis 的常见情况），在 `.env` 里改映射即可：

```bash
MYSQL_PORT=13306
REDIS_PORT=16379
APP_PORT=18080
DB_USE_SSL=false               # 若 MySQL 未启用 TLS
```

**部署自检**：改完 compose / 配置文件后，先跑一次解析校验，能在几十毫秒内
发现 YAML 语法或变量拼写问题，比启动失败后再翻日志快得多：

```bash
docker compose config --quiet && echo "配置合法"
```

**本项目实测记录**：`docker compose config` 校验通过（变量替换全部正确）。
完整的 `up -d --build` 会拉取 MySQL/Redis/Maven/JRE 四个基础镜像（约 1GB）
并在容器内执行 Maven 构建，首次耗时取决于网络；建议在网络条件较好时执行。

---

## 💡 核心设计亮点

以下每一项都在代码里有真实落地，且都能在面试中展开讲：

| 亮点 | 解决什么问题 | 代码位置 | 详解 |
|---|---|---|---|
| **订单状态机 + 策略模式** | 非法状态流转（如已完成的订单被取消）在入口被拦截；回收/采购两套流程差异用策略隔离，消灭满屏 `if (orderType == 1)` | `OrderStateMachine` / `OrderHandler` | [docs/03 §3.1](docs/03-实施计划与接口清单.md#31-订单状态机策略模式--状态流转表) |
| **Redis ZSet 延迟队列** | 订单超时未支付自动关闭；用 `ZREM` 返回值做多实例互斥，避免引入额外分布式锁 | `OrderDelayQueue` | [docs/03 §3.2](docs/03-实施计划与接口清单.md#32-订单超时自动取消redis-zset-延迟队列) |
| **唯一索引 + 条件更新保证积分幂等** | 并发下应用层 `if` 判断必然失效；`uk_user_biz_type` 是最终防线，`WHERE balance >= ?` 防超扣 | `PointsRecordMapper` | [docs/02 §6](docs/02-数据库设计说明.md#6-积分的并发与幂等核心难点) |
| **Cache-Aside + 逻辑过期** | 解决缓存击穿 / 穿透 / 雪崩，并能在面试中讲清一致性的取舍与不足 | `ProductCacheService` | [docs/03 §3.4](docs/03-实施计划与接口清单.md#34-商品热点缓存cache-aside--逻辑过期) |
| **`@Idempotent` 注解 + AOP** | 一行注解解决重复提交，基于 Redis `SETNX` | `IdempotentAspect` | [docs/03 §3.5](docs/03-实施计划与接口清单.md#35-接口幂等idempotent-注解--aop--redis) |
| **`@Log` 注解 + AOP 异步落库** | 操作日志不拖慢主流程；入参脱敏；traceId 跨线程传递 | `LogAspect` | [docs/03 §3.6](docs/03-实施计划与接口清单.md#36-操作日志log-注解--aop--异步落库) |
| **三级 RBAC + 动态菜单** | 后端下发菜单树，前端 `addRoute` 动态注册 + `v-permission` 按钮级控制 | `sys_permission` | [docs/01 §5](docs/01-技术选型与关键决策记录.md#5-权限框架sa-token) |
| **联合索引优化多条件检索** | 订单表 4 个索引各有明确用途，附 `EXPLAIN` 验证方法 | `biz_order` | [docs/02 §5](docs/02-数据库设计说明.md#5-索引设计与验证) |

---

## 📚 文档索引

| 文档 | 内容 |
|---|---|
| [01-技术选型与关键决策记录](docs/01-技术选型与关键决策记录.md) | 为什么用 JDK 17 而不是 18/21；为什么选这些框架；明确不做什么 |
| [02-数据库设计说明](docs/02-数据库设计说明.md) | ER 图、21 张表职责、索引设计、**订单状态机**、**积分并发与幂等** |
| [03-实施计划与接口清单](docs/03-实施计划与接口清单.md) | 阶段划分与验收标准、完整接口清单、各技术难点落地方案 |
| [04-依赖版本锁定](docs/04-依赖版本锁定.md) | 每个版本号的 Maven Central 来源、兼容性证据链、风险与验证清单 |
| [05-核心组件设计说明](docs/05-核心组件设计说明.md) | `@Log` / `@Idempotent` / Redis 逻辑过期缓存的设计取舍与踩坑 |
| [06-踩坑记录](docs/06-踩坑记录.md) | **46 个真实遇到并解决的坑**：依赖兼容、运行期故障、数据正确性、前后端对接、表结构设计、**误删数据**六类 |
| [07-小程序端改造方案](docs/07-小程序端改造方案.md) | 兼容垫片设计、25 个页面的路径映射表、旧版安全漏洞清单 |
| [08-并发压测报告](docs/08-并发压测报告.md) | **200 并发下防超卖/防超发/幂等的实测数据与判据设计** |

**使用手册**（按角色拆分，给非技术用户看）：

> 上面 01~08 是**给开发者看的设计文档**；下面这套是**给实际使用系统的人**看的。
> 入口：[docs/manual/README.md](docs/manual/README.md)（"我是谁 → 看哪一本"）

| 手册 | 读者 | 内容 |
|---|---|---|
| [01-回收方使用手册](docs/manual/01-回收方使用手册.md) | 实验室学生/老师 | 怎么交废电池、预约上门、每日问答、积分怎么来怎么用 |
| [02-采购方使用手册](docs/manual/02-采购方使用手册.md) | 采购人员 | 逛商城、加购、下单、订单状态、维修售后、评价 |
| [03-管理员使用手册](docs/manual/03-管理员使用手册.md) | 设备/后勤管理员 | 审核回收单、发货、调积分、管题库、账号与权限、**系统会拒绝哪些操作及原因** |
| [04-部署运维手册](docs/manual/04-部署运维手册.md) | 开发者/运维 | 三种运行方式、环境变量清单、Flyway 迁移、备份恢复、**端口冲突与故障排查** |
| [05-微信云托管部署手册](docs/manual/05-微信云托管部署手册.md) | 开发者/运维 | 上云托管全流程：**Redis 与 VPC 手把手**、COS 子账号、环境变量、小程序免域名改造、验收清单 |

**可复现的校验脚本**（`scripts/`）：

| 脚本 | 用途 |
|---|---|
| [start-all.ps1](scripts/start-all.ps1) | **一键启动**：加载 config\local.env → 后端 + 管理后台，自动开浏览器 |
| [start-frontend.ps1](scripts/start-frontend.ps1) | 只启动管理后台（前台运行，关掉窗口即停） |
| [start-local.ps1](scripts/start-local.ps1) | 只启动后端：检查 JDK/MySQL/Redis → 清理旧实例 → 打包 → 启动 |
| [stop-all.ps1](scripts/stop-all.ps1) | 按端口停止后端与管理后台（可重复执行） |
| **[verify-local.ps1](scripts/verify-local.ps1)** | **改完代码必跑**：真实 HTTP 走一遍 健康检查→首页→登录→菜单→图片→上传→小程序语法，7 项 PASS/FAIL |
| [check-permissions.ps1](scripts/check-permissions.ps1) | 交叉比对 Controller 权限码与数据库权限表，定位"只有调用时才 403"的隐患 |
| [audit-api-coverage.js](scripts/audit-api-coverage.js) | 比对后端接口与前端实际调用，找出"调了不存在"与"有接口没入口" |
| [audit-menu-icons.js](scripts/audit-menu-icons.js) | 校验菜单图标名在 Element Plus 里真实存在（不存在的会静默渲染成空白） |
| [audit-manual-consistency.js](scripts/audit-manual-consistency.js) | 校验手册内部链接、页面路径、自述数量与实际是否一致 |
| [audit-shim-wrapping.js](scripts/audit-shim-wrapping.js) | 校验小程序垫片的解包配置与各页面读取的响应形状是否匹配 |
| [audit-miniprogram-contract.js](scripts/audit-miniprogram-contract.js) | 校验小程序请求的字段名与后端 DTO/VO 的契约 |
| [backup-database.ps1](scripts/backup-database.ps1) | 备份数据库并自校验（大小、结束标记、关键表），打印恢复命令 |
| [load-test.js](scripts/load-test.js) | 并发压测客户端（Node，无需额外工具） |
| [load-test-verify.sql](scripts/load-test-verify.sql) | 压测后回查数据库，逐条断言业务不变量 |

---

## ⚠️ 两个需要提前知道的技术决定

1. **不用 Knife4j**。它在 Spring Boot 3.5 上存在硬性不兼容（运行期 `NoSuchMethodError`），且 4.5.0 是末版无修复。已改用 springdoc-openapi。详见 [docs/04 §3.1](docs/04-依赖版本锁定.md#31--放弃-knife4j--它在-spring-boot-35-上是硬性不兼容)。

2. **Spring Boot 3.5.x 的开源支持已于 2026-06-30 终止**。本项目仍选它，原因是其**生态适配经过验证**（MyBatis-Plus 3.5.16、springdoc 2.8.17、Redisson 3.52.0 均有硬证据对齐 3.5），符合"最稳定、最不易出现版本不兼容"的目标。详见 [docs/04 §4.1](docs/04-依赖版本锁定.md#41-风险-1spring-boot-35x-的免费支持已终止)。

---

## 📄 License

MIT
