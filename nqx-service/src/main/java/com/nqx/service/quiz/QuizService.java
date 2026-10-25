package com.nqx.service.quiz;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nqx.common.api.PageResult;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.mapper.QuestionMapper;
import com.nqx.mapper.UserAnswerMapper;
import com.nqx.pojo.dto.app.QuizSubmitDTO;
import com.nqx.pojo.dto.question.AdminAnswerQuery;
import com.nqx.pojo.dto.question.QuestionQuery;
import com.nqx.pojo.dto.question.QuestionSaveDTO;
import com.nqx.pojo.entity.Question;
import com.nqx.pojo.entity.UserAnswer;
import com.nqx.pojo.enums.PointsChangeTypeEnum;
import com.nqx.pojo.util.PageUtil;
import com.nqx.pojo.vo.app.DailyQuizVO;
import com.nqx.pojo.vo.app.QuizQuestionVO;
import com.nqx.pojo.vo.app.QuizSubmitVO;
import com.nqx.pojo.vo.question.AdminUserAnswerVO;
import com.nqx.pojo.vo.question.QuestionDetailVO;
import com.nqx.service.points.PointsService;
import com.nqx.service.satoken.StpAppUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 每日问答服务。
 *
 * <h2>业务规则：每人每天一题</h2>
 *
 * <p>这个规则由三处共同保证：
 * <ol>
 *   <li>发题前查"今天答过没"，答过就不给题（体验层）；</li>
 *   <li>提交前再查一次（防绕过，例如把昨天的题目页留着今天提交）；</li>
 *   <li>数据库唯一索引 {@code (user_id, question_date)}（最终防线，挡住并发）。</li>
 * </ol>
 *
 * <h2>答案为什么不能随题目下发</h2>
 *
 * <p>见 {@link QuizQuestionVO} 的说明：用 VO 白名单而不是"返回前删字段"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuizService {

    /** 已发布状态 */
    private static final int STATUS_PUBLISHED = 1;

    /** 草稿状态：新建题目一律先落草稿，发布是显式的运营动作 */
    private static final int STATUS_DRAFT = 0;

    /** 已下线状态 */
    private static final int STATUS_OFFLINE = 2;

    /** 单选题的选项个数上限，用于把下标换算成字母 */
    private static final int MAX_OPTION_COUNT = 26;

    /** 答对得分的上界。与 {@code QuestionSaveDTO.points} 的 @Max 保持一致 */
    private static final int MAX_QUESTION_POINTS = 100;

    /** 选项 JSON 的结构解析器。ObjectMapper 是线程安全的，静态持有即可 */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final QuestionMapper questionMapper;
    private final UserAnswerMapper userAnswerMapper;
    private final PointsService pointsService;

    /**
     * 今日题目。
     *
     * <p>选题策略：<b>优先出没答过的题</b>；若题库已被答完，则从全部启用题目中随机
     * （题目在不同日期重复出现是正常的，每日一题的题库本来就有限）。
     */
    public DailyQuizVO today() {
        Long userId = StpAppUtil.getLoginIdAsLong();
        LocalDate today = LocalDate.now();

        if (hasAnsweredToday(userId, today)) {
            return DailyQuizVO.answered("今日已完成答题，明天再来吧");
        }

        // 一次取出全部启用题目。题库是几十到几百条的量级，
        // 在内存里做筛选与随机比在数据库里 ORDER BY RAND() 更划算 ——
        // 后者需要为每一行生成随机数再全表排序，且无法利用索引。
        // 若题库增长到万级，正确做法是按日预生成（每天凌晨为全部用户生成当日题目），
        // 而不是把这个查询写得更"聪明"。
        List<Question> allEnabled = questionMapper.selectList(Wrappers.<Question>lambdaQuery()
                .eq(Question::getStatus, STATUS_PUBLISHED));
        if (allEnabled.isEmpty()) {
            // 运维还没配题，返回"已答"形态让前端展示提示，而不是报 404 ——
            // 对用户来说"今天没题"和"今天答过了"都是"今天不用答了"，无需区分
            log.warn("题库中没有已发布的题目 | userId={}", userId);
            return DailyQuizVO.answered("今日暂无题目");
        }

        Set<Long> answeredIds = userAnswerMapper.selectList(Wrappers.<UserAnswer>lambdaQuery()
                        .eq(UserAnswer::getUserId, userId))
                .stream()
                .map(UserAnswer::getQuestionId)
                .collect(Collectors.toSet());

        List<Question> candidates = allEnabled.stream()
                .filter(q -> !answeredIds.contains(q.getId()))
                .toList();
        if (candidates.isEmpty()) {
            candidates = allEnabled;
        }

        Question question = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        return DailyQuizVO.of(QuizQuestionVO.from(question));
    }

    /**
     * 提交答案。
     *
     * <p>答对才发积分，幂等键用 {@code QUIZ-{userId}-{date}} ——
     * 即便唯一索引那道防线被绕过（例如有人直接改库把答题记录删了再答），
     * 积分流水层的唯一索引仍会拦住重复发放。
     */
    @Transactional(rollbackFor = Exception.class)
    public QuizSubmitVO submit(QuizSubmitDTO dto) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        LocalDate today = LocalDate.now();

        // 1. 防绕过：杜绝"留着昨天的题目页今天再提交"
        if (hasAnsweredToday(userId, today)) {
            throw new BizException(ResultCode.QUESTION_ALREADY_ANSWERED, "今日已完成答题，无法重复提交");
        }

        // 2. 题目校验
        Question question = questionMapper.selectById(dto.getQuestionId());
        BizException.assertNotNull(question, ResultCode.QUESTION_NOT_FOUND);
        if (question.getStatus() == null || question.getStatus() != STATUS_PUBLISHED) {
            throw new BizException(ResultCode.QUESTION_NOT_PUBLISHED);
        }
        if (!dto.hasAnyAnswer()) {
            throw new BizException(ResultCode.ANSWER_FORMAT_INVALID, "请先选择答案");
        }

        // 3. 判定
        String userAnswer = normalizeAnswer(question, dto);
        boolean correct = canonical(question.getCorrectAnswer()).equals(canonical(userAnswer));
        int points = (correct && question.getPoints() != null) ? question.getPoints() : 0;

        // 4. 写答题记录（唯一索引兜住并发重复提交）
        UserAnswer record = new UserAnswer();
        record.setUserId(userId);
        record.setQuestionId(question.getId());
        record.setQuestionDate(today);
        record.setUserAnswer(userAnswer);
        record.setIsCorrect(correct ? 1 : 0);
        record.setGainPoints(points);
        record.setCostTime(dto.getCostTime() == null ? 0 : dto.getCostTime());
        try {
            userAnswerMapper.insert(record);
        } catch (DuplicateKeyException e) {
            // 两个请求同时提交，另一个先成功 —— 属于幂等冲突，转成业务提示
            log.info("答题记录重复（并发提交）| userId={} | date={}", userId, today);
            throw new BizException(ResultCode.QUESTION_ALREADY_ANSWERED, "今日已完成答题，无法重复提交");
        }

        // 5. 答对发积分
        Integer balanceAfter;
        if (points > 0) {
            balanceAfter = pointsService.reward(userId, points, PointsChangeTypeEnum.DAILY_QUIZ,
                    "QUIZ-" + userId + "-" + today, question.getId(), "每日答题奖励");
        } else {
            balanceAfter = pointsService.getBalance(userId);
        }

        log.info("每日答题提交 | userId={} | questionId={} | 答案={} | 正确={} | 得分={}",
                userId, question.getId(), userAnswer, correct, points);

        return QuizSubmitVO.of(correct, points, toOptionIndex(question.getCorrectAnswer()),
                question.getCorrectAnswer(), question.getAnalysis(), balanceAfter);
    }

    /** 后台题库多条件检索 */
    public PageResult<Question> adminPage(QuestionQuery query) {
        Page<Question> page = new Page<>(query.safePageNum(), query.safePageSize());
        IPage<Question> result = questionMapper.selectAdminPage(page, query);
        return PageUtil.of(result);
    }

    /**
     * 题目详情（含 options）。
     *
     * <p>列表 SQL 刻意不查 options 以减小响应体，编辑弹窗需要完整选项时再单独取。
     */
    public QuestionDetailVO adminDetail(Long id) {
        Question question = questionMapper.selectById(id);
        BizException.assertNotNull(question, ResultCode.QUESTION_NOT_FOUND);
        return QuestionDetailVO.from(question);
    }

    /**
     * 新增题目。
     *
     * <p>状态默认落草稿（0）而不是已发布：新增即发布意味着一次保存就把题目
     * 推给了所有用户，"先建好、检查无误再发布"才是安全顺序。
     * 前端显式选了已发布时才发布。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createQuestion(QuestionSaveDTO dto) {
        List<ParsedOption> options = validateAndParse(dto);

        Question question = new Question();
        applyDto(question, dto, options);
        question.setStatus(dto.getStatus() == null ? STATUS_DRAFT : dto.getStatus());
        if (question.getSort() == null) {
            question.setSort(0);
        }
        question.setDeleted(0);
        questionMapper.insert(question);

        log.info("新增题目 | id={} | 题型={} | 状态={}", question.getId(),
                question.getQuestionType(), question.getStatus());
        return question.getId();
    }

    /**
     * 修改题目。
     *
     * <h2>为什么「已被答过的题」不允许改正确答案</h2>
     *
     * <p>答题记录 {@code user_answer} 里存的是用户当时提交的答案与判定结果。
     * 若一道题已经被用户答过，事后再改 {@code correctAnswer}，
     * 历史记录就会与题目对不上：用户当时被判错、如果按新答案本应判对（反之亦然），
     * 他会明确感到"判题不公平"，而这条记录已经没法重判（积分已发、已计入流水）。
     *
     * <p><b>为什么不干脆对所有已发布的题都禁止改答案？</b>
     * 那样会出现一个死局：某道题发布后被发现答案写错了，既不能改（已发布）、
     * 又不能删（详见 {@link #deleteQuestion} 对已有答题记录的保护），
     * 运营只能眼看着一道错题留在题库里。因此这里把限制收紧到<b>确实有据可依</b>的
     * 条件上 —— 只要还没有人答过，改正答案就是纯收益，不损害任何人的利益。
     *
     * <p>题干、选项、解析、积分等字段不受此限制：改题干不会让历史记录变得不公平
     * （判定依据是答案不是题干），但会让列表显示出新题干，这是可接受的。
     * 若确实需要"撤回"一道已被答过的题，正确做法是把它下线（status=2），
     * 而不是篡改答案。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateQuestion(QuestionSaveDTO dto) {
        BizException.assertNotNull(dto.getId(), ResultCode.PARAM_ERROR);
        Question exists = questionMapper.selectById(dto.getId());
        BizException.assertNotNull(exists, ResultCode.QUESTION_NOT_FOUND);

        boolean answered = hasAnyAnswer(dto.getId());
        List<ParsedOption> options = validateAndParse(dto);
        checkAnswerUnchanged(exists, dto);

        Question question = new Question();
        question.setId(dto.getId());
        applyDto(question, dto, options);
        // 状态不在这里改，由独立的发布/下线接口负责：
        // 编辑资料时顺手改了状态，等于让"改错别字"变成一次上线动作
        question.setStatus(null);
        questionMapper.updateById(question);

        log.info("修改题目 | id={} | 已被答过={}", dto.getId(), answered);
    }

    /**
     * 删除题目（逻辑删除）。
     *
     * <p>已有用户作答的题目不允许删除：{@code user_answer} 是积分发放的唯一依据，
     * 记录里只有 {@code question_id}。题目一旦被删，后台答题记录列表的题干与标准答案
     * 都会变成空白，管理员再也无法核对"这笔每日问答积分发得对不对"。
     * 题库本来就不需要"清理"，不再使用的题应该下线（status=2）而不是删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteQuestion(Long id) {
        Question exists = questionMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.QUESTION_NOT_FOUND);

        if (hasAnyAnswer(id)) {
            throw new BizException(ResultCode.QUESTION_IS_ANSWERED,
                    "该题目已有用户作答，删除会让历史答题记录失去题干与答案依据，请改为「下线」");
        }

        // @TableLogic 会把 deleteById 改写成 UPDATE question SET deleted = 1 WHERE id = ?
        questionMapper.deleteById(id);
        log.info("删除题目 | id={}", id);
    }

    /**
     * 发布 / 下线 / 转草稿。
     *
     * <p>发布（1）前再校验一次题目内容完整性：草稿阶段允许内容残缺（边写边存），
     * 但一旦发布就会被推送给用户，此时选项与答案必须已经合法。
     * 若只靠新增/修改时的校验，一道"先存草稿、后被改坏"的题目仍可能被发布出去。
     */
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, Integer status) {
        if (status == null
                || (status != STATUS_DRAFT && status != STATUS_PUBLISHED && status != STATUS_OFFLINE)) {
            throw new BizException(ResultCode.PARAM_ERROR, "状态值只能是 0（草稿）、1（已发布）或 2（已下线）");
        }
        Question exists = questionMapper.selectById(id);
        BizException.assertNotNull(exists, ResultCode.QUESTION_NOT_FOUND);

        if (status == STATUS_PUBLISHED) {
            QuestionSaveDTO recheck = toSaveDto(exists);
            validateAndParse(recheck);
        }

        Question question = new Question();
        question.setId(id);
        question.setStatus(status);
        questionMapper.updateById(question);

        log.info("题目状态变更 | id={} | status={}", id, status);
    }

    /**
     * 后台答题记录多条件检索。
     *
     * <p>返回的是 JOIN 后的 VO（含昵称与题干），SQL 见 {@code UserAnswerMapper.xml}。
     */
    public PageResult<AdminUserAnswerVO> adminAnswerPage(AdminAnswerQuery query) {
        Page<AdminUserAnswerVO> page = new Page<>(query.safePageNum(), query.safePageSize());
        return PageUtil.of(userAnswerMapper.selectAdminPage(page, query));
    }

    /**
     * 导出用：取符合条件的全部答题记录（上限 {@code maxRows} 条）。
     *
     * <p>与 {@link #adminAnswerPage} 同一条 Mapper SQL，关掉计数并放大页大小。
     */
    public List<AdminUserAnswerVO> exportAnswers(AdminAnswerQuery query, int maxRows) {
        Page<AdminUserAnswerVO> page = new Page<>(1, maxRows, false);
        return userAnswerMapper.selectAdminPage(page, query).getRecords();
    }

    /** 我的答题记录 */
    public PageResult<UserAnswer> myAnswers(long pageNum, long pageSize) {
        Long userId = StpAppUtil.getLoginIdAsLong();
        Page<UserAnswer> page = new Page<>(Math.max(pageNum, 1), Math.min(Math.max(pageSize, 1), 100));
        return PageUtil.of(userAnswerMapper.selectPage(page, Wrappers.<UserAnswer>lambdaQuery()
                .eq(UserAnswer::getUserId, userId)
                .orderByDesc(UserAnswer::getQuestionDate)
                .orderByDesc(UserAnswer::getId)));
    }

    // ==================== 私有方法 ====================

    private boolean hasAnsweredToday(Long userId, LocalDate today) {
        Long count = userAnswerMapper.selectCount(Wrappers.<UserAnswer>lambdaQuery()
                .eq(UserAnswer::getUserId, userId)
                .eq(UserAnswer::getQuestionDate, today));
        return count != null && count > 0;
    }

    /**
     * 把提交的答案归一化成字母串。
     *
     * <p>前端可能传下标（旧版方式）也可能传字母，统一成字母后再比较。
     */
    private String normalizeAnswer(Question question, QuizSubmitDTO dto) {
        if (StringUtils.hasText(dto.getAnswer())) {
            return dto.getAnswer().trim().toUpperCase();
        }

        int index = dto.getAnswerIndex();
        List<String> options = QuizQuestionVO.from(question).getOptions();
        if (index < 0 || index >= options.size()) {
            throw new BizException(ResultCode.ANSWER_FORMAT_INVALID,
                    "选项下标越界，该题共 " + options.size() + " 个选项");
        }
        if (index >= MAX_OPTION_COUNT) {
            throw new BizException(ResultCode.ANSWER_FORMAT_INVALID);
        }
        return String.valueOf((char) ('A' + index));
    }

    /**
     * 答案规范化，用于比较。
     *
     * <p>去掉非字母字符并<b>排序</b>，这样多选答案 {@code "BA"} 与 {@code "AB"}
     * 会被判为同一个答案 —— 用户勾选顺序不该影响正误判定。
     */
    private String canonical(String answer) {
        if (answer == null) {
            return "";
        }
        char[] chars = answer.toUpperCase().replaceAll("[^A-Z]", "").toCharArray();
        Arrays.sort(chars);
        return new String(chars);
    }

    /**
     * 把正确答案换算成选项下标。
     *
     * <p>仅单选题有意义；多选题（如 {@code "ABD"}）返回 -1，
     * 前端据此决定是否高亮某个选项 —— 强行返回首字母的下标会误导用户
     * （把 D 也正确的情况显示成只选了 A）。
     */
    private int toOptionIndex(String correctAnswer) {
        String canonical = canonical(correctAnswer);
        if (canonical.length() != 1) {
            return -1;
        }
        return canonical.charAt(0) - 'A';
    }

    // ==================== 后台题库校验 ====================

    /**
     * 选项 JSON 的解析结果。
     *
     * <p>用一个 record 而不是 {@code Map<String, String>}：
     * 选项的顺序是业务语义的一部分（A 必须排在最前），
     * {@code HashMap} 会把它打乱，前端下拉框与答案提示就会错位。
     */
    private record ParsedOption(String key, String value) {
    }

    /**
     * 校验题目内容、解析并规范化选项。
     *
     * <p>校验通过后就地改写 DTO 的 {@code correctAnswer} 为规范形式
     * （仅保留字母、统一大写、按字母排序），调用方之后直接取用即可。
     *
     * @return 规范化（键大写、按字母序）后的选项列表
     */
    private List<ParsedOption> validateAndParse(QuestionSaveDTO dto) {
        if (!StringUtils.hasText(dto.getContent())) {
            throw new BizException(ResultCode.PARAM_ERROR, "题干不能为空");
        }
        if (dto.getPoints() == null || dto.getPoints() <= 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "答对得分必须大于 0");
        }
        if (dto.getPoints() > MAX_QUESTION_POINTS) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "答对得分不能超过 " + MAX_QUESTION_POINTS + "（每日问答是纯发放型业务，大额积分请走管理员调整）");
        }
        if (dto.getQuestionType() != null
                && (dto.getQuestionType() < 1 || dto.getQuestionType() > 3)) {
            throw new BizException(ResultCode.PARAM_ERROR, "题型只能是 1（单选）、2（多选）或 3（判断）");
        }
        if (dto.getDifficulty() != null && (dto.getDifficulty() < 1 || dto.getDifficulty() > 3)) {
            throw new BizException(ResultCode.PARAM_ERROR, "难度只能是 1（易）、2（中）或 3（难）");
        }

        List<ParsedOption> options = parseOptions(dto.getOptions());
        dto.setCorrectAnswer(canonical(dto.getCorrectAnswer()));
        if (dto.getCorrectAnswer().isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "正确答案不能为空");
        }
        if (options.size() < 2) {
            // 一个选项的"选择题"没有任何意义，也必然是配置失误
            throw new BizException(ResultCode.PARAM_ERROR, "至少需要 2 个选项");
        }

        // 正确答案必须落在选项 key 里。少了这一步，一道"答案 A、选项只有 BCD"的题
        // 会安静地存进库，直到有用户来反馈"怎么答都错"才被发现 ——
        // 这种题不会报错、不会告警，只会持续消耗用户耐心。
        Set<String> keys = options.stream()
                .map(ParsedOption::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (char c : dto.getCorrectAnswer().toCharArray()) {
            if (!keys.contains(String.valueOf(c))) {
                throw new BizException(ResultCode.PARAM_ERROR,
                        "正确答案「" + c + "」不在选项里，该题现有选项：" + String.join("、", keys));
            }
        }

        if (dto.getQuestionType() != null && dto.getQuestionType() == 1
                && dto.getCorrectAnswer().length() > 1) {
            throw new BizException(ResultCode.PARAM_ERROR, "单选题只能有一个正确答案");
        }

        return options;
    }

    /**
     * 校验"已有用户作答的题目不得修改正确答案"。
     *
     * <p>比较用规范化后的值，因此把 {@code "BA"} 改成 {@code "AB"} 不算修改
     * （顺序不影响判定，见 {@link #canonical}）；反过来，把 {@code "AB"} 删成
     * {@code "A"} 会被拦下 —— 那会让当时判错的用户觉得自己被区别对待。
     */
    private void checkAnswerUnchanged(Question exists, QuestionSaveDTO dto) {
        if (!hasAnyAnswer(exists.getId())) {
            return;
        }
        if (!canonical(exists.getCorrectAnswer()).equals(canonical(dto.getCorrectAnswer()))) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "该题目已有用户作答，修改正确答案会让历史判定变得不公平；如需作废请改为「下线」");
        }
    }

    /**
     * 解析并规范化选项 JSON。
     *
     * <p>规范化三件事：去掉 key/value 的空白、key 统一大写、
     * 按 key 字母序排序。这样 {@code "ab"}、{@code "A、B"} 这类输入
     * 都会被归一成 {@code "AB"}，与 {@link #canonical} 的比较口径一致 ——
     * 否则"答案写成小写就永远判错"这类问题会以最难查的形式出现。
     */
    private List<ParsedOption> parseOptions(String optionsJson) {
        if (!StringUtils.hasText(optionsJson)) {
            throw new BizException(ResultCode.PARAM_ERROR, "选项不能为空");
        }
        List<ParsedOption> raw = new ArrayList<>();
        try {
            List<OptionJson> parsed = JSON.readValue(optionsJson, new TypeReference<List<OptionJson>>() {
            });
            if (parsed == null || parsed.isEmpty()) {
                throw new BizException(ResultCode.PARAM_ERROR, "选项不能为空");
            }
            for (OptionJson option : parsed) {
                if (option == null || !StringUtils.hasText(option.key()) || !StringUtils.hasText(option.value())) {
                    throw new BizException(ResultCode.PARAM_ERROR, "每个选项都必须有 key 与内容");
                }
                raw.add(new ParsedOption(option.key().trim().toUpperCase(), option.value().trim()));
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 把 Jackson 的原始异常转成能指导运营修改的提示：
            // 只说"参数错误"的话，管理员看着自己填的选项不知道错在哪里
            throw new BizException(ResultCode.PARAM_ERROR,
                    "选项格式不合法，应为 [{\"key\":\"A\",\"value\":\"选项内容\"}] 形式的 JSON：" + e.getMessage());
        }

        Set<String> keys = new LinkedHashSet<>();
        for (ParsedOption option : raw) {
            if (!keys.add(option.key())) {
                throw new BizException(ResultCode.PARAM_ERROR, "选项 key 重复：" + option.key());
            }
        }
        return raw.stream()
                .sorted((a, b) -> a.key().compareTo(b.key()))
                .toList();
    }

    /** options JSON 的元素结构，仅供 Jackson 反序列化 */
    private record OptionJson(String key, String value) {
    }

    /** 把解析后的选项重新序列化成入库用的 JSON 字符串 */
    private String toOptionsJson(List<ParsedOption> options) {
        try {
            return JSON.writeValueAsString(options);
        } catch (Exception e) {
            // 走到这里说明上面刚解析过的对象反而序列化不了，属于编码错误
            throw new IllegalStateException("选项序列化失败", e);
        }
    }

    /**
     * 把 DTO 的字段搬到实体上。
     *
     * <p>集中在方法里而不是 update/create 各写一遍：漏搬一个字段的后果是
     * "编辑时该字段被静默清空"，而 MyBatis-Plus 的 not_null 更新策略
     * 又恰好会掩盖一部分这类问题（null 不更新，看起来"没改"）。
     */
    private void applyDto(Question question, QuestionSaveDTO dto, List<ParsedOption> options) {
        question.setContent(dto.getContent().trim());
        // 题型/难度不传时兜默认值：这两列在库里有 NOT NULL 约束，
        // 序列化后传 null 会被 MyBatis-Plus 的更新策略跳过（新增时则直接报错）
        question.setQuestionType(dto.getQuestionType() == null ? 1 : dto.getQuestionType());
        question.setOptions(toOptionsJson(options));
        question.setCorrectAnswer(dto.getCorrectAnswer());
        question.setAnalysis(dto.getAnalysis());
        question.setPoints(dto.getPoints());
        question.setDifficulty(dto.getDifficulty() == null ? 1 : dto.getDifficulty());
        // 生效日期为空时取当天：题库的选题只认 status，publish_date 是给运营看的排期，
        // 留空会让列表出现一列空白，不如按"立即生效"处理
        question.setPublishDate(dto.getPublishDate() == null ? LocalDate.now() : dto.getPublishDate());
        // sort 允许为 null：MyBatis-Plus 的 not_null 更新策略会跳过该列，
        // 于是"没传 sort"= 不改排序，而不是把已有排序重置为 0
        question.setSort(dto.getSort());
    }

    /** 实体 → SaveDTO，供"发布前再校验一次"复用同一套规则 */
    private QuestionSaveDTO toSaveDto(Question question) {
        QuestionSaveDTO dto = new QuestionSaveDTO();
        dto.setId(question.getId());
        dto.setContent(question.getContent());
        dto.setQuestionType(question.getQuestionType());
        dto.setOptions(question.getOptions());
        dto.setCorrectAnswer(question.getCorrectAnswer());
        dto.setAnalysis(question.getAnalysis());
        dto.setPoints(question.getPoints());
        dto.setDifficulty(question.getDifficulty());
        dto.setPublishDate(question.getPublishDate());
        dto.setStatus(question.getStatus());
        dto.setSort(question.getSort());
        return dto;
    }

    /** 该题是否已有人作答 —— 删除保护与"不许改答案"共用同一个判断 */
    private boolean hasAnyAnswer(Long questionId) {
        Long count = userAnswerMapper.selectCount(Wrappers.<UserAnswer>lambdaQuery()
                .eq(UserAnswer::getQuestionId, questionId));
        return count != null && count > 0;
    }
}
