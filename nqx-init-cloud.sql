/*
============================================================================
 纽启新生 (NQX) — 云端数据库一次性初始化脚本
----------------------------------------------------------------------------
 【用途】
   微信云托管的 MySQL 算力只有 0.25 CCU 起步，应用启动时 Flyway 要跑 16 个
   迁移脚本，耗时超过平台健康检查的容忍时间，容器还没监听 8080 就被判失败。
   所以把建表这一步从启动流程里挪出来，在这里手工执行一次。

 【执行完成后必须做的事】
   在云托管服务的环境变量里加上：
       SPRING_FLYWAY_ENABLED = false
   否则 Flyway 会发现「表存在但没有迁移历史表」而报错：
       Found non-empty schema(s) but no schema history table

 【重要】
   本脚本假定 nqx 库是空的。如果库里已有部分表，会报 table already exists。
   遇到这种情况，请先在 Navicat 里清空 nqx 库再执行本脚本：
       DROP DATABASE IF EXISTS nqx;
       CREATE DATABASE nqx DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

 【内容来源】
   按版本号顺序拼接 V1 ~ V16，内容与 Flyway 启动时执行的完全一致。
============================================================================
*/

-- 声明字符集：不写这一行，中文注释会被按 latin1 解析而报语法错误
SET NAMES utf8mb4;

-- 建表期间关闭外键检查，避免脚本顺序造成的临时性约束冲突
SET FOREIGN_KEY_CHECKS = 0;

USE `nqx`;

/* ------------------------------------------------------------------------
   V1__init_schema.sql
   ------------------------------------------------------------------------ */

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


/* ------------------------------------------------------------------------
   V2__init_data.sql
   ------------------------------------------------------------------------ */

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


/* ------------------------------------------------------------------------
   V3__add_trace_id_to_operation_log.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V3：为操作日志增加链路追踪 ID
-- ----------------------------------------------------------------------------
-- 背景：V1 建 sys_operation_log 时只考虑了「谁在什么时候做了什么」，
--       没预留 traceId。实际排查线上问题时，光知道"某次审核失败"不够，
--       还需要用 traceId 去日志文件里捞出那次请求的完整调用链
--       （拦截器 → Service → SQL → 异常堆栈）。
--
-- 这条迁移本身就是 Flyway 存在意义的最好说明：
--   表结构在 V1 已经上线并被执行过，此后**不允许再修改 V1**
--   （validate-on-migrate 会校验和并直接让应用启动失败）。
--   正确做法就是新增一条 V3 迁移，让"加字段"这件事也有版本、有记录、可回放。
--   团队里每个人、每套环境（开发/测试/生产）执行的都是同一串脚本，
--   不会出现「代码里有这个字段、线上库没有」的经典事故。
-- ============================================================================

ALTER TABLE `sys_operation_log`
    ADD COLUMN `trace_id` VARCHAR(32) NULL COMMENT '链路追踪ID，可用于在日志文件中定位该次请求的完整调用链' AFTER `id`,
    ADD INDEX `idx_trace_id` (`trace_id`);


/* ------------------------------------------------------------------------
   V4__unify_operator_type_comment.sql
   ------------------------------------------------------------------------ */

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


/* ------------------------------------------------------------------------
   V5__align_with_miniprogram.sql
   ------------------------------------------------------------------------ */

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


