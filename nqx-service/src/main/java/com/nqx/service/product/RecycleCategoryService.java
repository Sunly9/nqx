package com.nqx.service.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.OrderItemMapper;
import com.nqx.mapper.RecycleCategoryMapper;
import com.nqx.pojo.dto.product.RecycleCategoryQuery;
import com.nqx.pojo.dto.product.RecycleCategorySaveDTO;
import com.nqx.pojo.entity.OrderItem;
import com.nqx.pojo.entity.RecycleCategory;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.product.RecycleCategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 回收品类服务。
 *
 * <p>回收品类与商品是两套独立概念（见建表脚本的说明）：商品是"卖出去的"，
 * 回收品类是"收进来的"。它们唯一的关联点在订单明细上 ——
 * {@code order_item.item_type} 用来区分两者。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecycleCategoryService {

    /**
     * {@code order_item.item_type} 的取值：1商品 2回收品类，
     * 与 V1 建表脚本的列注释保持一致。
     *
     * <p>写成常量而不是在 SQL 里裸写 2：这个数字在订单域多处出现，
     * 裸写一旦有一处写成 1，就会出现「品类明明被引用却判定为可删」的错删事故。
     */
    private static final int ORDER_ITEM_TYPE_RECYCLE = 2;

    private final RecycleCategoryMapper recycleCategoryMapper;
    private final OrderItemMapper orderItemMapper;

    // ==================== 查询 ====================

    /**
     * 管理端多条件分页检索。
     *
     * <p>分页参数先经 {@code safePageNum/safePageSize} 归一化再交给分页插件：
     * 前端传 {@code pageSize = 999999} 会被夹到 100，
     * 传 {@code pageNum = 0} 会被修正为 1，避免生成非法 LIMIT。
     */
    public PageResult<RecycleCategoryVO> page(RecycleCategoryQuery query) {
        Page<RecycleCategory> page = new Page<>(query.safePageNum(), query.safePageSize());
        return PageUtil.of(recycleCategoryMapper.selectPageByCondition(page, query), RecycleCategoryVO::from);
    }

    /**
     * 启用中的回收品类列表，供小程序端展示。
     *
     * <p>状态条件由服务端写死为「启用」，不接受前端传参：
     * 否则只要请求里带上 {@code status=0}，禁用中的品类就会被展示出来并进入下单流程。
     */
    public List<RecycleCategoryVO> listEnabled() {
        List<RecycleCategory> list = recycleCategoryMapper.selectList(
                Wrappers.<RecycleCategory>lambdaQuery()
                        .eq(RecycleCategory::getStatus, CommonConstants.STATUS_ENABLED)
                        .orderByAsc(RecycleCategory::getSort)
                        .orderByAsc(RecycleCategory::getId));
        return list.stream().map(RecycleCategoryVO::from).toList();
    }

    // ==================== 增删改 ====================

    @Transactional(rollbackFor = Exception.class)
    public Long create(RecycleCategorySaveDTO dto) {
        RecycleCategory entity = new RecycleCategory();
        applyDto(entity, dto);
        // 与建表脚本的 `status TINYINT NOT NULL DEFAULT 1` 保持一致。
        // 服务端与数据库对同一个字段给出不同的默认值，是最容易埋雷的地方：
        // 走接口创建和直接导入 SQL 会得到状态不同的数据，排查时极难想到。
        if (entity.getStatus() == null) {
            entity.setStatus(CommonConstants.STATUS_ENABLED);
        }
        entity.setDeleted(CommonConstants.DELETED_NO);
        recycleCategoryMapper.insert(entity);

        log.info("新增回收品类 | id={} | name={} | unit={} | pointsPerKg={}",
                entity.getId(), entity.getName(), entity.getUnit(), entity.getPointsPerKg());
        return entity.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(RecycleCategorySaveDTO dto) {
        // 新增与修改共用 DTO，「修改必须带 id」只能在业务层断言
        BizException.assertNotNull(dto.getId(), ResultCode.PARAM_ERROR);
        RecycleCategory exists = recycleCategoryMapper.selectById(dto.getId());
        BizException.assertNotNull(exists, ResultCode.RECYCLE_CATEGORY_NOT_FOUND);

        RecycleCategory entity = new RecycleCategory();
        entity.setId(dto.getId());
        applyDto(entity, dto);
        // 更新策略是 not_null：DTO 里为 null 的字段会被跳过（「不传」=「不改」）。
        // 若要清空图标 / 回收说明，前端要传空字符串 "" 而不是 null。
        recycleCategoryMapper.updateById(entity);

        log.info("修改回收品类 | id={} | name={}", dto.getId(), dto.getName());
    }

    /**
     * 删除回收品类。
     *
     * <p>已被订单明细引用的一律不允许删除：回收单是用户与积分流水的事实依据，
     * 品类删除后订单详情页会显示不出这条明细，用户会直接来问「我的回收记录去哪了」。
     * 运营的正确做法是把它<b>禁用</b>（status = 0），既不再对新用户展示，
     * 又保住历史数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        RecycleCategory exists = recycleCategoryMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.RECYCLE_CATEGORY_NOT_FOUND);

        // order_item 表没有 deleted 列（明细只允许追加、不允许删除），
        // 所以这里的计数天然覆盖全部历史明细，不需要也不能加 deleted = 0。
        Long refCount = orderItemMapper.selectCount(
                Wrappers.<OrderItem>lambdaQuery()
                        .eq(OrderItem::getItemType, ORDER_ITEM_TYPE_RECYCLE)
                        .eq(OrderItem::getItemId, id));
        if (refCount != null && refCount > 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "该回收品类已被订单引用，无法删除");
        }

        // @TableLogic 会改写成 UPDATE recycle_category SET deleted = 1 WHERE id = ?
        recycleCategoryMapper.deleteById(id);
        log.info("删除回收品类 | id={} | name={}", id, exists.getName());
    }

    // ==================== 私有方法 ====================

    /** DTO → 实体字段拷贝 */
    private void applyDto(RecycleCategory entity, RecycleCategorySaveDTO dto) {
        entity.setName(dto.getName());
        entity.setIcon(dto.getIcon());
        entity.setUnit(dto.getUnit());
        entity.setPointsPerKg(dto.getPointsPerKg() == null ? 0 : dto.getPointsPerKg());
        entity.setDescription(dto.getDescription());
        entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
        entity.setStatus(dto.getStatus());
    }
}
