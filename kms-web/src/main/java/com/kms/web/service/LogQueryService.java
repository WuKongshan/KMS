package com.kms.web.service;

import com.kms.web.dto.LogTail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the tail of the three KMS application log files for the web log viewer
 * (the collector, analyser and web processes each write their own Logback file).
 *
 * <p>【中文】为"系统日志"页面读取三个 KMS 应用（collector / analyser / web）的日志文件尾部。
 * 三个进程各自写自己的 Logback 文件，本服务直接在同一台机器上按约定路径读取，
 * 因此不需要为看日志而给每个进程开 HTTP 端口。
 *
 * <p>【中文】安全约束（重要）：用户传入的 {@code service} 只作为<b>白名单 Map 的键</b>使用，
 * 永不参与路径拼接——否则 {@code service=../../../etc/passwd} 就是一次路径穿越。
 * 行数与关键字长度同样钳制上限，避免一次请求把大文件读进内存。
 */
@Service
public class LogQueryService {

    private static final Logger log = LoggerFactory.getLogger(LogQueryService.class);

    /**
     * Whitelist: service name -> log file name (never a user-supplied path).
     *
     * <p>【中文】白名单：服务名 → 日志文件名。用户输入只用来查这张表，查不到就报错，
     * 因此任何路径穿越、绝对路径注入的尝试都会在第一步被拦住。
     */
    private static final Map<String, String> SERVICE_FILES = Map.of(
            "collector", "kms-collector.log",
            "analyser", "kms-analyser.log",
            "web", "kms-web.log");

    // 服务名的固定展示顺序：Map.of 的迭代顺序不稳定，前端下拉框需要稳定顺序
    private static final List<String> ORDERED_SERVICES = List.of("collector", "analyser", "web");

    // 单次最多读取的尾部字节数（2 MB）：再大也没有排查价值，且会拖慢响应
    private static final int MAX_TAIL_BYTES = 2 * 1024 * 1024;
    // 单次最多返回的行数上限（防止前端一次渲染几万行卡死浏览器）
    private static final int MAX_LINES = 2000;
    // 关键字长度上限：仅用于内存过滤（不做正则），限制长度避免构造超长匹配串
    private static final int MAX_KEYWORD_LENGTH = 100;

    // 候选日志目录（按顺序探测，命中即用）：默认覆盖"从项目根启动"与"从模块目录启动"两种常见方式
    private final List<Path> candidateDirs;

    public LogQueryService(@Value("${kms.web.log-dirs:logs,kms-collector/logs,kms-analyser/logs,kms-web/logs}")
                           String logDirs) {
        this.candidateDirs = Arrays.stream(logDirs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Path::of)
                .toList();
    }

    /**
     * Available services with the resolved file path (for the page's service selector).
     *
     * <p>【中文】列出可选服务及其解析到的文件路径，供页面下拉框展示"哪些服务的日志能看、
     * 具体读的是哪个文件"；未找到文件的服务也会返回，只是 available=false。
     */
    public List<Map<String, Object>> services() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String service : ORDERED_SERVICES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("service", service);
            Path file = resolve(service);
            item.put("available", file != null);
            item.put("file", file == null ? null : file.toAbsolutePath().toString());
            result.add(item);
        }
        return result;
    }

    /** Whether the service name is in the whitelist. 【中文】服务名是否在白名单内。 */
    public boolean isKnownService(String service) {
        return service != null && SERVICE_FILES.containsKey(service);
    }

    /**
     * Read the last {@code lines} lines of the service's log file, optionally
     * keeping only lines containing {@code keyword} (case-insensitive).
     *
     * <p>【中文】读取指定服务日志文件的最后 N 行，可选按关键字过滤（不区分大小写）。
     * 关键字过滤在内存中做（{@code contains}，不用正则），过滤后仍只返回最后 N 行。
     */
    public LogTail query(String service, int lines, String keyword) {
        int limit = Math.max(1, Math.min(lines, MAX_LINES));
        String kw = normalizeKeyword(keyword);

        LogTail tail = new LogTail();
        tail.setService(service);
        tail.setRequestedLines(limit);
        tail.setLines(List.of());

        Path file = resolve(service);
        if (file == null) {
            // 文件不存在不是异常：可能该进程还没启动过，或从别的工作目录启动。如实告知探测过的目录。
            tail.setAvailable(false);
            tail.setMessage("未找到日志文件 " + SERVICE_FILES.get(service) + "，已探测目录: " + candidateDirs);
            return tail;
        }
        tail.setFile(file.toAbsolutePath().toString());
        try {
            List<String> all = readTail(file);
            tail.setTruncated(isTruncated(file));
            List<String> filtered = kw == null
                    ? all
                    : all.stream().filter(line -> line.toLowerCase(Locale.ROOT).contains(kw)).toList();
            int from = Math.max(0, filtered.size() - limit);
            List<String> result = new ArrayList<>(filtered.subList(from, filtered.size()));
            tail.setAvailable(true);
            tail.setLines(result);
            tail.setReturnedLines(result.size());
        } catch (IOException e) {
            // 读取失败（权限、文件被独占等）：同样降级为"不可用 + 原因"，不抛 500
            log.warn("Failed to read log file {}: {}", file, e.getMessage());
            tail.setAvailable(false);
            tail.setMessage("读取日志文件失败: " + e.getMessage());
        }
        return tail;
    }

    // 按候选目录顺序探测日志文件，返回第一个存在的文件；都不存在返回 null
    private Path resolve(String service) {
        String fileName = SERVICE_FILES.get(service);
        if (fileName == null) {
            return null;
        }
        for (Path dir : candidateDirs) {
            Path candidate = dir.resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // 只读文件末尾 MAX_TAIL_BYTES 字节，按行切分；文件较小时等价于读全文
    private List<String> readTail(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            long start = Math.max(0, length - MAX_TAIL_BYTES);
            byte[] buffer = new byte[(int) (length - start)];
            raf.seek(start);
            raf.readFully(buffer);
            String text = new String(buffer, StandardCharsets.UTF_8);
            // -1 保留末尾空串，便于后面显式清理"结尾换行"造成的伪空行
            List<String> lines = new ArrayList<>(Arrays.asList(text.split("\r?\n", -1)));
            // 日志文件通常以换行结尾，split 会切出一个末尾空串：它不是真实的日志行，去掉。
            // 只清理"末尾"的空行——堆栈信息中间的空行是有意义的，不能一起删。
            while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            if (start > 0 && !lines.isEmpty()) {
                // 从文件中间开始读时，第一行几乎必然是被切断的半行，丢弃它
                lines.remove(0);
            }
            return lines;
        }
    }

    // 判断本次读取是否属于"只读了结尾"（供前端提示"仅显示最近内容"）
    private boolean isTruncated(Path file) {
        try {
            return Files.size(file) > MAX_TAIL_BYTES;
        } catch (IOException e) {
            return false;
        }
    }

    // 关键字归一化：空白视为"无过滤"，超长截断，统一转小写用于忽略大小写匹配
    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) {
            trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
