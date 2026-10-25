package com.nqx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.nqx.pojo.dto.question.QuestionQuery;
import com.nqx.pojo.entity.Question;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 每日问答题库 Mapper。
 */
@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    /**
     * 后台题库多条件检索（动态 SQL）。
     *
     * <p>SQL 见 {@code resources/mapper/QuestionMapper.xml}。
     * 注意 {@code options} 是 JSON 列，在实体里映射为 String，
     * 后台列表不需要展示选项内容，因此这条 SQL 不查它 ——
     * 少传一个大字段，列表接口的响应体能小不少。
     */
    IPage<Question> selectAdminPage(IPage<Question> page, @Param("query") QuestionQuery query);
}