/* ------------------------------------------------------------------------
   V6__fix_unique_key_and_unify_pricing.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V6：修正唯一键缺陷 + 统一回收计价模型
-- ----------------------------------------------------------------------------
-- 本迁移解决两处由代码审查发现的一致性问题。
-- ============================================================================

-- ============================================================================
-- 一、修复 repair_apply 的唯一键
-- ----------------------------------------------------------------------------
-- 【问题】V5 建表时写成了 `UNIQUE KEY uk_apply_no (apply_no)`，
--        没有把 deleted 放进唯一索引。
--        这直接违反了 V1 建表脚本开头定下的「通用约定 4」：
--        逻辑删除字段必须与业务唯一键组成联合唯一索引。
--
-- 【后果】维修申请单被逻辑删除后，它的单号会被**永久占用**，
--        同一个单号再也无法生成。正常情况下单号带时间戳不会重复，
--        但一旦发生（如手工补数据、时钟回拨），就会插入失败且原因难以理解。
--
-- 【注意】同批创建的 product_review 用的是
--        `(order_id, product_id, deleted)`，是符合约定的 ——
--        说明这不是"有意区别对待"，而是单纯的遗漏。
-- ============================================================================
ALTER TABLE `repair_apply`
    DROP INDEX `uk_apply_no`,
    ADD UNIQUE KEY `uk_apply_no` (`apply_no`, `deleted`);

-- ============================================================================
-- 二、统一回收计价模型：按「单位个数」→ 按「重量」
-- ----------------------------------------------------------------------------
-- 【背景】项目里曾并存两套计价模型：
--        · V1 的 recycle_category.points_per_unit —— 每个电池值多少积分
--        · V5 的 site_setting.recycle_points_per_kg —— 每公斤值多少积分
--        而前端实际提交的是**重量**（weight / actual_weight），
--        旧版 Node 后端也是按重量结算（1kg = 100 积分）。两套模型并存会让
--        "这笔回收单到底该按哪个算"没有唯一答案，是必须在写业务代码前解决的问题。
--
-- 【决策】统一为按重量计价，并把单价细化到品类级别。
--        理由：
--        1. 与前端交互一致 —— 用户填的就是重量，不需要额外换算；
--        2. 比旧版更合理 —— 旧版所有电池都按 1kg = 100 积分，
--           但氧化银电池含银、回收价值显著高于碱性电池，
--           按品类区分单价才符合真实业务；
--        3. site_setting.recycle_points_per_kg 保留为「未选择品类时的兜底单价」，
--           两者语义清晰、不冲突。
-- ============================================================================
ALTER TABLE `recycle_category`
    CHANGE COLUMN `points_per_unit` `points_per_kg` INT NOT NULL DEFAULT 100
        COMMENT '每公斤可获积分（按重量计价，与前端提交的重量对应）';

ALTER TABLE `recycle_category`
    MODIFY COLUMN `unit` VARCHAR(20) NOT NULL DEFAULT 'kg'
        COMMENT '计量单位。统一为 kg —— 前端提交的是重量而非个数';

-- 品类单价：含贵金属的含量越高、处理难度越大，单价越高
UPDATE `recycle_category` SET `unit` = 'kg', `points_per_kg` = 100 WHERE `id` = 1; -- 锂锰 CR 系列
UPDATE `recycle_category` SET `unit` = 'kg', `points_per_kg` = 180 WHERE `id` = 2; -- 氧化银 SR 系列（含银）
UPDATE `recycle_category` SET `unit` = 'kg', `points_per_kg` = 60  WHERE `id` = 3; -- 碱性 LR 系列
UPDATE `recycle_category` SET `unit` = 'kg', `points_per_kg` = 150 WHERE `id` = 4; -- 镍氢/镍镉（有毒，处理成本高）
UPDATE `recycle_category` SET `unit` = 'kg', `points_per_kg` = 50  WHERE `id` = 5; -- 混装未分类（需二次分拣）
UPDATE `recycle_category` SET `unit` = 'kg', `points_per_kg` = 200 WHERE `id` = 6; -- 鼓包/漏液（危险废物处置补贴）

-- ============================================================================
-- 三、修正 V5 中 comment 里的表述
-- ----------------------------------------------------------------------------
-- biz_order.recycle_category_id 原来注释成"前端主要按重量计价，品类为辅助信息"，
-- 现在品类单价已生效，改为准确描述。
-- ============================================================================
ALTER TABLE `biz_order`
    MODIFY COLUMN `recycle_category_id` BIGINT DEFAULT NULL
        COMMENT '回收品类 ID：决定本单的积分单价（points_per_kg）；为空时使用站点配置的兜底单价';


/* ------------------------------------------------------------------------
   V7__add_recycle_method.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V7：订单表增加「回收方式」
-- ----------------------------------------------------------------------------
-- 【背景】读旧版小程序代码发现，回收单的 type 字段表达的不是「回收单还是采购单」，
--        而是【回收方式】：
--            type = 'send'   → 自行寄送（用户填快递单号）
--            type = 'pickup' → 上门回收（用户预约上门时间）
--        前端订单列表与详情页都依赖这个值展示文案：
--            item.type === 'send' ? '寄送处理' : '上门回收'
--
--        而新设计的 biz_order.order_type 表达的是「订单业务类型」（1回收 2采购），
--        两者语义完全不同，必须分开存，不能复用同一列。
--
-- 【为什么用 VARCHAR 而不是 TINYINT】
--        这一列是直接暴露给旧客户端的字段，保留字符串取值可以省掉一层映射，
--        也让数据库里的值自解释（看到 'pickup' 就知道是上门回收，
--        而看到 2 还得去翻注释确认是"上门"还是"采购"）。
--        取值集合固定且极小，用 VARCHAR(16) 的空间代价可以忽略。
-- ============================================================================

ALTER TABLE `biz_order`
    ADD COLUMN `recycle_method` VARCHAR(16) DEFAULT NULL
        COMMENT '回收方式 send=自行寄送 pickup=上门回收（仅回收单有值；对外字段名为 type）'
        AFTER `recycle_category_id`,
    ADD INDEX `idx_recycle_method` (`recycle_method`);


/* ------------------------------------------------------------------------
   V8__add_business_settings.sql
   ------------------------------------------------------------------------ */

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


