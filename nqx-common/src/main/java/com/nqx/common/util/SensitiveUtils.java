package com.nqx.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 敏感信息脱敏工具。
 *
 * <p><b>为什么操作日志必须脱敏？</b>
 * 操作日志会把接口入参完整落库。如果不脱敏，`sys_operation_log` 表就会变成
 * 全系统最大的一处明文数据泄露点：管理员重置密码的请求体里带着新密码、
 * 小程序登录接口带着手机号、绑定微信的接口带着 openid。
 * 一旦这张表被导出或被低权限人员查询，等于把用户凭证直接交出去。
 *
 * <p><b>实现方式为什么用 Jackson 遍历而不是正则替换？</b>
 * 正则处理 JSON 极易出错：字段值里本身可能包含引号、转义符、嵌套的 JSON 字符串，
 * 一处理不当要么漏脱敏（安全问题），要么把 JSON 改坏（日志不可读）。
 * 把参数先转成 {@link JsonNode} 树再按字段名遍历，语义明确且不受值内容影响。
 */
public final class SensitiveUtils {

    private SensitiveUtils() {
    }

    /** 完全隐藏其值的字段名（统一小写比较） */
    private static final Set<String> SECRET_FIELDS = Set.of(
            "password", "oldpassword", "newpassword", "confirmpassword",
            "pwd", "passwd", "token", "accesstoken", "refreshtoken",
            "secret", "appsecret", "authorization", "privatekey"
    );

    /** 需要保留首尾部分字符的字段 → 保留前几位 */
    private static final Map<String, Integer> PARTIAL_FIELDS = Map.of(
            "phone", 3,
            "mobile", 3,
            "receiverphone", 3,
            "contactphone", 3,
            "telephone", 3,
            "openid", 4,
            "unionid", 4,
            "idcard", 3,
            "idnumber", 3
    );

    /** 打码后的占位符 */
    private static final String MASK = "****";

    /**
     * 将对象序列化为 JSON 字符串，并按字段名脱敏。
     *
     * @param objectMapper Spring 容器中的 ObjectMapper（已注册 JavaTimeModule，能处理 LocalDateTime）
     * @param value        待处理对象，可为 null
     * @return 脱敏后的 JSON 字符串；序列化失败时返回类型名而非抛异常
     *         —— 记日志的动作绝不能因为记日志本身而失败
     */
    public static String toMaskedJson(ObjectMapper objectMapper, Object value) {
        if (value == null) {
            return null;
        }
        try {
            JsonNode root = objectMapper.valueToTree(value);
            mask(root);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            // 不抛异常：操作日志是旁路功能，不能影响主流程
            return "[无法序列化的参数: " + value.getClass().getSimpleName() + "]";
        }
    }

    /** 递归遍历 JSON 树并按字段名脱敏 */
    private static void mask(JsonNode node) {
        if (node instanceof ObjectNode objectNode) {
            List<String> secretKeys = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (SECRET_FIELDS.contains(key)) {
                    secretKeys.add(entry.getKey());
                } else {
                    // 先处理子节点，再处理自身，保证嵌套结构也被覆盖
                    mask(entry.getValue());
                    if (PARTIAL_FIELDS.containsKey(key)) {
                        maskPartial(objectNode, entry.getKey(), PARTIAL_FIELDS.get(key));
                    }
                }
            }
            // 收集完再修改，避免遍历时改动结构导致 ConcurrentModificationException
            secretKeys.forEach(key -> objectNode.put(key, MASK));
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(SensitiveUtils::mask);
        }
    }

    /** 保留前 prefixLength 位，其余打码，例如 138****8888 */
    private static void maskPartial(ObjectNode node, String key, int prefixLength) {
        JsonNode valueNode = node.get(key);
        if (valueNode == null || !valueNode.isTextual()) {
            return;
        }
        String text = valueNode.asText();
        if (text.length() <= prefixLength) {
            node.put(key, MASK);
            return;
        }
        node.put(key, text.substring(0, prefixLength) + MASK);
    }

    /**
     * 截断超长文本。
     *
     * <p>操作日志的入参与响应存储在 {@code TEXT} 列（上限 65535 字节），
     * 但一次批量操作的入参可能远超这个长度。若不在应用层截断，
     * MySQL 会直接报 {@code Data too long for column} 导致日志丢失
     * （在严格模式下正是抛异常，而这个异常发生在异步线程中，几乎不会被注意到）。
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...[已截断]";
    }
}
