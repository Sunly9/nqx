-- ============================================================================
-- 纽启新生 (NQX) — 初始数据
-- Flyway: V2__init_data.sql
-- ----------------------------------------------------------------------------
-- 本脚本只写入「与业务无关、必须存在」的元数据：
--   角色、权限菜单树、角色权限映射、字典化的基础业务分类与演示数据。
--
-- 【为什么不在这里写管理员账号？】
--   密码必须经 BCrypt 加密，而 BCrypt 每次加盐结果都不同，写死在 SQL 里既不便
--   维护，也容易把「默认弱口令」带进生产。因此管理员由应用启动时的
--   NqxDataInitializer 检测 sys_user 为空后自动创建（见 docs/03 实施计划）。
--   默认账号：admin / Admin@123，首次登录后请立即修改。
-- ============================================================================

SET NAMES utf8mb4;

-- ============================================================================
-- 一、角色（三级，用于演示 RBAC 权限差异）
-- ============================================================================
INSERT INTO `sys_role` (`id`, `role_code`, `role_name`, `description`, `sort`, `status`)
VALUES (1, 'SUPER_ADMIN', '超级管理员', '拥有全部权限，含系统管理', 1, 1),
       (2, 'OPERATOR', '运营管理员', '负责商品、订单、积分、问答的日常运营，无系统管理权限', 2, 1),
       (3, 'AUDITOR', '回收审核员', '仅负责回收订单审核与回收品类查看，无其他权限', 3, 1);

-- ============================================================================
-- 二、权限树
--     ID 分段约定（便于按段批量授权，也让脚本可读）：
--       1000~1999 系统管理  2000~2999 商品管理  3000~3999 订单管理
--       4000~4999 积分管理  5000~5999 问答管理  6000~6999 数据看板
--     perm_type: 1目录 2菜单 3按钮
-- ============================================================================
INSERT INTO `sys_permission` (`id`, `parent_id`, `perm_name`, `perm_code`, `perm_type`, `path`, `component`, `icon`,
                              `sort`, `visible`, `status`)
VALUES
-- ---------- 1000 系统管理 ----------
(1000, 0, '系统管理', NULL, 1, '/system', NULL, 'Setting', 90, 1, 1),
(1010, 1000, '管理员管理', 'sys:user:list', 2, '/system/user', 'system/user/index', 'User', 1, 1, 1),
(1011, 1010, '新增管理员', 'sys:user:add', 3, NULL, NULL, NULL, 1, 1, 1),
(1012, 1010, '修改管理员', 'sys:user:edit', 3, NULL, NULL, NULL, 2, 1, 1),
(1013, 1010, '删除管理员', 'sys:user:delete', 3, NULL, NULL, NULL, 3, 1, 1),
(1014, 1010, '重置密码', 'sys:user:resetPwd', 3, NULL, NULL, NULL, 4, 1, 1),
(1020, 1000, '角色管理', 'sys:role:list', 2, '/system/role', 'system/role/index', 'UserFilled', 2, 1, 1),
(1021, 1020, '新增角色', 'sys:role:add', 3, NULL, NULL, NULL, 1, 1, 1),
(1022, 1020, '修改角色', 'sys:role:edit', 3, NULL, NULL, NULL, 2, 1, 1),
(1023, 1020, '删除角色', 'sys:role:delete', 3, NULL, NULL, NULL, 3, 1, 1),
(1024, 1020, '分配权限', 'sys:role:assign', 3, NULL, NULL, NULL, 4, 1, 1),
(1030, 1000, '权限菜单', 'sys:perm:list', 2, '/system/permission', 'system/permission/index', 'Menu', 3, 1, 1),
(1031, 1030, '新增权限', 'sys:perm:add', 3, NULL, NULL, NULL, 1, 1, 1),
(1032, 1030, '修改权限', 'sys:perm:edit', 3, NULL, NULL, NULL, 2, 1, 1),
(1033, 1030, '删除权限', 'sys:perm:delete', 3, NULL, NULL, NULL, 3, 1, 1),
(1040, 1000, '操作日志', 'sys:log:operation', 2, '/system/log/operation', 'system/log/operation', 'Document', 4, 1, 1),
(1050, 1000, '登录日志', 'sys:log:login', 2, '/system/log/login', 'system/log/login', 'Key', 5, 1, 1),