/* ------------------------------------------------------------------------
   V9__add_purchase_points_setting.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V9：补充采购业务的可配置项
-- ----------------------------------------------------------------------------
-- 旧版在订单完成时发放积分（"完成订单 X 奖励"），但奖励规则硬编码在 Node 代码里。
-- 这里挪到 site_setting，规则为「每消费 1 元得 N 积分」，N 可运营调整。
--
-- 为什么不直接写进 V8？
--   V8 已经执行过，Flyway 会校验脚本校验和，改动已执行的迁移会让应用启动失败。
--   这正是迁移体系的价值：它强迫你不去改历史，而是追加新的变更记录。
-- ============================================================================

INSERT INTO `site_setting` (`setting_key`, `setting_value`, `remark`)
VALUES ('purchase_points_per_yuan', '1', '采购每消费 1 元可获得积分（旧版硬编码在订单完成逻辑里）')
ON DUPLICATE KEY UPDATE `remark` = VALUES(`remark`);


/* ------------------------------------------------------------------------
   V10__fix_quiz_unique_key.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V10：修正每日问答的幂等键
-- ----------------------------------------------------------------------------
-- 【问题】V1 建表时给 user_answer 加的是 `uk_user_question (user_id, question_id)`，
--        语义是「每人每题只能答一次（永远）」。
--
--        但真实的业务规则是【每人每天一题】：
--        · 旧版 Node 用 `WHERE user_id = ? AND DATE(created_at) = CURDATE()`
--          判断今天答没答过，答过就拒绝；
--        · 题库只有几十道题，用户连续参与十几天后，
--          (user_id, question_id) 的组合会逐渐耗尽 ——
--          到那时无论怎么点都提示"您已作答过该题目"，
--          而运营侧看数据只会觉得"这个用户突然不答题了"。
--
--        题目在<b>不同日期重复出现</b>是正常的（每日一题的题库本来就有限），
--        真正需要保证唯一的是「每人每天只有一条答题记录」。
--
-- 【修正】幂等键改为 `(user_id, question_date)`。
--        它同时承担两个职责：约束（每人每天一条）+ 查询索引（查今天的记录），
--        因此原来为查询单独建的 `idx_user_date` 就成了冗余，一并删掉 ——
--        同一个列组合建两个索引是纯粹的空间浪费与写入负担。
--
-- 【注意】这条迁移会影响已有的答题数据：若历史上同一用户在同一天答过多道题，
--        ADD UNIQUE 会失败。当前是开发库、且该表尚无生产数据，
--        因此直接改；若线上已有数据，正确做法是先清理/合并重复再建索引。
-- ============================================================================

ALTER TABLE `user_answer`
    DROP INDEX `uk_user_question`,
    DROP INDEX `idx_user_date`,
    ADD UNIQUE KEY `uk_user_date` (`user_id`, `question_date`);


/* ------------------------------------------------------------------------
   V11__add_repair_warranty_setting.sql
   ------------------------------------------------------------------------ */

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


