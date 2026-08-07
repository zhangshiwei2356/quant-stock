package com.quant.stock.kuangrui;

/**
 * 宽睿登录凭据：库内 active 优先，否则环境变量。
 */
public final class KuangruiCredentials {

    private final String username;
    private final String password;
    private final String source;

    public KuangruiCredentials(String username, String password, String source) {
        this.username = username;
        this.password = password;
        this.source = source == null ? "none" : source;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    /** db | env | none */
    public String getSource() {
        return source;
    }

    public boolean isPresent() {
        return username != null && !username.isEmpty()
                && password != null && !password.isEmpty();
    }
}
