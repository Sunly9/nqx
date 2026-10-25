package com.nqx.controller.app;

import com.nqx.common.api.Result;
import com.nqx.pojo.vo.app.CartVO;
import com.nqx.service.app.CartService;
import com.nqx.service.satoken.StpAppUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端购物车接口。
 *
 * <p>需要登录。旧版把购物车放在小程序本地 Storage，
 * 换设备或清缓存就丢，下单时也无法在服务端校验价格与库存。
 */
@Tag(name = "小程序端-购物车", description = "加入购物车、列表、改数量、删除")
@RestController
@RequestMapping("/app/cart")
@RequiredArgsConstructor
public class AppCartController {

    private final CartService cartService;

    @Operation(summary = "购物车列表", description = "含商品名称、封面、单价、库存，一次请求即可渲染整个页面")
    @GetMapping("/list")
    public Result<List<CartVO>> list() {
        return Result.success(cartService.listDetail(StpAppUtil.getLoginIdAsLong()));
    }

    @Operation(summary = "加入购物车", description = "已存在则累加数量；并发重复加购由唯一索引兜底")
    @PostMapping
    public Result<Void> add(@RequestParam("productId") Long productId,
                            @RequestParam(value = "quantity", defaultValue = "1") Integer quantity) {
        cartService.add(StpAppUtil.getLoginIdAsLong(), productId, quantity);
        return Result.success("已加入购物车", null);
    }

    @Operation(summary = "修改数量或勾选状态", description = "参数为 null 表示该项不改")
    @PutMapping("/{cartId}")
    public Result<Void> update(@PathVariable Long cartId,
                               @RequestParam(value = "quantity", required = false) Integer quantity,
                               @RequestParam(value = "selected", required = false) Integer selected) {
        cartService.update(StpAppUtil.getLoginIdAsLong(), cartId, quantity, selected);
        return Result.success("已更新", null);
    }

    @Operation(summary = "删除单项")
    @DeleteMapping("/{cartId}")
    public Result<Void> delete(@PathVariable Long cartId) {
        cartService.delete(StpAppUtil.getLoginIdAsLong(), cartId);
        return Result.success("已删除", null);
    }

    @Operation(summary = "清空购物车")
    @DeleteMapping
    public Result<Void> clear() {
        cartService.clear(StpAppUtil.getLoginIdAsLong());
        return Result.success("已清空", null);
    }
}