-- ---------- 2000 商品管理 ----------
(2000, 0, '商品管理', NULL, 1, '/product', NULL, 'Goods', 10, 1, 1),
(2010, 2000, '分类管理', 'product:category:list', 2, '/product/category', 'product/category/index', 'Grid', 1, 1, 1),
(2011, 2010, '新增分类', 'product:category:add', 3, NULL, NULL, NULL, 1, 1, 1),
(2012, 2010, '修改分类', 'product:category:edit', 3, NULL, NULL, NULL, 2, 1, 1),
(2013, 2010, '删除分类', 'product:category:delete', 3, NULL, NULL, NULL, 3, 1, 1),
(2020, 2000, '商品列表', 'product:list', 2, '/product/list', 'product/list/index', 'Shop', 2, 1, 1),
(2021, 2020, '新增商品', 'product:add', 3, NULL, NULL, NULL, 1, 1, 1),
(2022, 2020, '修改商品', 'product:edit', 3, NULL, NULL, NULL, 2, 1, 1),
(2023, 2020, '删除商品', 'product:delete', 3, NULL, NULL, NULL, 3, 1, 1),
(2024, 2020, '上下架', 'product:changeStatus', 3, NULL, NULL, NULL, 4, 1, 1),
(2030, 2000, '回收品类', 'recycle:category:list', 2, '/product/recycle', 'product/recycle/index', 'Recycle', 3, 1, 1),
(2031, 2030, '新增回收品类', 'recycle:category:add', 3, NULL, NULL, NULL, 1, 1, 1),
(2032, 2030, '修改回收品类', 'recycle:category:edit', 3, NULL, NULL, NULL, 2, 1, 1),
(2033, 2030, '删除回收品类', 'recycle:category:delete', 3, NULL, NULL, NULL, 3, 1, 1),

-- ---------- 3000 订单管理 ----------
(3000, 0, '订单管理', NULL, 1, '/order', NULL, 'List', 20, 1, 1),
(3010, 3000, '回收订单', 'order:recycle:list', 2, '/order/recycle', 'order/recycle/index', 'Refresh', 1, 1, 1),
(3011, 3010, '订单详情', 'order:recycle:detail', 3, NULL, NULL, NULL, 1, 1, 1),
(3012, 3010, '审核通过', 'order:recycle:audit', 3, NULL, NULL, NULL, 2, 1, 1),
(3013, 3010, '驳回订单', 'order:recycle:reject', 3, NULL, NULL, NULL, 3, 1, 1),
(3014, 3010, '确认回收', 'order:recycle:confirm', 3, NULL, NULL, NULL, 4, 1, 1),
(3015, 3010, '导出订单', 'order:recycle:export', 3, NULL, NULL, NULL, 5, 1, 1),
(3020, 3000, '采购订单', 'order:purchase:list', 2, '/order/purchase', 'order/purchase/index', 'ShoppingCart', 2, 1, 1),
(3021, 3020, '订单详情', 'order:purchase:detail', 3, NULL, NULL, NULL, 1, 1, 1),
(3022, 3020, '订单发货', 'order:purchase:deliver', 3, NULL, NULL, NULL, 2, 1, 1),
(3023, 3020, '导出订单', 'order:purchase:export', 3, NULL, NULL, NULL, 3, 1, 1),

