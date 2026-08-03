package com.quant.stock.kuangrui;

import com.quant.stock.config.QuantProperties;
import com.quant.stock.market.CoreMarketBarService;
import com.quant360.api.callback.MdsCallBack;
import com.quant360.api.client.MdsClient;
import com.quant360.api.client.impl.MdsClientImpl;
import com.quant360.api.model.ClientLogonReq;
import com.quant360.api.model.ClientLogonRsp;
import com.quant360.api.model.mds.MdsMktDataRequestEntry;
import com.quant360.api.model.mds.MdsMktDataRequestReq;
import com.quant360.api.model.mds.MdsMktDataRequestRsp;
import com.quant360.api.model.mds.MdsMktDataSnapshotBase;
import com.quant360.api.model.mds.MdsMktDataSnapshotHead;
import com.quant360.api.model.mds.MdsQryMktDataSnapshotReq;
import com.quant360.api.model.mds.MdsStockSnapshotBody;
import com.quant360.api.model.mds.enu.MdsExchangeId;
import com.quant360.api.model.mds.enu.MdsMktSubscribeFlag;
import com.quant360.api.model.mds.enu.MdsSecurityType;
import com.quant360.api.model.mds.enu.MdsSubscribeDataType;
import com.quant360.api.model.mds.enu.MdsSubscribeMode;
import com.quant360.api.model.mds.enu.MdsSubscribedTickType;
import com.quant360.api.model.oes.enu.OesLogonEncryptType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 宽睿 MDS L1 → market_1min(MDS)。仅 {@code -Pkuangrui} 编译进主包。
 */
@Slf4j
@Service
@ConditionalOnClass(MdsClientImpl.class)
@ConditionalOnBean(CoreMarketBarService.class)
@ConditionalOnProperty(name = {
        "quant.kuangrui.enabled",
        "quant.kuangrui.mds.enabled"
}, havingValue = "true")
public class KuangruiMdsMinuteIngestService implements MdsMinuteIngestService {

    private final QuantProperties quantProperties;
    private final MdsMinuteAggregator aggregator;

    private final Object clientLock = new Object();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicReference<String> lastError = new AtomicReference<String>();
    private volatile MdsClientImpl client;

    public KuangruiMdsMinuteIngestService(QuantProperties quantProperties,
                                          CoreMarketBarService coreMarketBarService) {
        this.quantProperties = quantProperties;
        this.aggregator = new MdsMinuteAggregator(coreMarketBarService);
    }

    @Override
    public boolean isLive() {
        return true;
    }

