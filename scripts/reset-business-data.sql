-- ============================================================================
--  重置业务数据：把系统恢复成「刚上线、还没有任何业务往来」的状态
-- ============================================================================
--
--  ██   这是一个会删除用户账号及其全部历史记录的危险脚本   ██
--
--  【为什么加了确认口令】
--
--  这个脚本会 `DELETE FROM app_user` —— 用户账号一旦删除，
--  他名下的订单、地址、积分流水、评价就全部失去归属，**无法恢复**。
--
--  真正发生过一次事故：开发者（我）把它当成"清一下测试数据"随手执行了，
--  结果把用户的真实使用记录连账号一起删光，而当时**没有任何备份**。
--
--  用户的反馈是准确的：「正常软件的登录历史不可能消失」。
--  排查后确认**登录逻辑本身是对的**（同一 openid 重复登录会命中同一个用户，
--  历史完整保留，已实测），问题完全出在这个脚本被误用。
--
--  所以现在它需要一个**显式口令**才会执行。口令故意不写在能直接复制的位置，
--  逼操作者先读一遍上面的说明。
--
--  【使用方式】
--
--    1) 先备份（务必）：
--         .\scripts\backup-database.ps1
--
--    2) 取消下面第 60 行附近的注释（把 SET 那行的 `-- ` 去掉），再执行：
--         Get-Content scripts\reset-business-data.sql -Raw |
--             & mysql -uroot -p123456 --default-character-set=utf8mb4
--
--    3) 忘记加口令就执行的话，脚本会在**一条数据都不删**的情况下报错退出。
--
--  【如果你只是想清掉自己测试造的数据，不要用这个脚本】
--  直接按 id 或昵称删除对应的几行即可 —— 删除范围越精确越安全。
--  凡是"清空全部"的操作，都应该先问一句：备份了吗？
--
--  PowerShell 下注意用管道而不是 -e 参数：
--      （用 -e 传多行 SQL 会被参数解析搞坏，mysql 只会打印帮助然后退出）
-- ============================================================================

-- 脚本自包含：显式选库，这样无论从哪里执行都不用额外传库名
USE `nqx`;

-- ---------------------------------------------------------------------------
-- 强制确认：没有口令就报错退出，一条数据都不动
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS `nqx_require_wipe_confirm`;
DELIMITER //
CREATE PROCEDURE `nqx_require_wipe_confirm`(IN provided VARCHAR(64))
BEGIN
    IF provided IS NULL OR provided <> 'WIPE-ALL-DEMO-DATA' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '已拒绝执行：本脚本会删除全部用户账号与其历史记录（不可恢复）。请先运行 scripts/backup-database.ps1 备份，再取消脚本里 SET @nqx_confirm_wipe 那一行的注释后重试。';
    END IF;
END//
DELIMITER ;

-- ⚠️ 要真正执行，请取消下面这一行的注释（这是唯一的口令来源）
-- SET @nqx_confirm_wipe := 'WIPE-ALL-DEMO-DATA';

CALL `nqx_require_wipe_confirm`(@nqx_confirm_wipe);
DROP PROCEDURE IF EXISTS `nqx_require_wipe_confirm`;


--  【为什么要区分「初始化数据」与「业务数据」】
--
--  这两类数据的性质完全不同，处理方式也必须不同：
--
--  ┌────────────────────────────┬──────────────────────────────────────────┐
--  │  初始化数据（保留）         │  业务数据（清空）                          │
--  ├────────────────────────────┼──────────────────────────────────────────┤
--  │ 商品、分类、回收品类        │ 用户、地址、购物车                          │
--  │ 题库、FAQ、小贴士、公告     │ 订单、订单明细、流转日志                     │
--  │ 站点配置                   │ 积分账户、积分流水                          │
--  │ 管理员、角色、权限          │ 评价、维修申请、答题记录                     │
--  │                            │ 操作日志、登录日志                          │
--  └────────────────────────────┴──────────────────────────────────────────┘
--
--  · 初始化数据是**系统运转的前提**：没有商品就下不了单，没有题库就出不了题，
--    没有角色权限管理员就登不进后台。真实项目上线前也必须配齐这些，
--    所以它们不是"假数据"，而是"配置"。
--
--  · 业务数据是**经营结果**：订单、积分、评价都是已经发生的事实。
--    如果里面混着测试造的数据，所有报表都会失真 ——
--    看板上的"回收总量 2.2kg""已发放积分 741"根本不代表真实业务，
--    面试时被问"这些订单哪来的"会很难回答。
--
--  因此：**真实的系统在初始化后，所有业务指标都应该是 0。**
--        数字只能由真实操作累积出来。
--
--  ⚠️ 执行顺序有讲究：先删子表再删主表，避免残留孤儿数据。
--     （本项目没用外键约束，所以顺序错了不会报错，只会悄悄留下垃圾数据 ——
--      这正是必须自己理清顺序的原因。）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1. 订单相关的子表 → 主表
-- ---------------------------------------------------------------------------
DELETE FROM `order_flow`;      -- 流转日志（引用 order_id）
DELETE FROM `order_item`;      -- 订单明细（引用 order_id）
DELETE FROM `biz_order`;       -- 订单主表

