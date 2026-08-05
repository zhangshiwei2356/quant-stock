package com.quant.stock.market;

import com.quant.stock.config.QuantProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调用本机 Python 通达信灌数脚本（日线 / 池内分钟）。
 * 需 {@code quant.tdx-script.enabled=true}；默认关闭以免未装依赖时误跑。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "quant.db-enabled", havingValue = "true")
public class TdxScriptBackfillService {

    /** 脚本进度行形如 {@code [12/5000] 600036: daily=120 ...} */
    private static final Pattern PROGRESS_LINE = Pattern.compile("\\[(\\d+)/(\\d+)]");

    private final QuantProperties quantProperties;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService asyncPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tdx-script-backfill");
        t.setDaemon(true);
        return t;
    });

    private volatile String currentTag = "";
    private volatile long startedAtMs;
    private volatile String lastLine = "";
    private volatile int lineCount;
    private volatile Integer progressIndex;
    private volatile Integer progressTotal;
    private volatile String phase = "idle";
    private volatile String phaseLabel = "";
    private volatile String summary = "";
    private volatile String currentSymbol = "";
    private volatile Map<String, Object> lastFinished;

    public boolean isEnabled() {
        QuantProperties.TdxScript cfg = quantProperties.getTdxScript();
        return cfg != null && cfg.isEnabled();
    }

    /** 同步跑池内 1 分钟回填（{@code --from-pool}）。 */
    public Map<String, Object> backfillPoolMinuteSync() {
        return runScript("min1-pool", resolveMin1Script(), "--from-pool");
    }

    /** 异步跑池内分钟回填；已在跑则跳过。 */
    public Map<String, Object> backfillPoolMinuteAsync() {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        if (!isEnabled()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "quant.tdx-script.enabled=false");
            out.put("hint", "python scripts/fetch_min1_tdx.py --from-pool");
            return out;
        }
        if (running.get()) {
            out.put("ok", false);
            out.put("skipped", true);
            out.put("message", "已有 TDX 脚本在执行");
            return out;
        }
        asyncPool.submit(new Runnable() {
            @Override
            public void run() {
                Map<String, Object> r = backfillPoolMinuteSync();
                log.info("[tdx-script] 异步池内分钟回填结束 {}", r);
            }
        });
        out.put("ok", true);
        out.put("async", true);
        out.put("message", "已提交异步：fetch_min1_tdx.py --from-pool");
        out.put("hint", "python scripts/fetch_min1_tdx.py --from-pool");
        return out;
    }

    /**
     * 同步跑全市场日线回填。
     *
     * @param years 回溯年数，≤0 时用 1
     */
    public Map<String, Object> backfillDailySync(double years) {
        double y = years <= 0 ? 1.0 : years;
        return runScript("daily-basic", resolveDailyScript(),
                "--from-basic", "--years", String.valueOf(y), "--incremental",
                "--workers", "4", "--skip-fresh-days", "3");
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        QuantProperties.TdxScript cfg = quantProperties.getTdxScript();
        boolean run = running.get();
        m.put("enabled", isEnabled());
        m.put("running", run);
        m.put("tag", currentTag == null ? "" : currentTag);
        m.put("tagLabel", tagLabel(currentTag));
        m.put("startedAtMs", startedAtMs > 0 ? startedAtMs : null);
        Long elapsedMs = run && startedAtMs > 0 ? System.currentTimeMillis() - startedAtMs : null;
        m.put("elapsedMs", elapsedMs);
        m.put("lastLine", lastLine == null ? "" : lastLine);
        m.put("lineCount", lineCount);
        m.put("progressIndex", progressIndex);
        m.put("progressTotal", progressTotal);
        Integer pct = null;
        if (progressIndex != null && progressTotal != null && progressTotal > 0) {
            pct = Math.min(100, (int) Math.round(100.0 * progressIndex / progressTotal));
        }
        m.put("progressPct", pct);
        m.put("phase", phase == null ? "idle" : phase);
        m.put("phaseLabel", phaseLabel == null ? "" : phaseLabel);
        m.put("summary", summary == null || summary.isEmpty()
                ? (run ? "任务执行中…" : "") : summary);
        m.put("currentSymbol", currentSymbol == null ? "" : currentSymbol);
        m.put("etaSec", estimateEtaSec(elapsedMs));
        m.put("lastFinished", lastFinished);
        m.put("python", cfg == null ? "python" : cfg.getPython());
        m.put("workingDir", resolveWorkDir().toString());
        m.put("min1Script", resolveMin1Script().toString());
        m.put("dailyScript", resolveDailyScript().toString());
        m.put("min1Exists", Files.isRegularFile(resolveMin1Script()));
        m.put("dailyExists", Files.isRegularFile(resolveDailyScript()));
        m.put("poolRebuildBackfillMinute", quantProperties.isPoolRebuildBackfillMinute());
        m.put("hint", "关闭：quant.tdx-script.enabled=false；依赖 pip install pytdx pymysql");
        return m;
    }

    private Map<String, Object> runScript(String tag, Path script, String... args) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        out.put("tag", tag);
        if (!isEnabled()) {
            out.put("ok", false);
            out.put("message", "quant.tdx-script.enabled=false，未执行");
            out.put("hint", "python " + script.getFileName() + " " + joinArgs(args));
            return out;
        }
        if (!Files.isRegularFile(script)) {
            out.put("ok", false);
            out.put("message", "脚本不存在: " + script);
            return out;
        }
        if (!running.compareAndSet(false, true)) {
            out.put("ok", false);
            out.put("message", "已有 TDX 脚本在执行");
            return out;
        }
        currentTag = tag == null ? "" : tag;
        startedAtMs = System.currentTimeMillis();
        lastLine = "正在启动脚本…";
        lineCount = 0;
        progressIndex = null;
        progressTotal = null;
        phase = "starting";
        phaseLabel = "启动中";
        summary = tagLabel(currentTag) + "：正在启动…";
        currentSymbol = "";
        QuantProperties.TdxScript cfg = quantProperties.getTdxScript();
        List<String> cmd = new ArrayList<String>();
        cmd.add(cfg.getPython() == null || cfg.getPython().trim().isEmpty() ? "python" : cfg.getPython().trim());
        cmd.add(script.toAbsolutePath().toString());
        if (args != null) {
            for (String a : args) {
                if (a != null) {
                    cmd.add(a);
                }
            }
        }
        out.put("command", cmd);
        Path work = resolveWorkDir();
        int timeout = cfg.getTimeoutSeconds() <= 0 ? 3600 : cfg.getTimeoutSeconds();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(work.toFile());
        pb.redirectErrorStream(true);
        // 统一 UTF-8，避免 Windows 默认 GBK 与 Python 输出不一致导致进度中文乱码
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");
        long start = System.currentTimeMillis();
        final StringBuilder logBuf = new StringBuilder();
        try {
            log.info("[tdx-script] 启动 tag={} cmd={} cwd={}", tag, cmd, work);
            final Process p = pb.start();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            log.info("[tdx-script:{}] {}", tag, line);
                            noteProgressLine(line);
                            synchronized (logBuf) {
                                if (logBuf.length() < 8000) {
                                    logBuf.append(line).append('\n');
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[tdx-script] 读输出失败: {}", e.getMessage());
                    }
                }
            }, "tdx-script-stdout-" + tag);
            reader.setDaemon(true);
            reader.start();
            boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                out.put("ok", false);
                out.put("message", "超时 " + timeout + "s，已强制结束");
                out.put("tail", tailOf(logBuf));
                rememberFinished(out);
                return out;
            }
            reader.join(30_000L);
            int code = p.exitValue();
            out.put("exitCode", code);
            out.put("elapsedMs", System.currentTimeMillis() - start);
            out.put("ok", code == 0);
            out.put("message", code == 0 ? "完成" : "脚本退出码 " + code);
            out.put("tail", trimTail(tailOf(logBuf), 2000));
            rememberFinished(out);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage());
            log.warn("[tdx-script] 执行失败 tag={}: {}", tag, e.getMessage());
            rememberFinished(out);
            return out;
        } finally {
            running.set(false);
        }
    }

    private void noteProgressLine(String line) {
        if (line == null) {
            return;
        }
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        lastLine = trimmed.length() > 240 ? trimmed.substring(0, 240) + "…" : trimmed;
        lineCount++;
        Matcher matcher = PROGRESS_LINE.matcher(trimmed);
        if (matcher.find()) {
            try {
                progressIndex = Integer.parseInt(matcher.group(1));
                progressTotal = Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
                // keep previous
            }
        }
        applyPhaseFromLine(trimmed);
    }

    private void applyPhaseFromLine(String line) {
        if (line.contains("同步全市场") || line.toLowerCase().contains("sync stock_basic")) {
            phase = "sync";
            phaseLabel = "同步列表";
            summary = "正在同步全市场股票列表到 stock_basic…";
            currentSymbol = "";
            return;
        }
        if (line.contains("列表已就绪") || line.toLowerCase().contains("universe source=")) {
            phase = "sync";
            phaseLabel = "同步列表";
            summary = line.startsWith("【") ? line.replaceFirst("^【阶段】", "").trim()
                    : ("股票列表已更新 · " + line);
            currentSymbol = "";
            return;
        }
        if (line.contains("开始拉取日线") || line.startsWith("target=")) {
            phase = "fetch";
            phaseLabel = "拉取日线";
            if (line.contains("共 ")) {
                summary = line.replaceFirst("^【阶段】", "").trim();
            } else {
                summary = "开始拉取日线：" + line;
            }
            // target=5529 ...
            Matcher tm = Pattern.compile("(?:共\\s*|target=)(\\d+)").matcher(line);
            if (tm.find()) {
                try {
                    progressTotal = Integer.parseInt(tm.group(1));
                    if (progressIndex == null) {
                        progressIndex = 0;
                    }
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
            currentSymbol = "";
            return;
        }
        if (line.contains("【完成】") || line.startsWith("done ")) {
            phase = "done";
            phaseLabel = "收尾";
            summary = line.startsWith("【") ? line.replaceFirst("^【完成】", "已完成 ·").trim()
                    : ("脚本收尾：" + line);
            if (progressTotal != null) {
                progressIndex = progressTotal;
            }
            return;
        }
        if (line.contains("已齐跳过")) {
            phase = "fetch";
            phaseLabel = "拉取日线";
            summary = line;
            Matcher sm = Pattern.compile("^\\[(\\d+)/(\\d+)]").matcher(line);
            if (sm.find()) {
                try {
                    progressIndex = Integer.parseInt(sm.group(1));
                    progressTotal = Integer.parseInt(sm.group(2));
                } catch (NumberFormatException ignored) {
                    // ignore
                }
            }
            return;
        }
        Matcher item = Pattern.compile("^\\[(\\d+)/(\\d+)]\\s*(\\d{6})?(.*)").matcher(line);
        if (item.find()) {
            phase = "fetch";
            phaseLabel = "拉取日线";
            String code = item.group(3) == null ? "" : item.group(3);
            currentSymbol = code;
            String rest = item.group(4) == null ? "" : item.group(4).trim();
            if (rest.startsWith("·")) {
                rest = rest.substring(1).trim();
            }
            if (!code.isEmpty() && !rest.isEmpty()) {
                summary = "正在处理 " + code + " · " + rest;
            } else if (!code.isEmpty()) {
                summary = "正在处理 " + code;
            } else {
                summary = rest.isEmpty() ? line : rest;
            }
            return;
        }
        if ("starting".equals(phase) || phase == null || phase.isEmpty() || "idle".equals(phase)) {
            phase = "running";
            phaseLabel = "执行中";
            summary = line;
        } else if (!line.startsWith("TDX ") && !line.startsWith("  ")) {
            // 保留阶段，刷新摘要为较可读行
            if (summary == null || summary.isEmpty() || "starting".equals(phase)) {
                summary = line;
            }
        }
    }

    private Long estimateEtaSec(Long elapsedMs) {
        if (elapsedMs == null || elapsedMs < 3000) {
            return null;
        }
        if (progressIndex == null || progressTotal == null || progressIndex < 3 || progressTotal <= 0) {
            return null;
        }
        if (progressIndex >= progressTotal) {
            return 0L;
        }
        double per = elapsedMs * 1.0 / progressIndex;
        return Math.max(0L, Math.round(per * (progressTotal - progressIndex) / 1000.0));
    }

    private static String tagLabel(String tag) {
        if (tag == null || tag.isEmpty()) {
            return "TDX 任务";
        }
        if ("daily-basic".equals(tag)) {
            return "全市场日线";
        }
        if ("min1-pool".equals(tag)) {
            return "目标池分钟";
        }
        return tag;
    }

    private void rememberFinished(Map<String, Object> out) {
        Map<String, Object> snap = new LinkedHashMap<String, Object>();
        snap.put("tag", currentTag);
        snap.put("tagLabel", tagLabel(currentTag));
        snap.put("ok", out.get("ok"));
        snap.put("message", out.get("message"));
        snap.put("exitCode", out.get("exitCode"));
        snap.put("elapsedMs", out.get("elapsedMs"));
        snap.put("finishedAtMs", System.currentTimeMillis());
        snap.put("lastLine", lastLine);
        snap.put("phase", phase);
        snap.put("summary", summary);
        snap.put("progressIndex", progressIndex);
        snap.put("progressTotal", progressTotal);
        Boolean ok = out.get("ok") instanceof Boolean ? (Boolean) out.get("ok") : null;
        if (Boolean.TRUE.equals(ok)) {
            snap.put("summaryFriendly", summary != null && !summary.isEmpty()
                    ? summary : (tagLabel(currentTag) + "已完成"));
        } else {
            snap.put("summaryFriendly", out.get("message") == null
                    ? (tagLabel(currentTag) + "失败")
                    : String.valueOf(out.get("message")));
        }
        lastFinished = snap;
        if (Boolean.TRUE.equals(ok)) {
            phase = "done";
            phaseLabel = "已完成";
        } else {
            phase = "error";
            phaseLabel = "失败";
        }
    }

    private Path resolveWorkDir() {
        QuantProperties.TdxScript cfg = quantProperties.getTdxScript();
        String raw = cfg == null ? null : cfg.getWorkingDir();
        if (raw != null && !raw.trim().isEmpty()) {
            return Paths.get(raw.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    private Path resolveMin1Script() {
        QuantProperties.TdxScript cfg = quantProperties.getTdxScript();
        String rel = cfg == null || cfg.getMin1Script() == null
                ? "scripts/fetch_min1_tdx.py" : cfg.getMin1Script();
        return resolveWorkDir().resolve(rel).normalize();
    }

    private Path resolveDailyScript() {
        QuantProperties.TdxScript cfg = quantProperties.getTdxScript();
        String rel = cfg == null || cfg.getDailyScript() == null
                ? "scripts/fetch_daily_tdx.py" : cfg.getDailyScript();
        return resolveWorkDir().resolve(rel).normalize();
    }

    private static String joinArgs(String... args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(a);
        }
        return sb.toString();
    }

    private static String tailOf(StringBuilder buf) {
        synchronized (buf) {
            return buf.toString();
        }
    }

    private static String trimTail(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(s.length() - max);
    }
}
