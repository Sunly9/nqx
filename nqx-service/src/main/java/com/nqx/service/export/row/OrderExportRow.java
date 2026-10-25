package com.nqx.service.export.row;

import cn.idev.excel.annotation.ExcelProperty;
import com.nqx.pojo.vo.order.AdminOrderVO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

/**
 * 订单导出的一行（回收单与采购单共用）。
 *
 * <h2>为什么这个类在 service 模块而不在 pojo 模块</h2>
 *
 * <p>它依赖 {@code @ExcelProperty}（来自 {@code cn.idev.excel:fastexcel}），
 * 而 fastexcel 只被 {@code nqx-service} 引入 —— {@code nqx-pojo} 是纯数据结构模块，
 * 不该为了导出功能背上一个 Excel 库（连带它的一串传递依赖）。
 *
 * <p>第一次写的时候把它放在了 pojo 的 {@code vo/export} 下，编译直接报
 * "程序包 cn.idev.excel.annotation 不存在"。**模块边界的报错通常很直接，
 * 关键是别用"给 pojo 加个依赖"来绕过它** —— 那会让 pojo 越来越重。
 *
 * <h2>为什么不直接导出 {@code AdminOrderVO}</h2>
 *
 * <p>VO 是给**界面**用的：里面有 {@code id}、{@code status}（数字码）、
 * {@code orderType}（数字码）。直接导出去，拿到 Excel 的人看到一列 "41"
 * 完全不知道是什么。本类只保留业务上需要的列，并把枚举翻译成中文 ——
 * <b>导出的表格是给不写代码的人看的</b>，这一点决定了列的取舍。
 */
@Data
public class OrderExportRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ExcelProperty("订单号")
    private String orderNo;

    @ExcelProperty("订单类型")
    private String orderTypeText;

    @ExcelProperty("状态")
    private String statusText;

    @ExcelProperty("用户昵称")
    private String nickname;

    @ExcelProperty("联系人")
    private String contactName;

    @ExcelProperty("联系电话")
    private String contactPhone;

    /** 回收单才有意义 */
    @ExcelProperty("回收方式")
    private String recycleMethod;

    @ExcelProperty("预估重量(kg)")
    private BigDecimal estimatedWeight;

    @ExcelProperty("实际重量(kg)")
    private BigDecimal actualWeight;

    @ExcelProperty("碳减排(kg)")
    private BigDecimal carbonSaved;

    @ExcelProperty("获得积分")
    private Integer totalPoints;

    @ExcelProperty("快递单号")
    private String courierNo;

    @ExcelProperty("下单时间")
    private String createTime;

    @ExcelProperty("完成时间")
    private String finishTime;

    public static OrderExportRow from(AdminOrderVO vo) {
        OrderExportRow row = new OrderExportRow();
        row.setOrderNo(vo.getOrderNo());
        row.setOrderTypeText(vo.getOrderTypeText());
        row.setStatusText(vo.getStatusText());
        row.setNickname(vo.getNickname());
        row.setContactName(vo.getContactName());
        row.setContactPhone(vo.getContactPhone());
        row.setRecycleMethod(recycleMethodText(vo.getRecycleMethod()));
        row.setEstimatedWeight(vo.getEstimatedWeight());
        row.setActualWeight(vo.getActualWeight());
        row.setCarbonSaved(vo.getCarbonSaved());
        row.setTotalPoints(vo.getTotalPoints());
        row.setCourierNo(vo.getCourierNo());
        // 导出用「字符串时间」而不是 LocalDateTime：单元格里是文本，
        // 不会因为 Excel 的时区/格式推断变成奇怪的数字
        row.setCreateTime(vo.getCreateTime() == null ? null : DTF.format(vo.getCreateTime()));
        row.setFinishTime(vo.getFinishTime() == null ? null : DTF.format(vo.getFinishTime()));
        return row;
    }

    /**
     * 回收方式翻译成中文。
     *
     * <p>数据库里存的是 {@code pickup} / {@code send}，后台列表页自己维护了一份
     * 翻译（{@code recycleMethodText}）。导出的表格是**给人看的**，
     * 出现一列光秃秃的 "send" 没有任何意义 —— 拿到表的人只会问"这是什么"。
     *
     * <p>为什么不在后端 VO 里直接返回中文：那个字段同时被前端的**筛选项**
     * 用来做值匹配，改成中文会让筛选失效。所以翻译只发生在"面向人的输出"这一层。
     *
     * <p>未知值原样返回而不是输出空：宁可让人看到看不懂的原值，
     * 也不要让一格空白 —— 空白会被当成"没有数据"，而原值至少提示了还有别的取值。
     */
    private static String recycleMethodText(String method) {
        if (method == null) {
            return null;
        }
        return switch (method) {
            case "pickup" -> "上门回收";
            case "send" -> "邮寄回收";
            default -> method;
        };
    }
}
