import com.quant360.api.callback.MdsCallBack;
import com.quant360.api.callback.OesCallBack;
import com.quant360.api.client.impl.MdsClientImpl;
import com.quant360.api.client.impl.OesClientImpl;
import com.quant360.api.model.ClientLogonReq;
import com.quant360.api.model.ClientLogonRsp;
import com.quant360.api.model.oes.enu.OesBusinessType;
import com.quant360.api.model.oes.enu.OesLogonEncryptType;

/**
 * M0 环境探针：仅尝试 OES / MDS 登录，不下单、不订阅业务行情。
 * <p>
 * 账号密码：QUANT_KUANGRUI_USER / QUANT_KUANGRUI_PASSWORD
 * 可选：QUANT_KUANGRUI_DRIVER_ID、QUANT_KUANGRUI_CLIENT_IP、QUANT_KUANGRUI_CLIENT_MAC
 * 可选：QUANT_KUANGRUI_ENCRYPT=0|1|2（默认不显式设置，走配置文件 encryptType）
 */
public class M0LoginProbe {

    public static void main(String[] args) {
        String user = env("QUANT_KUANGRUI_USER", null);
        String pass = env("QUANT_KUANGRUI_PASSWORD", null);
        String driver = env("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");
        String clientIp = env("QUANT_KUANGRUI_CLIENT_IP", null);
        String clientMac = env("QUANT_KUANGRUI_CLIENT_MAC", null);
        String encrypt = env("QUANT_KUANGRUI_ENCRYPT", null);
        String oesCfg = null;
        String mdsCfg = null;
        boolean doOes = true;
        boolean doMds = true;

        for (String a : args) {
            if (a.startsWith("--oes-config=")) {
                oesCfg = a.substring("--oes-config=".length());
            } else if (a.startsWith("--mds-config=")) {
                mdsCfg = a.substring("--mds-config=".length());
            } else if ("--oes-only".equals(a)) {
                doMds = false;
            } else if ("--mds-only".equals(a)) {
                doOes = false;
            }
        }

        if (user == null || user.isEmpty() || pass == null || pass.isEmpty()) {
            System.err.println("[M0] FAIL: 未设置 QUANT_KUANGRUI_USER / QUANT_KUANGRUI_PASSWORD");
            System.exit(2);
        }
        if (doOes && (oesCfg == null || oesCfg.isEmpty())) {
            System.err.println("[M0] FAIL: 缺少 --oes-config=");
            System.exit(2);
        }
        if (doMds && (mdsCfg == null || mdsCfg.isEmpty())) {
            System.err.println("[M0] FAIL: 缺少 --mds-config=");
            System.exit(2);
        }

        boolean ok = true;
        if (doOes) {
            ok = probeOes(user, pass, driver, clientIp, clientMac, encrypt, oesCfg) && ok;
        }
        if (doMds) {
            ok = probeMds(user, pass, driver, clientIp, clientMac, encrypt, mdsCfg) && ok;
        }
        System.out.println(ok ? "[M0] PASS: 登录探针全部成功" : "[M0] FAIL: 存在登录失败项");
        System.exit(ok ? 0 : 1);
    }

    private static boolean probeOes(String user, String pass, String driver,
                                    String clientIp, String clientMac, String encrypt, String cfg) {
        System.out.println("[M0] OES 登录尝试 config=" + cfg + " user=" + user
                + " ip=" + clientIp + " mac=" + clientMac + " encrypt=" + encrypt);
        OesClientImpl client = null;
        try {
            client = new OesClientImpl(1, cfg);
            client.initCallBack(new OesCallBack() {
            });
            ClientLogonReq req = baseReq(user, pass, driver, clientIp, clientMac, encrypt);
            req.setBusinessType(OesBusinessType.OES_BUSINESS_TYPE_STOCK);
            ClientLogonRsp rsp = client.start(req);
            if (rsp != null && rsp.isSuccess()) {
                System.out.println("[M0] OES OK lastInMsgSeq=" + rsp.getLastInMsgSeq()
                        + " applVerId=" + rsp.getApplVerId() + " minVerId=" + rsp.getMinVerId());
                return true;
            }
            System.err.println("[M0] OES FAIL errorCode=" + (rsp == null ? "null" : rsp.getErrorCode())
                    + (rsp == null || rsp.getApplVerId() == null ? "" : (" serverApplVerId=" + rsp.getApplVerId())));
            return false;
        } catch (Exception e) {
            System.err.println("[M0] OES FAIL exception=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static boolean probeMds(String user, String pass, String driver,
                                    String clientIp, String clientMac, String encrypt, String cfg) {
        System.out.println("[M0] MDS 登录尝试 config=" + cfg + " user=" + user
                + " ip=" + clientIp + " mac=" + clientMac + " encrypt=" + encrypt);
        MdsClientImpl client = null;
        try {
            client = new MdsClientImpl(cfg);
            client.initCallBack(new MdsCallBack() {
            });
            ClientLogonReq req = baseReq(user, pass, driver, clientIp, clientMac, encrypt);
            ClientLogonRsp rsp = client.start(req);
            if (rsp != null && rsp.isSuccess()) {
                System.out.println("[M0] MDS OK applVerId=" + rsp.getApplVerId() + " minVerId=" + rsp.getMinVerId());
                return true;
            }
            System.err.println("[M0] MDS FAIL errorCode=" + (rsp == null ? "null" : rsp.getErrorCode())
                    + (rsp == null || rsp.getApplVerId() == null ? "" : (" serverApplVerId=" + rsp.getApplVerId())));
            return false;
        } catch (Exception e) {
            System.err.println("[M0] MDS FAIL exception=" + e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        } finally {
            if (client != null) {
                try {
                    client.close();
                } catch (Exception ignore) {
                }
            }
        }
    }

    private static ClientLogonReq baseReq(String user, String pass, String driver,
                                          String clientIp, String clientMac, String encrypt) {
        ClientLogonReq req = new ClientLogonReq();
        req.setHeartBtInt(30);
        req.setUsername(user);
        req.setPassword(pass);
        req.setClientDriverId(driver);
        if (clientIp != null && !clientIp.isEmpty()) {
            req.setClientIp(clientIp);
        }
        if (clientMac != null && !clientMac.isEmpty()) {
            req.setClientMac(clientMac.replace('-', ':').toUpperCase());
        }
        if (encrypt != null && !encrypt.isEmpty()) {
            int v = Integer.parseInt(encrypt.trim());
            req.setLogonEncryptType(OesLogonEncryptType.valueOf(v));
        }
        return req;
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.trim().isEmpty() ? def : v.trim();
    }
}
