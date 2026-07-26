package com.quant.stock.risk;

import com.quant.stock.calendar.TradingCalendar;
import com.quant.stock.config.QuantProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 策略退役与冷却（P0-92）：预注册停机状态，禁新开；冷却满后方可 resume。
 * 强制恢复需双人复核（两步令牌），不改金叉信号逻辑。
 */
@Service
public class StrategyRetirementService {

    private static final int FORCE_TOKEN_TTL_MINUTES = 30;

    private final QuantProperties props;
    private final TradingCalendar tradingCalendar;

    private volatile boolean retired;
    private volatile LocalDate retiredOn;
    private volatile String reason;
    private volatile String note;
    private volatile String pendingForceToken;
    private volatile LocalDateTime pendingForceAt;

    public StrategyRetirementService(QuantProperties props, TradingCalendar tradingCalendar) {
        this.props = props;
        this.tradingCalendar = tradingCalendar;
    }

    public boolean isRetired() {
        return retired;
    }

    public boolean allowNewOpen() {
        return !retired;
    }

    /** 持续期熔断时自动退役（可配置关闭）。 */
    public void onAccountHalt(AccountRiskState state, LocalDate tradeDay) {
        if (retired || state == null || !state.isHalted()) {
            return;
        }
        if (!props.isAutoRetireOnDurationHalt()) {
            return;
        }
        if (!AccountRiskState.HALT_DURATION.equals(state.getHaltReason())) {
            return;
        }
        retire(tradeDay, "DURATION_HALT",
                "回撤持续期熔断自动退役 underwaterDays=" + state.getUnderwaterTradingDays());
    }

    public synchronized Map<String, Object> retire(LocalDate on, String reasonCode, String noteText) {
        retired = true;
        retiredOn = on == null ? LocalDate.now() : on;
        reason = reasonCode == null ? "MANUAL" : reasonCode;
        note = noteText;
        clearForceArm();
        return status(LocalDate.now());
    }

    /**
     * 冷却满后解除退役。
     * force=true 时需双人复核：第一次仅武装令牌；第二次带 confirmCode 才真正恢复。
     */
    public synchronized Map<String, Object> resume(LocalDate today, boolean force) {
        return resume(today, force, null);
    }

    public synchronized Map<String, Object> resume(LocalDate today, boolean force, String confirmCode) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (!retired) {
            m.put("ok", true);
            m.put("message", "当前未退役");
            m.putAll(status(today));
            return m;
        }
        if (force) {
            if (!StringUtils.hasText(confirmCode) || !confirmCode.trim().equals(pendingForceToken)
                    || !forceTokenFresh()) {
                String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
                pendingForceToken = token;
                pendingForceAt = LocalDateTime.now();
                m.put("ok", false);
                m.put("needDualConfirm", true);
                m.put("forceConfirmToken", token);
                m.put("message", "强制恢复需双人复核：请第二人提交 force=true&confirmCode=" + token
                        + "（" + FORCE_TOKEN_TTL_MINUTES + "分钟内有效）");
                m.putAll(status(today));
                return m;
            }
        } else if (!cooldownSatisfied(today)) {
            m.put("ok", false);
            m.put("message", "冷却未满，剩余交易日约 "
                    + remainingCooldownDays(today) + "（或双人复核强制恢复）");
            m.putAll(status(today));
            return m;
        }
        retired = false;
        retiredOn = null;
        reason = null;
        note = null;
        clearForceArm();
        m.put("ok", true);
        m.put("message", force ? "双人复核通过，已强制恢复" : "冷却已满，已恢复");
        m.putAll(status(today));
        return m;
    }

    public boolean cooldownSatisfied(LocalDate today) {
        int need = props.getRetirementCooldownTradingDays();
        if (need <= 0) {
            return false;
        }
        if (retiredOn == null) {
            return false;
        }
        return tradingCalendar.tradingDaysAfter(retiredOn, today == null ? LocalDate.now() : today) >= need;
    }

    public int remainingCooldownDays(LocalDate today) {
        int need = props.getRetirementCooldownTradingDays();
        if (!retired || need <= 0 || retiredOn == null) {
            return 0;
        }
        int passed = tradingCalendar.tradingDaysAfter(retiredOn, today == null ? LocalDate.now() : today);
        return Math.max(0, need - passed);
    }

    public Map<String, Object> status(LocalDate today) {
        LocalDate asOf = today == null ? LocalDate.now() : today;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("retired", retired);
        m.put("retiredOn", retiredOn == null ? null : retiredOn.toString());
        m.put("reason", reason);
        m.put("note", note);
        m.put("cooldownTradingDays", props.getRetirementCooldownTradingDays());
        m.put("remainingCooldownDays", remainingCooldownDays(asOf));
        m.put("cooldownSatisfied", !retired || cooldownSatisfied(asOf));
        m.put("autoRetireOnDurationHalt", props.isAutoRetireOnDurationHalt());
        m.put("dualConfirmRequired", true);
        m.put("forceArmed", forceTokenFresh());
        m.put("asOf", LocalDateTime.now().toString());
        m.put("hint", retired
                ? "策略已退役，禁新开；冷却满后 resume，或双人复核强制恢复"
                : "策略运行中");
        return m;
    }

    public void clearForTests() {
        retired = false;
        retiredOn = null;
        reason = null;
        note = null;
        clearForceArm();
    }

    private boolean forceTokenFresh() {
        if (!StringUtils.hasText(pendingForceToken) || pendingForceAt == null) {
            return false;
        }
        return pendingForceAt.plusMinutes(FORCE_TOKEN_TTL_MINUTES).isAfter(LocalDateTime.now());
    }

    private void clearForceArm() {
        pendingForceToken = null;
        pendingForceAt = null;
    }
}
