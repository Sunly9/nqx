package com.nqx.pojo.jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * 「0/1 开关」字段的宽容反序列化器：同时接受 JSON 布尔值与数字。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>本项目里有一批字段语义上是"开关"，但在不同层用不同的表示：
 * <ul>
 *   <li><b>数据库</b>用 {@code TINYINT}（0/1）—— 例如
 *       {@code user_address.is_default}、{@code cart.selected}；</li>
 *   <li><b>旧小程序前端</b>用 <b>JSON 布尔值</b>提交 ——
 *       例如地址编辑页的 {@code is_default: false}；</li>
 *   <li><b>新管理后台</b>用数字 —— Vue 的 el-switch 绑定的就是 0/1。</li>
 * </ul>
 *
 * <p>把 DTO 字段声明成 {@code Integer} 时，**Jackson 默认不能把 JSON 布尔值
 * 转成 Integer**，会抛 {@code MismatchedInputException}。这个坑真实踩过：
 * 小程序点「保存地址」返回 400「请求参数格式不正确」，
 * 而页面把它显示成「网络错误」，排查方向一开始完全跑偏了。
 *
 * <h2>为什么不用 Boolean 或 Integer 二选一</h2>
 *
 * <p>选 {@code Boolean}：新后台传 0/1 会失败；
 * 选 {@code Integer}：旧小程序传 true/false 会失败。
 * <b>两种客户端都真实存在，所以必须都接受。</b>
 *
 * <p>这类"同一语义在不同层有多种表示"的字段，正确的做法是
 * <b>在边界处做宽容转换，而不是强迫某一端改变习惯</b> ——
 * 尤其是当那一端是已经上线、不方便改的旧客户端时。
 *
 * <h2>容错策略</h2>
 *
 * <p>无法识别的输入一律返回 0（视为"否"）而不是抛异常。
 * 这是刻意的：对开关类字段而言，「关闭」是安全且无副作用的默认值；
 * 而为了一个开关值把整个请求打回，会让用户看到一个语焉不详的失败。
 */
public class FlexibleZeroOneDeserializer extends JsonDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == null) {
            return null;
        }
        return switch (token) {
            case VALUE_TRUE -> 1;
            case VALUE_FALSE -> 0;
            case VALUE_NULL -> null;
            case VALUE_STRING -> fromText(parser.getText());
            // 数字（含小数）走这里；其它未知 token 一律按"否"处理
            default -> token.isNumeric() ? parser.getIntValue() : 0;
        };
    }

    /** 字符串形式的开关值：兼容 "true"/"false"/"1"/"0" */
    private Integer fromText(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return 0;
        }
        try {
            // 非 0 一律视为"是"，与 JavaScript 的真值判断保持一致
            return Integer.parseInt(trimmed) == 0 ? 0 : 1;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
