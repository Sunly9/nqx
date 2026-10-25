package com.nqx;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * 启动阶段诊断。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>部署到容器平台（微信云托管）后，应用启动失败时日志往往**停在某一行就不动了**，
 * 而且**一条 ERROR 都没有** —— 因为多半不是报错退出，而是被平台杀掉：
 *
 * <pre>
 *   容器启动 → JVM 起来 → MySQL 连上 → 继续初始化（Redis / Flyway / Tomcat）
 *   → 健康检查探针等不及，判定"没响应" → 直接杀掉容器 → 重启
 * </pre>
 *
 * <p>这种情况下看不到异常，也永远看不到 {@code Started NqxApplication}。
 * 光看日志根本无法判断"它是在慢慢启动"还是"它卡死了"。
 *
 * <h2>两个手段</h2>
 *
 * <ol>
 *   <li><b>阶段标记</b>：监听 Spring Boot 的关键事件，打上时间戳。
 *       {@code ContextRefreshedEvent} 表示所有 Bean 都创建完了，
 *       {@code ApplicationReadyEvent} 表示 Tomcat 已经在监听端口。
 *       卡在哪个阶段之间，问题就在哪一段。</li>
 *   <li><b>心跳线程</b>：每 3 秒打一行"已运行 X 秒，仍在初始化"。
 *       心跳还在 → 说明进程活着，只是慢（那就调大超时）；
 *       心跳突然停 → 说明被杀或卡在某个阻塞调用上。</li>
 * </ol>
 *
 * <h2>为什么用守护线程</h2>
 *
 * <p>守护线程不会阻止 JVM 退出 —— 启动成功后主线程进入 Spring 的事件循环，
 * 这个线程继续跑也无妨；启动失败时进程退出，线程自动结束，不需要额外清理。
 *
 * <p>另外加了开关 {@code nqx.boot.watchdog-enabled}（默认 true）。
 * 本地开发时日志里多几十行没意义，可以关掉。
 */
@Slf4j
public final class StartupWatchdog {

    /** 进程启动时刻（类加载时记录，尽量靠近 JVM 启动） */
    private static final long START_AT = System.currentTimeMillis();

    private static volatile boolean finished = false;

    private StartupWatchdog() {
    }

    /** 距离启动过去了多少毫秒 */
    public static long elapsedMs() {
        return System.currentTimeMillis() - START_AT;
    }

    /**
     * 启动心跳线程。
     *
     * @param enabled false 时不启动（本地开发用）
     */
    public static void start(boolean enabled) {
        if (!enabled) {
            return;
        }
        Thread t = new Thread(() -> {
            int seconds = 0;
            while (!finished) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (finished) {
                    return;
                }
                seconds += 3;
                log.info("[BOOT +{}s] 仍在初始化…（进程存活，说明不是崩溃，是慢）", seconds);
            }
        }, "nqx-startup-watchdog");
        t.setDaemon(true);
        t.start();
        log.info("[BOOT +0ms] JVM 已启动，开始 Spring 初始化");
    }

    /** 启动完成，停止心跳 */
    public static void finish() {
        finished = true;
        log.info("[BOOT +{}ms] ✅ 启动完成", elapsedMs());
    }

    /** 打印一个阶段标记 */
    public static void phase(String name) {
        log.info("[BOOT +{}ms] {}", elapsedMs(), name);
    }

    /**
     * Spring Boot 事件监听器。
     *
     * <p>通过 {@code springApplication.addListeners(...)} 在 main 里注册 ——
     * 不能做成 {@code @Component}，因为它在 Bean 创建**之前**就要开始工作。
     */
    public static class PhaseListener implements ApplicationListener<ApplicationEvent> {

        @Override
        public void onApplicationEvent(ApplicationEvent event) {
            if (event instanceof ApplicationEnvironmentPreparedEvent) {
                // 配置文件与 profile 已加载，下一步是创建 Bean
                phase("配置文件已加载（profile 确定）");
            } else if (event instanceof ApplicationPreparedEvent) {
                // 容器刷新前的最后一刻
                phase("Spring 容器准备完毕，开始创建 Bean");
            } else if (event instanceof ContextRefreshedEvent) {
                // 所有 Bean 都创建完成 —— 卡在这之前，问题在某个 Bean（常见的：Redis、Flyway）
                phase("所有 Bean 创建完毕（ContextRefreshed）");
            } else if (event instanceof ApplicationReadyEvent) {
                // 嵌入式容器已启动，端口已监听
                phase("Tomcat 已监听端口（ApplicationReady）");
            }
        }
    }
}