-- ---------------------------------------------------------------------------
-- 2. 积分：先流水后账户
--    账户直接删掉而不是清零 —— 它会在用户注册时重新创建，
--    保留一个"余额 0 但累计获得 741"的账户反而是自相矛盾的脏数据。
-- ---------------------------------------------------------------------------
DELETE FROM `points_record`;
DELETE FROM `points_account`;

-- ---------------------------------------------------------------------------
-- 3. 其他业务记录
-- ---------------------------------------------------------------------------
DELETE FROM `product_review`;  -- 商品评价
DELETE FROM `repair_apply`;    -- 维修申请
DELETE FROM `user_answer`;     -- 每日答题记录
DELETE FROM `cart`;            -- 购物车
DELETE FROM `user_address`;    -- 收货地址

-- ---------------------------------------------------------------------------
-- 4. 日志
--    操作日志与登录日志记录的是"已经发生过的行为"。测试期间的日志
--    不代表真实运营情况，而且量很大（几百条）会干扰分页与排查。
-- ---------------------------------------------------------------------------
DELETE FROM `sys_operation_log`;
DELETE FROM `sys_login_log`;

-- ---------------------------------------------------------------------------
-- 5. 小程序用户
--    全部删除，让每个用户从"新注册"开始 —— 这样注册奖励、
--    初始积分账户、角色绑定都会按真实流程重新走一遍。
--    管理员账号（sys_user）**不在此列**，那是初始化数据。
-- ---------------------------------------------------------------------------
DELETE FROM `app_user_role`;
DELETE FROM `app_user`;

-- ---------------------------------------------------------------------------
-- 5b. 测试期间新建的管理员 / 角色 / 权限节点
--
--     这些**不是**初始化数据，而是联调时为了让接口跑起来临时造的账号。
--     保留它们的代价是：登录页面上列着一堆 `sys_tester`、`sys_admin2`，
--     而真正在用的只有 `admin` 一个 —— 演示时很难解释它们是什么。
--
--     ⚠️ id = 1 的 super admin 必须留下，否则整个后台就没人能登进去了
--        （而 `sys_user` 是逻辑删除表，删掉后靠 SQL 恢复的代价远大于收益）。
-- ---------------------------------------------------------------------------
DELETE FROM `sys_user_role` WHERE `user_id` <> 1;
DELETE FROM `sys_role_permission` WHERE `role_id` > 3;
DELETE FROM `sys_user` WHERE `id` <> 1;
DELETE FROM `sys_role` WHERE `id` > 3;
-- 初始化之外的权限节点（V2 里种下的节点 id 都小于 7000）
DELETE FROM `sys_role_permission` WHERE `permission_id` >= 7000;
DELETE FROM `sys_permission` WHERE `id` >= 7000;

-- ---------------------------------------------------------------------------
-- 6. 商品：恢复初始库存，并把**销量清零**，同时删掉测试期间造出来的垃圾商品
--
--    这里要区分两个字段的性质，不能一起处理：
--
--    · stock（库存）= **初始资产**。商品上架时就备了这么多货，属于配置，
--      保留 V2 里的原始值（下面逐条对照 V2__init_data.sql 抄的，不是凭记忆写的。
--      第一版我凭印象填，9 个商品里有 5 个的库存是错的 ——
--      这类"看起来合理"的错误最麻烦：脚本能跑通、不报错，
--      只是把数据改成了一个错误的"初始值"，要等下次对账才发现）。
--
--    · sold_count（销量）= **经营结果**。它是"卖出去过多少件"的累计，
--      一个刚上线的系统不可能已经卖过 156 件。
--      保留假销量会让商品列表显示"已售 156"、让看板的热销榜凭空有数据，
--      而这与"订单表是空的"直接矛盾 —— 报表之间自相矛盾比数字难看严重得多。
--
--    所以：库存保留原值，销量一律归零。
-- ---------------------------------------------------------------------------
UPDATE `product` SET stock = 2000 WHERE id = 1;  -- CR2032
UPDATE `product` SET stock = 1500 WHERE id = 2;  -- CR2025
UPDATE `product` SET stock = 800  WHERE id = 3;  -- CR2016
UPDATE `product` SET stock = 600  WHERE id = 4;  -- SR626SW
UPDATE `product` SET stock = 400  WHERE id = 5;  -- SR621SW
UPDATE `product` SET stock = 3000 WHERE id = 6;  -- LR44
UPDATE `product` SET stock = 200  WHERE id = 7;  -- 测试座
UPDATE `product` SET stock = 300  WHERE id = 8;  -- 收纳盒
UPDATE `product` SET stock = 500  WHERE id = 9;  -- 绝缘胶带

