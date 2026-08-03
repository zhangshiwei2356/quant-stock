package com.quant.stock.kuangrui;

import com.quant.stock.market.CoreMarketBarService;
import com.quant.stock.market.MarketDataSources;
import com.quant.stock.market.dto.BarDTO;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分钟桶与落库（不依赖 quant360）；真实 MDS 客户端只负责投喂快照。
 */
@Slf4j
public class MdsMinuteAggregator {

    private final ConcurrentHashMap<String, MdsMinuteBucket> buckets = new ConcurrentHashMap<String, MdsMinuteBucket>();
    private final ConcurrentLinkedQueue<BarDTO> pendingClosed = new ConcurrentLinkedQueue<BarDTO>();
    private final AtomicLong tickCount = new AtomicLong();
    private final AtomicLong upsertCount = new AtomicLong();

    private final CoreMarketBarService coreMarketBarService;

    public MdsMinuteAggregator(CoreMarketBarService coreMarketBarService) {
        this.coreMarketBarService = coreMarketBarService;
    }

    public long getTickCount() {
        return tickCount.get();
    }

    public long getUpsertCount() {
        return upsertCount.get();
    }

    public int bucketSize() {
        return buckets.size();
    }

    public int pendingSize() {
        return pendingClosed.size();
    }

    /**
     * @param code           股票代码
     * @param tradeDate      YYYYMMDD
     * @param updateTime     HHMMSS(sss)
     * @param tradePxMilli   最新价（毫）
     * @param cumVolume      日累计成交量
     * @param cumAmountMilli 日累计成交额（毫）
     */
    public void onSnapshot(String code, int tradeDate, int updateTime,
                           long tradePxMilli, long cumVolume, long cumAmountMilli) {
        if (code == null || code.trim().isEmpty()) {
            return;
        }
        String symbol = code.trim();
        LocalDateTime begin = MdsBarTimeUtil.barBegin(tradeDate, updateTime);
        BigDecimal px = KuangruiPriceScale.toYuan(tradePxMilli);
        if (begin == null || px == null) {
            return;
        }
        tickCount.incrementAndGet();
        MdsMinuteBucket bucket = buckets.get(symbol);
        if (bucket == null) {
            MdsMinuteBucket created = new MdsMinuteBucket(symbol);
            MdsMinuteBucket prev = buckets.putIfAbsent(symbol, created);
            bucket = prev == null ? created : prev;
        }
        BarDTO closed;
        synchronized (bucket) {
            closed = bucket.onTick(begin, px, cumVolume, cumAmountMilli);
        }
        if (closed != null) {
            pendingClosed.offer(closed);
        }
    }

    /** 将闭合队列 +（可选）当前未闭合桶刷入库。 */
    public int flush(boolean includeOpen) {
        if (coreMarketBarService == null) {
            return 0;
        }
        List<BarDTO> batch = new ArrayList<BarDTO>();
        BarDTO one;
        while ((one = pendingClosed.poll()) != null) {
            batch.add(one);
        }
        if (includeOpen) {
            for (MdsMinuteBucket bucket : buckets.values()) {
                BarDTO snap;
                synchronized (bucket) {
                    snap = bucket.snapshot();
                }
                if (snap != null) {
                    batch.add(snap);
                }
            }
        }
        if (batch.isEmpty()) {
            return 0;
        }
        try {
            int n = coreMarketBarService.saveMinutes1(batch, MarketDataSources.MDS);
            upsertCount.addAndGet(n);
            return n;
        } catch (Exception e) {
            log.warn("[mds] 落库失败 size={}: {}", batch.size(), e.getMessage());
            // 回队避免丢（仅闭合队列）；开盘桶仍在 map 中
            for (BarDTO b : batch) {
                if (b != null) {
                    pendingClosed.offer(b);
                }
            }
            return 0;
        }
    }

    public Map<String, Object> stats() {
        Map<String, Object> m = new ConcurrentHashMap<String, Object>();
        m.put("buckets", buckets.size());
        m.put("pendingClosed", pendingClosed.size());
        m.put("ticks", tickCount.get());
        m.put("upserts", upsertCount.get());
        return m;
    }
}
