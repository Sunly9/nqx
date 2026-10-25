-- ============================================================================
-- V4：统一「操作人类型」的取值约定
-- ----------------------------------------------------------------------------
-- 【问题】
--   V1 建表时，三处记录操作人类型的列注释不一致：
--       order_flow.operator_type        1用户   2管理员  3系统
--       sys_operation_log.user_type     1管理员 2小程序用户
--       sys_login_log.user_type         1管理员 2小程序用户
--   而 Java 侧 CommonConstants 统一采用「1小程序用户 2管理员 3系统」。
--   结果是：管理员登录后，sys_operation_log 存 2、sys_login_log 存 1（被硬编码），
--   同一个管理员在两张表里的类型值不同，做统计时必然出错。
--
-- 【修正】
--   统一为「1小程序用户 / 2管理员 / 3系统」，与 order_flow 及 Java 常量保持一致。
--   Java 代码无需改动，只需修正列注释并回填 sys_login_log 中的错误数据。
--
-- 【为什么能直接全表回填】
--   小程序端尚未实现，sys_login_log 中所有记录都是管理员产生的，
--   且旧代码把它们一律硬编码为 1，因此 `SET user_type = 2 WHERE user_type = 1`
--   是精确且完整的修正，不会误伤小程序用户数据。
--   若这张表已存在真实的小程序用户登录记录，则必须改用其他可区分条件，
--   绝不能这样无条件回填 —— 这也是迁移脚本必须先想清楚数据现状再写的原因。
-- ============================================================================

ALTER TABLE `sys_operation_log`
    MODIFY COLUMN `user_type` TINYINT NOT NULL DEFAULT 1
        COMMENT '操作人类型 1小程序用户 2管理员 3系统';

ALTER TABLE `sys_login_log`
    MODIFY COLUMN `user_type` TINYINT NOT NULL DEFAULT 1
        COMMENT '用户类型 1小程序用户 2管理员';

UPDATE `sys_login_log` SET `user_type` = 2 WHERE `user_type` = 1;