-- 销量归零：没有订单，就不该有销量
UPDATE `product` SET sold_count = 0;

-- 测试期间为了验证编码问题造的两个垃圾商品，直接软删除
UPDATE `product` SET deleted = 1
WHERE `name` IN ('??????', '中文编码验证商品');

-- ---------------------------------------------------------------------------
-- 6b. 联调时手工新增的题目
--
--     初始化题库的 id 是 1~8（见 V2__init_data.sql）。自增列不会因为
--     DELETE 而回退，所以联调时新增的题会落在很大的 id 上（实测是 9003）。
--     这里按"id 明显超出初始化范围"清理 —— 比起按题干内容匹配，
--     这样不会误伤同名题干。
--
--     ⚠️ 这也暴露了"重置脚本"本身的局限：它只能清理**已知形态**的测试数据。
--        真正可靠的做法是演示前直接重跑迁移 + 初始化脚本，
--        而不是在脏数据上反复做减法。这里选择了"够用且不误删"的折中。
-- ---------------------------------------------------------------------------
DELETE FROM `user_answer` WHERE `question_id` >= 9000;
DELETE FROM `question` WHERE `id` >= 9000;

-- ---------------------------------------------------------------------------
-- 7. 兜底：把"累计获得 − 累计消耗 = 余额"这个不变量重新对齐
--    （正常清空后没有账户了，这条是为残留数据准备的保险）
-- ---------------------------------------------------------------------------
DELETE FROM `points_account`
WHERE user_id NOT IN (SELECT id FROM `app_user`);

-- ---------------------------------------------------------------------------
-- 8. 自检：执行完打印一遍结果，确认业务表都空了、初始化数据还在
-- ---------------------------------------------------------------------------
SELECT '===== 业务数据（应全为 0）=====' AS `检查`;
SELECT '用户' AS `表`, COUNT(*) AS `剩余` FROM `app_user`
UNION ALL SELECT '地址',        COUNT(*) FROM `user_address`
UNION ALL SELECT '订单',        COUNT(*) FROM `biz_order`
UNION ALL SELECT '订单明细',    COUNT(*) FROM `order_item`
UNION ALL SELECT '流转日志',    COUNT(*) FROM `order_flow`
UNION ALL SELECT '积分账户',    COUNT(*) FROM `points_account`
UNION ALL SELECT '积分流水',    COUNT(*) FROM `points_record`
UNION ALL SELECT '商品评价',    COUNT(*) FROM `product_review`
UNION ALL SELECT '维修申请',    COUNT(*) FROM `repair_apply`
UNION ALL SELECT '答题记录',    COUNT(*) FROM `user_answer`
UNION ALL SELECT '操作日志',    COUNT(*) FROM `sys_operation_log`
UNION ALL SELECT '登录日志',    COUNT(*) FROM `sys_login_log`
UNION ALL SELECT '额外管理员',  COUNT(*) FROM `sys_user` WHERE `id` <> 1;

SELECT '' AS '';
SELECT '===== 初始化数据（应保留）=====' AS `检查`;
SELECT '商品分类' AS `表`, COUNT(*) AS `条数` FROM `product_category` WHERE deleted = 0
UNION ALL SELECT '回收品类', COUNT(*) FROM `recycle_category` WHERE deleted = 0
UNION ALL SELECT '商品',     COUNT(*) FROM `product` WHERE deleted = 0
UNION ALL SELECT '题库',     COUNT(*) FROM `question` WHERE deleted = 0
UNION ALL SELECT '常见问题', COUNT(*) FROM `faq` WHERE deleted = 0
UNION ALL SELECT '小贴士',   COUNT(*) FROM `daily_tip` WHERE deleted = 0
UNION ALL SELECT '公告',     COUNT(*) FROM `announcement` WHERE deleted = 0
UNION ALL SELECT '站点配置', COUNT(*) FROM `site_setting`
UNION ALL SELECT '管理员',   COUNT(*) FROM `sys_user` WHERE deleted = 0
UNION ALL SELECT '角色',     COUNT(*) FROM `sys_role` WHERE deleted = 0
UNION ALL SELECT '权限',     COUNT(*) FROM `sys_permission` WHERE deleted = 0;

-- ⚠️ 最后必须清一次 Redis：
--    Sa-Token 会把「已登录管理员的角色与权限码」缓存在 Account-Session 里
--    （见 StpInterfaceImpl）。上面删掉的管理员/角色在缓存里可能还留着，
--    不清掉会出现"数据库里已经没有这个角色了，但旧会话仍然按它鉴权"。
SELECT '' AS '';
SELECT '提醒：脚本执行完请再执行一次 redis-cli FLUSHDB，清掉会话缓存' AS `后续步骤`;