/* ------------------------------------------------------------------------
   V12__relax_repair_contact_not_null.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V12：放宽维修申请的联系人约束
-- ----------------------------------------------------------------------------
-- 【问题】V1 建表时把 contact_name / contact_phone 定成了 NOT NULL，
--        但旧前端的维修申请页只提交 { order_id, description, images } ——
--        它从来就不收集联系人信息。
--
--        结果是：用户一提交维修申请就必然违反非空约束，
--        接口返回 500「系统繁忙」。这是一个上线后立刻会暴露、
--        但在开发阶段如果只测"能不能打开页面"就发现不了的问题。
--
-- 【为什么不改成"必填校验"？】
--        从数据完整性看，让用户填联系人是更严格的方案。但：
--        1. 那要改小程序页面（本次重构的目标之一是页面尽量不改）；
--        2. 用户资料里本来就有昵称与手机号，后端取来兜底即可 ——
--           让用户重复填一遍自己已有的信息是糟糕的设计；
--        3. 真的缺失时（用户没绑手机），客服可以在处理环节补录，
--           不该因此把申请直接挡在门外。
--
-- 【结论】列改为可空，由后端尽力填充默认值（见 RepairApplyService）。
--        这样既不挡用户，也不会因为"某一列没值"就整笔申请失败。
--
-- 注：MySQL 的 MODIFY COLUMN 只改列定义，不会动已有数据。
-- ============================================================================

ALTER TABLE `repair_apply`
    MODIFY COLUMN `contact_name` VARCHAR(50) NULL COMMENT '联系人（默认取用户昵称，可为空）',
    MODIFY COLUMN `contact_phone` VARCHAR(20) NULL COMMENT '联系电话（默认取用户手机号，可为空）';


/* ------------------------------------------------------------------------
   V13__fix_logical_delete_unique_key.sql
   ------------------------------------------------------------------------ */

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


