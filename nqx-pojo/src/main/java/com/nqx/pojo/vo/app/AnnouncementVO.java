package com.nqx.pojo.vo.app;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nqx.pojo.entity.Announcement;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 公告。
 *
 * <p>旧版把 {@code announcements} 表的整行返回给前端，因此页面引用的是
 * <b>数据库列名</b>（如 {@code updated_at}）。这里用 {@link JsonProperty}
 * 在 JSON 侧还原成下划线命名，Java 侧保持驼峰。
 *
 * <p>新表比旧表多了 {@code start_time} / {@code end_time} 生效时间窗，
 * 用于支持"活动公告到点自动下线"——旧版只有一个 {@code is_active} 开关，
 * 活动结束后必须有人手动去关，忘了就会一直挂在首页。
 */
@Data
public class AnnouncementVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    private String title;

    private String content;

    /** 类型 1公告 2活动 */
    private Integer annType;

    @JsonProperty("start_time")
    private LocalDateTime startTime;

    @JsonProperty("end_time")
    private LocalDateTime endTime;

    @JsonProperty("created_at")
    private LocalDateTime createTime;

    @JsonProperty("updated_at")
    private LocalDateTime updateTime;

    public static AnnouncementVO from(Announcement a) {
        AnnouncementVO vo = new AnnouncementVO();
        vo.setId(a.getId());
        vo.setTitle(a.getTitle());
        vo.setContent(a.getContent());
        vo.setAnnType(a.getAnnType());
        vo.setStartTime(a.getStartTime());
        vo.setEndTime(a.getEndTime());
        vo.setCreateTime(a.getCreateTime());
        vo.setUpdateTime(a.getUpdateTime());
        return vo;
    }
}
