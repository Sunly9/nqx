# SQL 脚本说明

本目录提供**两条互斥**的建库路径，请按需选择其一，不要混用。

---

## 方式 A（推荐）：只建库，表结构交给 Flyway

```bash
mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/00_bootstrap.sql
```

这个脚本**只做一件事**：创建数据库 `nqx`（以及一个可选的最小权限应用账号）。

建完直接启动应用，Flyway 会在启动时自动执行：

| 迁移脚本 | 内容 |
|---|---|
| `V1__init_schema.sql` | 21 张表 + 全部索引 + 唯一约束 |
| `V2__init_data.sql` | 3 个角色、权限菜单树、角色权限映射、商品/回收品类/问答题演示数据 |
| `V3__add_trace_id_to_operation_log.sql` | 操作日志增加 `trace_id`，便于从数据库记录反查完整调用链 |
| `V4__unify_operator_type_comment.sql` | 统一「操作人类型」取值约定并回填历史数据 |
| `V5__align_with_miniprogram.sql` | 对齐小程序前端：新增评价/维修/FAQ/公告/站点配置表，订单增加回收重量与快递单号 |
| `V6__fix_unique_key_and_unify_pricing.sql` | 修复 `repair_apply` 唯一键漏带 `deleted`；回收计价由「按个数」统一为「按重量」并细化到品类单价 |

这 6 条迁移本身就是 Flyway 价值的演示：
**V1 执行过之后就绝不能改**（`validate-on-migrate` 会校验和并让启动失败），
所以 V3 加字段、V4 改注释与回填数据、V5 扩模块、V6 修正设计缺陷，
全都作为新迁移追加 —— 每套环境（开发/测试/生产）执行的都是同一串脚本，
不会出现「代码里有这个字段、线上库没有」的经典事故。

脚本真实位置：`nqx-web/src/main/resources/db/migration/`

**为什么推荐这种方式？**

因为 DDL 被纳入了版本管理。每次表结构变更都会在 `flyway_schema_history` 表里留下一条
带校验和的记录，且历史脚本一旦执行过就不允许再修改（`validate-on-migrate: true`）。
这能杜绝团队协作中最常见的事故：**代码里加了字段，但线上库没人加。**

---

## 方式 B：手工导入全部内容

```bash
mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/nqx-full-import.sql
```

这个脚本一次性完成：`CREATE DATABASE` + 建 21 张表 + 写入全部初始数据。

> ⚠️ **用方式 B 之后必须关闭 Flyway**，否则应用启动时 Flyway 会尝试重新执行
> V1/V2，因表已存在而报 `Table 'xxx' already exists`。
>
> 关闭方式（任选其一）：
> - 启动参数：`--spring.flyway.enabled=false`
> - `application-dev.yml` 中把 `spring.flyway.enabled` 改为 `false`

`nqx-full-import.sql` 是**自动拼接生成**的，请勿手工编辑它 ——
修改表结构请改 `db/migration/` 下的迁移脚本，然后重新生成。

如果只是想让 Flyway「认账」而不重复建表，也可以在手工导入后执行一次：

```sql
-- 让 Flyway 认为 V1、V2 已经执行过（baseline 到版本 2）
-- 注意：这需要手工维护 flyway_schema_history 表，不推荐新手操作
```

---

## 连接配置

开发环境默认连接（见 `nqx-web/src/main/resources/application-dev.yml`）：

| 项 | 默认值 | 可用环境变量覆盖 |
|---|---|---|
| host | `localhost` | `DB_HOST` |
| port | `3306` | `DB_PORT` |
| database | `nqx` | `DB_NAME` |
| username | `root` | `DB_USERNAME` |
| password | `123456` | `DB_PASSWORD` |

> 开发环境用 root 是为了省事。**生产环境务必改用最小权限账号**
> （`00_bootstrap.sql` 里已提供 `nqx` 账号的创建语句），
> 绝不要让应用在生产环境持有 root 权限 —— 一旦存在 SQL 注入，
> root 意味着攻击者可以读写整个实例的任意数据库。

---

## 字符集注意事项

- 所有脚本均为 **UTF-8 无 BOM**。带 BOM 时 MySQL 客户端会把 BOM 当作 SQL 语句的一部分，
  报出难以理解的语法错误。
- 导入时建议显式指定 `--default-character-set=utf8mb4`，否则中文注释与数据可能乱码。
- MySQL 需为 **8.0 及以上**：建表语句使用了 `utf8mb4_0900_ai_ci` 排序规则，
  该规则在 MySQL 5.7 中不存在。
