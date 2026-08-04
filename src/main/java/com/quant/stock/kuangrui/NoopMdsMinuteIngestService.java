package com.quant.stock.kuangrui;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认 MDS 摄入桩：恒不可用；关开关时保持主路径不变。
 * <p>
 * 始终注册；启用宽睿时由 {@code KuangruiMdsMinuteIngestService}（{@code @Primary}）优先注入。
 * </p>
 */
@Service
public class NoopMdsMinuteIngestService implements MdsMinuteIngestService {

    @Override
    public boolean isLive() {
        return false;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", false);
        m.put("impl", "noop");
        m.put("hint", "默认关闭。启用需：mvn -Pkuangrui 打包/运行 + quant.kuangrui.enabled=true + quant.kuangrui.mds.enabled=true + local MDS 配置与账号");
        return m;
    }

    @Override
    public int pullAndPersist(List<String> codes) {
        return 0;
    }

    @Override
    public boolean startSubscribe(List<String> codes) {
        return false;
    }

    @Override
    public void stopSubscribe() {
        // no-op
    }

    @Override
    public int flushBuckets() {
        return 0;
    }
}
