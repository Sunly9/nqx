package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.dto.question.AdminAnswerQuery;
import com.nqx.pojo.entity.UserAnswer;
import com.nqx.pojo.vo.question.AdminUserAnswerVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户答题记录 Mapper。
 *
 * <p>本表是「只追加」的：只插入、不更新、不删除。答题记录是积分发放的事实依据。
 *
 * <p>唯一索引 {@code uk_user_date (user_id, question_date)}
 * 是「每人每天只能答一题」的最终防线。
 * 应用层虽然也会先查一次"今天答过没"，但那一步在并发下并不可靠
 * （两个请求可能同时查到"没答过"）—— 真正的保证来自这里：
 * 重复插入会抛 {@code DuplicateKeyException}，
 * 由 {@code QuizService} 捕获并转为"今日已完成答题"的业务提示。
 *
 * <p>注意本表没有 {@code deleted} 列，手写 SQL 不要加 {@code deleted = 0}。
 */
@Mapper
public interface UserAnswerMapper extends BaseMapper<UserAnswer> {

    /**
     * 后台答题记录多条件检索。
     *
     * <p>SQL 见 {@code resources/mapper/UserAnswerMapper.xml}。
     * 需要 JOIN {@code app_user} 取昵称、JOIN {@code question} 取题干，
     * MyBatis-Plus 的 Wrapper 表达不了（也不该硬塞），因此手写 XML。
     */
    IPage<AdminUserAnswerVO> selectAdminPage(IPage<AdminUserAnswerVO> page,
                                             @Param("query") AdminAnswerQuery query);
}
