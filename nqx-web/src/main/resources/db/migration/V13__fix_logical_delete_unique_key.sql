-- ============================================================================
-- V13：修复「逻辑删除 + 唯一索引」的冲突
-- ----------------------------------------------------------------------------
-- 【问题】
--   sys_user 上的唯一索引是 uk_username(username, deleted)，
--   sys_role 上是 uk_role_code(role_code, deleted)。
--   而逻辑删除只是把 deleted 从 0 改成 1，于是同一个账号/角色标识
--   **第二次**被删除时，库里会同时出现两行 (username, 1)，
--   直接撞唯一键并抛 DuplicateKeyException（表现为 500「系统繁忙」）。
--
--   也就是说这条完全正常的运维路径必然失败：
--       创建账号 A → 删除 A → 再创建同名账号 A → 再删除 A   ← 这里 500
--   而且失败信息对管理员毫无意义（"系统繁忙"）。
--
-- 【为什么不能用"已删除时写本行 id"的函数索引】
--   最直觉的修法是函数索引：
--       ADD UNIQUE KEY uk_username (username, (IF(deleted = 0, 0, id)))
--   但 MySQL 明确拒绝（实测）：
--       ERROR 3754: Functional index 'uk_username' cannot refer to an auto-increment column.
--   即函数索引不允许引用自增列。这条路走不通。
--
-- 【本迁移的做法】
--   把 deleted 从 TINYINT 放宽成 BIGINT，让业务层的删除操作可以写
--   **本行 id** 而不是 1：
--       UPDATE sys_user SET deleted = <id> WHERE id = <id> AND deleted = 0
--
--   于是唯一索引 (username, deleted) 的语义变成：
--       · 未删除的行：deleted = 0     → 同一 username 全局唯一（需求成立）
--       · 已删除的行：deleted = 行 id → 每行都不同，同一 username 可无限次删除
--
--   这个方法不需要新增列、不需要改索引，且**用户名本身保持原样** ——
--   这点很重要：审计时你必须能看出"这个被删的账号原来叫什么"。
--
--   为什么不用「删除时把用户名改名成 原名#del#<id>」这种业务层规避：
--   它确实能绕开撞键，但代价是把 username 这个审计字段改坏了。
--   实测中库里留下了 sys_tester#del#4 / sys_tester#del#9 这类记录，
--   既看不出原始名字，也让"查这个账号的历史"变得困难。
--   **能用表结构解决的事，不应该让业务代码去迁就。**
--
-- 【影响范围】
--   只有"同一个业务键可能被删除多次"的表才需要这样处理，本项目有两张：
--     · sys_user（管理员账号确实会被反复增删）
--     · sys_role（角色标识同理）
--   另外两张带 deleted 的唯一索引表（app_user.uk_openid、
--   product_review.uk_order_product）当前**没有删除入口**，
--   不会走到这条路径，因此本次不动它们 —— 改动越小越安全。
--   若将来新增它们的删除功能，按同样的方式处理即可。
--
-- 【兼容性】
--   BIGINT 完全兼容原有的 0/1 语义：
--     · MyBatis-Plus 的逻辑删除过滤条件仍是 `WHERE deleted = 0`，不受影响；
--     · `IFNULL(deleted,0)`、`COUNT(*) WHERE deleted = 0` 这类写法照常工作。
-- ============================================================================

USE `nqx`;

ALTER TABLE `sys_user`
    MODIFY COLUMN `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删，已删时存本行 id（保证唯一索引可重复删除）';

ALTER TABLE `sys_role`
    MODIFY COLUMN `deleted` BIGINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删，已删时存本行 id（保证唯一索引可重复删除）';

-- ---------------------------------------------------------------------------
-- 修复历史数据：还原被「改名规避」改坏的审计字段，并让已删除行各自占一个唯一值
--
-- ⚠️ **这两步的顺序不能颠倒**，否则迁移自己就会撞唯一索引。
--   这不是假设 —— 第一版写反了，实测直接失败：
--
--       SQL State  : 23000
--       Error Code : 1062  Duplicate entry
--       Script V13__fix_logical_delete_unique_key.sql failed
--
--   原因：若先还原用户名，此刻库里可能同时存在
--   (sys_tester, deleted=1) 与 (sys_tester#del#4, deleted=1)，
--   把后者改回 sys_tester 就立刻与前者冲突。
--
--   先写 deleted = id，则每个已删除的行都占有一个不同的 deleted 值，
--   之后无论把 username 改成什么都不会冲突。
--
--   语句本身都是对的，错的只是它们之间的先后关系 ——
--   这类"数据修复脚本"尤其要把顺序写清楚。
--
-- 另注：MySQL 的 DDL **不参与事务、会自动提交**，所以上面两条 ALTER
--   在第一次失败时已经生效了。本迁移因此必须能重复执行（MODIFY COLUMN
--   改成同样的类型是幂等的），否则修完顺序也跑不起来。
-- ---------------------------------------------------------------------------

-- 第一步：让每个已删除的行占有一个唯一值（本行 id）
UPDATE `sys_user`
SET `deleted` = `id`
WHERE `deleted` <> 0 AND `deleted` <> `id`;

UPDATE `sys_role`
SET `deleted` = `id`
WHERE `deleted` <> 0 AND `deleted` <> `id`;

-- 第二步：把被改坏的用户名/角色标识还原成原始值（现在不再有冲突风险）
UPDATE `sys_user`
SET `username` = SUBSTRING_INDEX(`username`, '#del#', 1)
WHERE `username` LIKE '%#del#%';

UPDATE `sys_role`
SET `role_code` = SUBSTRING_INDEX(`role_code`, '#del#', 1)
WHERE `role_code` LIKE '%#del#%';