-- ---------- 4000 积分管理 ----------
(4000, 0, '积分管理', NULL, 1, '/points', NULL, 'Coin', 30, 1, 1),
(4010, 4000, '积分账户', 'points:account:list', 2, '/points/account', 'points/account/index', 'Wallet', 1, 1, 1),
(4011, 4010, '调整积分', 'points:account:adjust', 3, NULL, NULL, NULL, 1, 1, 1),
(4020, 4000, '积分流水', 'points:record:list', 2, '/points/record', 'points/record/index', 'Tickets', 2, 1, 1),
(4021, 4020, '导出流水', 'points:record:export', 3, NULL, NULL, NULL, 1, 1, 1),

-- ---------- 5000 问答管理 ----------
(5000, 0, '问答管理', NULL, 1, '/question', NULL, 'ChatDotSquare', 40, 1, 1),
(5010, 5000, '题库管理', 'question:list', 2, '/question/list', 'question/list/index', 'EditPen', 1, 1, 1),
(5011, 5010, '新增题目', 'question:add', 3, NULL, NULL, NULL, 1, 1, 1),
(5012, 5010, '修改题目', 'question:edit', 3, NULL, NULL, NULL, 2, 1, 1),
(5013, 5010, '删除题目', 'question:delete', 3, NULL, NULL, NULL, 3, 1, 1),
(5014, 5010, '发布/下线', 'question:changeStatus', 3, NULL, NULL, NULL, 4, 1, 1),
(5020, 5000, '答题记录', 'question:answer:list', 2, '/question/answer', 'question/answer/index', 'DataLine', 2, 1, 1),
(5021, 5020, '导出记录', 'question:answer:export', 3, NULL, NULL, NULL, 1, 1, 1),

-- ---------- 6000 数据看板 ----------
(6000, 0, '数据看板', NULL, 1, '/dashboard', NULL, 'DataAnalysis', 1, 1, 1),
(6010, 6000, '运营看板', 'dashboard:view', 2, '/dashboard/index', 'dashboard/index', 'TrendCharts', 1, 1, 1);

-- ============================================================================
-- 三、角色 - 权限映射
-- ============================================================================
-- 3.1 超级管理员：全部权限
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 1, `id`
FROM `sys_permission`;

-- 3.2 运营管理员：除「系统管理」外的全部权限（ID >= 2000 即业务模块段）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 2, `id`
FROM `sys_permission`
WHERE `id` >= 2000;

-- 3.3 回收审核员：仅看板 + 回收订单 + 回收品类（体现最小权限原则）
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
VALUES (3, 6000), (3, 6010),
       (3, 3000), (3, 3010), (3, 3011), (3, 3012), (3, 3013), (3, 3014),
       (3, 2000), (3, 2030);

-- ============================================================================
-- 四、基础业务数据（演示用）
-- ============================================================================

-- ---------- 4.1 商品分类 ----------
INSERT INTO `product_category` (`id`, `parent_id`, `name`, `sort`, `status`)
VALUES (1, 0, '纽扣电池', 1, 1),
       (2, 0, '实验耗材', 2, 1),
       (11, 1, '锂锰电池 CR 系列', 1, 1),
       (12, 1, '氧化银电池 SR 系列', 2, 1),
       (13, 1, '碱性电池 LR 系列', 3, 1),
       (21, 2, '电池测试配件', 1, 1),
       (22, 2, '回收收纳用品', 2, 1);

-- ---------- 4.2 商品 ----------
-- price 单位：分（1 元 = 100 分）；product_type: 1采购 2积分兑换 3两者皆可
INSERT INTO `product` (`id`, `category_id`, `name`, `sub_title`, `spec`, `product_type`, `price`, `points_price`, `unit`,
                       `stock`, `sold_count`, `exchange_limit`, `is_hot`, `status`, `sort`, `description`)
