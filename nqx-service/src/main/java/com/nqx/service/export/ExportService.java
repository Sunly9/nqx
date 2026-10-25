package com.nqx.service.export;

import cn.idev.excel.FastExcel;
import com.nqx.common.api.ResultCode;
import com.nqx.common.exception.BizException;
import com.nqx.pojo.dto.order.AdminOrderQuery;
import com.nqx.pojo.dto.points.AdminPointsQuery;
import com.nqx.pojo.dto.question.AdminAnswerQuery;
import com.nqx.service.export.row.AnswerExportRow;
import com.nqx.service.export.row.OrderExportRow;
import com.nqx.service.export.row.PointsRecordExportRow;
import com.nqx.service.file.FileStorageService;
import com.nqx.service.order.AdminOrderService;
import com.nqx.service.points.AdminPointsService;
import com.nqx.service.quiz.QuizService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Excel 导出。
 *
 * <h2>为什么返回「下载链接」而不是直接吐文件流</h2>
 *
 * <p>最初的做法是把 xlsx 直接写进 HTTP 响应流。本地跑没问题，
 * 但**微信云托管对返回包有 1000KiB 的硬限制** —— 订单导出一多，
 * 响应就会被截断，前端拿到的是一个损坏的 xlsx（Excel 提示"文件已损坏"）。
 *
 * <p>现在的做法：
 * <pre>
 *   生成 xlsx 到内存 → 存到对象存储（或本地） → 返回一个**下载链接**
 * </pre>
 * 接口响应只是一个短字符串（几十字节），彻底绕开大小限制；
 * 下载文件走静态资源/COS，不占应用带宽。
 *
 * <p>顺带的好处：链接可以直接发给别人、可以重复下载，
 * 而"一次性流"发出去就再也找不回来了。
 *
 * <h2>为什么导出不放在 {@code @Transactional} 方法里</h2>
 *
 * <p>导出是"读一批数据 + 生成一个可能很大的文件"。整个过程中持有数据库连接和事务
 * 没有意义 —— 数据读完之后，写 Excel 可能花几秒，这几秒里事务白白占着一个连接。
 * 并发导出几个人就可能把连接池耗光。
 *
 * <h2>为什么要限制行数</h2>
 *
 * <p>{@link #MAX_EXPORT_ROWS} 是硬上限。不设上限的话，一次"全部导出"会把
 * 几十万行读进内存再写进 Excel —— 结果是 OOM，而且是**在导出时**OOM，
 * 用户看到的是服务短暂不可用而不是一条错误提示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    /**
     * 单次导出的行数上限。
     *
     * <p>1 万行 × 约 15 列，内存占用在几十 MB 量级，对演示/小规模使用足够。
     * 数据量真的上去之后应该改成"异步导出 + 任务通知"，而不是继续加大这个数字。
     */
    public static final int MAX_EXPORT_ROWS = 10000;

    private final AdminOrderService adminOrderService;
    private final AdminPointsService adminPointsService;
    private final QuizService quizService;

    /**
     * 文件存储（本地或 COS）。
     *
     * <p>复用同一个存储实现，而不是导出单独写一份：这样"本地开发存磁盘、
     * 云托管存 COS"的分流逻辑只有一处，不会出现两边行为不一致。
     */
    private final FileStorageService fileStorageService;

    /**
     * 导出订单（回收单 + 采购单，按 query 里的条件筛选）。
     *
     * @return 可下载的 URL
     */
    public String exportOrders(AdminOrderQuery query) {
        List<OrderExportRow> rows = adminOrderService.exportList(query, MAX_EXPORT_ROWS)
                .stream().map(OrderExportRow::from).toList();
        checkNotEmpty(rows);
        warnIfTruncated(rows.size(), "订单");
        return store("订单列表", OrderExportRow.class, rows);
    }

    /** 导出积分流水，返回可下载的 URL */
    public String exportPointsRecords(AdminPointsQuery query) {
        List<PointsRecordExportRow> rows = adminPointsService.exportRecords(query, MAX_EXPORT_ROWS)
                .stream().map(PointsRecordExportRow::from).toList();
        checkNotEmpty(rows);
        warnIfTruncated(rows.size(), "积分流水");
        return store("积分流水", PointsRecordExportRow.class, rows);
    }

    /** 导出答题记录，返回可下载的 URL */
    public String exportAnswers(AdminAnswerQuery query) {
        List<AnswerExportRow> rows = quizService.exportAnswers(query, MAX_EXPORT_ROWS)
                .stream().map(AnswerExportRow::from).toList();
        checkNotEmpty(rows);
        warnIfTruncated(rows.size(), "答题记录");
        return store("答题记录", AnswerExportRow.class, rows);
    }

    // ------------------------------------------------------------------------

    /**
     * 没有数据时明确报错，而不是导出空文件。
     *
     * <p>一个只有表头的 xlsx 很容易被误认为"导出成功但数据为空"，
     * 用户会以为系统里真的没数据。给一条明确提示省掉一轮沟通。
     */
    private void checkNotEmpty(List<?> rows) {
        if (rows.isEmpty()) {
            throw new BizException(ResultCode.PARAM_ERROR, "当前筛选条件下没有数据可导出");
        }
    }

    private void warnIfTruncated(int size, String what) {
        if (size >= MAX_EXPORT_ROWS) {
            log.warn("导出{}达到上限 {} 条，结果已截断 | 如需全量请缩小筛选范围",
                    what, MAX_EXPORT_ROWS);
        }
    }

    /**
     * 生成 xlsx 并存储，返回下载地址。
     *
     * <p>写进内存（{@link ByteArrayOutputStream}）而不是临时文件：
     * 上限 1 万行、几十 MB 量级，内存完全放得下；
     * 而临时文件要考虑清理、权限、容器磁盘（还是那句话：容器磁盘是临时的）。
     */
    private <T> String store(String sheetName, Class<T> clazz, List<T> rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FastExcel.write(out, clazz).sheet(sheetName).doWrite(rows);

        // 把 sheet 名作为文件名提示：下载下来是「订单列表_20261020153012.xlsx」，
        // 而不是一串 UUID。同一个链接下载两次也不会互相覆盖。
        String url = fileStorageService.storeXlsx(out.toByteArray(), "export", sheetName);
        log.info("导出完成 | sheet={} | 行数={} | 大小={}字节 | url={}",
                sheetName, rows.size(), out.size(), url);
        return url;
    }
}
