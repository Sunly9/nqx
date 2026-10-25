package com.nqx.service.export.row;

import cn.idev.excel.annotation.ExcelProperty;
import com.nqx.pojo.vo.order.AdminPointsRecordVO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.format.DateTimeFormatter;

/**
 * 积分流水导出的一行。
 *
 * <p>与 {@link OrderExportRow} 同样放在 service 模块（原因见那个类的说明）。
 *
 * <p><b>「变动积分」带正负号</b>：积分的加减是有方向的，导出成无符号数会让
 * 对账的人无法区分"加了 100"和"扣了 100"，只能再去看类型列，
 * 而类型列（注册奖励/积分兑换）并不总能一眼看出方向。
 */
@Data
public class PointsRecordExportRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @ExcelProperty("流水号")
    private Long id;

    @ExcelProperty("用户昵称")
    private String nickname;

    @ExcelProperty("用户ID")
    private Long userId;

    @ExcelProperty("变动类型")
    private String changeTypeText;

    /** 有符号：正数为获得，负数为消耗 */
    @ExcelProperty("变动积分")
    private Integer changeValue;

    @ExcelProperty("变动后余额")
    private Integer balanceAfter;

    @ExcelProperty("关联业务单号")
    private String bizNo;

    @ExcelProperty("备注")
    private String remark;

    @ExcelProperty("时间")
    private String createTime;

    public static PointsRecordExportRow from(AdminPointsRecordVO vo) {
        PointsRecordExportRow row = new PointsRecordExportRow();
        row.setId(vo.getId());
        row.setNickname(vo.getNickname());
        row.setUserId(vo.getUserId());
        row.setChangeTypeText(vo.getChangeTypeText());
        row.setChangeValue(vo.getChangeValue());
        row.setBalanceAfter(vo.getBalanceAfter());
        row.setBizNo(vo.getBizNo());
        row.setRemark(vo.getRemark());
        row.setCreateTime(vo.getCreateTime() == null ? null : DTF.format(vo.getCreateTime()));
        return row;
    }
}
