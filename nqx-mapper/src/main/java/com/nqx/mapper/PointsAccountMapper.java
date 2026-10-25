package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.entity.PointsAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 积分账户 Mapper。
 */
@Mapper
public interface PointsAccountMapper extends BaseMapper<PointsAccount> {

    /**
     * 条件扣减积分 —— 项目里最重要的防超扣手段。
     *
     * <p><b>为什么不用「先 SELECT 查余额，再判断够不够，再 UPDATE」？</b>
     * 那三步之间存在竞态窗口：两个并发请求可能同时查到余额 100，
     * 都认为"够扣 100"，于是各扣一次，余额变成 -100。
     *
     * <p>本方法把判断与扣减合并进<b>一条 SQL</b>：
     * <pre>{@code UPDATE points_account SET balance = balance - ? WHERE user_id = ? AND balance >= ?}</pre>
     * InnoDB 会对这一行加行锁，两个并发事务串行执行，
     * 第二个事务的 {@code balance >= ?} 判断会基于第一个事务提交后的值，
     * 因此扣不动 —— 表现为 affectedRows = 0。
     *
     * <p><b>调用方必须检查返回值</b>：返回 0 说明余额不足，要抛业务异常让事务回滚。
     * 忽略返回值是这个方案最常见的失效原因。
     *
     * @return 受影响行数，1 表示扣减成功，0 表示余额不足
     */
    int deductPoints(@Param("userId") Long userId,
                     @Param("points") Integer points);

    /**
     * 增加积分（回收返积分、答题奖励、管理员调整）。
     *
     * <p>加积分不存在超发风险（余额只会变大），但同样要同时维护
     * {@code total_earn} 累计值，避免出现"余额对得上、累计值对不上"的不一致。
     */
    int addPoints(@Param("userId") Long userId,
                  @Param("points") Integer points);

    /**
     * 加行锁读取账户。
     *
     * <p><b>为什么积分变更前必须用 {@code SELECT ... FOR UPDATE}？</b>
     * 因为要往流水里写 {@code balance_after}（变动后余额）用于对账。
     * 若用普通查询读余额再计算，两个并发事务会读到同一个旧余额，
     * 各自算出一个"变动后余额"，其中一条必然是错的：
     *
     * <pre>
     *   余额 100，T1 加 50、T2 加 30 并发执行
     *   普通读：T1 读到 100 → 记 150；T2 也读到 100 → 记 130   ← 130 是错的，实际是 180
     *   加行锁：T2 必须等 T1 提交，读到 150 → 记 180            ← 正确
     * </pre>
     *
     * <p>行锁把<b>同一个用户</b>的积分操作串行化。不同用户之间互不影响，
     * 因此并发度不会成为瓶颈 —— 现实中也不存在需要同时给同一个用户加两次积分的高频场景。
     */
    PointsAccount selectForUpdate(@Param("userId") Long userId);

    /**
     * 后台积分账户多条件检索（需要 JOIN app_user 取昵称，故手写 XML）。
     *
     * <p>SQL 见 {@code resources/mapper/PointsAccountMapper.xml}。
     */
    IPage<com.nqx.pojo.vo.order.AdminPointsAccountVO> selectAdminPage(
            IPage<com.nqx.pojo.vo.order.AdminPointsAccountVO> page,
            @Param("query") com.nqx.pojo.dto.points.AdminPointsQuery query);
}
