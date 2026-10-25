package com.nqx;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 纽启新生 — 应用启动类。
 *
 * <p>位于 {@code com.nqx} 包下，因此 Spring 默认扫描 {@code com.nqx.**} 全部子包，
 * 包括 {@code nqx-common}、{@code nqx-service} 等模块中的组件 ——
 * 这是多模块项目能共用一套组件扫描的常见做法。
 *
 * <p>三个注解的作用：
 * <ul>
 *   <li>{@code @MapperScan} —— 扫描 Mapper 接口并注册为代理 Bean。若不加，
 *       每个 Mapper 接口都要单独标 {@code @Mapper}，容易漏。</li>
 *   <li>{@code @EnableAsync} —— 启用 {@code @Async}，
 *       操作日志异步落库、缓存异步重建都依赖它。</li>
 *   <li>{@code @EnableScheduling} —— 启用定时任务，
 *       订单超时扫描与补偿任务依赖它。</li>
 * </ul>
 */
@EnableAsync
@EnableScheduling
@MapperScan("com.nqx.mapper")
@SpringBootApplication
public class NqxApplication {

    public static void main(String[] args) {
        /*
         * 启动看门狗与阶段标记。
         *
         * 为什么要手动 new SpringApplication 而不是直接 run(...)：
         * 需要在**创建任何 Bean 之前**就把监听器挂上去，
         * 否则像「配置文件加载完成」这种早期事件会收不到。
         *
         * 开关来自环境变量 BOOT_WATCHDOG_ENABLED，默认开启 ——
         * 容器平台上的启动耗时是不可预期的，这个日志比多打几行更重要。
         * 本地开发嫌吵可以设成 false。
         */
        boolean watchdog = !"false".equalsIgnoreCase(System.getenv("BOOT_WATCHDOG_ENABLED"))
                && !"false".equalsIgnoreCase(System.getProperty("nqx.boot.watchdog"));

        StartupWatchdog.start(watchdog);

        SpringApplication app = new SpringApplication(NqxApplication.class);
        if (watchdog) {
            app.addListeners(new StartupWatchdog.PhaseListener());
        }

        try {
            app.run(args);
        } catch (Throwable e) {
            // 启动失败时先把"失败在哪个阶段"打出来，再让异常继续往上抛
            StartupWatchdog.phase("❌ 启动失败，异常类型：" + e.getClass().getName());
            throw e;
        }

        StartupWatchdog.finish();
    }
}
