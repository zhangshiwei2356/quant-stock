package com.quant.stock.session;

import com.quant.stock.market.dto.BarDTO;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** 分钟推进时的会话上下文。 */
@Data
@Builder
public class SessionContext {
    private String stockCode;
    private LocalDate sessionDay;
    private SessionBranch branch;
    private BarDTO bar;
    private int barIndex;
    private HoldDayState holdState;
    private BigDecimal equity;
    @Builder.Default
    private Set<SessionBranch> degradedBranches = new LinkedHashSet<SessionBranch>();

    public boolean isBranchDegraded() {
        return branch != null && degradedBranches != null && degradedBranches.contains(branch);
    }

    public Set<SessionBranch> degradedView() {
        return degradedBranches == null
                ? Collections.<SessionBranch>emptySet()
                : Collections.unmodifiableSet(degradedBranches);
    }
}
