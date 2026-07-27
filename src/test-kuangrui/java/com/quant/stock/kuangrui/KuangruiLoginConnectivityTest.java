package com.quant.stock.kuangrui;

import com.quant360.api.callback.MdsCallBack;
import com.quant360.api.callback.OesCallBack;
import com.quant360.api.client.impl.MdsClientImpl;
import com.quant360.api.client.impl.OesClientImpl;
import com.quant360.api.model.ClientLogonReq;
import com.quant360.api.model.ClientLogonRsp;
import com.quant360.api.model.oes.enu.ErrorCode;
import com.quant360.api.model.oes.enu.OesBusinessType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * 宽睿阿里云模拟联通测试：步骤对齐资料包 Demo（{@code OesExample#startClient} / {@code MdsExample#startClient}）。
 * <p>
 * <b>不进 Spring 主路径</b>；仅在 Maven profile {@code kuangrui} 下编译运行。
 * 正确路径：{@code src/test-kuangrui/java/com/quant/stock/kuangrui/}（勿放在 {@code src/main/java}）。
 * <p>
 * 复现当前 BLOCKED（Pre Logon 1045）：
 * <ol>
 *   <li>安装 jar：{@code mvn install:install-file -Dfile=.../quant360-all-api-0.17.6.4.jar ...}</li>
 *   <li>准备 {@code config/kuangrui/local/oes_api_config.json} 与 {@code mds_api_config.json}</li>
 *   <li>设置环境变量 {@code QUANT_KUANGRUI_USER} / {@code QUANT_KUANGRUI_PASSWORD}</li>
 *   <li>{@code mvn -Pkuangrui test -Dtest=KuangruiLoginConnectivityTest}</li>
 * </ol>
 * 期望现象（账号/协议未对齐时）：TCP 成功后日志出现
 * {@code Pre Logon faild ! ... errorCode = 1045}，本测试以失败断言复现该状态；
 * 登录真正成功时测试通过（M0 COMPLETE）。
 */
class KuangruiLoginConnectivityTest {

    @Test
    @DisplayName("OES 登录联通（对齐 OesExample：建客户端→回调→start）")
    void oesLoginConnectivity_likeDemo() {
        assumeCredAndConfig("oes");
        Path cfg = resolveConfig("oes_api_config.json");
        String user = env("QUANT_KUANGRUI_USER");
        String pass = env("QUANT_KUANGRUI_PASSWORD");
        String driver = envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");

        OesClientImpl client = null;
        try {
            // Demo STEP1/2/3
            client = new OesClientImpl(1, cfg.toAbsolutePath().toString());
            client.initCallBack(new OesCallBack() {
            });
            ClientLogonReq req = buildLogonReq(user, pass, driver);
            req.setBusinessType(OesBusinessType.OES_BUSINESS_TYPE_STOCK);
            ClientLogonRsp rsp = client.start(req);
            assertLoginOk("OES", rsp);
        } catch (Exception e) {
            fail(formatFail("OES", e.getClass().getSimpleName() + ": " + e.getMessage(), null));
        } finally {
            closeQuietly(client);
        }
    }

    @Test
    @DisplayName("MDS 登录联通（对齐 MdsExample：建客户端→回调→start）")
    void mdsLoginConnectivity_likeDemo() {
        assumeCredAndConfig("mds");
        Path cfg = resolveConfig("mds_api_config.json");
        String user = env("QUANT_KUANGRUI_USER");
        String pass = env("QUANT_KUANGRUI_PASSWORD");
        String driver = envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56");

        MdsClientImpl client = null;
        try {
            client = new MdsClientImpl(cfg.toAbsolutePath().toString());
            client.initCallBack(new MdsCallBack() {
            });
            ClientLogonReq req = buildLogonReq(user, pass, driver);
            ClientLogonRsp rsp = client.start(req);
            assertLoginOk("MDS", rsp);
        } catch (Exception e) {
            fail(formatFail("MDS", e.getClass().getSimpleName() + ": " + e.getMessage(), null));
        } finally {
            closeQuietly(client);
        }
    }

    /**
     * 可选：显式断言「仍复现 Pre Logon 1045」。
     * <pre>
     * mvn -Pkuangrui test -Dtest=KuangruiLoginConnectivityTest#oesStillBlockedWithPreLogon1045 -Dkuangrui.expect1045=true
     * </pre>
     */
    @Test
    @DisplayName("OES：可选断言当前仍为 Pre Logon 1045（需 -Dkuangrui.expect1045=true）")
    void oesStillBlockedWithPreLogon1045() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getProperty("kuangrui.expect1045")),
                "未设置 -Dkuangrui.expect1045=true，跳过「期望 1045」断言");
        assumeCredAndConfig("oes");
        Path cfg = resolveConfig("oes_api_config.json");
        OesClientImpl client = null;
        try {
            client = new OesClientImpl(1, cfg.toAbsolutePath().toString());
            client.initCallBack(new OesCallBack() {
            });
            ClientLogonReq req = buildLogonReq(
                    env("QUANT_KUANGRUI_USER"),
                    env("QUANT_KUANGRUI_PASSWORD"),
                    envOr("QUANT_KUANGRUI_DRIVER_ID", "DAEB7F56"));
            req.setBusinessType(OesBusinessType.OES_BUSINESS_TYPE_STOCK);
            ClientLogonRsp rsp = client.start(req);
            if (rsp != null && rsp.isSuccess()) {
                fail("期望仍为 Pre Logon 1045，但 OES 已登录成功（M0 可能已 COMPLETE），请去掉 -Dkuangrui.expect1045");
            }
            ErrorCode code = rsp == null ? null : rsp.getErrorCode();
            // Java 枚举无 1045，API 映射为 OTHER_ERROR；以日志 Pre Logon 1045 为准
            if (code != ErrorCode.OTHER_ERROR && code != null) {
                fail("期望 OTHER_ERROR(对应日志 1045)，实际 errorCode=" + code);
            }
            System.out.println("[KuangruiIT] 已复现 OES 预登录失败（日志应含 Pre Logon ... 1045），errorCode=" + code);
        } catch (Exception e) {
            fail("OES 连接异常（非预登录业务码）: " + e.getMessage());
        } finally {
            closeQuietly(client);
        }
    }

    private static ClientLogonReq buildLogonReq(String user, String pass, String driver) {
        // 对齐 all/demo/OesExample#buildClientLogonReq
        ClientLogonReq req = new ClientLogonReq();
        req.setHeartBtInt(30);
        req.setUsername(user);
        req.setPassword(pass);
        req.setClientDriverId(driver);
        String ip = env("QUANT_KUANGRUI_CLIENT_IP");
        String mac = env("QUANT_KUANGRUI_CLIENT_MAC");
        if (ip != null && !ip.isEmpty()) {
            req.setClientIp(ip);
        }
        if (mac != null && !mac.isEmpty()) {
            req.setClientMac(mac.replace('-', ':').toUpperCase());
        }
        return req;
    }

    private static void assertLoginOk(String channel, ClientLogonRsp rsp) {
        if (rsp != null && rsp.isSuccess()) {
            System.out.println("[KuangruiIT] " + channel + " 登录成功 applVerId=" + rsp.getApplVerId()
                    + " lastInMsgSeq=" + rsp.getLastInMsgSeq());
            return;
        }
        ErrorCode code = rsp == null ? null : rsp.getErrorCode();
        fail(formatFail(channel, "errorCode=" + code, rsp));
    }

    private static String formatFail(String channel, String detail, ClientLogonRsp rsp) {
        StringBuilder sb = new StringBuilder();
        sb.append(channel).append(" 登录未成功，可用于复现当前 M0 BLOCKED。\n");
        sb.append("detail: ").append(detail).append('\n');
        sb.append("说明: 若控制台/日志出现「Pre Logon faild ... errorCode = 1045」，\n");
        sb.append("  表示 TCP 已通、预登录被拒（手册 V0.17.6 无 1045，Java 侧常映射 OTHER_ERROR）。\n");
        sb.append("  预登录阶段尚未验密；换 encryptType/clEnvId 通常无效。\n");
        sb.append("复现命令:\n");
        sb.append("  $env:QUANT_KUANGRUI_USER='...'; $env:QUANT_KUANGRUI_PASSWORD='...'\n");
        sb.append("  mvn -Pkuangrui test -Dtest=KuangruiLoginConnectivityTest\n");
        sb.append("  或: .\\scripts\\kuangrui\\m0-env-check.ps1 -RunLoginProbe\n");
        if (rsp != null) {
            sb.append("rsp.success=").append(rsp.isSuccess())
                    .append(" applVerId=").append(rsp.getApplVerId())
                    .append(" minVerId=").append(rsp.getMinVerId()).append('\n');
        }
        return sb.toString();
    }

    private static void assumeCredAndConfig(String kind) {
        Assumptions.assumeTrue(env("QUANT_KUANGRUI_USER") != null && env("QUANT_KUANGRUI_PASSWORD") != null,
                "未设置 QUANT_KUANGRUI_USER / QUANT_KUANGRUI_PASSWORD，跳过宽睿联通测试");
        Path cfg = resolveConfig(kind.equals("oes") ? "oes_api_config.json" : "mds_api_config.json");
        Assumptions.assumeTrue(Files.isRegularFile(cfg),
                "缺少配置文件: " + cfg.toAbsolutePath() + "（从 config/kuangrui/examples 复制到 local/）");
    }

    private static Path resolveConfig(String fileName) {
        String override = env("QUANT_KUANGRUI_CONFIG_DIR");
        Path dir = override != null && !override.isEmpty()
                ? Paths.get(override)
                : Paths.get("config", "kuangrui", "local");
        return dir.resolve(fileName);
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

    private static void closeQuietly(Object client) {
        if (client == null) {
            return;
        }
        try {
            if (client instanceof OesClientImpl) {
                ((OesClientImpl) client).close();
            } else if (client instanceof MdsClientImpl) {
                ((MdsClientImpl) client).close();
            }
        } catch (Exception ignore) {
            // ignore
        }
    }
}
