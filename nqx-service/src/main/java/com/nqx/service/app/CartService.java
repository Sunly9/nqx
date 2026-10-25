package com.nqx.service.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.CartMapper;
import com.nqx.mapper.ProductMapper;
import com.nqx.pojo.entity.Cart;
import com.nqx.pojo.entity.Product;
import com.nqx.pojo.vo.app.CartVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 购物车服务。
 *
 * <p>旧版把购物车完全放在小程序的本地 Storage 里（{@code wx.getStorageSync('cart')}），
 * 换手机或清缓存就丢，且下单时无法在服务端校验价格与库存。
 * 改为服务端存储后，下单前能拿到权威的库存与价格。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    /** 单个商品在购物车中的数量上限，防止被脚本加到离谱的值 */
    private static final int MAX_QUANTITY_PER_ITEM = 999;

    private final CartMapper cartMapper;
    private final ProductMapper productMapper;

    public List<Cart> list(Long userId) {
        return cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .orderByDesc(Cart::getCreateTime));
    }

    /**
     * 加入购物车。
     *
     * <p>已存在则累加数量。"先查再决定插入还是更新"在并发下会产生两行，
     * 但表上的唯一索引 {@code (user_id, product_id)} 会拦住重复插入 ——
     * 捕获 {@code DuplicateKeyException} 后转为累加即可。
     * 这是"应用层逻辑 + 数据库约束"配合的典型写法：
     * 应用层负责常规路径，数据库负责兜住竞态。
     */
    @Transactional(rollbackFor = Exception.class)
    public void add(Long userId, Long productId, int quantity) {
        Product product = requireOnSaleProduct(productId);
        int finalQuantity = normalizeQuantity(quantity);

        Cart existing = cartMapper.selectOne(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getProductId, productId));

        if (existing != null) {
            accumulate(existing, finalQuantity, product);
            return;
        }

        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setProductId(productId);
        cart.setQuantity(quantityWithinStock(finalQuantity, product));
        cart.setSelected(1);
        try {
            cartMapper.insert(cart);
        } catch (DuplicateKeyException e) {
            // 并发下另一个请求刚插入成功 —— 退化为累加，保证不丢用户操作
            log.debug("购物车已存在（并发加购），转为累加 | userId={} | productId={}", userId, productId);
            Cart concurrent = cartMapper.selectOne(Wrappers.<Cart>lambdaQuery()
                    .eq(Cart::getUserId, userId).eq(Cart::getProductId, productId));
            if (concurrent != null) {
                accumulate(concurrent, finalQuantity, product);
            }
        }
    }

    /** 修改数量或勾选状态 */
    public void update(Long userId, Long cartId, Integer quantity, Integer selected) {
        Cart existing = requireOwned(userId, cartId);

        Cart update = new Cart();
        update.setId(cartId);
        if (quantity != null) {
            Product product = requireOnSaleProduct(existing.getProductId());
            update.setQuantity(quantityWithinStock(normalizeQuantity(quantity), product));
        }
        if (selected != null) {
            update.setSelected(selected == 0 ? 0 : 1);
        }
        cartMapper.updateById(update);
    }

    public void delete(Long userId, Long cartId) {
        requireOwned(userId, cartId);
        cartMapper.deleteById(cartId);
    }

    public void clear(Long userId) {
        cartMapper.delete(Wrappers.<Cart>lambdaQuery().eq(Cart::getUserId, userId));
    }

    /** 下单成功后清除已结算的商品 */
    public void removeByProductIds(Long userId, List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return;
        }
        cartMapper.delete(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .in(Cart::getProductId, productIds));
    }

    /** 当前用户已勾选的购物车项 */
    public List<Cart> listSelected(Long userId) {
        return cartMapper.selectList(Wrappers.<Cart>lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getSelected, 1));
    }

    /**
     * 购物车列表（含商品信息）。
     *
     * <p>用 {@code selectBatchIds} 一次查出所有商品，而不是在循环里逐个查库。
     * 购物车通常只有几项，但 N+1 是习惯问题 —— 用户"全选加购"时很容易变成几十项。
     */
    public List<CartVO> listDetail(Long userId) {
        List<Cart> items = list(userId);
        if (items.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> productIds = items.stream().map(Cart::getProductId).distinct().toList();
        Map<Long, Product> productMap = productMapper.selectBatchIds(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a, HashMap::new));
        return items.stream()
                .map(item -> CartVO.from(item, productMap.get(item.getProductId())))
                .toList();
    }

    // ==================== 私有方法 ====================

    private Cart requireOwned(Long userId, Long cartId) {
        Cart cart = cartMapper.selectById(cartId);
        if (cart == null) {
            throw new BizException(ResultCode.CART_ITEM_NOT_FOUND);
        }
        if (!cart.getUserId().equals(userId)) {
            throw new BizException(ResultCode.ORDER_NOT_BELONG_TO_USER, "无权操作他人的购物车");
        }
        return cart;
    }

    /** 校验商品存在且在售 —— 下架商品不允许加购，否则下单时才发现会很尴尬 */
    private Product requireOnSaleProduct(Long productId) {
        Product product = productMapper.selectById(productId);
        if (product == null) {
            throw new BizException(ResultCode.PRODUCT_NOT_FOUND);
        }
        if (product.getStatus() == null || product.getStatus() != 1) {
            throw new BizException(ResultCode.PRODUCT_OFF_SHELF);
        }
        return product;
    }

    private int normalizeQuantity(int quantity) {
        if (quantity < 1) {
            return 1;
        }
        return Math.min(quantity, MAX_QUANTITY_PER_ITEM);
    }

    /** 数量不超过库存，避免加购时就注定失败 */
    private int quantityWithinStock(int quantity, Product product) {
        int stock = product.getStock() == null ? 0 : product.getStock();
        if (stock <= 0) {
            throw new BizException(ResultCode.PRODUCT_STOCK_NOT_ENOUGH);
        }
        return Math.min(quantity, stock);
    }

    private void accumulate(Cart existing, int delta, Product product) {
        int newQuantity = quantityWithinStock(existing.getQuantity() + delta, product);
        Cart update = new Cart();
        update.setId(existing.getId());
        update.setQuantity(newQuantity);
        update.setSelected(1);
        cartMapper.updateById(update);
    }
}
