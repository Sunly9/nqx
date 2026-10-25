package com.nqx.service.system;

import com.nqx.mapper.SysOperationLogMapper;
import com.nqx.pojo.entity.SysOperationLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 操作日志异步落库器。
 *
 * <p><b>为什么必须独立成一个 Bean？</b>
 * Spring 的 {@code @Async} 依赖 AOP 代理生效，而代理只对「从外部调用」的方法生效。
 * 如果把 {@code record} 方法写在 {@code LogAspect} 内部并直接 {@code this.record(...)}
 * 调用，那是自调用，会绕过代理 —— 方法体将同步执行，
 * 「异步」完全失效且不会有任何报错。这是 {@code @Async} 最经典的失效场景。
 *
 * <p>拆成独立 Bean 后，切面通过注入调用，走的是代理对象，异步才真正生效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OperationLogRecorder {

    private final SysOperationLogMapper sysOperationLogMapper;

    /**
     * 异步写入操作日志。
     *
     * <p><b>三点关键设计</b>：
     * <ol>
     *   <li>实体对象在<b>调用方线程</b>就已构造完毕，这里只负责入库。
     *       好处是 traceId、IP、请求参数等依赖请求上下文的数据在主线程取好，
     *       不依赖异步线程能否拿到 {@code RequestContextHolder}
     *       —— 那个上下文默认不会跨线程传递，是另一个常见坑。</li>
     *   <li>内部 try-catch 包住所有异常。<b>记日志失败绝不能影响业务</b>，
     *       而且异常若逃逸到线程池，只会打印一条没有业务上下文的堆栈，
     *       既定位不到问题，又白白浪费一次告警。</li>
     *   <li>使用 {@code nqxTaskExecutor} 线程池（见 {@code ThreadPoolConfig}），
     *       队列满时走 CallerRunsPolicy 形成背压，而不是静默丢日志。</li>
     * </ol>
     */
    @Async("nqxTaskExecutor")
    public void record(SysOperationLog operationLog) {
        try {
            sysOperationLogMapper.insert(operationLog);
        } catch (Exception e) {
            log.error("操作日志落库失败 | traceId={} | module={} | operation={} | uri={}",
                    operationLog.getTraceId(), operationLog.getModule(),
                    operationLog.getOperation(), operationLog.getRequestUrl(), e);
        }
    }
}
