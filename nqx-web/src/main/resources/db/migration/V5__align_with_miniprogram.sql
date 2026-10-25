-- ============================================================================
-- V5：对齐微信小程序前端的业务模型
-- ----------------------------------------------------------------------------
-- 【背景】
--   新后端的表结构是按「回收品类 × 数量」设计的，但读取旧版小程序前端代码后
--   发现真实的回收业务模型是【按重量计价】：
--       pointsEarned = floor(actual_weight * 100)   -- 1kg = 100 积分
--       carbonSaved  = actual_weight * 0.5          -- 碳减排量
--   用户下单时提交的是：预估重量、联系人、电话、地址、快递单号、预约上门时间。
--
--   前端页面还依赖以下业务模块，而当前表结构里完全没有：
--       商品评价（商品详情页评价列表 + 订单评价）
--       维修申请（repair/apply、repair/list）
--       每日小贴士（回收端首页）
--       常见问题 FAQ（help 页，两端各一套）
--       公告（首页公告位）
--       站点配置（logo、回收单价等可运营项）
--
-- 【类型选择的说明】
--   重量用 DECIMAL(10,2) 而不是 INT：
--   - 金额必须用 INT 存「分」，因为浮点无法精确表示 0.1 元，累计运算会出现误差；
--   - 但重量是「度量值」，需要保留两位小数且参与乘除运算
--     （1.35kg × 100 积分/kg），DECIMAL 是定点数、运算精确，
--     比"用 INT 存克"更直观，也不会引入浮点误差。二者场景不同，不要混为一谈。
-- ============================================================================

-- ============================================================================
-- 一、扩展用户表：回收累计指标
-- ============================================================================
ALTER TABLE `app_user`
    ADD COLUMN `total_weight` DECIMAL(12, 2) NOT NULL DEFAULT 0.00
        COMMENT '累计回收重量（kg）' AFTER `lab_name`,
    ADD COLUMN `carbon_saved` DECIMAL(12, 2) NOT NULL DEFAULT 0.00
        COMMENT '累计碳减排量（kg），前端"环保贡献"展示用' AFTER `total_weight`;

-- ============================================================================
-- 二、扩展订单表：回收重量、碳减排、快递单号
-- ============================================================================
ALTER TABLE `biz_order`
    ADD COLUMN `estimated_weight` DECIMAL(10, 2) DEFAULT NULL
        COMMENT '用户预估重量（kg），提交回收单时填写' AFTER `total_points`,
    ADD COLUMN `actual_weight` DECIMAL(10, 2) DEFAULT NULL
        COMMENT '实际回收重量（kg），管理员确认回收时录入' AFTER `estimated_weight`,
    ADD COLUMN `carbon_saved` DECIMAL(10, 2) DEFAULT NULL
        COMMENT '本次回收的碳减排量（kg）' AFTER `actual_weight`,
    ADD COLUMN `courier_no` VARCHAR(64) DEFAULT NULL
        COMMENT '快递单号（用户自行寄回时填写）' AFTER `carbon_saved`,
    ADD COLUMN `recycle_category_id` BIGINT DEFAULT NULL
        COMMENT '回收品类 ID（可空；前端主要按重量计价，品类为辅助信息）' AFTER `order_type`,
    ADD INDEX `idx_recycle_category` (`recycle_category_id`);