VALUES (1, 11, 'CR2032 锂锰纽扣电池', '实验室最常用型号，3V 稳定放电', 'CR2032 3V 220mAh', 3, 120, 50, '个', 2000, 156, 20, 1, 1, 1,
        '标称电压 3V，容量 220mAh，工作温度 -20℃~+70℃，适用于主板、电子秤、遥控器等。'),
       (2, 11, 'CR2025 锂锰纽扣电池', '薄型设计，适配小型设备', 'CR2025 3V 165mAh', 3, 110, 45, '个', 1500, 98, 20, 1, 1, 2,
        '厚度 2.5mm，适用于计算器、电子表等轻薄设备。'),
       (3, 11, 'CR2016 锂锰纽扣电池', '超薄 1.6mm', 'CR2016 3V 90mAh', 1, 100, 0, '个', 800, 42, 0, 0, 1, 3,
        '厚度 1.6mm，适用于车钥匙、超薄遥控器。'),
       (4, 12, 'SR626SW 氧化银电池', '高稳定性，适合精密仪器', 'SR626SW 1.55V', 1, 180, 0, '个', 600, 30, 0, 0, 1, 4,
        '氧化银电池放电曲线平稳，适合对电压稳定性要求高的实验仪器。'),
       (5, 12, 'SR621SW 氧化银电池', '低自放电，长寿命', 'SR621SW 1.55V', 2, 0, 80, '个', 400, 25, 10, 1, 1, 5,
        '低自放电率，常温下可存放 3 年以上，适合备用仪表。'),
       (6, 13, 'LR44 / AG13 碱性纽扣电池', '性价比之选，通用性强', 'LR44 1.5V', 2, 0, 30, '个', 3000, 210, 30, 0, 1, 6,
        '通用型号，兼容 AG13 / L1154 / 357 等多种叫法。'),
       (7, 21, '纽扣电池测试座', '免焊接，快速测量', '适配 CR2032/CR2025', 1, 1500, 0, '个', 200, 18, 0, 1, 1, 7,
        '弹片式接触，适配直径 20mm 系列纽扣电池，用于容量与内阻测试。'),
       (8, 22, '纽扣电池回收收纳盒', '防短路分格设计', '24 格 / 盒', 3, 800, 200, '盒', 300, 66, 5, 1, 1, 8,
        '分格独立存放，避免正负极接触短路；透明上盖便于清点数量。'),
       (9, 22, '绝缘胶带（电池封装用）', '回收前封装正负极', '宽 10mm × 长 20m', 2, 0, 60, '卷', 500, 74, 10, 0, 1, 9,
        '回收纽扣电池前粘贴正负极，可有效降低运输过程中的短路起火风险。');

-- ---------- 4.3 回收品类 ----------
INSERT INTO `recycle_category` (`id`, `name`, `unit`, `points_per_unit`, `description`, `status`, `sort`)
VALUES (1, '锂锰纽扣电池（CR 系列）', '节', 5, '含锂，属危险废物，需单独存放，禁止挤压穿刺。', 1, 1),
       (2, '氧化银纽扣电池（SR 系列）', '节', 8, '含银，回收价值高，建议按型号分装。', 1, 2),
       (3, '碱性纽扣电池（LR 系列）', '节', 3, '含锌锰，普通有害垃圾，可与其他纽扣电池混装。', 1, 3),
       (4, '镍氢/镍镉可充纽扣电池', '节', 10, '含镍镉，毒性较高，必须单独密封包装。', 1, 4),
       (5, '混装未分类纽扣电池', '节', 2, '未按型号分类的混合电池，需人工二次分拣。', 1, 5),
       (6, '鼓包/漏液纽扣电池', '节', 15, '已发生鼓包或漏液，危险性高，需佩戴防护用具处理。', 1, 6);

-- ---------- 4.4 每日问答题库 ----------
INSERT INTO `question` (`id`, `content`, `question_type`, `options`, `correct_answer`, `analysis`, `points`, `difficulty`,
                        `publish_date`, `status`, `sort`)
