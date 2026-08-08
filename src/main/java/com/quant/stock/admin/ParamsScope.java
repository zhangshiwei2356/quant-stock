package com.quant.stock.admin;


import lombok.extern.slf4j.Slf4j;
import com.quant.stock.config.QuantProperties;

import java.util.concurrent.Callable;

/**
 * 线程内生效参数快照：纸面扫描 / 回测入口安装，策略与风控通过 {@link #current} 读取。
 */
@Slf4j
public final class ParamsScope {

    private static final ThreadLocal<QuantProperties> TL = new ThreadLocal<QuantProperties>();

    private ParamsScope() {
    }

    public static QuantProperties current(QuantProperties global) {
        QuantProperties o = TL.get();
        return o != null ? o : global;
    }

    public static void run(QuantProperties snapshot, Runnable action) {
        QuantProperties prev = TL.get();
        TL.set(snapshot);
        try {
            action.run();
        } finally {
            if (prev != null) {
                TL.set(prev);
            } else {
                TL.remove();
            }
        }
    }

    public static <T> T call(QuantProperties snapshot, Callable<T> action) {
        QuantProperties prev = TL.get();
        TL.set(snapshot);
        try {
            return action.call();
        } catch (RuntimeException e) {
            log.error("线程内参数快照执行异常", e);
            throw e;
        } catch (Exception e) {
            log.error("线程内参数快照执行异常", e);
            throw new IllegalStateException(e);
        } finally {
            if (prev != null) {
                TL.set(prev);
            } else {
                TL.remove();
            }
        }
    }
}