-- ============================================================================
-- 三、商品评价
-- ============================================================================
CREATE TABLE `product_review`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`      BIGINT        NOT NULL COMMENT '关联订单 ID',
    `order_no`      VARCHAR(32)   NOT NULL COMMENT '订单号（冗余，便于排查）',
    `user_id`       BIGINT        NOT NULL COMMENT '评价人 ID',
    `product_id`    BIGINT        NOT NULL COMMENT '商品 ID',
    `rating`        TINYINT       NOT NULL DEFAULT 5 COMMENT '评分 1~5 星',
    `content`       VARCHAR(1000)          DEFAULT NULL COMMENT '评价内容',
    `images`        JSON                   DEFAULT NULL COMMENT '晒图 URL 数组',
    `is_anonymous`  TINYINT       NOT NULL DEFAULT 0 COMMENT '是否匿名 0否 1是',
    `reply_content` VARCHAR(500)           DEFAULT NULL COMMENT '商家回复',
    `reply_time`    DATETIME               DEFAULT NULL COMMENT '回复时间',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态 0隐藏 1显示',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评价时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    -- 一个订单里的同一个商品只能评价一次，防止重复刷评价
    UNIQUE KEY `uk_order_product` (`order_id`, `product_id`, `deleted`),
    -- 商品详情页按商品查评价列表并按时间倒序
    KEY `idx_product_status_time` (`product_id`, `status`, `create_time`),
    KEY `idx_user_time` (`user_id`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商品评价';

-- ============================================================================
-- 四、维修申请
-- ============================================================================
CREATE TABLE `repair_apply`
(
    `id`            BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `apply_no`      VARCHAR(32)   NOT NULL COMMENT '申请单号',
    `user_id`       BIGINT        NOT NULL COMMENT '申请人 ID',
    `order_id`      BIGINT                 DEFAULT NULL COMMENT '关联订单 ID（可空：也可对未下单的商品申请）',
    `product_id`    BIGINT                 DEFAULT NULL COMMENT '关联商品 ID',
    `product_name`  VARCHAR(100)           DEFAULT NULL COMMENT '商品名称快照',
    `fault_desc`    VARCHAR(1000) NOT NULL COMMENT '故障描述',
    `images`        JSON                   DEFAULT NULL COMMENT '故障图片 URL 数组',
    `contact_name`  VARCHAR(50)   NOT NULL COMMENT '联系人',
    `contact_phone` VARCHAR(20)   NOT NULL COMMENT '联系电话',
    `address`       VARCHAR(500)           DEFAULT NULL COMMENT '寄修地址',
    `status`        TINYINT       NOT NULL DEFAULT 1 COMMENT '状态 1待处理 2处理中 3已完成 4已驳回',
    `handle_remark` VARCHAR(500)           DEFAULT NULL COMMENT '处理说明',
    `handler_id`    BIGINT                 DEFAULT NULL COMMENT '处理人（管理员）ID',
    `handle_time`   DATETIME               DEFAULT NULL COMMENT '处理时间',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '申请时间',
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`       TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_apply_no` (`apply_no`),
    KEY `idx_user_status_time` (`user_id`, `status`, `create_time`),
    KEY `idx_status_time` (`status`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='维修申请';

-- ============================================================================
-- 五、每日小贴士（回收端首页随机展示一条）
-- ============================================================================
CREATE TABLE `daily_tip`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `content`     VARCHAR(500) NOT NULL COMMENT '贴士内容',
    `category`    VARCHAR(50)           DEFAULT NULL COMMENT '分类，如 安全/环保/常识',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0停用 1启用',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='每日小贴士';

-- ============================================================================
-- 六、常见问题
--     旧版把 FAQ 拆成了 faqs 与 procurement_faqs 两张一模一样的表，
--     这里统一为一张，用 faq_type 区分展示端。
-- ============================================================================
CREATE TABLE `faq`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `question`    VARCHAR(500) NOT NULL COMMENT '问题',
    `answer`      TEXT         NOT NULL COMMENT '回答',
    `faq_type`    TINYINT      NOT NULL DEFAULT 3 COMMENT '展示端 1回收端 2采购端 3通用',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0停用 1启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_type_status_sort` (`faq_type`, `status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='常见问题';

-- ============================================================================
-- 七、公告
-- ============================================================================
CREATE TABLE `announcement`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`       VARCHAR(200) NOT NULL COMMENT '标题',
    `content`     TEXT         NOT NULL COMMENT '内容',
    `ann_type`    TINYINT      NOT NULL DEFAULT 1 COMMENT '类型 1公告 2活动',
    `status`      TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0未发布 1已发布 2已下线',
    `start_time`  DATETIME              DEFAULT NULL COMMENT '生效开始时间，为空表示不限制',
    `end_time`    DATETIME              DEFAULT NULL COMMENT '生效结束时间，为空表示不限制',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    -- 查询"当前生效的公告"会同时用到 status 与时间范围
    KEY `idx_status_time` (`status`, `start_time`, `end_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='公告';

-- ============================================================================
-- 八、站点配置（KV 结构）
--     把「回收单价」「碳减排系数」「Logo 地址」这类可运营项从代码里挪出来。
--     旧版把 1kg=100 积分硬编码在 app.js 里，改个单价要改代码重新部署，
--     运营同学完全无法自助调整。
-- ============================================================================
CREATE TABLE `site_setting`
(
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `setting_key`   VARCHAR(64)  NOT NULL COMMENT '配置键',
    `setting_value` VARCHAR(1000)         DEFAULT NULL COMMENT '配置值（统一按字符串存，读取时按需转换）',
    `remark`        VARCHAR(255)          DEFAULT NULL COMMENT '配置说明',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='站点配置';

-- ============================================================================
-- 九、补充初始数据
-- ============================================================================

-- ---------- 9.1 站点配置 ----------
INSERT INTO `site_setting` (`setting_key`, `setting_value`, `remark`)
VALUES ('logo_url', '/files/common/logo.png', '小程序首页 Logo 地址'),
       ('recycle_points_per_kg', '100', '回收单价：每公斤可得积分（旧版硬编码为 100）'),
       ('carbon_factor', '0.5', '碳减排系数：每公斤回收物对应减排量（kg）'),
       ('service_phone', '400-000-0000', '客服电话'),
       ('brand_name', '纽启新生', '品牌名称');

-- ---------- 9.2 每日小贴士 ----------
INSERT INTO `daily_tip` (`content`, `category`, `status`, `sort`)
VALUES ('纽扣电池属于有害垃圾，请勿投入普通垃圾桶。', '环保', 1, 1),
       ('回收前请用绝缘胶带封住纽扣电池的正负极，避免运输途中短路起火。', '安全', 1, 2),
       ('不同型号的纽扣电池请分开放置，混装会增加后续分拣成本。', '常识', 1, 3),
       ('已鼓包或漏液的电池危险性高，请单独密封后再交投。', '安全', 1, 4),
       ('一枚纽扣电池可污染约 600 立方米的水，回收意义重大。', '环保', 1, 5),
       ('CR 系列为锂锰电池，标称电压 3V；SR 系列为氧化银电池，标称 1.55V。', '常识', 1, 6),
       ('实验室建议设置专用回收容器，并张贴电池型号对照表。', '常识', 1, 7),
       ('回收后可获得积分，积分能在商城兑换实验耗材。', '环保', 1, 8);

-- ---------- 9.3 常见问题 ----------
INSERT INTO `faq` (`question`, `answer`, `faq_type`, `sort`, `status`)
VALUES ('回收的纽扣电池有什么要求？',
        '请确保电池为正品且未破损。已鼓包、漏液的电池请单独密封包装并在备注中说明，以便我们按危险废物流程处理。',
        1, 1, 1),
       ('上门回收需要多久？',
        '提交回收单后，工作人员会在 1~3 个工作日内联系您确认上门时间。校内实验室通常可在 24 小时内完成。',
        1, 2, 1),
       ('积分是怎么计算的？',
        '按实际回收重量计算：每公斤可得 100 积分。最终积分以工作人员现场称重后的结果为准。',
        1, 3, 1),
       ('碳减排量是怎么算的？',
        '按每公斤回收物减排 0.5kg 估算，仅用于展示环保贡献，不作为积分计算依据。',
        1, 4, 1),
       ('积分可以在哪里使用？',
        '积分可在「商城」的积分专区兑换实验耗材，如纽扣电池、测试座、回收收纳盒等。',
        3, 5, 1),
       ('下单后多久发货？',
        '支付成功后 1~2 个工作日内发货。校内订单可选择自提，自提时间以短信通知为准。',
        2, 6, 1),
       ('可以开发票吗？',
        '可以。请在订单备注中填写发票抬头与税号，我们会在订单完成后 3 个工作日内开具电子发票。',
        2, 7, 1),
       ('收到的电池有问题怎么办？',
        '请在订单详情页提交「维修申请」并附上故障照片，工作人员会在 1 个工作日内响应。',
        2, 8, 1);

-- ---------- 9.4 公告 ----------
INSERT INTO `announcement` (`title`, `content`, `ann_type`, `status`, `sort`)
VALUES ('纽启新生平台正式上线',
        '欢迎使用纽启新生！现在提交废弃纽扣电池回收单，即可获得积分奖励，积分可在商城兑换实验耗材。',
        1, 1, 1),
       ('实验室安全回收指南',
        '回收前请务必用绝缘胶带封住电池正负极，不同型号分开存放。已鼓包或漏液的电池请单独密封并在备注中说明。',
        1, 1, 2),
       ('新学期积分翻倍活动',
        '活动期间回收纽扣电池，积分按 1kg = 200 积分计算。活动时间以首页公告为准。',
        2, 1, 3);
