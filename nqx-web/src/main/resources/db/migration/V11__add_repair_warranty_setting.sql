-- ============================================================================
-- V11：补充维修业务的保修期配置
-- ----------------------------------------------------------------------------
-- 旧版 Node 把保修期长度硬编码在代码里：
--     const oneYearLater = new Date(receivedDate);
--     oneYearLater.setFullYear(oneYearLater.getFullYear() + 1);
-- 想改成 18 个月就得改代码重新部署。
--
-- 统一挪到 site_setting，读取见 ContentService#getIntSetting。
-- ============================================================================

INSERT INTO `site_setting` (`setting_key`, `setting_value`, `remark`)
VALUES ('repair_warranty_months', '12', '维修保修期时长（月），从订单确认收货时间起算')
ON DUPLICATE KEY UPDATE `remark` = VALUES(`remark`);
