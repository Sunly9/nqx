-- ============================================================================
--  压测结果核对（在 load-test.js 执行完之后运行）
-- ============================================================================
--  这个脚本只做一件事：把压测前后应当成立的「业务不变量」逐条断言出来。
--
--  为什么必须回查数据库？
--    接口返回 code=200 只说明"这次请求被受理了"，不说明"结果是对的"。
--    超卖与超发的典型表现恰恰是：**每一个请求都返回成功，但总量超出了上限。**
--    所以判据必须是数据库里的最终数字，而不是响应码。
-- ============================================================================

SELECT '========== 场景 A：库存防超卖（期望：库存 0，销量 100，永不为负）==========' AS `检查项`;

SELECT id,
       name,
       stock                                              AS `剩余库存`,
       sold_count                                         AS `累计销量`,
       CASE WHEN stock < 0 THEN '❌ 库存为负——发生超卖' ELSE '✅ 未为负' END AS `库存判定`,
       CASE WHEN stock = 0 THEN '✅ 精确归零' ELSE CONCAT('⚠️ 期望 0，实际 ', stock) END AS `归零判定`
FROM product
WHERE id = 3;

-- 实际卖出了多少件：以订单明细为准，而不是看销量字段
SELECT COUNT(*)                                   AS `成功下单笔数`,
       IFNULL(SUM(oi.quantity), 0)                AS `实际卖出件数`,
       CASE WHEN IFNULL(SUM(oi.quantity), 0) = 100 THEN '✅ 恰好 100 件，未超卖'
            ELSE CONCAT('❌ 期望 100 件，实际 ', IFNULL(SUM(oi.quantity), 0), ' 件') END AS `超卖判定`
FROM order_item oi
         INNER JOIN biz_order o ON o.id = oi.order_id
WHERE oi.item_id = 3
  AND oi.item_type = 1
  AND o.deleted = 0
  AND o.status NOT IN (30, 50);

SELECT '' AS '';

SELECT '========== 场景 B：积分防超发（期望：成功 12 次，余额 40，永不为负）==========' AS `检查项`;

SELECT user_id,
       balance                                                     AS `剩余积分`,
       total_earn                                                  AS `累计获得`,
       total_spend                                                 AS `累计消耗`,
       CASE WHEN balance < 0 THEN '❌ 积分为负——发生超发' ELSE '✅ 未为负' END AS `积分判定`,
       CASE WHEN balance = 40 THEN '✅ 精确剩余 40' ELSE CONCAT('⚠️ 期望 40，实际 ', balance) END AS `余额判定`,
       CASE WHEN total_earn - total_spend = balance THEN '✅ 账目自洽'
            ELSE '❌ 账目不自洽（余额 ≠ 累计获得 − 累计消耗）' END      AS `自洽判定`
FROM points_account
WHERE user_id = 2;

-- 成功兑换了多少件：既看订单也看积分流水，两者必须一致
SELECT (SELECT COUNT(*)
        FROM biz_order
        WHERE user_id = 2 AND order_type = 2 AND pay_type = 1
          AND status NOT IN (30, 50) AND deleted = 0)          AS `兑换订单数`,
       (SELECT COUNT(*)
        FROM points_record
        WHERE user_id = 2 AND change_type = 4)                 AS `扣分流水条数`,
       CASE WHEN (SELECT COUNT(*) FROM points_record WHERE user_id = 2 AND change_type = 4) = 12
            THEN '✅ 恰好 12 条扣分流水'
            ELSE '❌ 扣分流水条数与预期不符' END                     AS `超发判定`;

SELECT '' AS '';

SELECT '========== 场景 C：接口幂等（期望：流水只新增 1 条，余额只加 7）==========' AS `检查项`;

-- 用本次压测的 request_id 前缀统计
SELECT change_type,
       change_value,
       balance_after,
       biz_no,
       remark,
       COUNT(*)                                                  AS `同幂等键条数`,
       CASE WHEN COUNT(*) = 1 THEN '✅ 恰好 1 条，唯一索引生效'
            ELSE CONCAT('❌ 出现 ', COUNT(*), ' 条重复入账') END    AS `幂等判定`
FROM points_record
WHERE biz_no LIKE 'ADJUST-loadtest-%'
GROUP BY change_type, change_value, balance_after, biz_no, remark;

SELECT '' AS '';

SELECT '========== 全局兜底：任何一处为负都说明实现有问题 ==========' AS `检查项`;

SELECT (SELECT COUNT(*) FROM product WHERE stock < 0)          AS `库存为负的商品数`,
       (SELECT COUNT(*) FROM points_account WHERE balance < 0) AS `积分为负的账户数`,
       (SELECT COUNT(*) FROM points_account
        WHERE total_earn - total_spend <> balance)             AS `账目不自洽的账户数`,
       CASE WHEN (SELECT COUNT(*) FROM product WHERE stock < 0) = 0
                 AND (SELECT COUNT(*) FROM points_account WHERE balance < 0) = 0
           THEN '✅ 全部通过'
           ELSE '❌ 存在不变量被破坏的数据' END                      AS `总判定`;
