package com.nqx.service.points;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.constant.CommonConstants;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.PointsAccountMapper;
import com.nqx.mapper.PointsRecordMapper;
import com.nqx.pojo.entity.PointsAccount;
import com.nqx.pojo.entity.PointsRecord;
import com.nqx.pojo.enums.PointsChangeTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.PointsRecordVO;
import com.nqx.service.content.ContentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 积分服务 —— <b>全项目唯一的积分写入口</b>。
 *
 * <h2>为什么必须收敛成唯一入口</h2>
 *
 * <p>积分是平台负债，任何一处绕过本类的 {@code UPDATE points_account}
 * 都会破坏"余额 = 流水累加"这个不变量，而且极难排查
 * （账对不上时你根本不知道是谁改的）。
 * 因此所有积分变动 —— 注册奖励、答题、回收结算、兑换、管理员调整、退款 ——
 * 都必须经过 {@link #change}。
 *
 * <h2>三道防线</h2>
 *
 * <ol>
 *   <li><b>唯一索引（最终防线）</b>：{@code points_record} 上的
 *       {@code (user_id, biz_no, change_type)}。并发下应用层判断必然失效，
 *       唯一索引 + 捕获 {@code DuplicateKeyException} 才是可靠的。
 *       它保证"同一笔业务最多只发一次积分"。</li>
 *
 *   <li><b>条件更新（防超扣）</b>：扣减走
 *       {@code UPDATE ... WHERE balance >= ?}，靠 affectedRows 判断是否成功，
 *       不存在"先查再判断"的竞态窗口。</li>
 *
 *   <li><b>事务（保一致）</b>：流水与余额在同一事务内，
 *       任一步失败整体回滚，不会出现"流水记了但余额没加"。</li>
 * </ol>
 *
 * <h2>执行顺序为什么是「先写流水，再改余额」</h2>
 *
 * <p>若反过来（先改余额再写流水），一笔重复请求会先成功扣款/加分，
 * 然后才在写流水时因唯一键冲突而回滚 —— 虽然事务能保证最终一致，
 * 但白白做了一次无用的余额变更，且在扣减场景下还可能先因为
 * "余额不足"报错，掩盖了真正的"重复提交"事实。
 *
 * <p>先写流水则重复请求在第一步就被拦下，直接返回幂等成功，代价最小。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PointsService {

    /** 记账用的兜底单号前缀，仅在调用方确实没有业务单号时使用 */
    private static final String MANUAL_BIZ_PREFIX = "MANUAL-";

    /** 余额上限保护，与 points_account.balance 的 INT 类型匹配 */
    private static final int MAX_BALANCE = 2_000_000_000;

    private final PointsAccountMapper pointsAccountMapper;
    private final PointsRecordMapper pointsRecordMapper;
    private final ContentService contentService;

    /**
     * 变更积分。
     *
     * @param userId     用户 ID
     * @param delta      变动值，正为增、负为减，不能为 0
     * @param type       变动类型，决定方向校验与幂等键
     * @param bizNo      业务单号，<b>幂等键的核心</b>。同一笔业务重复调用必须传相同的值
     * @param bizId      业务主键，便于反查（可为空）
     * @param remark     流水备注，会展示给用户，请写人话
     * @param operatorId 操作人（管理员调整时传管理员 ID，系统自动发放传 null）
     * @return 变动后的余额
     */
    @Transactional(rollbackFor = Exception.class)
    public int change(Long userId, int delta, PointsChangeTypeEnum type,
                      String bizNo, Long bizId, String remark, Long operatorId) {
        BizException.assertNotNull(userId, ResultCode.USER_NOT_FOUND);
        // 方向与类型不匹配属于编码错误，直接抛断言异常，在开发阶段就暴露
        type.validateDirection(delta);

        String effectiveBizNo = StringUtils.hasText(bizNo)
                ? bizNo
                : MANUAL_BIZ_PREFIX + System.currentTimeMillis() + "-" + ThreadLocalRandom.current().nextInt(1000);

        PointsAccount account = ensureAccountLocked(userId);
        int balanceBefore = account.getBalance() == null ? 0 : account.getBalance();

        // ---------- 第一道防线：先写流水，重复请求在这里被唯一索引拦下 ----------
        PointsRecord record = new PointsRecord();
        record.setUserId(userId);
        record.setChangeType(type.getCode());
        record.setChangeValue(delta);
        // 持有行锁，此时读到的余额不会被其它事务改变，balance_after 必然准确
        record.setBalanceAfter(balanceBefore + delta);
        record.setBizNo(effectiveBizNo);
        record.setBizId(bizId);
        record.setRemark(remark);
        record.setOperatorId(operatorId);

        try {
            pointsRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 同一个 (user_id, biz_no, change_type) 已经记过账 —— 这是重复请求，
            // 属于幂等成功而不是失败。返回当前余额即可，不要报错打扰用户。
            log.info("积分变动重复请求，按幂等处理 | userId={} | bizNo={} | type={}",
                    userId, effectiveBizNo, type.name());
            return balanceBefore;
        }

        // ---------- 第二道防线：原子条件更新 ----------
        int affectedRows = delta > 0
                ? pointsAccountMapper.addPoints(userId, delta)
                : pointsAccountMapper.deductPoints(userId, -delta);

        if (affectedRows == 0) {
            // 加分的 0 行说明触发了余额上限保护；扣分的 0 行说明余额不足。
            // 无论哪种，都抛异常让事务回滚 —— 刚插入的流水会被一并撤销，
            // 不会留下"有流水但余额没变"的脏数据。
            log.warn("积分变动未生效 | userId={} | delta={} | 当前余额={}", userId, delta, balanceBefore);
            throw new BizException(ResultCode.POINTS_NOT_ENOUGH);
        }

        log.info("积分变动成功 | userId={} | {}{} | 余额 {} -> {} | 类型={} | bizNo={}",
                userId, delta > 0 ? "+" : "", delta, balanceBefore, balanceBefore + delta,
                type.getDesc(), effectiveBizNo);
        return balanceBefore + delta;
    }

    // ==================== 语义化封装 ====================

    /** 发放积分（收入） */
    public int reward(Long userId, int points, PointsChangeTypeEnum type,
                      String bizNo, Long bizId, String remark) {
        if (points <= 0) {
            throw new IllegalArgumentException("发放积分必须为正数，实际传入 " + points);
        }
        return change(userId, points, type, bizNo, bizId, remark, null);
    }

    /** 扣减积分（支出），余额不足会抛 {@code POINTS_NOT_ENOUGH} */
    public int deduct(Long userId, int points, PointsChangeTypeEnum type,
                      String bizNo, Long bizId, String remark) {
        if (points <= 0) {
            throw new IllegalArgumentException("扣减积分必须为正数（内部会转成负数），实际传入 " + points);
        }
        return change(userId, -points, type, bizNo, bizId, remark, null);
    }

    /**
     * 新用户注册奖励。
     *
     * <p>积分值取自站点配置 {@code register_points}，默认 100 ——
     * 旧版把初始积分硬编码在 Node 代码里，运营想调整必须改代码重新部署。
     *
     * <p>幂等键用 {@code REGISTER-{userId}}，保证同一用户无论触发多少次都只发一次。
     */
    public int grantRegisterBonus(Long userId) {
        int points = contentService.getIntSetting("register_points", 100);
        if (points <= 0) {
            return 0;
        }
        return reward(userId, points, PointsChangeTypeEnum.REGISTER,
                "REGISTER-" + userId, userId, "新用户注册奖励");
    }

    // ==================== 查询 ====================

    /** 当前可用积分。账户不存在时返回 0 而不是抛异常 */
    public int getBalance(Long userId) {
        PointsAccount account = pointsAccountMapper.selectOne(
                Wrappers.<PointsAccount>lambdaQuery().eq(PointsAccount::getUserId, userId));
        return (account == null || account.getBalance() == null) ? 0 : account.getBalance();
    }

    /** 我的积分流水（分页） */
    public PageResult<PointsRecordVO> pageRecords(Long userId, long pageNum, long pageSize) {
        Page<PointsRecord> page = new Page<>(
                pageNum < 1 ? CommonConstants.PAGE_NUM_DEFAULT : pageNum,
                pageSize < 1 ? CommonConstants.PAGE_SIZE_DEFAULT
                        : Math.min(pageSize, CommonConstants.PAGE_SIZE_MAX));
        Page<PointsRecord> result = pointsRecordMapper.selectPage(page,
                Wrappers.<PointsRecord>lambdaQuery()
                        .eq(PointsRecord::getUserId, userId)
                        // 与 idx_user_time (user_id, create_time) 索引顺序一致
                        .orderByDesc(PointsRecord::getCreateTime)
                        .orderByDesc(PointsRecord::getId));
        return PageUtil.of(result, PointsRecordVO::from);
    }

    // ==================== 私有方法 ====================

    /**
     * 确保账户存在，并以行锁方式返回。
     *
     * <p>正常情况下账户在用户注册时就已创建（见 {@code AppAuthService.register}），
     * 这里只是防御性补建 —— 但补建的写法有讲究：
     * <b>先普通查询、缺失则插入、最后再加锁读</b>，
     * 而不是"先 FOR UPDATE 查、发现没有就插入"。
     *
     * <p>后者在两个并发事务同时处理一个不存在的账户时，
     * 会对同一个间隙加锁然后各自尝试插入，形成死锁
     * （MySQL 会检测到并回滚其中一个，表现为随机的"操作失败"）。
     * 前者则靠唯一索引 {@code uk_user_id} 兜底：并发插入时只有一个成功，
     * 另一个捕获 {@code DuplicateKeyException} 忽略即可。
     */
    private PointsAccount ensureAccountLocked(Long userId) {
        PointsAccount existing = pointsAccountMapper.selectOne(
                Wrappers.<PointsAccount>lambdaQuery().eq(PointsAccount::getUserId, userId));

        if (existing == null) {
            PointsAccount created = new PointsAccount();
            created.setUserId(userId);
            created.setBalance(0);
            created.setTotalEarn(0);
            created.setTotalSpend(0);
            created.setVersion(0);
            try {
                pointsAccountMapper.insert(created);
                log.warn("补建积分账户（正常情况下应在注册时创建）| userId={}", userId);
            } catch (DuplicateKeyException e) {
                // 并发补建，另一个事务先成功了，属于正常情况
                log.debug("积分账户已被并发创建 | userId={}", userId);
            }
        }

        PointsAccount account = pointsAccountMapper.selectForUpdate(userId);
        BizException.assertNotNull(account, ResultCode.POINTS_ACCOUNT_NOT_FOUND);
        return account;
    }
}