/* ------------------------------------------------------------------------
   V14__add_app_user_management.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V14：新增「用户管理」菜单 + 清理联调测试留下的垃圾权限节点
-- ============================================================================
--
--  【背景一：管理后台看不到小程序用户】
--
--  原来的后台能管商品、订单、积分、题库、管理员，**唯独看不到小程序用户**。
--  这带来几个实际问题：
--
--    · 无法查某个用户的全部订单（用户反馈问题时只能让他自己截图）；
--    · 无法禁用恶意/异常账号（风控完全没有手段）；
--    · 无法看某个用户的累计回收量与碳减排（运营数据缺失一块）。
--
--  后端 `app_user` 表数据一直是完整的，只是没有管理入口。
--
--  【背景二：权限树里有 8 个测试垃圾节点】
--
--  联调系统管理模块时，为了验证"新增/移动/删除权限"这几个接口，
--  反复建了一批 `test:` 前缀的节点。接口测完之后**没有清理**，于是：
--
--    · 它们在权限树里真实存在（`test:menu:list` × 4、`test:subdir:list` × 4）；
--    · 管理后台的侧边栏会渲染成 4 个「临时子目录」和 4 个「联调测试菜单」；
--    · 超级管理员的权限里有它们，显得权限体系很乱。
--
--  这是"测试数据污染生产配置"的典型 —— 与 docs/06 第 31 条是同一类问题：
--  **测试必须留下可清理的痕迹，或者用完立刻删。**
--
--  ⚠️ 本次清理只删 perm_code 以 `test:` 开头的节点。
--     这是本项目联调测试的约定前缀，正式权限码不会用它
--     （正式码形如 `sys:user:list`、`order:recycle:audit`）。
--     用前缀而不是 id 列表，是为了把可能漏掉的同类节点也一并清掉。
-- ============================================================================

USE `nqx`;

-- ---------------------------------------------------------------------------
-- 1. 清理联调测试产生的权限节点
--
--    先删角色关联（否则会留下指向不存在节点的孤儿关联），再删节点本身。
-- ---------------------------------------------------------------------------
DELETE FROM `sys_role_permission`
WHERE `permission_id` IN (SELECT id FROM `sys_permission` WHERE `perm_code` LIKE 'test:%');

-- 子节点先删，父节点后删（虽然没有外键约束，但保持"先子后父"的习惯，
-- 万一将来加了外键也不会因此失败）
DELETE FROM `sys_permission` WHERE `perm_code` LIKE 'test:%' AND `parent_id` <> 0;
DELETE FROM `sys_permission` WHERE `perm_code` LIKE 'test:%';

-- ---------------------------------------------------------------------------
-- 2. 新增「用户管理」一级目录
--
--    为什么单独开一个一级菜单，而不是塞进「系统管理」：
--    「系统管理」下面放的是**管理员自己的账号、角色、权限、日志**，
--    而小程序用户是**业务对象**（平台服务的对象），性质不同。
--    混在一起会让管理员以为"删掉这个用户等于删掉一个后台账号"。
--
--    sort=50：排在「问答管理」(40) 之后、「系统管理」(90) 之前。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission`
    (`id`, `parent_id`, `perm_name`, `perm_code`, `perm_type`, `path`, `component`, `icon`,
     `sort`, `visible`, `status`, `deleted`)
VALUES (1100, 0, '用户管理', NULL, 1, '/user', NULL, 'Avatar', 50, 1, 1, 0);

-- ---------------------------------------------------------------------------
-- 3. 「小程序用户」菜单（列表页）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission`
    (`id`, `parent_id`, `perm_name`, `perm_code`, `perm_type`, `path`, `component`, `icon`,
     `sort`, `visible`, `status`, `deleted`)
VALUES (1110, 1100, '小程序用户', 'app:user:list', 2, '/user/list', 'user/list/index', 'User', 1, 1, 1, 0);

-- ---------------------------------------------------------------------------
-- 4. 「启用/禁用」按钮权限（perm_type=3 表示按钮级）
--
--    单独一个权限码而不是复用 app:user:list：
--    看得见用户列表 ≠ 有权限制账号。风控动作值得单独授权。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission`
    (`id`, `parent_id`, `perm_name`, `perm_code`, `perm_type`, `path`, `component`, `icon`,
     `sort`, `visible`, `status`, `deleted`)
VALUES (1111, 1110, '启用/禁用用户', 'app:user:edit', 3, NULL, NULL, NULL, 1, 1, 1, 0);

-- ---------------------------------------------------------------------------
-- 5. 授权给超级管理员
--
--    只给 SUPER_ADMIN，不自动给 OPERATOR/AUDITOR：
--    **按最小权限原则，新功能默认只开给超管**，需要谁用就让超管在
--    「角色管理 → 分配权限」里勾上。默认全开是很多权限事故的起点。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 1, id FROM `sys_permission` WHERE `id` IN (1100, 1110, 1111);


/* ------------------------------------------------------------------------
   V15__add_repair_management.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V15：新增「维修管理」菜单，并补上维修申请的保修判定字段
-- ============================================================================
--
--  【背景一：维修申请提交了，但没有任何人能处理】
--
--  小程序端 `POST /app/repair/apply` 一直在正常工作，用户能提交维修申请、
--  能看到自己的申请列表。但**管理后台完全没有维修相关的接口和页面** ——
--  没有列表、没有详情、没有受理/完成/驳回。
--
--  结果就是：用户提交后状态永远是「⏳ 等待商家审核」，
--  后台连这条申请存在都不知道。这是整个系统里唯一一条
--  "用户能提交、但没有任何人能处理"的断头流程。
--
--  状态 1/2/3/4 的定义其实早就定好了（后端 RepairApplyVO.statusText 与
--  小程序 getRepairStatusText 完全一致），缺的只是让状态能往前走的操作入口。
--
--  【背景二：保修判定没有落库】
--
--  提交维修时服务端会算「是否在保修期内」（warranty / paid），
--  但这个结果**只放在接口响应里返回给用户，从没写进数据库**
--  （RepairApplyService.apply 里算完 warrantyType 后，只传给了 VO，
--   日志里记了一笔，表里没有对应列）。
--
--  这恰恰是管理员最需要的信息：在保修期内 → 免费修；已过保 → 要报价。
--  没有它，管理员只能自己再去翻订单的收货时间去心算。
-- ============================================================================

USE `nqx`;

-- ---------------------------------------------------------------------------
-- 1. 补上保修判定字段
--
--    取值为 warranty / paid 两个值，用 VARCHAR(16) 而不是 TINYINT：
--    这个字段表达的是"业务结论"，不是状态码。用字符串让库里的值自解释，
--    将来若多出第三种（如 second_hand），也不需要重新定义数字含义。
--
--    允许 NULL：历史数据（当前为 0 条）无法回填，且"不知道"和"已过保"
--    是两件事，不该用同一个默认值糊过去。
-- ---------------------------------------------------------------------------
ALTER TABLE `repair_apply`
    ADD COLUMN `warranty_type` VARCHAR(16) NULL
        COMMENT '保修判定：warranty 保修期内 / paid 已过保需付费；NULL 未判定'
        AFTER `status`;

-- ---------------------------------------------------------------------------
-- 2. 新增「维修管理」一级目录
--
--    为什么不挂到「订单管理」下面：
--    维修是**订单完成之后**才发生的事，它的生命周期独立于订单状态机；
--    挂进订单管理会让人以为"处理维修会改变订单状态"，那是错的。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission`
    (`id`, `parent_id`, `perm_name`, `perm_code`, `perm_type`, `path`, `component`, `icon`,
     `sort`, `visible`, `status`, `deleted`)
VALUES (1200, 0, '维修管理', NULL, 1, '/repair', NULL, 'Tools', 55, 1, 1, 0);

-- ---------------------------------------------------------------------------
-- 3. 「维修工单」菜单
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission`
    (`id`, `parent_id`, `perm_name`, `perm_code`, `perm_type`, `path`, `component`, `icon`,
     `sort`, `visible`, `status`, `deleted`)
VALUES (1210, 1200, '维修工单', 'repair:list', 2, '/repair/list', 'repair/list/index', 'Tickets', 1, 1, 1, 0);

-- ---------------------------------------------------------------------------
-- 4. 「处理工单」按钮权限（受理 / 完成 / 驳回）
--
--    一个权限码覆盖三个动作：它们都是"处理"的不同结果，
--    没有业务场景会需要"能驳回但不能受理"。拆细只会增加配置负担。
-- ---------------------------------------------------------------------------
INSERT INTO `sys_permission`
    (`id`, `parent_id`, `perm_name`, `perm_code`, `perm_type`, `path`, `component`, `icon`,
     `sort`, `visible`, `status`, `deleted`)
VALUES (1211, 1210, '处理工单', 'repair:handle', 3, NULL, NULL, NULL, 1, 1, 1, 0);

-- ---------------------------------------------------------------------------
-- 5. 授权给超级管理员（最小权限原则，需要谁用由超管在角色管理里勾）
-- ---------------------------------------------------------------------------
INSERT INTO `sys_role_permission` (`role_id`, `permission_id`)
SELECT 1, id FROM `sys_permission` WHERE `id` IN (1200, 1210, 1211);


/* ------------------------------------------------------------------------
   V16__fix_recycle_category_menu_icon.sql
   ------------------------------------------------------------------------ */

