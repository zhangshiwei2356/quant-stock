package com.quant.stock.kuangrui;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 适配宽睿 OES 各版本 {@code sendRptSync} / {@code initRptSync} 签名差异。
 * <p>
 * 资料包 Demo：登录后必须回报同步；常见形态包括：
 * <ul>
 *   <li>{@code sendRptSync(long)}</li>
 *   <li>{@code initRptSync(long)} + {@code sendRptSync()}</li>
 *   <li>{@code sendRptSync(byte/int envId, boolean/int subscribeAll, long lastRptSeq)}</li>
 *   <li>{@code sendRptSync(OesReportSynchronizationReq)} 等请求对象</li>
 * </ul>
 */
public final class OesRptSyncInvoker {

    private OesRptSyncInvoker() {
    }

    /** 调用结果：成功或带明细的失败原因（含可用方法签名，便于本机排障）。 */
    public static final class Result {
        public final boolean ok;
        public final String detail;
        public final String methodUsed;

        private Result(boolean ok, String detail, String methodUsed) {
            this.ok = ok;
            this.detail = detail;
            this.methodUsed = methodUsed;
        }

        public static Result success(String methodUsed) {
            return new Result(true, null, methodUsed);
        }

        public static Result fail(String detail) {
            return new Result(false, detail, null);
        }
    }

    public static Result invoke(Object client, long lastInMsgSeq) {
        if (client == null) {
            return Result.fail("OES 客户端为空");
        }
        List<String> errors = new ArrayList<String>();
        Set<String> signatures = new LinkedHashSet<String>();
        List<Method> sendWithArgs = new ArrayList<Method>();
        List<Method> initWithArgs = new ArrayList<Method>();
        List<Method> sendNoArg = new ArrayList<Method>();

        for (Method m : client.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = m.getName();
            if (!isSyncMethodName(name)) {
                continue;
            }
            signatures.add(formatSig(m));
            int n = m.getParameterTypes().length;
            if (isInitOnlyName(name)) {
                if (n > 0) {
                    initWithArgs.add(m);
                }
            } else if (n == 0) {
                sendNoArg.add(m);
            } else {
                sendWithArgs.add(m);
            }
        }

        // 1) 优先：带序号/请求对象的 send*（Demo：用 lastInMsgSeq 同步）
        Result r = tryMethods(client, sendWithArgs, lastInMsgSeq, errors);
        if (r.ok) {
            return r;
        }

        // 2) init*(seq) + 无参 send*
        r = tryMethods(client, initWithArgs, lastInMsgSeq, errors);
        if (r.ok) {
            Result send = tryInvokeMethods(client, sendNoArg, new Object[0], errors);
            if (send.ok) {
                return Result.success(r.methodUsed + " + " + send.methodUsed);
            }
            // 部分版本 init 即触发同步
            return Result.success(r.methodUsed + " (init-only)");
        }

        // 3) 兜底无参 send*
        r = tryInvokeMethods(client, sendNoArg, new Object[0], errors);
        if (r.ok) {
            return r;
        }

        // 4) 按名再试一遍（防 getMethods 遗漏桥接）
        Result init = tryNamed(client, "initRptSync", lastInMsgSeq, errors, signatures);
        if (!init.ok) {
            init = tryNamed(client, "initMsgId", lastInMsgSeq, errors, signatures);
        }
        if (init.ok) {
            Result send = tryNoArgSend(client, errors, signatures);
            if (send.ok) {
                return Result.success(init.methodUsed + " + " + send.methodUsed);
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("回报同步失败");
        if (!signatures.isEmpty()) {
            sb.append("；可用方法: ").append(signatures);
        } else {
            sb.append("；客户端上未找到 sendRptSync/initRptSync 等方法（请确认 -Pkuangrui 与 quant360-all-api 0.19.4.0）");
        }
        if (!errors.isEmpty()) {
            int n = Math.min(errors.size(), 6);
            sb.append("；尝试: ");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(errors.get(i));
            }
        }
        return Result.fail(sb.toString());
    }

    private static Result tryMethods(Object client, List<Method> methods, long seq, List<String> errors) {
        for (Method m : methods) {
            Object[] args = buildArgs(m.getParameterTypes(), seq);
            if (args == null) {
                errors.add(formatSig(m) + ": 无法构造参数");
                continue;
            }
            Result r = tryInvokeMethods(client, java.util.Collections.singletonList(m), args, errors);
            if (r.ok) {
                return r;
            }
        }
        return Result.fail("no match");
    }

    private static Result tryInvokeMethods(Object client, List<Method> methods, Object[] args,
                                           List<String> errors) {
        for (Method m : methods) {
            try {
                Object ret = m.invoke(client, args);
                if (isNegativeCode(ret)) {
                    errors.add(formatSig(m) + ": 返回 " + ret);
                    continue;
                }
                return Result.success(formatSig(m));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(formatSig(m) + ": " + cause.getClass().getSimpleName()
                        + " " + safeMsg(cause));
            }
        }
        return Result.fail("invoke failed");
    }

    static boolean isSyncMethodName(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("rptsync")
                || n.contains("reportsync")
                || "sendreportsynchronization".equals(n)
                || "initmsgid".equals(n);
    }

    private static boolean isInitOnlyName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("init") || n.contains("initrpt");
    }

