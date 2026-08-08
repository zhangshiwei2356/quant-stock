package com.quant.stock.kuangrui;


import lombok.extern.slf4j.Slf4j;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 宽睿 OES 查询列表反射适配：兼容资料包 Demo 的
 * {@code Rsp queryXxx(Filter, QueryMode.ALL)}（再取 {@code getQryItems()}），以及
 * {@code List queryXxx(Filter)}、无参、{@code int queryXxx(Filter, Callback)}、数组返回。
 */
@Slf4j
public final class OesQueryListInvoker {

    private OesQueryListInvoker() {
    }

    public static final class Result {
        public final boolean ok;
        public final List<?> list;
        public final String detail;
        public final String methodUsed;

        private Result(boolean ok, List<?> list, String detail, String methodUsed) {
            this.ok = ok;
            this.list = list == null ? Collections.emptyList() : list;
            this.detail = detail;
            this.methodUsed = methodUsed;
        }

        public static Result success(List<?> list, String methodUsed) {
            return new Result(true, list, null, methodUsed);
        }

        public static Result fail(String detail) {
            return new Result(false, Collections.emptyList(), detail, null);
        }
    }

    /**
     * @param client                 OesClientImpl
     * @param methodNames            候选方法名（如 queryCashAsset）
     * @param filterClassCandidates  Filter 全限定类名候选
     */
    public static Result invoke(Object client, String[] methodNames, String[] filterClassCandidates) {
        return invokeWithFilters(client, methodNames, buildFilters(filterClassCandidates));
    }

