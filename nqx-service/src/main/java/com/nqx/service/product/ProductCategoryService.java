package com.nqx.service.product;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.ProductCategoryMapper;
import com.nqx.mapper.ProductMapper;
import com.nqx.pojo.dto.product.CategorySaveDTO;
import com.nqx.pojo.entity.ProductCategory;
import com.nqx.pojo.vo.product.CategoryVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品分类服务。
 *
 * <p>分类是本项目里唯一带层级的商品数据，因此它的三个「特有坑」都在这里处理：
 * <ol>
 *   <li><b>建树</b> —— 数据库只存扁平的 {@code parent_id}，树在内存里一次建好（O(n)）；</li>
 *   <li><b>删除前置校验</b> —— 有子分类或有商品引用时不允许删除，
 *       否则会留下指向不存在分类的商品（幽灵商品）；</li>
 *   <li><b>父级成环</b> —— 修改时禁止把分类挂到自己或自己的后代下，
 *       成环的分类会从接口返回里彻底消失（详见 {@link #checkNoCycle}）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCategoryService {

    /** 根分类的 parent_id 约定值（建表脚本里 parent_id 为 NOT NULL DEFAULT 0） */
    private static final long ROOT_PARENT_ID = 0L;

    private final ProductCategoryMapper productCategoryMapper;
    private final ProductMapper productMapper;

    // ==================== 查询 ====================

    /**
     * 分类树（管理端与小程序端共用）。
     *
     * <p>只返回<b>启用且未删除</b>的分类：禁用分类对两端都不该可见。
     * 逻辑删除由 {@code @TableLogic} 自动追加 {@code deleted = 0}，
     * 这里只额外限定状态。
     *
     * <p>一次把全量分类查出来再在内存里建树，而不是「先查父节点、再逐个查子节点」：
     * 后者是典型的 N+1 查询，分类层级一深就会打出成百上千条 SQL。
     */
    public List<CategoryVO> tree() {
        List<ProductCategory> categories = productCategoryMapper.selectList(
                Wrappers.<ProductCategory>lambdaQuery()
                        .eq(ProductCategory::getStatus, CommonConstants.STATUS_ENABLED)
                        .orderByAsc(ProductCategory::getSort)
                        .orderByAsc(ProductCategory::getId));
        return buildTree(categories);
    }

    // ==================== 增删改 ====================

    @Transactional(rollbackFor = Exception.class)
    public Long create(CategorySaveDTO dto) {
        checkParentExists(dto.getParentId());

        ProductCategory entity = new ProductCategory();
        applyDto(entity, dto);
        // 新增分类默认启用。这里和商品（新增默认下架）刻意不同：
        // 商品下架是因为对外可见需要资料完整（图、价、库存）；
        // 分类只是一个筛选维度，缺省可见不会造成交易问题，反而少一步运营操作。
        if (entity.getStatus() == null) {
            entity.setStatus(CommonConstants.STATUS_ENABLED);
        }
        entity.setDeleted(CommonConstants.DELETED_NO);
        productCategoryMapper.insert(entity);

        log.info("新增商品分类 | id={} | name={} | parentId={}",
                entity.getId(), entity.getName(), entity.getParentId());
        return entity.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(CategorySaveDTO dto) {
        // 新增与修改共用一个 DTO，「修改必须带 id」这条规则只能在这里断言
        BizException.assertNotNull(dto.getId(), ResultCode.PARAM_ERROR);
        ProductCategory exists = productCategoryMapper.selectById(dto.getId());
        BizException.assertNotNull(exists, ResultCode.CATEGORY_NOT_FOUND);

        checkParentExists(dto.getParentId());
        checkNoCycle(dto.getId(), dto.getParentId());

        ProductCategory entity = new ProductCategory();
        entity.setId(dto.getId());
        applyDto(entity, dto);
        // 注意 MyBatis-Plus 的更新策略是 not_null：DTO 里为 null 的字段会被直接跳过，
        // 也就是说「不传」等于「不改」。若要清空图标这类可选字段，前端应传空字符串 ""
        // 而不是 null，否则旧值会被一直保留，运营会以为「改了没生效」。
        productCategoryMapper.updateById(entity);

        log.info("修改商品分类 | id={} | name={}", dto.getId(), dto.getName());
    }

    /**
     * 删除分类。
     *
     * <p><b>两道前置校验缺一不可</b>：分类被商品引用时删除，商品详情里的
     * {@code categoryName} 会变成 null（列表页出现「分类已删除」），
     * 后台按分类筛选也会查不到这些商品 —— 数据还在，但运营再也找不到它们。
     * 有子分类时删除则会让整棵子树的节点全部变成查不到的孤儿节点。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ProductCategory exists = productCategoryMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.CATEGORY_NOT_FOUND);

        // ① 子分类校验放在最前面：走 idx_parent_sort 索引的最左前缀，成本最低，
        //    而且这条提示对管理员更有指导性（先处理子分类，而不是先去翻商品）。
        //    selectCount 由 MyBatis-Plus 生成，会自带 deleted = 0。
        Long childCount = productCategoryMapper.selectCount(
                Wrappers.<ProductCategory>lambdaQuery().eq(ProductCategory::getParentId, id));
        if (childCount != null && childCount > 0) {
            throw new BizException(ResultCode.CATEGORY_HAS_CHILDREN);
        }

        // ② 商品占用校验。必须是「未删除」的商品数：已逻辑删除的商品仍留在表里，
        //    若不带 deleted = 0，一个曾经放过商品的分类就会永远删不掉。
        //    这条 SQL 手写在 ProductMapper.xml 里，手写 SQL 的逻辑删除 MP 不介入，
        //    所以 XML 自己带了 deleted = 0。
        Long productCount = productMapper.countByCategoryId(id);
        if (productCount != null && productCount > 0) {
            throw new BizException(ResultCode.CATEGORY_HAS_PRODUCT);
        }

        // @TableLogic 会把 deleteById 改写成 UPDATE product_category SET deleted = 1 WHERE id = ?
        // 不能物理删除：历史订单明细里引用的分类 ID 还需要能追溯。
        productCategoryMapper.deleteById(id);
        log.info("删除商品分类 | id={} | name={}", id, exists.getName());
    }

    // ==================== 私有方法 ====================

    /**
     * 扁平列表 → 树，O(n)。
     *
     * <p>写法与 {@code MenuTreeBuilder} 完全一致，理由也相同：
     * 「对每个节点递归查找其子节点」是 O(n²)，节点多了会比较上百万次；
     * 这里一次遍历建 id 索引、一次遍历挂父子关系，是 O(n)，
     * 且不受树深度影响（递归实现深度过大还会栈溢出）。
     *
     * <p>用 LinkedHashMap 保持 SQL 的 {@code ORDER BY sort, id} 顺序，
     * 保证同一层级内的展示顺序稳定。
     */
    private List<CategoryVO> buildTree(List<ProductCategory> categories) {
        if (categories == null || categories.isEmpty()) {
            return new ArrayList<>();
        }

        Map<Long, CategoryVO> nodeMap = new LinkedHashMap<>(categories.size() * 2);
        for (ProductCategory c : categories) {
            nodeMap.put(c.getId(), CategoryVO.from(c));
        }

        List<CategoryVO> roots = new ArrayList<>();
        for (CategoryVO node : nodeMap.values()) {
            CategoryVO parent = node.getParentId() == null ? null : nodeMap.get(node.getParentId());
            if (parent == null) {
                // 两种情况会走到这里：
                //   1) parentId = 0，本来就是根分类；
                //   2) 父分类被禁用（本次查询不到）或已被删除。
                // 第 2 种**必须**把子分类提升为根：否则「把父分类禁用」会连带
                // 让所有子分类从小程序里静默消失，运营只会看到"分类不见了"，
                // 却完全找不到原因。宁可多显示几个平级节点，也不能静默丢数据。
                roots.add(node);
            } else {
                parent.addChild(node);
            }
        }
        return roots;
    }

    /**
     * 校验上级分类存在。
     *
     * <p>根分类（parentId = 0）是约定值，表里并不存在 id = 0 的记录，
     * 必须直接放行，否则所有根分类都建不出来。
     */
    private void checkParentExists(Long parentId) {
        if (parentId == null || parentId == ROOT_PARENT_ID) {
            return;
        }
        ProductCategory parent = productCategoryMapper.selectById(parentId);
        if (parent == null) {
            throw new BizException(ResultCode.CATEGORY_NOT_FOUND, "上级分类不存在或已被删除");
        }
    }

    /**
     * 防止把分类挂到它自己或它自己的后代下面。
     *
     * <p>成环的后果不是报错，而是<b>静默消失</b>：建树时该节点既不是根
     * （parentId 能在 nodeMap 里找到），又不会被任何真正的父节点收下，
     * 于是它连同整棵子树都不会出现在接口返回里，而数据库里数据还在。
     *
     * <p>实现上从目标父节点沿 parent_id 向上回溯：分类树通常只有两三级，
     * 用一次全表查询换取「1 条 SQL 而不是逐级查库」。
     * 用 visited 集合终止循环是必须的 —— 万一库里已经存在历史环，
     * 无终止条件的回溯会把整个请求挂死。
     */
    private void checkNoCycle(Long id, Long parentId) {
        if (parentId == null || parentId == ROOT_PARENT_ID) {
            return;
        }
        if (parentId.equals(id)) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能把分类的上级设置为它自己");
        }

        Map<Long, Long> parentMap = new HashMap<>();
        for (ProductCategory c : productCategoryMapper.selectList(null)) {
            parentMap.put(c.getId(), c.getParentId());
        }

        Set<Long> visited = new HashSet<>();
        Long cursor = parentId;
        while (cursor != null && cursor != ROOT_PARENT_ID && visited.add(cursor)) {
            if (cursor.equals(id)) {
                throw new BizException(ResultCode.PARAM_ERROR, "不能把分类移动到它自己的子分类下面");
            }
            cursor = parentMap.get(cursor);
        }
    }

    /** DTO → 实体字段拷贝 */
    private void applyDto(ProductCategory entity, CategorySaveDTO dto) {
        // parentId 的必填校验由 DTO 上的 @NotNull 保证；这里不做兜底默认值，
        // 避免「漏传被悄悄挂到根节点」这种难以排查的静默行为
        entity.setParentId(dto.getParentId());
        entity.setName(dto.getName());
        entity.setIcon(dto.getIcon());
        entity.setSort(dto.getSort() == null ? 0 : dto.getSort());
        entity.setStatus(dto.getStatus());
    }
}
