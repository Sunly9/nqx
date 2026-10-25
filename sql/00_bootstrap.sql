-- ============================================================================
-- 纽启新生 (NQX) — 数据库引导脚本
-- ----------------------------------------------------------------------------
-- 用法（只会创建数据库，不会建表）：
--     mysql -uroot -p123456 --default-character-set=utf8mb4 < sql/00_bootstrap.sql
--
-- 执行完直接启动应用即可 —— **表结构与初始数据由 Flyway 在应用启动时自动创建**，
-- 迁移脚本位于 nqx-web/src/main/resources/db/migration/：
--     V1__init_schema.sql   21 张表 + 索引
--     V2__init_data.sql     角色 / 权限树 / 演示数据
--
-- 为什么推荐这种方式，而不是手工导一份建表 SQL？
--   DDL 纳入版本管理后，每次表结构变更都会留下一条不可篡改的迁移记录
--   （flyway_schema_history 表）。这样才能避免最常见的团队事故：
--   「代码里加了字段，但线上库没人加」。
--
-- 如果你确实想手工建表，请改用 sql/nqx-full-import.sql，
-- 并记得关闭 Flyway（--spring.flyway.enabled=false），否则会因表已存在而启动失败。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 创建数据库
-- ---------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS `nqx`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------------------
-- 2. 可选：创建最小权限的应用账号
--    开发环境为了省事，application-dev.yml 默认用 root 连接；
--    生产环境**强烈建议**改用下面这个专用账号，避免应用持有 root 权限。
--
--    创建后，把 nqx-web/src/main/resources/application-dev.yml 中的
--        username: ${DB_USERNAME:root}
--        password: ${DB_PASSWORD:123456}
--    改为 nqx / nqx@2026，或直接用环境变量覆盖。
-- ---------------------------------------------------------------------------
CREATE USER IF NOT EXISTS 'nqx'@'%' IDENTIFIED BY 'nqx@2026';

-- 只授予业务必需的权限，不授予 GRANT / SUPER / FILE 等管理权限
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, INDEX, REFERENCES
    ON `nqx`.* TO 'nqx'@'%';

FLUSH PRIVILEGES;

-- ---------------------------------------------------------------------------
-- 3. 校验
-- ---------------------------------------------------------------------------
SELECT SCHEMA_NAME             AS `database`,
       DEFAULT_CHARACTER_SET_NAME AS `charset`,
       DEFAULT_COLLATION_NAME  AS `collation`
FROM information_schema.SCHEMATA
WHERE SCHEMA_NAME = 'nqx';

SELECT '数据库 nqx 已就绪，现在可以启动应用，Flyway 将自动建表' AS `提示`;
