package com.quant.stock.kuangrui;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 宽睿账号密文存储与取凭据（DB active 优先，env 回退）。
 * <p>
 * 主密钥在表 {@code kuangrui_crypto_key}；与密文同库时仅防误读账号表，整库泄露仍可还原。
 * </p>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class KuangruiCredentialStore {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LEN = 12;
    private static final int KEY_BITS = 256;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public KuangruiCredentialStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        ensureTables();
    }

    public void ensureTables() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS `kuangrui_crypto_key` ("
                        + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                        + "`key_material` VARCHAR(128) NOT NULL,"
                        + "`algo` VARCHAR(64) NOT NULL DEFAULT 'AES/GCM/NoPadding',"
                        + "`created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (`id`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS `kuangrui_account` ("
                        + "`id` BIGINT NOT NULL AUTO_INCREMENT,"
                        + "`username` VARCHAR(64) NOT NULL,"
                        + "`password_cipher` VARCHAR(512) NOT NULL,"
                        + "`iv` VARCHAR(64) NOT NULL,"
                        + "`active` TINYINT NOT NULL DEFAULT 0,"
                        + "`last_login_at` DATETIME NULL,"
                        + "`last_login_ok` TINYINT NOT NULL DEFAULT 0,"
                        + "`updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                        + "PRIMARY KEY (`id`),"
                        + "UNIQUE KEY `uk_kuangrui_account_username` (`username`),"
                        + "KEY `idx_kuangrui_account_active` (`active`)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    /** 解析当前可用凭据：库 active → env。 */
    public KuangruiCredentials resolve() {
        ensureTables();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT username, password_cipher, iv FROM kuangrui_account WHERE active=1 ORDER BY id DESC LIMIT 1");
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String user = str(row.get("username"));
                String cipher = str(row.get("password_cipher"));
                String iv = str(row.get("iv"));
                String pass = decrypt(cipher, iv);
                if (StringUtils.hasText(user) && StringUtils.hasText(pass)) {
                    return new KuangruiCredentials(user, pass, "db");
                }
                log.error("[kuangrui-cred] active 账号解密失败，回退环境变量");
            }
        } catch (Exception e) {
            log.error("[kuangrui-cred] 读库失败，回退环境变量: {}", e.getMessage(), e);
        }
        String user = trimToNull(System.getenv("QUANT_KUANGRUI_USER"));
        String pass = trimToNull(System.getenv("QUANT_KUANGRUI_PASSWORD"));
        if (user != null && pass != null) {
            return new KuangruiCredentials(user, pass, "env");
        }
        return new KuangruiCredentials(null, null, "none");
    }

    /** 验柜成功后：加密落库并设为唯一 active。 */
    public void saveActiveAfterLogin(String username, String password) {
        ensureTables();
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            throw new IllegalArgumentException("username/password 不能为空");
        }
        String user = username.trim();
        CipherBlob blob = encrypt(password);
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("UPDATE kuangrui_account SET active=0 WHERE active=1");
        Integer cnt = jdbc.query(
                "SELECT COUNT(1) FROM kuangrui_account WHERE username=?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                user);
        if (cnt != null && cnt.intValue() > 0) {
            jdbc.update(
                    "UPDATE kuangrui_account SET password_cipher=?, iv=?, active=1, last_login_at=?, last_login_ok=1, updated_at=? WHERE username=?",
                    blob.cipherB64, blob.ivB64, now, now, user);
        } else {
            jdbc.update(
                    "INSERT INTO kuangrui_account(username, password_cipher, iv, active, last_login_at, last_login_ok, updated_at) "
                            + "VALUES(?,?,?,1,?,?,?)",
                    user, blob.cipherB64, blob.ivB64, now, 1, now);
        }
        log.info("[kuangrui-cred] 已保存 active 账号 user={}", user);
    }

    /** 清除 active（不删历史行）。 */
    public void clearActive() {
        ensureTables();
        jdbc.update("UPDATE kuangrui_account SET active=0 WHERE active=1");
    }

    /** 对外状态（无密钥/密文）。 */
    public Map<String, Object> statusView() {
        ensureTables();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        KuangruiCredentials c = resolve();
        m.put("credSource", c.getSource());
        m.put("hasCred", Boolean.valueOf(c.isPresent()));
        // 当前生效用户名（DB active 或 env）；查询「当前登录账号」用此字段
        m.put("currentUsername", c.isPresent() ? c.getUsername() : null);
        boolean hasDb = false;
        String activeUser = null;
        Object lastLoginAt = null;
        Object lastLoginOk = null;
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT username, last_login_at, last_login_ok FROM kuangrui_account "
                            + "WHERE active=1 ORDER BY id DESC LIMIT 1");
            if (!rows.isEmpty()) {
                hasDb = true;
                activeUser = str(rows.get(0).get("username"));
                lastLoginAt = rows.get(0).get("last_login_at");
                lastLoginOk = rows.get(0).get("last_login_ok");
            }
        } catch (Exception ignore) {
            log.error("宽睿凭证存取异常", ignore);
            // ignore
        }
        m.put("hasDbAccount", Boolean.valueOf(hasDb));
        m.put("activeUsername", activeUser);
        if (lastLoginAt != null) {
            m.put("lastLoginAt", String.valueOf(lastLoginAt));
        }
        if (lastLoginOk != null) {
            m.put("lastLoginOk", lastLoginOk instanceof Number
                    ? Boolean.valueOf(((Number) lastLoginOk).intValue() != 0)
                    : lastLoginOk);
        }
        m.put("hasEnvFallback", Boolean.valueOf(
                trimToNull(System.getenv("QUANT_KUANGRUI_USER")) != null
                        && trimToNull(System.getenv("QUANT_KUANGRUI_PASSWORD")) != null));
        m.put("hint", "密码密文存 kuangrui_account；主密钥在 kuangrui_crypto_key（同库，仅防误读）。"
                + "取密：DB active 优先，否则环境变量。currentUsername=当前生效账号。");
        return m;
    }

    /**
     * 查询当前生效宽睿账号（无密码）。
     * {@code currentUsername} 为实际取密用户；{@code activeUsername} 仅为库内 active。
     */
    public Map<String, Object> currentAccountView() {
        Map<String, Object> m = new LinkedHashMap<String, Object>(statusView());
        m.put("ok", Boolean.TRUE.equals(m.get("hasCred")));
        if (Boolean.TRUE.equals(m.get("hasCred"))) {
            m.put("message", "当前生效账号: " + m.get("currentUsername")
                    + "（来源 " + m.get("credSource") + "）");
        } else {
            m.put("message", "当前无可用宽睿账号（库内无 active，且未设置环境变量）");
        }
        return m;
    }

    private SecretKey ensureKey() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT key_material FROM kuangrui_crypto_key ORDER BY id DESC LIMIT 1");
        if (!rows.isEmpty()) {
            byte[] raw = Base64.getDecoder().decode(str(rows.get(0).get("key_material")));
            return new SecretKeySpec(raw, "AES");
        }
        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(KEY_BITS, random);
            SecretKey key = kg.generateKey();
            String b64 = Base64.getEncoder().encodeToString(key.getEncoded());
            jdbc.update(
                    "INSERT INTO kuangrui_crypto_key(key_material, algo, created_at) VALUES(?,?,?)",
                    b64, ALGO, LocalDateTime.now());
            log.info("[kuangrui-cred] 已生成主密钥并写入 kuangrui_crypto_key");
            return key;
        } catch (Exception e) {
            log.error("宽睿凭证存取异常", e);
            throw new IllegalStateException("生成宽睿主密钥失败: " + e.getMessage(), e);
        }
    }

    private CipherBlob encrypt(String plain) {
        try {
            SecretKey key = ensureKey();
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] enc = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return new CipherBlob(
                    Base64.getEncoder().encodeToString(enc),
                    Base64.getEncoder().encodeToString(iv));
        } catch (Exception e) {
            log.error("宽睿凭证存取异常", e);
            throw new IllegalStateException("加密失败: " + e.getMessage(), e);
        }
    }

    private String decrypt(String cipherB64, String ivB64) {
        if (!StringUtils.hasText(cipherB64) || !StringUtils.hasText(ivB64)) {
            return null;
        }
        try {
            SecretKey key = ensureKey();
            byte[] iv = Base64.getDecoder().decode(ivB64);
            byte[] enc = Base64.getDecoder().decode(cipherB64);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("[kuangrui-cred] 解密失败: {}", e.getMessage(), e);
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static final class CipherBlob {
        final String cipherB64;
        final String ivB64;

        CipherBlob(String cipherB64, String ivB64) {
            this.cipherB64 = cipherB64;
            this.ivB64 = ivB64;
        }
    }
}