    @Override
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("live", true);
        m.put("impl", "kuangrui-mds");
        m.put("loggedIn", client != null);
        m.put("subscribed", subscribed.get());
        m.put("lastError", lastError.get());
        m.put("configPath", resolveMdsConfig().toString());
        m.put("configExists", Files.isRegularFile(resolveMdsConfig()));
        m.put("hasCred", env("QUANT_KUANGRUI_USER") != null && env("QUANT_KUANGRUI_PASSWORD") != null);
        m.putAll(aggregator.stats());
        return m;
    }

    @Override
    public int pullAndPersist(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return 0;
        }
        try {
            ensureClient();
        } catch (Exception e) {
            lastError.set(e.getMessage());
            log.warn("[mds] 登录失败: {}", e.getMessage());
            return 0;
        }
        int ok = 0;
        for (String code : codes) {
            try {
                if (queryOne(code)) {
                    ok++;
                }
            } catch (Exception e) {
                log.debug("[mds] 查询失败 code={}: {}", code, e.getMessage());
            }
        }
        return aggregator.flush(true);
    }

    @Override
    public boolean startSubscribe(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            lastError.set("订阅列表为空");
            return false;
        }
        synchronized (clientLock) {
            try {
                ensureClient();
                doSubscribe(codes);
                subscribed.set(true);
                lastError.set(null);
                log.info("[mds] 已订阅 L1 codes={}", codes.size());
                return true;
            } catch (Exception e) {
                lastError.set(e.getMessage());
                log.warn("[mds] 订阅失败: {}", e.getMessage());
                return false;
            }
        }
    }

    @Override
    public void stopSubscribe() {
        synchronized (clientLock) {
            subscribed.set(false);
            closeClient();
        }
    }

    @Override
    public int flushBuckets() {
        return aggregator.flush(true);
    }

    @PreDestroy
    public void destroy() {
        stopSubscribe();
    }

    private boolean queryOne(String code) throws Exception {
        int exch = KuangruiExchangeIds.fromStockCode(code);
        int instr = KuangruiExchangeIds.toInstrId(code);
        if (exch == 0 || instr == 0) {
            return false;
        }
        MdsQryMktDataSnapshotReq req = new MdsQryMktDataSnapshotReq();
        req.setExchId(toExch(exch));
        req.setSecurityType(MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
        req.setInstrId(instr);
        MdsMktDataSnapshotBase snap = client.qryMktDataSnapshot(req);
        if (snap == null || snap.getHead() == null || snap.getStock() == null) {
            return false;
        }
        feed(snap.getHead(), snap.getStock());
        return true;
    }

    private void doSubscribe(List<String> codes) throws Exception {
        MdsMktDataRequestReq req = new MdsMktDataRequestReq();
        req.setSubMode(MdsSubscribeMode.SUB_MODE_SET);
        req.setTickType(MdsSubscribedTickType.MDS_TICK_TYPE_LATEST_SIMPLIFIED);
        req.setDataTypes(MdsSubscribeDataType.SUB_DATA_TYPE_L1_SNAPSHOT.value());
        req.setSseStockFlag(MdsMktSubscribeFlag.SUB_FLAG_DEFAULT);
        req.setSzseStockFlag(MdsMktSubscribeFlag.SUB_FLAG_DEFAULT);
        req.setBseStockFlag(MdsMktSubscribeFlag.SUB_FLAG_DEFAULT);
        req.setSseIndexFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setSzseIndexFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setSseOptionFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setSzseOptionFlag(MdsMktSubscribeFlag.SUB_FLAG_DISABLE);
        req.setRequireInitialMktData(true);
        req.setBeginTime(0);

        List<MdsMktDataRequestEntry> entries = new ArrayList<MdsMktDataRequestEntry>();
        for (String code : codes) {
            int exch = KuangruiExchangeIds.fromStockCode(code);
            int instr = KuangruiExchangeIds.toInstrId(code);
            if (exch == 0 || instr == 0) {
                continue;
            }
            MdsMktDataRequestEntry e = new MdsMktDataRequestEntry();
            e.setExchId(toExch(exch));
            e.setSecurityType(MdsSecurityType.MDS_SECURITY_TYPE_STOCK);
            e.setInstrId(instr);
            entries.add(e);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("无有效订阅标的");
        }
        client.subscribeMarketData(req, entries);
    }

    private void ensureClient() throws Exception {
        synchronized (clientLock) {
            if (client != null) {
                return;
            }
            Path cfg = resolveMdsConfig();
            if (!Files.isRegularFile(cfg)) {
                throw new IllegalStateException("缺少 MDS 配置: " + cfg.toAbsolutePath());
            }
            String user = env("QUANT_KUANGRUI_USER");
            String pass = env("QUANT_KUANGRUI_PASSWORD");
            if (user == null || pass == null) {
                throw new IllegalStateException("请设置环境变量 QUANT_KUANGRUI_USER / QUANT_KUANGRUI_PASSWORD");
            }
            String driver = envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");
            MdsClientImpl c = new MdsClientImpl(cfg.toAbsolutePath().toString());
            c.initCallBack(new MdsCallBack() {
                @Override
                public void onDisConn(MdsClient cl) {
                    log.warn("[mds] 连接中断");
                    subscribed.set(false);
                    lastError.set("disconnected");
                }

                @Override
                public void onMktReq(MdsMktDataRequestRsp rsp) {
                    log.info("[mds] 订阅应答: {}", rsp);
                }

                @Override
                public void onMktStock(MdsMktDataSnapshotHead head, MdsStockSnapshotBody data) {
                    try {
                        feed(head, data);
                    } catch (Exception ex) {
                        log.debug("[mds] 回调处理失败: {}", ex.getMessage());
                    }
                }
            });
            ClientLogonReq logon = new ClientLogonReq();
            logon.setHeartBtInt(30);
            logon.setUsername(user);
            logon.setPassword(pass);
            logon.setClientDriverId(driver);
            Integer enc = resolveEncryptType();
            if (enc != null) {
                logon.setLogonEncryptType(OesLogonEncryptType.valueOf(enc.intValue()));
            }
            ClientLogonRsp rsp = c.start(logon);
            if (rsp == null || !rsp.isSuccess()) {
                try {
                    c.close();
                } catch (Exception ignore) {
                    // ignore
                }
                throw new IllegalStateException("MDS 登录失败 rsp=" + rsp);
            }
            client = c;
            lastError.set(null);
            log.info("[mds] 登录成功 applVerId={}", rsp.getApplVerId());
        }
    }

    private void feed(MdsMktDataSnapshotHead head, MdsStockSnapshotBody data) {
        if (head == null || data == null) {
            return;
        }
        String code = data.getSecurityID();
        if (code == null || code.trim().isEmpty()) {
            code = String.valueOf(head.getInstrId());
            while (code.length() < 6) {
                code = "0" + code;
            }
        }
        aggregator.onSnapshot(
                code.trim(),
                head.getTradeDate(),
                head.getUpdateTime(),
                (long) data.getTradePx(),
                data.getTotalVolumeTraded(),
                data.getTotalValueTraded()
        );
    }

    private void closeClient() {
        MdsClientImpl c = client;
        client = null;
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.debug("[mds] close: {}", e.getMessage());
            }
        }
    }

    private Path resolveMdsConfig() {
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        String dir = k == null || k.getConfigDir() == null || k.getConfigDir().trim().isEmpty()
                ? "config/kuangrui/local"
                : k.getConfigDir().trim();
        String file = k == null || k.getMds() == null || k.getMds().getConfigFile() == null
                ? "mds_api_config.json"
                : k.getMds().getConfigFile().trim();
        return Paths.get(dir, file);
    }

    private Integer resolveEncryptType() {
        QuantProperties.Kuangrui k = quantProperties.getKuangrui();
        if (k == null || k.getMds() == null) {
            return null;
        }
        return k.getMds().getEncryptType();
    }

    private static MdsExchangeId toExch(int id) {
        if (id == KuangruiExchangeIds.SZSE) {
            return MdsExchangeId.MDS_EXCH_SZSE;
        }
        if (id == KuangruiExchangeIds.BSE) {
            return MdsExchangeId.MDS_EXCH_BSE;
        }
        return MdsExchangeId.MDS_EXCH_SSE;
    }

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        return v.trim();
    }

    private static String envOr(String key, String def) {
        String v = env(key);
        return v == null ? def : v;
    }
}
