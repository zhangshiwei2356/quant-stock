package com.quant.stock.task;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手动/长任务统一进度槽：与 {@link DynamicScheduleService} 的 manualRun 绑定，
 * 供因子重算、入池、校验、TDX 以外循环任务上报 {@code i/n}。
 */
@Component
public class JobProgressHub {

    public interface Listener {
        void onPhase(String phase, String phaseLabel, String summary);

        void onTick(int index, int total, String current, String detail);
    }

    private final AtomicReference<Listener> listenerRef = new AtomicReference<Listener>();

    public void attach(Listener listener) {
        listenerRef.set(listener);
    }

    public void detach() {
        listenerRef.set(null);
    }

    public void phase(String phase, String phaseLabel, String summary) {
        Listener l = listenerRef.get();
        if (l != null) {
            l.onPhase(phase, phaseLabel, summary);
        }
    }

    /**
     * @param index 已完成数（1-based 或累计完成均可；前端按 index/total 算百分比）
     * @param total 总数；&lt;=0 时仅更新文案
     */
    public void tick(int index, int total, String current, String detail) {
        Listener l = listenerRef.get();
        if (l != null) {
            l.onTick(index, total, current, detail);
        }
    }

    /** 便捷：阶段文案 + 无定量进度 */
    public void note(String summary) {
        phase("running", "执行中", summary);
    }

    public static Map<String, Object> emptyProgress() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("progressIndex", null);
        m.put("progressTotal", null);
        m.put("progressPct", null);
        m.put("currentSymbol", null);
        m.put("detail", null);
        return m;
    }
}
