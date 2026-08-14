package com.lanuis.ae2web.util;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 服务端主线程桥接工具。
 * <p>
 * HTTP 工作线程禁止直接触碰 AE2/世界状态；本类把任务投递到
 * {@link MinecraftServer} 主线程执行，并在调用方线程阻塞等待结果。
 * 超时失败时抛出带毫秒信息的 {@link TimeoutException}，便于 HTTP 层映射 504。
 * </p>
 * <p>
 * 线程边界：若调用方已在主线程，则同步直跑，避免死锁（主线程再 await 自己）。
 * </p>
 */
public final class MainThreadExecutor {
    /**
     * 工具类禁止实例化。
     */
    private MainThreadExecutor() {
    }

    /**
     * 在 Minecraft 服务端主线程执行任务并返回结果。
     * <p>
     * 已在主线程时直接 {@code call()}；否则 {@code server.execute} 投递后
     * 以 {@code timeoutMs} 等待。任意异常经 CompletableFuture 透传给调用方。
     * </p>
     *
     * @param server    当前 Minecraft 服务端实例，不可为 null
     * @param timeoutMs 跨线程等待上限（毫秒）；超时抛 TimeoutException
     * @param task      必须可在主线程安全访问 AE2/世界的逻辑
     * @param <T>       返回值类型
     * @return 任务返回值
     * @throws Exception 任务异常或等待超时
     */
    public static <T> T call(MinecraftServer server, long timeoutMs, Callable<T> task) throws Exception {
        // 主线程自调用必须同步执行，否则 future.get 会死锁
        if (server.isSameThread()) {
            return task.call();
        }
        // HTTP 线程：投递到主线程队列，再阻塞等待
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                // 正常完成：把结果回填给等待中的 HTTP 线程
                future.complete(task.call());
            } catch (Throwable t) {
                // 含 Error：必须 completeExceptionally，避免调用方永久挂起
                future.completeExceptionally(t);
            }
        });
        try {
            // 超时由配置/调用方决定（如合成规划可长达十余秒）
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 重新包装，附带超时毫秒，便于日志与 API 错误码
            throw new TimeoutException("Timed out waiting for server thread (" + timeoutMs + "ms)");
        }
    }
}
