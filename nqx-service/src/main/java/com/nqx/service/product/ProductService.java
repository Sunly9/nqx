package com.nqx.service.product;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.constant.RedisKeyConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.ProductCategoryMapper;
import com.nqx.mapper.ProductMapper;
import com.nqx.pojo.dto.product.ProductQuery;
import com.nqx.pojo.dto.product.ProductSaveDTO;
import com.nqx.pojo.entity.Product;
import com.nqx.pojo.entity.ProductCategory;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.product.ProductDetailVO;
import com.nqx.pojo.vo.product.ProductVO;
import com.nqx.service.cache.RedisCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductMapper productMapper;
    private final ProductCategoryMapper productCategoryMapper;
    private final RedisCacheService redisCacheService;

    // ==================== 管理端 ====================

    /** 多条件分页检索（条件拼装在 ProductMapper.xml 中） */
    public PageResult<ProductVO> page(ProductQuery query) {
        Page<ProductVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        return PageUtil.of(productMapper.selectPageByCondition(page, query));
    }

    /**
     * 商品详情。
     *
     * <p><b>这是本项目 Redis 缓存的主战场</b>：商品详情是典型的热点读多写少数据，
     * 因此走「逻辑过期 + 互斥重建 + 空值防穿透」的完整缓存链路
     * （实现见 {@link RedisCacheService}）。
     *
     * <p>注意这里传的 loader 是一个 lambda，缓存命中时<b>根本不会执行</b>，
     * 也就不会产生任何数据库查询。
     */
    public ProductDetailVO detail(Long id) {
        String cacheKey = RedisKeyConstants.CACHE_PRODUCT + id;
        ProductDetailVO cached = redisCacheService.getWithLogicalExpire(
                cacheKey, ProductDetailVO.class, () -> loadDetailFromDb(id));
        if (cached == null) {
            // 走到这里说明库里确实没有（缓存层已用空值哨兵挡住了后续重复请求）
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }
        return cached;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(ProductSaveDTO dto) {
        checkCategoryExists(dto.getCategoryId());
        validatePointsPrice(dto);

        Product product = new Product();
        applyDto(product, dto);
        product.setSoldCount(0);
        // 新增的商品一律先下架：上架应当是一个显式的运营动作，
        // 而不是"保存了就自动对外可见"。避免资料还没填完就被用户看到。
        product.setStatus(CommonConstants.STATUS_DISABLED);
        product.setDeleted(CommonConstants.DELETED_NO);
        productMapper.insert(product);

        log.info("新增商品 | id={} | name={}", product.getId(), product.getName());
        return product.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(ProductSaveDTO dto) {
        BizException.assertNotNull(dto.getId(), ResultCode.PARAM_ERROR);
        Product exists = productMapper.selectById(dto.getId());
        BizException.assertNotNull(exists, ResultCode.PRODUCT_NOT_FOUND);
        checkCategoryExists(dto.getCategoryId());
        validatePointsPrice(dto);

        Product product = new Product();
        product.setId(dto.getId());
        applyDto(product, dto);
        // 状态不在这里改，由独立的上下架接口负责，避免编辑资料时意外改变可见性
        product.setStatus(null);
        productMapper.updateById(product);

        evictCache(dto.getId());
        log.info("修改商品 | id={}", dto.getId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        Product exists = productMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.PRODUCT_NOT_FOUND);

        // @TableLogic 会把 deleteById 改写为 UPDATE product SET deleted = 1 WHERE id = ?
        // 而不是物理删除 —— 因为历史订单明细需要引用商品快照与 ID
        productMapper.deleteById(id);

        evictCache(id);
        log.info("删除商品 | id={}", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null || (status != CommonConstants.STATUS_ENABLED
                && status != CommonConstants.STATUS_DISABLED)) {
            throw new BizException(ResultCode.PARAM_ERROR, "状态值只能是 0（下架）或 1（上架）");
        }
        Product exists = productMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.PRODUCT_NOT_FOUND);

        Product product = new Product();
        product.setId(id);
        product.setStatus(status);
        productMapper.updateById(product);

        evictCache(id);
        log.info("商品上下架 | id={} | status={}", id, status);
    }

    // ==================== 小程序端 ====================

    /**
     * 小程序端商品列表。
     *
     * <p><b>强制覆盖 status，而不是相信前端传参。</b>
     * 若直接沿用前端传来的 status，只要把请求改成 {@code status=0} 就能查到全部下架商品；
     * 更糟的是，若前端不传 status，查询条件里就没有这个限制，同样会把下架商品查出来。
     * 因此"只能看上架商品"这条规则必须由服务端写死。
     */
    public PageResult<ProductVO> appPage(ProductQuery query) {
        query.setStatus(CommonConstants.STATUS_ENABLED);
        return page(query);
    }

    /** 热门商品（小程序首页） */
    public List<ProductVO> hotProducts(int limit) {
        ProductQuery query = new ProductQuery();
        query.setStatus(CommonConstants.STATUS_ENABLED);
        query.setIsHot(CommonConstants.STATUS_ENABLED);
        query.setPageNum(1L);
        query.setPageSize((long) Math.min(Math.max(limit, 1), 20));
        return page(query).getRecords();
    }

    // ==================== 私有方法 ====================

    /**
     * 从数据库加载商品详情。
     *
     * <p>刻意不做成 public：它只应作为缓存 loader 被调用，
     * 若暴露出去很容易被别处直接调用，从而绕过缓存。
     */
    private ProductDetailVO loadDetailFromDb(Long id) {
        Product product = productMapper.selectById(id);
        if (product == null) {
            return null;
        }
        String categoryName = null;
        if (product.getCategoryId() != null) {
            ProductCategory category = productCategoryMapper.selectById(product.getCategoryId());
            categoryName = category == null ? null : category.getName();
        }
        return ProductDetailVO.from(product, categoryName);
    }

    private void checkCategoryExists(Long categoryId) {
        ProductCategory category = productCategoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BizException(ResultCode.CATEGORY_NOT_FOUND);
        }
    }

    /**
     * 业务规则：支持积分兑换的商品必须设置积分价。
     *
     * <p>这类跨字段的一致性校验放在 Service 而不是靠 DTO 注解，
     * 因为 jakarta.validation 无法表达"当 A 字段为某值时 B 字段才必填"这种条件约束
     * （除非用分组校验，但那会让 DTO 变得更难读）。
     */
    private void validatePointsPrice(ProductSaveDTO dto) {
        Integer type = dto.getProductType();
        boolean supportExchange = type != null && type != 1;
        if (supportExchange && (dto.getPointsPrice() == null || dto.getPointsPrice() <= 0)) {
            throw new BizException(ResultCode.PARAM_ERROR, "支持积分兑换的商品必须设置大于 0 的积分价");
        }
    }

    /** DTO → 实体字段拷贝 */
    private void applyDto(Product product, ProductSaveDTO dto) {
        product.setCategoryId(dto.getCategoryId());
        product.setName(dto.getName());
        product.setSubTitle(dto.getSubTitle());
        product.setSpec(dto.getSpec());
        product.setCoverUrl(dto.getCoverUrl());
        product.setDescription(dto.getDescription());
        product.setProductType(dto.getProductType());
        product.setPrice(dto.getPrice() == null ? 0 : dto.getPrice());
        product.setPointsPrice(dto.getPointsPrice() == null ? 0 : dto.getPointsPrice());
        product.setUnit(dto.getUnit());
        product.setStock(dto.getStock() == null ? 0 : dto.getStock());
        product.setExchangeLimit(dto.getExchangeLimit() == null ? 0 : dto.getExchangeLimit());
        product.setIsHot(dto.getIsHot() == null ? 0 : dto.getIsHot());
        product.setSort(dto.getSort() == null ? 0 : dto.getSort());

        // 关键细节：图片列表为空时写入 "[]" 而不是 null。
        // 因为 MyBatis-Plus 默认的更新策略是 NOT_NULL —— null 字段会被**直接跳过**，
        // 于是"把商品图片全部删掉"这个操作永远无法落库，会一直保留旧图片。
        // 写入 "[]" 才是真正表达"清空"的正确方式。
        product.setImages(dto.getImages() == null || dto.getImages().isEmpty()
                ? "[]"
                : JSONUtil.toJsonStr(dto.getImages()));
    }

    /**
     * 数据变更后清理缓存。
     *
     * <p>采用 Cache-Aside 的「先更新数据库，再删除缓存」策略，而不是更新缓存。
     * 原因与残留缺陷见 {@link RedisCacheService#evict(String)} 的说明。
     */
    private void evictCache(Long productId) {
        redisCacheService.evict(RedisKeyConstants.CACHE_PRODUCT + productId);
    }

    // ==================== 库存变更（必须与缓存失效绑定） ====================

    /**
     * 扣减库存（防超卖），并在成功时失效商品详情缓存。
     *
     * <h2>⚠️ 为什么库存变更必须放在这里，而不是让订单模块直接调 Mapper</h2>
     *
     * <p>这里踩过一个真实的坑：商品详情走 Redis 逻辑过期缓存，
     * 而下单扣库存原本是订单模块直接调 {@code productMapper.deductStock(...)}，
     * <b>绕过了缓存失效</b>。后果是：
     *
     * <pre>
     *   ① 用户打开商品详情 → 缓存建立，stock = 797
     *   ② 用户买 2 件      → 数据库 stock = 795（扣减是对的）
     *   ③ 用户再看详情     → 仍然显示 797   ← 读到旧缓存
     * </pre>
     *
     * <p>接口不报错、日志没有异常、数据库也是对的，**只有页面上的数字是旧的**。
     * 这类问题最容易让人怀疑"是不是库存根本没扣"，而实际上扣得好好的。
     *
     * <p>修法不是"在每个扣库存的地方补一句清缓存"——那样只要以后新增一个
     * 库存变更入口、忘了补这一句，问题就会重现，而且同样不报错。
     * 正确的做法是<b>把「改数据」与「失效缓存」封进同一个方法</b>，
     * 让"忘记失效"在结构上不可能发生。
     *
     * <p>只有 {@code affectedRows > 0} 时才清缓存：扣减失败（库存不足）时
     * 数据没变，缓存也无须失效。
     *
     * @return 1 表示扣减成功，0 表示库存不足
     */
    public int deductStock(Long productId, int quantity) {
        int affected = productMapper.deductStock(productId, quantity);
        if (affected > 0) {
            evictCache(productId);
        }
        return affected;
    }

    /**
     * 回滚库存（订单取消 / 超时关闭 / 兑换失败），并失效缓存。
     *
     * <p>与 {@link #deductStock} 同理 —— 回滚同样是库存变更，
     * 同样会让缓存里的 {@code stock} / {@code soldCount} 变旧。
     */
    public int restoreStock(Long productId, int quantity) {
        int affected = productMapper.restoreStock(productId, quantity);
        if (affected > 0) {
            evictCache(productId);
        }
        return affected;
    }
}
