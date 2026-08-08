package com.quant.stock.kuangrui;


import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 适配宽睿 OES 各版本 {@code sendRptSync} / {@code initRptSync} 签名差异。
 * <p>
 * 资料包 Demo：登录后应回报同步；常见形态包括：
 * <ul>
 *   <li>{@code sendRptSync(long)}</li>
 *   <li>{@code initRptSync(long)} + {@code sendRptSync()}</li>
 *   <li>{@code sendRptSync(byte/int envId, boolean/int subscribeAll, long lastRptSeq)}</li>
 *   <li>{@code sendRptSync(OesReportSynchronizationReq)} 等请求对象</li>
 * </ul>
 * 配置里 {@code subcribeEnvId≤0} 表示订阅全部环境号回报。
 */
@Slf4j
public final class OesRptSyncInvoker {

    private static final int[] ENV_CANDIDATES = {0, 1, 99};

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
        return invoke(client, lastInMsgSeq, 0);
    }

    /**
     * @param preferSubscribeEnvId 优先使用的订阅环境号（≤0 表示订阅全部，与配置 subcribeEnvId 一致）
     */
    public static Result invoke(Object client, long lastInMsgSeq, int preferSubscribeEnvId) {
        if (client == null) {
            return Result.fail("OES 客户端为空");
        }
        List<String> errors = new ArrayList<String>();
        Set<String> signatures = new LinkedHashSet<String>();
        List<Method> sendWithArgs = new ArrayList<Method>();
        List<Method> initWithArgs = new ArrayList<Method>();
        List<Method> sendNoArg = new ArrayList<Method>();

        for (Method m : collectMethods(client.getClass())) {
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = m.getName();
            if (!isSyncMethodName(name)) {
                continue;
            }
            try {
                m.setAccessible(true);
            } catch (Exception ignore) {
                log.error("OES 回报同步反射调用异常", ignore);
                // ignore
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

        int[] envTry = envTryOrder(preferSubscribeEnvId);

        // 1) 优先：带序号/请求对象的 send*
        Result r = tryMethods(client, sendWithArgs, lastInMsgSeq, envTry, errors);
        if (r.ok) {
            return r;
        }

        // 2) init*(seq) + 无参 send*
        r = tryMethods(client, initWithArgs, lastInMsgSeq, envTry, errors);
        if (r.ok) {
            Result send = tryInvokeMethods(client, sendNoArg, new Object[0], errors);
            if (send.ok) {
                return Result.success(r.methodUsed + " + " + send.methodUsed);
            }
            return Result.success(r.methodUsed + " (init-only)");
        }

        // 3) 无参 send*
        r = tryInvokeMethods(client, sendNoArg, new Object[0], errors);
        if (r.ok) {
            return r;
        }

        // 4) 按名再试
        Result init = tryNamed(client, "initRptSync", lastInMsgSeq, envTry, errors, signatures);
        if (!init.ok) {
            init = tryNamed(client, "initMsgId", lastInMsgSeq, envTry, errors, signatures);
        }
        if (init.ok) {
            Result send = tryNoArgSend(client, errors, signatures);
            if (send.ok) {
                return Result.success(init.methodUsed + " + " + send.methodUsed);
            }
        }

        // 附带扫描：所有含 Rpt/Report/Sync 的方法名，便于对照资料包
        Set<String> related = new LinkedHashSet<String>();
        for (Method m : collectMethods(client.getClass())) {
            String methodNameLower = m.getName().toLowerCase(Locale.ROOT);
            if (methodNameLower.contains("rpt") || methodNameLower.contains("report")
                    || methodNameLower.contains("sync")) {
                related.add(formatSig(m));
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("回报同步失败");
        if (!signatures.isEmpty()) {
            sb.append("；可用方法: ").append(signatures);
        } else {
            sb.append("；未找到 sendRptSync/initRptSync（请确认 IDEA/Maven 勾选 -Pkuangrui 且已安装 quant360-all-api 0.19.4.0 后重新编译启动）");
        }
        if (!related.isEmpty() && related.size() <= 12) {
            sb.append("；相关方法: ").append(related);
        }
        if (!errors.isEmpty()) {
            int maxErrorsToShow = Math.min(errors.size(), 8);
            sb.append("；尝试: ");
            for (int i = 0; i < maxErrorsToShow; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(errors.get(i));
            }
        }
        return Result.fail(sb.toString());
    }

    private static List<Method> collectMethods(Class<?> type) {
        LinkedHashSet<Method> out = new LinkedHashSet<Method>();
        for (Method m : type.getMethods()) {
            out.add(m);
        }
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                out.add(m);
            }
            c = c.getSuperclass();
        }
        return new ArrayList<Method>(out);
    }

    private static int[] envTryOrder(int prefer) {
        LinkedHashSet<Integer> set = new LinkedHashSet<Integer>();
        set.add(Integer.valueOf(prefer));
        for (int e : ENV_CANDIDATES) {
            set.add(Integer.valueOf(e));
        }
        int[] arr = new int[set.size()];
        int i = 0;
        for (Integer v : set) {
            arr[i++] = v.intValue();
        }
        return arr;
    }

    private static Result tryMethods(Object client, List<Method> methods, long seq, int[] envTry,
                                     List<String> errors) {
        for (Method m : methods) {
            for (int envId : envTry) {
                Object[] args = buildArgs(m.getParameterTypes(), seq, envId);
                if (args == null) {
                    errors.add(formatSig(m) + "@env=" + envId + ": 无法构造参数");
                    continue;
                }
                Result r = tryInvokeMethods(client, java.util.Collections.singletonList(m), args, errors);
                if (r.ok) {
                    return Result.success(r.methodUsed + "@env=" + envId);
                }
            }
        }
        return Result.fail("no match");
    }

    private static Result tryInvokeMethods(Object client, List<Method> methods, Object[] args,
                                           List<String> errors) {
        for (Method m : methods) {
            try {
                m.setAccessible(true);
                Object ret = m.invoke(client, args);
                if (isNegativeCode(ret)) {
                    errors.add(formatSig(m) + ": 返回 " + ret);
                    continue;
                }
                return Result.success(formatSig(m));
            } catch (Exception e) {
                log.error("OES 回报同步反射调用异常", e);
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
        String methodNameLower = name.toLowerCase(Locale.ROOT);
        return methodNameLower.contains("rptsync")
                || methodNameLower.contains("reportsync")
                || methodNameLower.contains("reportsynchronization")
                || "initmsgid".equals(methodNameLower);
    }

    private static boolean isInitOnlyName(String name) {
        String methodNameLower = name.toLowerCase(Locale.ROOT);
        return methodNameLower.startsWith("init") || methodNameLower.contains("initrpt");
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
                log.error("OES 回报同步反射调用异常", ignore);
                // next
            } catch (Exception e) {
                log.error("OES 回报同步反射调用异常", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(name + "(): " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
            }
        }
        return Result.fail("无参 sendRptSync 不可用");
    }

    private static Result tryNamed(Object client, String name, long seq, int[] envTry,
                                   List<String> errors, Set<String> signatures) {
        for (Method m : collectMethods(client.getClass())) {
            if (!m.getName().equals(name)) {
                continue;
            }
            signatures.add(formatSig(m));
            for (int envId : envTry) {
                Object[] args = buildArgs(m.getParameterTypes(), seq, envId);
                if (args == null) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                    Object ret = m.invoke(client, args);
                    if (isNegativeCode(ret)) {
                        errors.add(formatSig(m) + ": 返回 " + ret);
                        continue;
                    }
                    return Result.success(formatSig(m) + "@env=" + envId);
                } catch (Exception e) {
                    log.error("OES 回报同步反射调用异常", e);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    errors.add(formatSig(m) + ": " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
                }
            }
        }
        return Result.fail(name + " 不可用");
    }

    /** 兼容旧测试：默认 envId=0。 */
    static Object[] buildArgs(Class<?>[] types, long seq) {
        return buildArgs(types, seq, 0);
    }

    /**
     * 按参数类型构造调用实参；无法适配时返回 null。
     */
    static Object[] buildArgs(Class<?>[] types, long seq, int subscribeEnvId) {
        if (types == null) {
            return null;
        }
        if (types.length == 0) {
            return new Object[0];
        }
        if (types.length == 1) {
            Object one = coerceSingle(types[0], seq, subscribeEnvId);
            return one == null ? null : new Object[]{one};
        }
        if (types.length == 2) {
            // (subscribeEnvId, lastSeq) 或 (lastSeq, subscribeAll)
            Class<?> t0 = types[0];
            Class<?> t1 = types[1];
            if (isIntegral(t0) && isIntegral(t1)) {
                // 两整型：env + seq
                Object a0 = coercePrimitiveOrWrapper(t0, subscribeEnvId);
                Object a1 = coercePrimitiveOrWrapper(t1, seq);
                if (a0 != null && a1 != null) {
                    return new Object[]{a0, a1};
                }
            }
            if (isIntegral(t0) && (t1 == Boolean.TYPE || t1 == Boolean.class)) {
                Object a0 = coercePrimitiveOrWrapper(t0, seq);
                Object a1 = Boolean.TRUE;
                if (a0 != null) {
                    return new Object[]{a0, a1};
                }
            }
            Object a0 = coerceLoose(t0, seq, true);
            Object a1 = coerceLoose(t1, seq, false);
            if (a0 != null && a1 != null) {
                return new Object[]{a0, a1};
            }
            return null;
        }
        if (types.length == 3) {
            // C API：subscribeEnvId, isSubscribeAll, lastRptSeqNum
            Object envId = coercePrimitiveOrWrapper(types[0], subscribeEnvId);
            Object all = coerceBooleanOrInt(types[1], true);
            Object last = coercePrimitiveOrWrapper(types[2], seq);
            if (envId != null && all != null && last != null) {
                return new Object[]{envId, all, last};
            }
            return null;
        }
        return null;
    }

    private static boolean isIntegral(Class<?> type) {
        return type == Long.TYPE || type == Long.class
                || type == Integer.TYPE || type == Integer.class
                || type == Byte.TYPE || type == Byte.class
                || type == Short.TYPE || type == Short.class;
    }

    private static Object coerceSingle(Class<?> type, long seq, int subscribeEnvId) {
        Object num = coercePrimitiveOrWrapper(type, seq);
        if (num != null) {
            return num;
        }
        try {
            Object req = type.getDeclaredConstructor().newInstance();
            if (fillSeqOnReq(req, seq, subscribeEnvId)) {
                return req;
            }
        } catch (Exception ignore) {
            log.error("OES 回报同步反射调用异常", ignore);
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
        return fillSeqOnReq(req, seq, 0);
    }

    static boolean fillSeqOnReq(Object req, long seq, int subscribeEnvId) {
        if (req == null) {
            return false;
        }
        String[] setters = {
                "setLastRptSeqNum", "setLastInMsgSeq", "setLastRptSeq",
                "setRptSeqNum", "setLastMsgSeq", "setMsgSeq"
        };
        boolean filled = false;
        for (String s : setters) {
            if (trySetLongish(req, s, seq)) {
                filled = true;
                break;
            }
        }
        if (!filled) {
            return false;
        }
        trySetLongish(req, "setSubscribeEnvId", subscribeEnvId);
        trySetLongish(req, "setSubcribeEnvId", subscribeEnvId); // 官方配置拼写
        trySetLongish(req, "setIsSubscribeAll", subscribeEnvId <= 0 ? 1L : 0L);
        trySetBoolean(req, "setSubscribeAll", subscribeEnvId <= 0);
        trySetBoolean(req, "setIsSubscribeAll", subscribeEnvId <= 0);
        return true;
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
                log.error("OES 回报同步反射调用异常", ignore);
                // try next
            }
        }
        return false;
    }

    /**
     * 按方法名扫描 setter；本版 {@code OesReportSynchronizationReq} 无 subscribeAll 字段时静默跳过，
     * 避免 {@code NoSuchMethodException} 误报 ERROR。
     */
    private static void trySetBoolean(Object target, String setter, boolean value) {
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(setter) || m.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> pt = m.getParameterTypes()[0];
            if (pt != boolean.class && pt != Boolean.class) {
                continue;
            }
            try {
                m.invoke(target, Boolean.valueOf(value));
                return;
            } catch (Exception e) {
                log.error("OES 回报同步反射调用异常", e);
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
        String message = t.getMessage().trim();
        if (message.length() > 180) {
            return message.substring(0, 180) + "...";
        }
        return message;
    }
}
