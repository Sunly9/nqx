-- ============================================================================
-- 纽启新生 (NQX) — 表结构初始化
-- Flyway: V1__init_schema.sql
-- ----------------------------------------------------------------------------
-- 数据库：nqx    引擎：InnoDB    字符集：utf8mb4 / utf8mb4_0900_ai_ci
-- 依赖：需先执行 sql/00_bootstrap.sql 建库建账号
--
-- 【通用约定】
--   1. 主键统一 `id BIGINT AUTO_INCREMENT`（单库单表，自增性能优于 UUID/雪花）。
--   2. 金额与积分统一用 `INT` 存「分」/「积分整数」，禁止 FLOAT/DOUBLE。
--      原因：二进制浮点无法精确表示 0.1，累计运算会出现 0.01 级误差。
--   3. 时间统一 `DATETIME`（不用 TIMESTAMP，避免 2038 问题与隐式时区转换）。
--   4. 逻辑删除统一 `deleted TINYINT`，且与业务唯一键组成联合唯一索引，
--      否则「删掉 A 后无法再建同名 A」。详见 docs/02-数据库设计说明.md。
--   5. 所有状态字段用 TINYINT/SMALLINT + Java 枚举，杜绝魔法数字散落业务代码。
-- ============================================================================

SET NAMES utf8mb4;

