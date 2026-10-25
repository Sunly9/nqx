-- ============================================================================
-- V8：补充运营可配置项
-- ----------------------------------------------------------------------------
-- 旧版把「新用户注册奖励积分」硬编码在 Node 代码里，运营想调整必须改代码重新部署。
-- 统一挪到 site_setting 表，后台改一个值即可生效（读取见 ContentService#getIntSetting）。
--
-- 用 INSERT ... ON DUPLICATE KEY UPDATE 而不是裸 INSERT：
-- 本表 setting_key 有唯一索引，裸 INSERT 在重复执行时会报错；
-- 加上 ON DUPLICATE 后脚本可安全重放，也只更新说明文字、不覆盖运营已改过的值。
-- ============================================================================

INSERT INTO `site_setting` (`setting_key`, `setting_value`, `remark`)
VALUES ('register_points', '100', '新用户注册奖励积分（旧版硬编码为 100）')
ON DUPLICATE KEY UPDATE `remark` = VALUES(`remark`);

INSERT INTO `site_setting` (`setting_key`, `setting_value`, `remark`)
VALUES ('exchange_rate', '1', '积分兑换比例（1 积分可抵扣的分数，用于积分兑换商品的定价换算）')
ON DUPLICATE KEY UPDATE `remark` = VALUES(`remark`);