    private static Result tryNoArgSend(Object client, List<String> errors, Set<String> signatures) {
        for (String name : new String[]{"sendRptSync", "sendReportSynchronization", "sendRptSynchronization"}) {
            try {
                Method m = client.getClass().getMethod(name);
                signatures.add(formatSig(m));
                Object ret = m.invoke(client);
                if (isNegativeCode(ret)) {
                    errors.add(formatSig(m) + ": 返回 " + ret);
                    continue;
                }
                return Result.success(formatSig(m));
            } catch (NoSuchMethodException ignore) {
                // next
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(name + "(): " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
            }
        }
        return Result.fail("无参 sendRptSync 不可用");
    }

    private static Result tryNamed(Object client, String name, long seq,
                                   List<String> errors, Set<String> signatures) {
        for (Method m : client.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            signatures.add(formatSig(m));
            Object[] args = buildArgs(m.getParameterTypes(), seq);
            if (args == null) {
                continue;
            }
            try {
                Object ret = m.invoke(client, args);
                if (isNegativeCode(ret)) {
                    errors.add(formatSig(m) + ": 返回 " + ret);
                    continue;
                }
                return Result.success(formatSig(m));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(formatSig(m) + ": " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
            }
        }
        return Result.fail(name + " 不可用");
    }

    /**
     * 按参数类型构造调用实参；无法适配时返回 null。
     */
    static Object[] buildArgs(Class<?>[] types, long seq) {
        if (types == null) {
            return null;
        }
        if (types.length == 0) {
            return new Object[0];
        }
        if (types.length == 1) {
            Object one = coerceSingle(types[0], seq);
            return one == null ? null : new Object[]{one};
        }
        if (types.length == 2) {
            // (lastSeq, subscribeAll) 或 (subscribeEnvId, lastSeq)
            Object a0 = coerceLoose(types[0], seq, true);
            Object a1 = coerceLoose(types[1], seq, false);
            if (a0 != null && a1 != null) {
                return new Object[]{a0, a1};
            }
            return null;
        }
        if (types.length == 3) {
            // C API 形态：subscribeEnvId, isSubscribeAll, lastRptSeqNum
            Object envId = coercePrimitiveOrWrapper(types[0], 0);
            Object all = coerceBooleanOrInt(types[1], true);
            Object last = coercePrimitiveOrWrapper(types[2], seq);
            if (envId != null && all != null && last != null) {
                return new Object[]{envId, all, last};
            }
            return null;
        }
        return null;
    }

    private static Object coerceSingle(Class<?> type, long seq) {
        Object num = coercePrimitiveOrWrapper(type, seq);
        if (num != null) {
            return num;
        }
        // 请求对象：new + setLast*
        try {
            Object req = type.getDeclaredConstructor().newInstance();
            if (fillSeqOnReq(req, seq)) {
                return req;
            }
        } catch (Exception ignore) {
            // not a bean req
        }
        return null;
    }

    private static Object coerceLoose(Class<?> type, long seq, boolean preferSeq) {
        if (type == Boolean.TYPE || type == Boolean.class) {
            return Boolean.TRUE;
        }
        if (preferSeq) {
            Object n = coercePrimitiveOrWrapper(type, seq);
            if (n != null) {
                return n;
            }
        }
        Object asBoolInt = coerceBooleanOrInt(type, true);
        if (asBoolInt != null && (type == Boolean.TYPE || type == Boolean.class
                || type == Integer.TYPE || type == Integer.class
                || type == Byte.TYPE || type == Byte.class)) {
            return asBoolInt;
        }
        return coercePrimitiveOrWrapper(type, preferSeq ? seq : 0L);
    }

    private static Object coerceBooleanOrInt(Class<?> type, boolean value) {
        if (type == Boolean.TYPE || type == Boolean.class) {
            return Boolean.valueOf(value);
        }
        if (type == Integer.TYPE || type == Integer.class) {
            return Integer.valueOf(value ? 1 : 0);
        }
        if (type == Byte.TYPE || type == Byte.class) {
            return Byte.valueOf((byte) (value ? 1 : 0));
        }
        if (type == Short.TYPE || type == Short.class) {
            return Short.valueOf((short) (value ? 1 : 0));
        }
        return null;
    }

    private static Object coercePrimitiveOrWrapper(Class<?> type, long value) {
        if (type == Long.TYPE || type == Long.class) {
            return Long.valueOf(value);
        }
        if (type == Integer.TYPE || type == Integer.class) {
            return Integer.valueOf((int) value);
        }
        if (type == Byte.TYPE || type == Byte.class) {
            return Byte.valueOf((byte) value);
        }
        if (type == Short.TYPE || type == Short.class) {
            return Short.valueOf((short) value);
        }
        if (type == Number.class) {
            return Long.valueOf(value);
        }
        return null;
    }

    static boolean fillSeqOnReq(Object req, long seq) {
        if (req == null) {
            return false;
        }
        String[] setters = {
                "setLastRptSeqNum", "setLastInMsgSeq", "setLastRptSeq",
                "setRptSeqNum", "setLastMsgSeq", "setMsgSeq"
        };
        for (String s : setters) {
            if (trySetLongish(req, s, seq)) {
                // 常见可选字段
                trySetLongish(req, "setSubscribeEnvId", 0L);
                trySetLongish(req, "setIsSubscribeAll", 1L);
                trySetBoolean(req, "setSubscribeAll", true);
                trySetBoolean(req, "setIsSubscribeAll", true);
                return true;
            }
        }
        return false;
    }

    private static boolean trySetLongish(Object target, String setter, long value) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(setter) || m.getParameterTypes().length != 1) {
                continue;
            }
            Object arg = coercePrimitiveOrWrapper(m.getParameterTypes()[0], value);
            if (arg == null) {
                continue;
            }
            try {
                m.invoke(target, arg);
                return true;
            } catch (Exception ignore) {
                // try next
            }
        }
        return false;
    }

    private static void trySetBoolean(Object target, String setter, boolean value) {
        try {
            Method m = target.getClass().getMethod(setter, boolean.class);
            m.invoke(target, Boolean.valueOf(value));
        } catch (Exception ignore) {
            try {
                Method m = target.getClass().getMethod(setter, Boolean.class);
                m.invoke(target, Boolean.valueOf(value));
            } catch (Exception ignore2) {
                // ignore
            }
        }
    }

    private static boolean isNegativeCode(Object ret) {
        return ret instanceof Number && ((Number) ret).intValue() < 0;
    }

    static String formatSig(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        Class<?>[] pts = m.getParameterTypes();
        for (int i = 0; i < pts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(pts[i].getSimpleName());
        }
        sb.append(')');
        return sb.toString();
    }

    private static String safeMsg(Throwable t) {
        if (t == null || t.getMessage() == null) {
            return "";
        }
        String s = t.getMessage().trim();
        if (s.length() > 180) {
            return s.substring(0, 180) + "...";
        }
        return s;
    }
}
