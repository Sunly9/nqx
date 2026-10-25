package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.entity.UserAddress;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 收货地址（小程序端）。
 *
 * <p>字段名沿用旧前端的取值代码（{@code item.name} / {@code item.phone} / {@code item.detail}），
 * 这样地址列表页一行都不用改。
 */
@Data
public class AddressVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 收货人，旧前端读 {@code name} */
    private String name;

    /** 联系电话，旧前端读 {@code phone} */
    private String phone;

    private String province;

    private String city;

    private String district;

    /** 详细地址，旧前端读 {@code detail} */
    private String detail;

    private String tag;

    /** 是否默认 0否 1是 */
    @JsonProperty("is_default")
    private Integer isDefault;

    public static AddressVO from(UserAddress address) {
        AddressVO vo = new AddressVO();
        vo.setId(address.getId());
        vo.setName(address.getReceiverName());
        vo.setPhone(address.getReceiverPhone());
        vo.setProvince(address.getProvince());
        vo.setCity(address.getCity());
        vo.setDistrict(address.getDistrict());
        vo.setDetail(address.getDetailAddress());
        vo.setTag(address.getTag());
        vo.setIsDefault(address.getIsDefault());
        return vo;
    }
}
