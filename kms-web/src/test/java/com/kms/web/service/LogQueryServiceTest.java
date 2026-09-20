package com.kms.web.service;

import com.kms.web.dto.LogTail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the log viewer service. No Spring context and no database
 * needed: the service only reads files, so tests run standalone.
 *
 * <p>【中文】日志查看服务的单元测试。服务只读文件，因此不需要 Spring 容器与数据库：
 * 测试用一个临时目录冒充日志目录，直接构造服务实例。
 *
 * <p>【中文】重点覆盖三类行为：
 * ① <b>安全</b>——服务名必须走白名单，任何"路径穿越"样式的输入都要被拒绝，
 * 且永远不能读到白名单之外的文件；② <b>尾部读取</b>——只返回最后 N 行；
 * ③ <b>过滤与钳制</b>——关键字不区分大小写、行数上限生效、文件缺失时降级不抛异常。
 */
class LogQueryServiceTest {

    @TempDir
    Path tempDir;

    // 在临时目录里造一个日志文件，内容为传入的各行
    private Path writeLog(String fileName, List<String> lines) throws IOException {
        Path file = tempDir.resolve(fileName);
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    // 用临时目录作为唯一的候选日志目录构造被测服务
    private LogQueryService service() {
        return new LogQueryService(tempDir.toString());
    }

    @Test
    void tailReturnsOnlyLastNLines() throws IOException {
        writeLog("kms-analyser.log", List.of("l1", "l2", "l3", "l4", "l5"));

        LogTail tail = service().query("analyser", 2, null);

        assertTrue(tail.isAvailable());
        assertEquals(2, tail.getReturnedLines());
        assertEquals(List.of("l4", "l5"), tail.getLines());
    }

    @Test
    void keywordFilterIsCaseInsensitive() throws IOException {
        writeLog("kms-analyser.log", List.of(
                "INFO  Analysis cycle started",
                "WARN  ALERT generated (level 3)",
                "ERROR boom",
                "INFO  done"));

        LogTail tail = service().query("analyser", 200, "alert");

        assertEquals(1, tail.getReturnedLines());
        assertTrue(tail.getLines().get(0).contains("ALERT generated"));
    }

    @Test
    void keywordNoMatchReturnsEmptyListButStillAvailable() throws IOException {
        writeLog("kms-analyser.log", List.of("INFO  hello"));

        LogTail tail = service().query("analyser", 200, "not-existing-keyword");

        assertTrue(tail.isAvailable());
        assertEquals(0, tail.getReturnedLines());
        assertTrue(tail.getLines().isEmpty());
    }

    @Test
    void linesAreClampedToUpperBound() throws IOException {
        writeLog("kms-analyser.log", List.of("INFO  only one line"));

        LogTail tail = service().query("analyser", 999_999, null);

        // 钳制上限 2000：请求再多也不会把大文件整块读进内存
        assertEquals(2000, tail.getRequestedLines());
    }

    @Test
    void missingFileDegradesGracefully() {
        LogTail tail = service().query("collector", 100, null);

        assertFalse(tail.isAvailable());
        assertEquals(0, tail.getReturnedLines());
        // 降级信息里要带上"探测过哪些目录"，方便排查日志为什么看不到
        assertTrue(tail.getMessage().contains("未找到日志文件"));
    }

    @Test
    void whitelistRejectsPathTraversalAndUnknownNames() {
        LogQueryService service = service();

        assertFalse(service.isKnownService("../../etc/passwd"));
        assertFalse(service.isKnownService("..\\windows\\win.ini"));
        assertFalse(service.isKnownService("realtime"));
        assertFalse(service.isKnownService(null));
        assertTrue(service.isKnownService("analyser"));

        // 未知服务名不会去读任何文件，直接降级为不可用
        LogTail tail = service.query("../../etc/passwd", 10, null);
        assertFalse(tail.isAvailable());
    }

    @Test
    void servicesListsThreeServicesWithAvailability() throws IOException {
        writeLog("kms-analyser.log", List.of("INFO  x"));

        List<java.util.Map<String, Object>> services = service().services();

        assertEquals(3, services.size());
        assertEquals("collector", services.get(0).get("service"));
        assertEquals("analyser", services.get(1).get("service"));
        assertEquals(Boolean.TRUE, services.get(1).get("available"));
        assertEquals(Boolean.FALSE, services.get(0).get("available"));
    }
}