    /** 使用已构造好的 Filter 实例（可带 securityId 等条件）。 */
    public static Result invokeWithFilters(Object client, String[] methodNames, List<Object> filterInstances) {
        if (client == null) {
            return Result.fail("OES 客户端为空");
        }
        if (methodNames == null || methodNames.length == 0) {
            return Result.fail("未指定查询方法名");
        }
        List<String> errors = new ArrayList<String>();
        Set<String> signatures = new LinkedHashSet<String>();
        List<Object> filters = filterInstances == null
                ? Collections.<Object>emptyList()
                : filterInstances;

        for (String methodName : methodNames) {
            if (methodName == null || methodName.trim().isEmpty()) {
                continue;
            }
            for (Method m : collectMethods(client.getClass())) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                try {
                    m.setAccessible(true);
                } catch (Exception ignore) {
                    log.error("OES 查询列表反射调用异常", ignore);
                    // ignore
                }
                signatures.add(formatSig(m));
                Class<?>[] pts = m.getParameterTypes();
                try {
                    if (pts.length == 0) {
                        Object ret = m.invoke(client);
                        Result r = coerceListResult(ret, null, formatSig(m));
                        if (r.ok) {
                            return r;
                        }
                        errors.add(formatSig(m) + ": " + r.detail);
                        continue;
                    }
                    if (pts.length == 1) {
                        // null filter
                        Result r = tryOneArg(client, m, null, errors);
                        if (r.ok) {
                            return r;
                        }
                        for (Object f : filters) {
                            if (f == null || !pts[0].isInstance(f)) {
                                continue;
                            }
                            r = tryOneArg(client, m, f, errors);
                            if (r.ok) {
                                return r;
                            }
                        }
                        continue;
                    }
                    if (pts.length == 2) {
                        // Filter + Callback / Cursor
                        Result r = tryTwoArg(client, m, null, errors);
                        if (r.ok) {
                            return r;
                        }
                        for (Object f : filters) {
                            if (f != null && !pts[0].isInstance(f)) {
                                continue;
                            }
                            r = tryTwoArg(client, m, f, errors);
                            if (r.ok) {
                                return r;
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("OES 查询列表反射调用异常", e);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    errors.add(formatSig(m) + ": " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
                }
            }
        }

        StringBuilder sb = new StringBuilder("查询未返回列表");
        sb.append("；方法候选=").append(java.util.Arrays.toString(methodNames));
        if (!signatures.isEmpty()) {
            sb.append("；可用签名=").append(signatures);
        } else {
            sb.append("；客户端上未找到同名方法");
        }
        if (!errors.isEmpty()) {
            int n = Math.min(errors.size(), 8);
            sb.append("；尝试=");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(" | ");
                }
                sb.append(errors.get(i));
            }
        }
        return Result.fail(sb.toString());
    }

    private static Result tryOneArg(Object client, Method m, Object filter, List<String> errors) {
        try {
            Object ret = m.invoke(client, filter);
            Result r = coerceListResult(ret, null, formatSig(m) + (filter == null ? "#nullFilter" : "#filter"));
            if (r.ok) {
                return r;
            }
            errors.add(formatSig(m) + ": " + r.detail);
            return r;
        } catch (Exception e) {
            log.error("OES 查询列表反射调用异常", e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String msg = formatSig(m) + ": " + cause.getClass().getSimpleName() + " " + safeMsg(cause);
            errors.add(msg);
            return Result.fail(msg);
        }
    }

    private static Result tryTwoArg(Object client, Method m, Object filter, List<String> errors) {
        Class<?>[] pts = m.getParameterTypes();
        Class<?> second = pts[1];

        // 0) Demo 主路径：Filter + QueryMode（枚举，常用 ALL）
        if (second.isEnum()) {
            Object mode = resolveQueryMode(second);
            if (mode == null) {
                errors.add(formatSig(m) + "#QueryMode: 枚举无可用常量");
                return Result.fail("QueryMode unresolved");
            }
            try {
                Object ret = m.invoke(client, filter, mode);
                Result r = coerceListResult(ret, null, formatSig(m) + "#" + String.valueOf(mode));
                if (r.ok) {
                    return r;
                }
                errors.add(formatSig(m) + "#" + mode + ": " + r.detail);
            } catch (Exception e) {
                log.error("OES 查询列表反射调用异常", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(formatSig(m) + "#" + mode + ": " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
            }
            return Result.fail("two-arg QueryMode failed");
        }

        // 1) 第二参 null（部分 Cursor/可选回调；枚举不可为 null，已在上方分支处理）
        try {
            Object ret = m.invoke(client, filter, null);
            Result r = coerceListResult(ret, null, formatSig(m) + "#cbNull");
            if (r.ok && !r.list.isEmpty()) {
                return r;
            }
            // 空 List 也可能合法；若 ret 是 int>=0 且无回调收集，继续试 callback
            if (r.ok && ret instanceof List) {
                return r;
            }
            if (r.detail != null) {
                errors.add(formatSig(m) + ": " + r.detail);
            }
        } catch (Exception e) {
            log.error("OES 查询列表反射调用异常", e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            errors.add(formatSig(m) + "#cbNull: " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
        }

        // 2) 回调代理收集
        if (second.isInterface()) {
            List<Object> bucket = new CopyOnWriteArrayList<Object>();
            Object cb = Proxy.newProxyInstance(
                    second.getClassLoader(),
                    new Class[]{second},
                    new CollectingHandler(bucket));
            try {
                Object ret = m.invoke(client, filter, cb);
                Result r = coerceListResult(ret, bucket, formatSig(m) + "#callback");
                if (r.ok) {
                    return r;
                }
                errors.add(formatSig(m) + "#callback: " + r.detail);
            } catch (Exception e) {
                log.error("OES 查询列表反射调用异常", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.add(formatSig(m) + "#callback: " + cause.getClass().getSimpleName() + " " + safeMsg(cause));
            }
        }
        return Result.fail("two-arg failed");
    }

    /** 优先 {@code ALL}（资料包 Demo），否则取枚举第一个常量。 */
    private static Object resolveQueryMode(Class<?> enumType) {
        Object[] constants = enumType.getEnumConstants();
        if (constants == null || constants.length == 0) {
            return null;
        }
        for (Object c : constants) {
            if (c != null && "ALL".equals(((Enum<?>) c).name())) {
                return c;
            }
        }
        return constants[0];
    }

    private static Result coerceListResult(Object ret, List<?> callbackBucket, String via) {
        if (ret instanceof List) {
            return Result.success((List<?>) ret, via);
        }
        if (ret instanceof Collection) {
            return Result.success(new ArrayList<Object>((Collection<?>) ret), via);
        }
        if (ret != null && ret.getClass().isArray()) {
            int n = Array.getLength(ret);
            List<Object> list = new ArrayList<Object>(n);
            for (int i = 0; i < n; i++) {
                list.add(Array.get(ret, i));
            }
            return Result.success(list, via);
        }
        // Demo：OesQryCashAssetRsp / OesQryStkHoldingRsp 等 → getQryItems()
        List<?> fromRsp = unwrapQryItems(ret);
        if (fromRsp != null) {
            return Result.success(fromRsp, via + "#getQryItems");
        }
        if (callbackBucket != null && !callbackBucket.isEmpty()) {
            return Result.success(new ArrayList<Object>(callbackBucket), via);
        }
        // int 返回：回调已灌入 bucket 则为成功（含 0 条）
        if (ret instanceof Number && callbackBucket != null) {
            return Result.success(new ArrayList<Object>(callbackBucket), via + "#count=" + ret);
        }
        if (ret == null && callbackBucket != null) {
            return Result.success(new ArrayList<Object>(callbackBucket), via);
        }
        if (ret == null) {
            return Result.fail("返回 null");
        }
        return Result.fail("返回类型=" + ret.getClass().getName() + " value=" + ret);
    }

    /**
     * 从查询应答对象取出明细列表；无对应 getter 时返回 null（非空 List，含 0 条）。
     */
    private static List<?> unwrapQryItems(Object rsp) {
        if (rsp == null) {
            return null;
        }
        String[] getters = {"getQryItems", "getItems", "getCashAssetItems", "getStkHoldingItems",
                "getOrdItems", "getTrdItems", "getStockItems",
                "getCounterCashItem", "getMaxTradableQtyItem"};
        for (String g : getters) {
            try {
                Method m = rsp.getClass().getMethod(g);
                Object v = m.invoke(rsp);
                if (v instanceof List) {
                    return (List<?>) v;
                }
                if (v instanceof Collection) {
                    return new ArrayList<Object>((Collection<?>) v);
                }
                if (v != null && v.getClass().isArray()) {
                    int n = Array.getLength(v);
                    List<Object> list = new ArrayList<Object>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(Array.get(v, i));
                    }
                    return list;
                }
                // 单对象应答（如 CounterCash / MaxTradableQty）
                if (v != null && ("getCounterCashItem".equals(g) || "getMaxTradableQtyItem".equals(g))) {
                    return Collections.singletonList(v);
                }
            } catch (NoSuchMethodException ignore) {
                // 候选 getter 不存在属正常，换下一个
            } catch (Exception e) {
                log.error("OES 查询列表反射调用异常", e);
            }
        }
        return null;
    }

    private static List<Object> buildFilters(String[] filterClassCandidates) {
        List<Object> out = new ArrayList<Object>();
        if (filterClassCandidates == null) {
            return out;
        }
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = OesQueryListInvoker.class.getClassLoader();
        }
        for (String cn : filterClassCandidates) {
            if (cn == null || cn.trim().isEmpty()) {
                continue;
            }
            String name = cn.trim();
            // 候选包名探测：不存在则跳过，避免 ClassNotFound 刷屏
            String resource = name.replace('.', '/') + ".class";
            if (cl.getResource(resource) == null) {
                continue;
            }
            try {
                Class<?> clazz = Class.forName(name, false, cl);
                out.add(clazz.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                log.error("OES 查询列表反射调用异常", e);
                // next candidate
            }
        }
        return out;
    }

    private static List<Method> collectMethods(Class<?> type) {
        LinkedHashSet<Method> out = new LinkedHashSet<Method>();
        for (Method m : type.getMethods()) {
            out.add(m);
        }
        Class<?> c = type;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) {
                    out.add(m);
                }
            }
            c = c.getSuperclass();
        }
        return new ArrayList<Method>(out);
    }

    private static final class CollectingHandler implements InvocationHandler {
        private final List<Object> bucket;

        private CollectingHandler(List<Object> bucket) {
            this.bucket = bucket;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == (args == null ? null : args[0]));
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if ("toString".equals(name)) {
                return "OesQueryCollectingCallback";
            }
            // 常见：onQryMsg / onQueryItem / handleItem / accept — 参数里找业务对象
            if (args != null) {
                for (Object a : args) {
                    if (a == null || a instanceof Number || a instanceof CharSequence || a instanceof Boolean) {
                        continue;
                    }
                    if (a.getClass().isPrimitive()) {
                        continue;
                    }
                    // 跳过明显的会话/请求头
                    String cn = a.getClass().getSimpleName().toLowerCase(Locale.ROOT);
                    if (cn.contains("session") || cn.contains("msghead") || cn.contains("filter")
                            || cn.contains("cursor") || cn.contains("channel")) {
                        continue;
                    }
                    bucket.add(a);
                    break;
                }
            }
            Class<?> rt = method.getReturnType();
            if (rt == Void.TYPE) {
                return null;
            }
            if (rt == boolean.class || rt == Boolean.class) {
                return Boolean.TRUE;
            }
            if (rt.isPrimitive()) {
                return Integer.valueOf(0);
            }
            return null;
        }
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
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
