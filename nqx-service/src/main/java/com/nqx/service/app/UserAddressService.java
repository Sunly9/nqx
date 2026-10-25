package com.nqx.service.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.UserAddressMapper;
import com.nqx.pojo.entity.UserAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 收货地址服务。
 *
 * <p>归属校验（只能操作自己的地址）是本模块最重要的一条规则 ——
 * 旧版 {@code PUT /api/address/edit/:id} 只按主键更新，不校验 user_id，
 * 任何人改一下 id 就能修改别人的收货地址。
 * 这不只是"看到别人的数据"，而是能把别人的包裹改寄到自己手上。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserAddressService {

    /** 单个用户的地址数量上限，防止被脚本灌爆 */
    private static final int MAX_ADDRESS_COUNT = 20;

    private final UserAddressMapper userAddressMapper;

    public List<UserAddress> list(Long userId) {
        return userAddressMapper.selectList(Wrappers.<UserAddress>lambdaQuery()
                .eq(UserAddress::getUserId, userId)
                // 默认地址排最前，其余按创建时间倒序 —— 与旧版 ORDER BY is_default DESC, created_at DESC 一致
                .orderByDesc(UserAddress::getIsDefault)
                .orderByDesc(UserAddress::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public Long create(Long userId, UserAddress address) {
        Long count = userAddressMapper.selectCount(Wrappers.<UserAddress>lambdaQuery()
                .eq(UserAddress::getUserId, userId));
        if (count != null && count >= MAX_ADDRESS_COUNT) {
            throw new BizException(ResultCode.PARAM_ERROR, "最多只能保存 " + MAX_ADDRESS_COUNT + " 个收货地址");
        }

        address.setId(null);
        address.setUserId(userId);
        address.setDeleted(CommonConstants.DELETED_NO);
        // 首个地址自动设为默认：否则用户下了单却因为没有默认地址而卡住
        boolean first = (count == null || count == 0);
        if (first || Boolean.TRUE.equals(isDefaultFlag(address))) {
            clearDefault(userId);
            address.setIsDefault(1);
        } else {
            address.setIsDefault(0);
        }
        userAddressMapper.insert(address);
        return address.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, UserAddress address) {
        BizException.assertNotNull(address.getId(), ResultCode.PARAM_ERROR);
        requireOwned(userId, address.getId());

        if (Boolean.TRUE.equals(isDefaultFlag(address))) {
            clearDefault(userId);
            address.setIsDefault(1);
        }
        // 不允许改 userId：避免通过请求体把地址"过户"给别人
        address.setUserId(null);
        address.setDeleted(null);
        userAddressMapper.updateById(address);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long addressId) {
        UserAddress existing = requireOwned(userId, addressId);
        userAddressMapper.deleteById(addressId);

        // 删掉的若是默认地址，把剩下最新的一条提为默认，
        // 否则用户下次下单会发现"没有默认地址"，需要手动再设一次
        if (Integer.valueOf(1).equals(existing.getIsDefault())) {
            List<UserAddress> rest = list(userId);
            if (!rest.isEmpty()) {
                UserAddress update = new UserAddress();
                update.setId(rest.get(0).getId());
                update.setIsDefault(1);
                userAddressMapper.updateById(update);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long addressId) {
        requireOwned(userId, addressId);
        clearDefault(userId);
        UserAddress update = new UserAddress();
        update.setId(addressId);
        update.setIsDefault(1);
        userAddressMapper.updateById(update);
    }

    /**
     * 取订单要用的地址，并校验归属。
     *
     * <p>下单时调用。这里必须校验归属 —— 否则用户传别人的 addressId，
     * 就能把货物寄到别人的地址，或者反过来拿到别人的联系方式。
     */
    public UserAddress requireOwned(Long userId, Long addressId) {
        UserAddress address = userAddressMapper.selectById(addressId);
        if (address == null) {
            throw new BizException(ResultCode.ADDRESS_NOT_FOUND);
        }
        if (!address.getUserId().equals(userId)) {
            throw new BizException(ResultCode.ADDRESS_NOT_BELONG_TO_USER);
        }
        return address;
    }

    /**
     * 取用户的默认地址；没有默认地址则退回最近添加的一条；一条都没有返回 null。
     *
     * <p>用于"下单时前端没传地址"的兜底。返回 null 而不是抛异常，
     * 是因为调用方需要区分"没有地址"（要提示用户去添加）
     * 与"地址不属于你"（要拒绝操作）两种情况，提示语完全不同。
     */
    public UserAddress findDefaultOrLatest(Long userId) {
        List<UserAddress> list = list(userId);
        return list.isEmpty() ? null : list.get(0);
    }

    /** 把该用户其它地址的默认标记清掉，保证"默认地址"全局唯一 */
    private void clearDefault(Long userId) {
        UserAddress reset = new UserAddress();
        reset.setIsDefault(0);
        userAddressMapper.update(reset, Wrappers.<UserAddress>lambdaQuery()
                .eq(UserAddress::getUserId, userId)
                .eq(UserAddress::getIsDefault, 1));
    }

    private Boolean isDefaultFlag(UserAddress address) {
        return Integer.valueOf(1).equals(address.getIsDefault());
    }

    /** 拼出完整地址文本，下单时作为快照存进订单 */
    public String formatFullAddress(UserAddress address) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, address.getProvince());
        appendIfPresent(sb, address.getCity());
        appendIfPresent(sb, address.getDistrict());
        appendIfPresent(sb, address.getDetailAddress());
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String part) {
        if (StringUtils.hasText(part)) {
            sb.append(part);
        }
    }
}