-- ============================================================================
-- 一、管理后台 RBAC 权限模型（用户 - 角色 - 权限 三级）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1.1 后台管理员
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_user`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`        VARCHAR(50)  NOT NULL COMMENT '登录账号',
    `password`        VARCHAR(100) NOT NULL COMMENT 'BCrypt 密文（不可逆，禁止明文/MD5）',
    `real_name`       VARCHAR(50)           DEFAULT NULL COMMENT '姓名',
    `phone`           VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    `email`           VARCHAR(100)          DEFAULT NULL COMMENT '邮箱',
    `avatar`          VARCHAR(500)          DEFAULT NULL COMMENT '头像 URL',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1启用',
    `last_login_time` DATETIME              DEFAULT NULL COMMENT '最后登录时间',
    `last_login_ip`   VARCHAR(50)           DEFAULT NULL COMMENT '最后登录 IP',
    `remark`          VARCHAR(255)          DEFAULT NULL COMMENT '备注',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`, `deleted`),
    KEY `idx_phone` (`phone`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='后台管理员';

-- ---------------------------------------------------------------------------
-- 1.2 角色
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_role`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_code`   VARCHAR(50) NOT NULL COMMENT '角色标识 如 SUPER_ADMIN',
    `role_name`   VARCHAR(50) NOT NULL COMMENT '角色名称',
    `description` VARCHAR(255)         DEFAULT NULL COMMENT '描述',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1启用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_code` (`role_code`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='后台角色';

-- ---------------------------------------------------------------------------
-- 1.3 权限（目录 / 菜单 / 按钮 三合一树）
--     后端据此下发菜单树，前端动态 addRoute + v-permission 按钮级控制
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_permission`
(
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`   BIGINT       NOT NULL DEFAULT 0 COMMENT '父级 ID，0 为根',
    `perm_name`   VARCHAR(50)  NOT NULL COMMENT '权限名称',
    `perm_code`   VARCHAR(100)          DEFAULT NULL COMMENT '权限标识 如 order:recycle:audit',
    `perm_type`   TINYINT      NOT NULL COMMENT '类型 1目录 2菜单 3按钮',
    `path`        VARCHAR(200)          DEFAULT NULL COMMENT '前端路由地址',
    `component`   VARCHAR(200)          DEFAULT NULL COMMENT '前端组件路径',
    `icon`        VARCHAR(100)          DEFAULT NULL COMMENT '图标',
    `sort`        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `visible`     TINYINT      NOT NULL DEFAULT 1 COMMENT '是否显示 0隐藏 1显示',
    `status`      TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1启用',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_parent_sort` (`parent_id`, `sort`),
    KEY `idx_perm_code` (`perm_code`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='后台权限（目录/菜单/按钮）';

-- ---------------------------------------------------------------------------
-- 1.4 管理员 - 角色 关联
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_user_role`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '管理员 ID',
    `role_id`     BIGINT   NOT NULL COMMENT '角色 ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role_id` (`role_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='管理员角色关联';

-- ---------------------------------------------------------------------------
-- 1.5 角色 - 权限 关联
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_role_permission`
(
    `id`            BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `role_id`       BIGINT   NOT NULL COMMENT '角色 ID',
    `permission_id` BIGINT   NOT NULL COMMENT '权限 ID',
    `create_time`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
    KEY `idx_permission_id` (`permission_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='角色权限关联';

-- ============================================================================
-- 二、小程序用户与身份
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 2.1 小程序用户
--     注意：微信自 2022 起废弃 wx.getUserProfile，昵称/头像需用户通过
--     <button open-type="chooseAvatar"> 与 <input type="nickname"> 主动提供，
--     故 nickname/avatar_url 允许为空，登录后逐步补全。
-- ---------------------------------------------------------------------------
CREATE TABLE `app_user`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `openid`          VARCHAR(64)  NOT NULL COMMENT '微信 openid（当前小程序唯一标识）',
    `unionid`         VARCHAR(64)           DEFAULT NULL COMMENT '微信 unionid（多应用打通时使用）',
    `nickname`        VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    `avatar_url`      VARCHAR(500)          DEFAULT NULL COMMENT '头像 URL',
    `phone`           VARCHAR(20)           DEFAULT NULL COMMENT '手机号',
    `real_name`       VARCHAR(50)           DEFAULT NULL COMMENT '真实姓名',
    `student_no`      VARCHAR(50)           DEFAULT NULL COMMENT '学号 / 工号',
    `college`         VARCHAR(100)          DEFAULT NULL COMMENT '学院',
    `lab_name`        VARCHAR(100)          DEFAULT NULL COMMENT '实验室名称',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1正常',
    `last_login_time` DATETIME              DEFAULT NULL COMMENT '最后登录时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_openid` (`openid`, `deleted`),
    KEY `idx_phone` (`phone`),
    KEY `idx_student_no` (`student_no`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='小程序用户';

-- ---------------------------------------------------------------------------
-- 2.2 用户身份（一个用户可同时是回收方和采购方，故用关联表而非单字段）
-- ---------------------------------------------------------------------------
CREATE TABLE `app_user_role`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
    `role_type`   TINYINT  NOT NULL COMMENT '身份 1回收方 2采购方',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role_type` (`user_id`, `role_type`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='小程序用户身份（回收方/采购方）';

-- ============================================================================
-- 三、商品与回收品类
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 3.1 商品分类
-- ---------------------------------------------------------------------------
CREATE TABLE `product_category`
(
    `id`          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `parent_id`   BIGINT      NOT NULL DEFAULT 0 COMMENT '父级 ID，0 为根',
    `name`        VARCHAR(50) NOT NULL COMMENT '分类名称',
    `icon`        VARCHAR(500)         DEFAULT NULL COMMENT '图标 URL',
    `sort`        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`      TINYINT     NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1启用',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_parent_sort` (`parent_id`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商品分类';

-- ---------------------------------------------------------------------------
-- 3.2 商品（采购商品 与 积分兑换商品 统一建模，用 product_type 区分）
-- ---------------------------------------------------------------------------
CREATE TABLE `product`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `category_id`    BIGINT       NOT NULL COMMENT '分类 ID',
    `name`           VARCHAR(100) NOT NULL COMMENT '商品名称',
    `sub_title`      VARCHAR(200)          DEFAULT NULL COMMENT '副标题/卖点',
    `spec`           VARCHAR(100)          DEFAULT NULL COMMENT '规格 如 CR2032 3V',
    `cover_url`      VARCHAR(500)          DEFAULT NULL COMMENT '封面图',
    `images`         JSON                  DEFAULT NULL COMMENT '轮播图 URL 数组',
    `description`    TEXT                  DEFAULT NULL COMMENT '详情（长文本）',
    `product_type`   TINYINT      NOT NULL DEFAULT 1 COMMENT '类型 1采购商品 2积分兑换商品 3两者皆可',
    `price`          INT          NOT NULL DEFAULT 0 COMMENT '单价（单位：分）',
    `points_price`   INT          NOT NULL DEFAULT 0 COMMENT '积分价（积分兑换时使用）',
    `unit`           VARCHAR(20)  NOT NULL DEFAULT '个' COMMENT '单位',
    `stock`          INT          NOT NULL DEFAULT 0 COMMENT '库存（超卖保护的扣减基数）',
    `sold_count`     INT          NOT NULL DEFAULT 0 COMMENT '累计销量',
    `exchange_limit` INT          NOT NULL DEFAULT 0 COMMENT '每人限兑数量，0 表示不限',
    `is_hot`         TINYINT      NOT NULL DEFAULT 0 COMMENT '是否热门 0否 1是',
    `status`         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态 0下架 1上架',
    `sort`           INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_category_status_sort` (`category_id`, `status`, `sort`),
    KEY `idx_type_status` (`product_type`, `status`),
    KEY `idx_is_hot` (`is_hot`, `status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='商品';

-- ---------------------------------------------------------------------------
-- 3.3 回收品类（废纽扣电池按品类/单位计积分，与商品是两套概念，独立建表）
-- ---------------------------------------------------------------------------
CREATE TABLE `recycle_category`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `name`            VARCHAR(50)  NOT NULL COMMENT '品类名称 如 锂锰纽扣电池',
    `icon`            VARCHAR(500)          DEFAULT NULL COMMENT '图标 URL',
    `unit`            VARCHAR(20)  NOT NULL DEFAULT '节' COMMENT '计量单位 节/个/kg',
    `points_per_unit` INT          NOT NULL DEFAULT 0 COMMENT '每单位可获积分',
    `description`     VARCHAR(500)          DEFAULT NULL COMMENT '回收说明/注意事项',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0禁用 1启用',
    `sort`            INT          NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_status_sort` (`status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='回收品类';

-- ============================================================================
-- 四、购物车与收货地址
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 4.1 购物车（联合唯一键兜住「重复加购」并发）
-- ---------------------------------------------------------------------------
CREATE TABLE `cart`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
    `product_id`  BIGINT   NOT NULL COMMENT '商品 ID',
    `quantity`    INT      NOT NULL DEFAULT 1 COMMENT '数量',
    `selected`    TINYINT  NOT NULL DEFAULT 1 COMMENT '是否勾选 0否 1是',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_product` (`user_id`, `product_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='购物车';

-- ---------------------------------------------------------------------------
-- 4.2 收货地址
-- ---------------------------------------------------------------------------
CREATE TABLE `user_address`
(
    `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        BIGINT       NOT NULL COMMENT '用户 ID',
    `receiver_name`  VARCHAR(50)  NOT NULL COMMENT '收货人',
    `receiver_phone` VARCHAR(20)  NOT NULL COMMENT '收货电话',
    `province`       VARCHAR(50)           DEFAULT NULL COMMENT '省',
    `city`           VARCHAR(50)           DEFAULT NULL COMMENT '市',
    `district`       VARCHAR(50)           DEFAULT NULL COMMENT '区/县',
    `detail_address` VARCHAR(255) NOT NULL COMMENT '详细地址',
    `postal_code`    VARCHAR(10)           DEFAULT NULL COMMENT '邮编',
    `tag`            VARCHAR(20)           DEFAULT NULL COMMENT '标签 实验室/宿舍/家',
    `is_default`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否默认 0否 1是',
    `create_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_user_default` (`user_id`, `is_default`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户收货地址';

-- ============================================================================
-- 五、订单（主表 + 明细 + 流转日志 三件套）
--     回收单与采购单统一建模，用 order_type 区分，状态机各自独立（见 Java 枚举）。
--     表名用 biz_order 而非 order：ORDER 是 MySQL 保留关键字，用 order 会到处需要
--     反引号，极易在动态 SQL 中出错。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 5.1 订单主表
-- ---------------------------------------------------------------------------
CREATE TABLE `biz_order`
(
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_no`        VARCHAR(32) NOT NULL COMMENT '订单号（业务唯一，对外暴露）',
    `user_id`         BIGINT      NOT NULL COMMENT '下单用户 ID',
    `order_type`      TINYINT     NOT NULL COMMENT '订单类型 1回收单 2采购单',
    `status`          SMALLINT    NOT NULL COMMENT '订单状态（两套状态机，见 OrderStatusEnum）',
    `total_quantity`  INT         NOT NULL DEFAULT 0 COMMENT '总数量',
    `total_amount`    INT         NOT NULL DEFAULT 0 COMMENT '总金额（分）',
    `total_points`    INT         NOT NULL DEFAULT 0 COMMENT '总积分',
    `pay_type`        TINYINT              DEFAULT NULL COMMENT '支付方式 1积分支付 2模拟支付 3线下结算',
    `pay_time`        DATETIME             DEFAULT NULL COMMENT '支付时间',
    `address_id`      BIGINT               DEFAULT NULL COMMENT '收货地址 ID',
    `contact_name`    VARCHAR(50)          DEFAULT NULL COMMENT '联系人（下单时快照）',
    `contact_phone`   VARCHAR(20)          DEFAULT NULL COMMENT '联系电话（下单时快照）',
    `address_detail`  VARCHAR(500)         DEFAULT NULL COMMENT '完整地址（下单时快照）',
    `expect_time`     DATETIME             DEFAULT NULL COMMENT '回收预约上门时间',
    `auditor_id`      BIGINT               DEFAULT NULL COMMENT '审核管理员 ID',
    `audit_time`      DATETIME             DEFAULT NULL COMMENT '审核时间',
    `audit_remark`    VARCHAR(255)         DEFAULT NULL COMMENT '审核备注',
    `reject_reason`   VARCHAR(255)         DEFAULT NULL COMMENT '驳回原因',
    `finish_time`     DATETIME             DEFAULT NULL COMMENT '完成时间',
    `cancel_time`     DATETIME             DEFAULT NULL COMMENT '取消时间',
    `cancel_reason`   VARCHAR(255)         DEFAULT NULL COMMENT '取消原因',
    `user_remark`     VARCHAR(500)         DEFAULT NULL COMMENT '用户备注',
    `admin_remark`    VARCHAR(500)         DEFAULT NULL COMMENT '管理员备注',
    `attachment_urls` JSON                 DEFAULT NULL COMMENT '凭证图片 URL 数组',
    `version`         INT         NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    -- 小程序端「我的订单」：必定带 user_id，按类型/状态筛选并按时间倒序 → 避免 filesort
    KEY `idx_user_type_status_time` (`user_id`, `order_type`, `status`, `create_time`),
    -- 后台「按类型 + 状态」筛选分页：不带 user_id，故需要独立的左前缀组合
    KEY `idx_type_status_time` (`order_type`, `status`, `create_time`),
    -- 后台「全部订单按状态」筛选：跨类型只看状态
    KEY `idx_status_time` (`status`, `create_time`),
    -- 看板按时间范围聚合（纯时间范围无法命中上面任何组合的最左前缀）
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单主表（回收单/采购单）';

-- ---------------------------------------------------------------------------
-- 5.2 订单明细
--     明细必须存商品「快照」（名称/规格/单价/积分/图片），
--     否则商品后续改价改名会导致历史订单数据错乱——经典业务陷阱。
-- ---------------------------------------------------------------------------
CREATE TABLE `order_item`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`        BIGINT       NOT NULL COMMENT '订单 ID',
    `order_no`        VARCHAR(32)  NOT NULL COMMENT '订单号（冗余，便于排查）',
    `item_type`       TINYINT      NOT NULL COMMENT '条目类型 1商品 2回收品类',
    `item_id`         BIGINT       NOT NULL COMMENT '商品/回收品类 ID',
    `item_name`       VARCHAR(100) NOT NULL COMMENT '名称快照',
    `item_spec`       VARCHAR(100)          DEFAULT NULL COMMENT '规格快照',
    `item_unit`       VARCHAR(20)           DEFAULT NULL COMMENT '单位快照',
    `item_cover`      VARCHAR(500)          DEFAULT NULL COMMENT '图片快照',
    `price`           INT          NOT NULL DEFAULT 0 COMMENT '单价快照（分）',
    `points`          INT          NOT NULL DEFAULT 0 COMMENT '单位积分快照',
    `quantity`        INT          NOT NULL DEFAULT 0 COMMENT '数量',
    `subtotal_amount` INT          NOT NULL DEFAULT 0 COMMENT '小计金额（分）',
    `subtotal_points` INT          NOT NULL DEFAULT 0 COMMENT '小计积分',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_id` (`order_id`),
    KEY `idx_item` (`item_type`, `item_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单明细（含商品快照）';

-- ---------------------------------------------------------------------------
-- 5.3 订单流转日志（进度查询、状态机审计、超时取消溯源都靠它）
-- ---------------------------------------------------------------------------
CREATE TABLE `order_flow`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `order_id`      BIGINT      NOT NULL COMMENT '订单 ID',
    `order_no`      VARCHAR(32) NOT NULL COMMENT '订单号（冗余）',
    `from_status`   SMALLINT    NOT NULL COMMENT '原状态',
    `to_status`     SMALLINT    NOT NULL COMMENT '新状态',
    `action`        VARCHAR(50) NOT NULL COMMENT '流转动作 如 SUBMIT/AUDIT_PASS/CANCEL',
    `operator_id`   BIGINT               DEFAULT NULL COMMENT '操作人 ID',
    `operator_type` TINYINT     NOT NULL DEFAULT 1 COMMENT '操作人类型 1用户 2管理员 3系统',
    `remark`        VARCHAR(255)         DEFAULT NULL COMMENT '备注',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_order_time` (`order_id`, `create_time`),
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='订单流转日志';

-- ============================================================================
-- 六、积分（账户 + 流水，标准金融双表模型）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 6.1 积分账户（余额唯一真相来源）
-- ---------------------------------------------------------------------------
CREATE TABLE `points_account`
(
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT   NOT NULL COMMENT '用户 ID',
    `balance`     INT      NOT NULL DEFAULT 0 COMMENT '当前可用积分',
    `total_earn`  INT      NOT NULL DEFAULT 0 COMMENT '累计获得',
    `total_spend` INT      NOT NULL DEFAULT 0 COMMENT '累计消耗',
    `version`     INT      NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='积分账户';

-- ---------------------------------------------------------------------------
-- 6.2 积分流水
--     uk_user_biz_type 是整套积分体系「防重复发放 / 防重复兑换」的最终防线：
--     并发下应用层 if 判断必然失效，唯一索引 + 捕获 DuplicateKeyException 才可靠。
-- ---------------------------------------------------------------------------
CREATE TABLE `points_record`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT      NOT NULL COMMENT '用户 ID',
    `change_type`   TINYINT     NOT NULL COMMENT '变动类型 1注册奖励 2每日问答 3回收返积分 4积分兑换 5兑换退回 6管理员调整',
    `change_value`  INT         NOT NULL COMMENT '变动值，正数为增，负数为减',
    `balance_after` INT         NOT NULL COMMENT '变动后余额（对账用）',
    `biz_no`        VARCHAR(64) NOT NULL COMMENT '业务单号（幂等键）',
    `biz_id`        BIGINT               DEFAULT NULL COMMENT '业务主键 ID',
    `remark`        VARCHAR(255)         DEFAULT NULL COMMENT '备注',
    `operator_id`   BIGINT               DEFAULT NULL COMMENT '操作人（管理员调整时）',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_biz_type` (`user_id`, `biz_no`, `change_type`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_type_time` (`change_type`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='积分流水';

-- ============================================================================
-- 七、每日问答
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 7.1 题库
-- ---------------------------------------------------------------------------
CREATE TABLE `question`
(
    `id`             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `content`        VARCHAR(1000) NOT NULL COMMENT '题干',
    `question_type`  TINYINT       NOT NULL DEFAULT 1 COMMENT '题型 1单选 2多选 3判断',
    `options`        JSON          DEFAULT NULL COMMENT '选项数组 [{"key":"A","value":"..."}]',
    `correct_answer` VARCHAR(50)   NOT NULL COMMENT '正确答案 如 A 或 AB',
    `analysis`       VARCHAR(1000) DEFAULT NULL COMMENT '答案解析',
    `points`         INT           NOT NULL DEFAULT 0 COMMENT '答对可得积分',
    `difficulty`     TINYINT       NOT NULL DEFAULT 1 COMMENT '难度 1易 2中 3难',
    `publish_date`   DATE          DEFAULT NULL COMMENT '发布/生效日期',
    `status`         TINYINT       NOT NULL DEFAULT 0 COMMENT '状态 0草稿 1已发布 2已下线',
    `sort`           INT           NOT NULL DEFAULT 0 COMMENT '排序',
    `create_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除 0未删 1已删',
    PRIMARY KEY (`id`),
    KEY `idx_date_status` (`publish_date`, `status`),
    KEY `idx_status_sort` (`status`, `sort`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='每日问答题库';

-- ---------------------------------------------------------------------------
-- 7.2 答题记录
--     uk_user_question：每人每题只能答一次 —— 防重复提交、防重复加分的幂等基础，
--     同时兼容「每日一题」与「每日多题」两种玩法。
-- ---------------------------------------------------------------------------
CREATE TABLE `user_answer`
(
    `id`            BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`       BIGINT      NOT NULL COMMENT '用户 ID',
    `question_id`   BIGINT      NOT NULL COMMENT '题目 ID',
    `question_date` DATE        NOT NULL COMMENT '答题归属日期（用于每日统计/排行）',
    `user_answer`   VARCHAR(50)          DEFAULT NULL COMMENT '用户答案',
    `is_correct`    TINYINT     NOT NULL DEFAULT 0 COMMENT '是否正确 0否 1是',
    `gain_points`   INT         NOT NULL DEFAULT 0 COMMENT '实际获得积分',
    `cost_time`     INT         NOT NULL DEFAULT 0 COMMENT '答题耗时（秒）',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '答题时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_question` (`user_id`, `question_id`),
    KEY `idx_user_date` (`user_id`, `question_date`),
    KEY `idx_question` (`question_id`),
    KEY `idx_date_correct` (`question_date`, `is_correct`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='用户答题记录';

-- ============================================================================
-- 八、日志（操作日志 + 登录日志）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 8.1 操作日志（由 @Log 注解 + AOP 异步落库）
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_operation_log`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`         BIGINT                DEFAULT NULL COMMENT '操作人 ID',
    `user_type`       TINYINT      NOT NULL DEFAULT 1 COMMENT '操作人类型 1管理员 2小程序用户',
    `user_name`       VARCHAR(50)           DEFAULT NULL COMMENT '操作人名称',
    `module`          VARCHAR(50)           DEFAULT NULL COMMENT '业务模块',
    `operation`       VARCHAR(100)          DEFAULT NULL COMMENT '操作描述',
    `method`          VARCHAR(200)          DEFAULT NULL COMMENT '目标方法',
    `request_url`     VARCHAR(255)          DEFAULT NULL COMMENT '请求 URL',
    `request_method`  VARCHAR(10)           DEFAULT NULL COMMENT 'HTTP 方法',
    `request_param`   TEXT                  DEFAULT NULL COMMENT '请求参数（脱敏后）',
    `response_result` TEXT                  DEFAULT NULL COMMENT '响应结果（截断）',
    `ip`              VARCHAR(50)           DEFAULT NULL COMMENT 'IP',
    `location`        VARCHAR(100)          DEFAULT NULL COMMENT 'IP 归属地',
    `status`          TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0失败 1成功',
    `error_msg`       VARCHAR(1000)         DEFAULT NULL COMMENT '异常信息',
    `cost_time`       BIGINT       NOT NULL DEFAULT 0 COMMENT '耗时（毫秒）',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `create_time`),
    KEY `idx_create_time` (`create_time`),
    KEY `idx_module_time` (`module`, `create_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='操作日志';

-- ---------------------------------------------------------------------------
-- 8.2 登录日志
-- ---------------------------------------------------------------------------
CREATE TABLE `sys_login_log`
(
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    BIGINT                DEFAULT NULL COMMENT '用户 ID',
    `user_type`  TINYINT      NOT NULL DEFAULT 1 COMMENT '用户类型 1管理员 2小程序用户',
    `user_name`  VARCHAR(50)           DEFAULT NULL COMMENT '账号',
    `ip`         VARCHAR(50)           DEFAULT NULL COMMENT 'IP',
    `location`   VARCHAR(100)          DEFAULT NULL COMMENT 'IP 归属地',
    `browser`    VARCHAR(100)          DEFAULT NULL COMMENT '浏览器',
    `os`         VARCHAR(100)          DEFAULT NULL COMMENT '操作系统',
    `status`     TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 0失败 1成功',
    `msg`        VARCHAR(255)          DEFAULT NULL COMMENT '提示信息',
    `login_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `login_time`),
    KEY `idx_login_time` (`login_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='登录日志';
