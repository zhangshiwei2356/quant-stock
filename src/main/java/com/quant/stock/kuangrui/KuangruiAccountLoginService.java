package com.quant.stock.kuangrui;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 宽睿账号登录：验柜成功后密文入库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class KuangruiAccountLoginService {

    private final KuangruiCredentialStore credentialStore;
    private final ObjectProvider<OesReadonlyService> oesReadonlyProvider;

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.putAll(credentialStore.statusView());
        OesReadonlyService oes = oesReadonlyProvider.getIfAvailable();
        m.put("oesLive", oes != null && oes.isLive());
        m.put("probeAvailable", oes != null && oes.isLive());
        if (oes == null || !oes.isLive()) {
            m.put("probeHint", "验柜需 mvn -Pkuangrui 且 quant.kuangrui.enabled + oes.enabled");
        }
        return m;
    }

    /** 查询当前生效宽睿账号（无密码；DB active 或 env）。 */
    public Map<String, Object> current() {
        Map<String, Object> m = new LinkedHashMap<String, Object>(credentialStore.currentAccountView());
        OesReadonlyService oes = oesReadonlyProvider.getIfAvailable();
        m.put("oesLive", oes != null && oes.isLive());
        m.put("probeAvailable", oes != null && oes.isLive());
        return m;
    }

    public Map<String, Object> login(String username, String password) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            m.put("ok", false);
            m.put("message", "请填写用户名和密码");
            return m;
        }
        String user = username.trim();
        OesReadonlyService oes = oesReadonlyProvider.getIfAvailable();
        if (oes == null || !oes.isLive()) {
            m.put("ok", false);
            m.put("message", "OES 未启用或未编译进 classpath，无法验柜");
            m.put("hint", "请以 Maven profile kuangrui 启动，并打开 quant.kuangrui.enabled + oes.enabled");
            return m;
        }
        Map<String, Object> probe = oes.probeLogon(user, password);
        if (probe == null || !Boolean.TRUE.equals(probe.get("ok"))) {
            m.put("ok", false);
            m.put("message", probe != null && probe.get("message") != null
                    ? String.valueOf(probe.get("message"))
                    : "柜台登录失败");
            if (probe != null && probe.get("hint") != null) {
                m.put("hint", probe.get("hint"));
            }
            return m;
        }
        try {
            credentialStore.saveActiveAfterLogin(user, password);
        } catch (Exception e) {
            log.error("[kuangrui-account] 验柜成功但落库失败: {}", e.getMessage(), e);
            m.put("ok", false);
            m.put("message", "验柜成功但写入数据库失败: " + e.getMessage());
            return m;
        }
        m.put("ok", true);
        m.put("username", user);
        m.put("lastLoginAt", LocalDateTime.now().toString());
        m.put("credSource", "db");
        m.put("message", "登录成功，账号已加密保存");
        m.putAll(credentialStore.statusView());
        m.put("username", user);
        return m;
    }

    public Map<String, Object> logout() {
        credentialStore.clearActive();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("message", "已清除当前库内账号（历史行保留）；将回退环境变量（若有）");
        m.putAll(credentialStore.statusView());
        return m;
    }
}
