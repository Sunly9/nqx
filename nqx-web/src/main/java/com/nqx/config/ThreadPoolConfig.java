package com.nqx.config;

import com.nqx.web.support.ContextCopyingTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步线程池配置。
 *
 * <p><b>为什么必须自己定义线程池，不能用 {@code @Async} 的默认行为？</b>
 * Spring 在没有可用 {@code TaskExecutor} Bean 时会退化成每次新建线程（或使用
 * {@code SimpleAsyncTaskExecutor}），完全没有池化与限流，高并发下会疯狂创建线程直至 OOM。
 *
 * <p><b>为什么不用 {@code Executors.newFixedThreadPool()}？</b>
 * 它内部使用无界队列 {@code LinkedBlockingQueue}，
 * 任务会无限堆积，{@code maximumPoolSize} 形同虚设，
 * 最终以「队列撑爆内存」的形式挂掉，且没有任何拒绝信号。这是《阿里巴巴 Java 开发手册》
 * 明确禁止的写法。这里显式指定队列容量 + 拒绝策略，让过载行为可预期。
 */
@Configuration
public class ThreadPoolConfig {

    /** 可用处理器数，作为线程池 sizing 的基准 */
    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    /**
     * 业务异步任务线程池。
     *
     * <p>当前承载：操作日志落库、缓存异步重建、订阅消息推送。
     * 这些都是「不该阻塞主流程」的旁路任务。
     *
     * <p>拒绝策略选 {@link ThreadPoolExecutor.CallerRunsPolicy}：
     * 队列满时由提交任务的主线程自己执行。对日志落库这类任务而言，
     * 这比直接丢弃更合适（丢日志等于丢证据），同时 CallerRuns 天然形成背压，
     * 让上游请求变慢从而阻止任务继续涌入，比静默丢弃安全得多。
     */
    @Bean("nqxTaskExecutor")
    public ThreadPoolTaskExecutor nqxTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：2 倍核数，异步任务多为 IO 密集型（写日志、发 HTTP 请求）
        executor.setCorePoolSize(CPU_CORES * 2);
        executor.setMaxPoolSize(CPU_CORES * 4);
        // 有界队列：显式设定容量，这是与 newFixedThreadPool 的关键区别
        executor.setQueueCapacity(500);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("nqx-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅停机：等待已提交任务执行完，避免日志丢失
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // 传递 MDC traceId 到异步线程
        executor.setTaskDecorator(new ContextCopyingTaskDecorator());
        executor.initialize();
        return executor;
    }
}
