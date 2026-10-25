package com.nqx.web.support;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 跨线程上下文传递装饰器。
 *
 * <p><b>为什么需要它？</b>
 * MDC 底层是 {@link ThreadLocal}，只在当前线程有效。
 * 一旦把任务丢给 {@code @Async} 线程池执行，子线程里读 MDC 得到的是 {@code null}，
 * 于是异步任务的日志全部丢失 traceId —— 排查问题时最关键的异步链路恰恰断了。
 *
 * <p>{@link TaskDecorator} 会在任务提交时于<b>主线程</b>捕获上下文，
 * 并在<b>子线程</b>执行前恢复它，执行后清理。Spring 的 {@code ThreadPoolTaskExecutor}
 * 原生支持该机制。
 *
 * <p><b>注意：这里刻意只传递 MDC，不传递 Sa-Token 的登录上下文。</b>
 * 原因是一个重要的设计原则：<b>异步任务不应依赖"当前登录用户"这种隐式上下文</b>。
 * 正确做法是在提交任务前（主线程中）就把 userId、userName 等所需信息取出来，
 * 作为方法参数显式传入。这样任务本身是自描述的，也避免了
 * 「任务延迟执行时登录态已过期」导致的诡异问题。
 */
public class ContextCopyingTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // 在提交任务的主线程中捕获 MDC 快照
        Map<String, String> contextMap = MDC.getCopyOfContextMap();

        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
                if (contextMap != null) {
                    MDC.setContextMap(contextMap);
                }
                runnable.run();
            } finally {
                // 恢复子线程原有上下文，避免污染线程池中的复用线程
                if (previous == null) {
                    MDC.clear();
                } else {
                    MDC.setContextMap(previous);
                }
            }
        };
    }
}
