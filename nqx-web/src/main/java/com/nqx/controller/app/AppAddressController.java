package com.nqx.controller.app;

import com.nqx.common.api.Result;
import com.nqx.pojo.dto.app.AddressSaveDTO;
import com.nqx.pojo.entity.UserAddress;
import com.nqx.pojo.vo.app.AddressVO;
import com.nqx.service.app.UserAddressService;
import com.nqx.service.satoken.StpAppUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端收货地址接口。
 *
 * <p><b>归属校验是本模块的核心</b>：所有单条操作都会先确认地址属于当前用户。
 * 旧版 {@code PUT /api/address/edit/:id} 只按主键更新、不校验 user_id ——
 * 改一下 id 就能修改别人的收货地址。这不只是"看到别人的数据"，
 * 而是能把别人的包裹改寄到自己手上。
 */
@Tag(name = "小程序端-收货地址", description = "地址增删改查与设为默认")
@RestController
@RequestMapping("/app/address")
@RequiredArgsConstructor
public class AppAddressController {

    private final UserAddressService userAddressService;

    @Operation(summary = "地址列表", description = "默认地址排最前")
    @GetMapping("/list")
    public Result<List<AddressVO>> list() {
        return Result.success(userAddressService.list(StpAppUtil.getLoginIdAsLong())
                .stream().map(AddressVO::from).toList());
    }

    @Operation(summary = "新增地址", description = "首个地址自动设为默认")
    @PostMapping
    public Result<Long> create(@RequestBody @Valid AddressSaveDTO dto) {
        return Result.success("保存成功", userAddressService.create(
                StpAppUtil.getLoginIdAsLong(), toEntity(dto)));
    }

    @Operation(summary = "修改地址")
    @PutMapping
    public Result<Void> update(@RequestBody @Valid AddressSaveDTO dto) {
        userAddressService.update(StpAppUtil.getLoginIdAsLong(), toEntity(dto));
        return Result.success("保存成功", null);
    }

    @Operation(summary = "删除地址", description = "删除默认地址后会自动把剩余最新的一条提为默认")
    @DeleteMapping("/{addressId}")
    public Result<Void> delete(@PathVariable Long addressId) {
        userAddressService.delete(StpAppUtil.getLoginIdAsLong(), addressId);
        return Result.success("已删除", null);
    }

    @Operation(summary = "设为默认地址")
    @PutMapping("/{addressId}/default")
    public Result<Void> setDefault(@PathVariable Long addressId) {
        userAddressService.setDefault(StpAppUtil.getLoginIdAsLong(), addressId);
        return Result.success("已设为默认", null);
    }

    /** DTO → 实体。userId 由 Service 从会话填充，绝不从请求体取 */
    private UserAddress toEntity(AddressSaveDTO dto) {
        UserAddress address = new UserAddress();
        address.setId(dto.getId());
        address.setReceiverName(dto.getReceiverName());
        address.setReceiverPhone(dto.getReceiverPhone());
        address.setProvince(dto.getProvince());
        address.setCity(dto.getCity());
        address.setDistrict(dto.getDistrict());
        address.setDetailAddress(dto.getDetailAddress());
        address.setTag(dto.getTag());
        address.setIsDefault(dto.getIsDefault());
        return address;
    }
}