-- ============================================================================
-- V16：修正「回收品类」菜单的图标名
-- ============================================================================
--
--  【问题】
--
--  管理后台侧边栏里「商品管理 → 回收品类」那一行**没有图标**，
--  而它上面的「分类管理」「商品列表」都有，看起来像风格不统一。
--
--  【根因】
--
--  数据库里这个菜单的 icon 写的是 `Recycle` —— 而 **Element Plus 根本没有
--  叫 `Recycle` 的图标**（它的 293 个图标里没有这个）。
--
--  前端是这么渲染的（`<el-icon><component :is="icon" /></el-icon>`）：
--  `<component is="不存在的组件名">` 在 Vue 里**不报错**，
--  只是渲染成空 —— 于是那一行就是"文字前面空了一块"。
--
--  这和 docs/06 第 38 条是同一类问题：**字符串指向的"外键"不存在时，
--  界面静默降级，没有任何报错可看。**
--
--  【为什么选 PriceTag】
--
--  「回收品类」这个页面管的核心是 **每公斤给多少积分**（points_per_kg）——
--  它就是一张回收定价表。`PriceTag`（价签）语义最贴切，
--  视觉上也与「分类管理」的 Grid、「商品列表」的 Shop 区分得开。
-- ============================================================================

USE `nqx`;

UPDATE `sys_permission`
SET `icon` = 'PriceTag'
WHERE `id` = 2030
  AND `perm_code` = 'recycle:category:list';

-- 顺带把可能存在的其它无效图标清零，避免以后又出现"图标位置空一块"。
-- 这里不写死具体值，而是把"明显不是 Element Plus 命名风格"的排除掉
-- （真伪校验交给 scripts/audit-menu-icons.js，那个脚本能拿到完整的图标清单）。
UPDATE `sys_permission`
SET `icon` = NULL
WHERE `icon` = 'Recycle';


SET FOREIGN_KEY_CHECKS = 1;

/*
----------------------------------------------------------------------------
 执行完成后，用下面这句确认表数量（期望 27 张业务表）：

   SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema = 'nqx';
----------------------------------------------------------------------------
*/
