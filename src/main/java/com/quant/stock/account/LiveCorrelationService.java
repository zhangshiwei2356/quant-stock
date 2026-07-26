package com.quant.stock.account;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.BarPeriod;
import com.quant.stock.market.MarketDataService;
import com.quant.stock.market.dto.BarDTO;
import com.quant.stock.portfolio.PortfolioCorrelationMonitor;
import com.quant.stock.risk.AlertSeverity;
import com.quant.stock.risk.RiskAlertService;
import com.quant.stock.risk.StressScenarioService;
import com.quant.stock.task.StrategyTask;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前持仓成分日收益相关监控（P0-105，只告警）。
 */
@Service
public class LiveCorrelationService {

    private final StrategyTask strategyTask;
    private final MarketDataService marketDataService;
    private final QuantProperties props;
    private final RiskAlertService riskAlertService;
    private final StressScenarioService stressScenarioService;

    public LiveCorrelationService(StrategyTask strategyTask,
                                  MarketDataService marketDataService,
                                  QuantProperties props,
                                  RiskAlertService riskAlertService,
                                  StressScenarioService stressScenarioService) {
        this.strategyTask = strategyTask;
        this.marketDataService = marketDataService;
        this.props = props;
        this.riskAlertService = riskAlertService;
        this.stressScenarioService = stressScenarioService;
    }

    public Map<String, Object> report() {
        Map<String, List<BigDecimal>> closes = new LinkedHashMap<String, List<BigDecimal>>();
        for (Map<String, Object> row : strategyTask.listLivePositionViews()) {
            if (row == null || row.get("code") == null) {
                continue;
            }
            String code = String.valueOf(row.get("code"));
            Object vol = row.get("volume");
            if (vol instanceof Number && ((Number) vol).intValue() <= 0) {
                continue;
            }
            List<BarDTO> bars = marketDataService.getKline(code, BarPeriod.DAY, null, null);
            List<BigDecimal> cs = new ArrayList<BigDecimal>();
            if (bars != null) {
                for (BarDTO b : bars) {
                    if (b != null && b.getClose() != null) {
                        cs.add(b.getClose());
                    }
                }
            }
            closes.put(code, cs);
        }
        Map<String, Object> m = PortfolioCorrelationMonitor.report(
                closes, props.getCorrelationLookbackDays(), props.getCorrelationWarnThreshold());
        m.put("scope", "LIVE_POSITIONS");
        Object warn = m.get("warn");
        Object avg = m.get("avgCorrelation");
        boolean spike = Boolean.TRUE.equals(warn);
        if (spike && avg instanceof BigDecimal) {
            riskAlertService.emit(LocalDate.now(), null, "CORRELATION_WARN", AlertSeverity.WARN,
                    (BigDecimal) avg, "持仓平均两两相关≥阈值 " + props.getCorrelationWarnThreshold());
            stressScenarioService.markCorrelationSpike(true, LocalDate.now(), (BigDecimal) avg);
        } else {
            stressScenarioService.markCorrelationSpike(false, LocalDate.now(), null);
        }
        return m;
    }
}