VALUES (1, '纽扣电池应该投入哪一类垃圾桶？', 1,
        '[{"key":"A","value":"可回收物"},{"key":"B","value":"厨余垃圾"},{"key":"C","value":"有害垃圾"},{"key":"D","value":"其他垃圾"}]',
        'C', '纽扣电池含有汞、镉、锂等重金属及活性物质，属于《国家危险废物名录》范畴，必须投入有害垃圾或交由专业回收渠道处理。',
        10, 1, CURDATE(), 1, 1),
       (2, '在实验室收集废弃纽扣电池时，下列哪种做法是错误的？', 1,
        '[{"key":"A","value":"用绝缘胶带封住正负极"},{"key":"B","value":"按型号分格存放"},{"key":"C","value":"把不同型号的电池混装在金属盒中"},{"key":"D","value":"对鼓包漏液的电池单独密封"}]',
        'C', '金属盒会导电，混装且正负极接触时极易发生短路、发热甚至起火，这是实验室回收最常见的隐患。',
        10, 2, CURDATE(), 1, 2),
       (3, '关于纽扣电池的标称电压，下列说法正确的是？', 1,
        '[{"key":"A","value":"锂锰 CR 系列标称 3V"},{"key":"B","value":"氧化银 SR 系列标称 1.55V"},{"key":"C","value":"碱性 LR 系列标称 1.5V"},{"key":"D","value":"以上都正确"}]',
        'D', 'CR 系列为锂锰体系，标称 3V；SR 氧化银与 LR 碱性的标称电压分别为 1.55V 和 1.5V。',
        15, 2, CURDATE(), 1, 3),
       (4, '下列哪些属于纽扣电池回收前应当做的处理？（多选）', 2,
        '[{"key":"A","value":"在正负极粘贴绝缘胶带"},{"key":"B","value":"按品类称重并记录数量"},{"key":"C","value":"用水冲洗干净电池表面"},{"key":"D","value":"鼓包电池单独密封包装"}]',
        'ABD', '严禁用水冲洗电池：锂遇水可能发生剧烈反应，且冲洗会加速外壳腐蚀导致漏液，属于典型的错误操作。',
        20, 3, CURDATE(), 1, 4),
       (5, '纽扣电池型号 CR2032 中的「20」代表什么？', 1,
        '[{"key":"A","value":"额定容量 20mAh"},{"key":"B","value":"直径 20mm"},{"key":"C","value":"厚度 2.0mm"},{"key":"D","value":"重量 2.0g"}]',
        'B', 'IEC 命名规则中，前两位数字表示直径（mm），后两位表示厚度（0.1mm）。CR2032 即直径 20mm、厚 3.2mm。',
        15, 2, CURDATE(), 1, 5),
       (6, '废弃纽扣电池随意丢弃可能带来的危害包括哪些？（多选）', 2,
        '[{"key":"A","value":"重金属渗入土壤和地下水"},{"key":"B","value":"锂电芯受挤压引发起火"},{"key":"C","value":"造成大量温室气体排放"},{"key":"D","value":"银、镍等有价金属资源浪费"}]',
        'ABD', '电池废弃主要风险是重金属污染、短路起火与有价金属流失；温室气体并非纽扣电池废弃的主要环境问题。',
        20, 3, CURDATE(), 1, 6),
       (7, '判断：所有纽扣电池都属于危险废物，必须按危废流程处置。', 3,
        '[{"key":"A","value":"正确"},{"key":"B","value":"错误"}]',
        'B', '碱性锌锰纽扣电池（无汞化后）通常按有害垃圾处理即可；而含汞、含镉电池则明确属于危险废物，需按危废流程处置，二者管理要求不同。',
        15, 3, CURDATE(), 1, 7),
       (8, '实验室纽扣电池回收记录中，下列哪项信息最不重要？', 1,
        '[{"key":"A","value":"电池型号与品类"},{"key":"B","value":"回收数量或重量"},{"key":"C","value":"回收时的电池外壳颜色"},{"key":"D","value":"回收日期与经手人"}]',
        'C', '回收记录的核心是「可追溯 + 可计量」：型号、数量、时间、经手人是必备要素，外壳颜色与回收管理无关。',
        10, 1, CURDATE(), 1, 8);
