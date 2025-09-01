
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;
import java.util.zip.GZIPInputStream;

/**
 * LogAnalyzer
 *
 * 支持 p6spy 风格日志（例如：
 *   [Time: 0 ms][Caller: com.xx.A#b:47][SQL: select ... from t1 join t2 on ...]
 * )
 *
 * 功能：
 *   A) 生成多 Sheet 工作簿（Excel 2003 XML，无第三方依赖）：
 *      - Summary（第 1 个 sheet）：分四段（SELECT/INSERT/UPDATE/DELETE），每段按“表的用量（出现次数）”降序。
 *      - <每个表一个 sheet>：列出该表相关的 Caller 方法统计（次数/平均耗时），按次数降序；
 *        并且剔除耗时=0ms 的样本后再计数与平均。
 *      - 识别 join / 子查询内的表（通过扫描 FROM/JOIN/INSERT INTO/UPDATE/DELETE FROM）。
 *
 *   B) 保留旧模式：
 *      --mode stats/where 仍可输出 CSV。
 *
 * 用法示例：
 *   javac LogAnalyzer.java
 *   java LogAnalyzer --workbook analysis.xls ./logs_dir_or_files
 *
 *   # 旧 CSV 模式：
 *   java LogAnalyzer --mode stats --out stats.csv ./logs
 *   java LogAnalyzer --mode where --out where.csv ./logs
 */
public class LogAnalyzer {
    // 旧 CSV 模式保留
    enum Mode { STATS, WHERE }

    private static final Pattern TIME_P   = Pattern.compile("\\[Time:\\s*(\\d+)\\s*ms\\]");
    private static final Pattern CALLER_P = Pattern.compile("\\[Caller:\\s*([^\\]]+)\\]");
    private static final Pattern SQL_P    = Pattern.compile("\\[SQL:\\s*(.+?)\\]\\s*$", Pattern.CASE_INSENSITIVE);

    // WHERE 子句提取：where 到下一个关键子句/结尾
    private static final Pattern WHERE_P = Pattern.compile(
            "(?is)\\bwhere\\b(.*?)(?=\\b(group\\s+by|order\\s+by|having|limit|offset|fetch|for\\s+update)\\b|$)"
    );

    // 多表统计用：操作与表提取
    private static final Pattern OP_P     = Pattern.compile("(?is)\\b(select|insert|update|delete)\\b");
    private static final Pattern FROM_P   = Pattern.compile("(?is)\\bfrom\\s+(?!\\()([\\w`\\\".$#]+)");
    private static final Pattern JOIN_P   = Pattern.compile("(?is)\\bjoin\\s+(?!\\()([\\w`\\\".$#]+)");
    private static final Pattern INSERT_P = Pattern.compile("(?is)\\binsert\\s+into\\s+([\\w`\\\".$#]+)");
    private static final Pattern UPDATE_P = Pattern.compile("(?is)\\bupdate\\s+([\\w`\\\".$#]+)");
    private static final Pattern DELETE_P = Pattern.compile("(?is)\\bdelete\\s+(?:\\w+\\s+)?from\\s+([\\w`\\\".$#]+)");

    // CLI 参数
    private static class Args {
        Mode mode;                 // 旧模式：stats/where
        Path out;                  // 旧模式输出 CSV
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        List<Path> inputs = new ArrayList<>();
        Path workbook;             // 新模式：多 sheet 工作簿输出（.xls）
    }

    // 统计结构
    enum Op { SELECT, INSERT, UPDATE, DELETE }

    private static class StatsAcc { // 仅用于“每表-每方法”统计，排除0ms
        final LongAdder count = new LongAdder();
        final LongAdder sumMs = new LongAdder();
        void add(long ms) { if (ms > 0) { count.increment(); sumMs.add(ms);} }
        long c() { return count.sum(); }
        long s() { return sumMs.sum(); }
    }

    public static void main(String[] args) throws Exception {
        Args a = parseArgs(args);
        if (a == null) { usage(); System.exit(1); }

        List<Path> files = collectFiles(a.inputs);
        if (files.isEmpty()) { System.err.println("未找到可处理的 .log / .log.gz 文件。"); System.exit(2); }

        System.out.printf("处理 %,d 个文件，线程=%d%n", files.size(), a.threads);

        if (a.workbook != null) {
            runWorkbook(files, a);
            return;
        }

        // 旧模式兼容
        if (a.mode == Mode.STATS && a.out != null) {
            runStatsCsv(files, a);
        } else if (a.mode == Mode.WHERE && a.out != null) {
            runWhereCsv(files, a);
        } else {
            usage(); System.exit(3);
        }
    }

    // ================= 新：生成多 sheet 工作簿（Summary + 每表） =================
    private static void runWorkbook(List<Path> files, Args a) throws Exception {
        // 1) 汇总结构
        Map<Op, ConcurrentHashMap<String, LongAdder>> usage = new EnumMap<>(Op.class);
        for (Op op : Op.values()) usage.put(op, new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<String, StatsAcc>> perTableCaller = new ConcurrentHashMap<>();

        // 2) 并行扫描
        processAll(files, a.threads, (caller, timeMs, sql) -> {
            Op op = detectOp(sql);
            if (op == null) return;
            List<String> tables = extractTables(sql, op);
            if (tables.isEmpty()) return;

            // 用量：不剔除0ms
            ConcurrentHashMap<String, LongAdder> bucket = usage.get(op);
            for (String t : tables) bucket.computeIfAbsent(t, k -> new LongAdder()).increment();

            // 每表-每方法：剔除0ms
            for (String t : tables) {
                perTableCaller
                        .computeIfAbsent(t, k -> new ConcurrentHashMap<>())
                        .computeIfAbsent(caller == null ? "<unknown>" : caller, k -> new StatsAcc())
                        .add(timeMs);
            }
        });

        // 3) 写 Excel 2003 XML（单文件多 sheet，无第三方库）
        try (BufferedWriter w = Files.newBufferedWriter(a.workbook, StandardCharsets.UTF_8)) {
            writeXmlHeader(w);
            writeStyles(w);
            writeSummarySheet(w, usage);
            writePerTableSheets(w, perTableCaller);
            w.write("</Workbook>\n");
        }
        System.out.println("已生成工作簿 => " + a.workbook.toAbsolutePath());
    }

    // ---- XML 写出 ----
    private static void writeXmlHeader(Writer w) throws IOException {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" "
                + "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n");
    }

    private static void writeStyles(Writer w) throws IOException {
        w.write("  <Styles>\n");
        w.write("    <Style ss:ID=\"header\"><Font ss:Bold=\"1\"/><Interior ss:Color=\"#D9D9D9\" ss:Pattern=\"Solid\"/></Style>\n");
        w.write("    <Style ss:ID=\"monospace\"><Font ss:FontName=\"Consolas\"/></Style>\n");
        w.write("  </Styles>\n");
    }

    private static void writeSummarySheet(Writer w, Map<Op, ConcurrentHashMap<String, LongAdder>> usage) throws IOException {
        w.write("  <Worksheet ss:Name=\"Summary\">\n    <Table>\n");
        for (Op op : Op.values()) {
            // 小标题
            writeRow(w, List.of(op.name() + " 表用量 (降序)") , true, true);
            // 表头
            writeRow(w, List.of("table", "usage_count"), true, false);
            // 排序输出
            List<Map.Entry<String, Long>> rows = usage.get(op).entrySet().stream()
                    .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                    .sorted((a,b) -> Long.compare(b.getValue(), a.getValue()))
                    .toList();
            for (var e : rows) {
                writeRow(w, List.of(e.getKey(), String.valueOf(e.getValue())), false, false);
            }
            // 空行分隔
            writeEmptyRow(w);
        }
        w.write("    </Table>\n  </Worksheet>\n");
    }

    private static void writePerTableSheets(Writer w, ConcurrentHashMap<String, ConcurrentHashMap<String, StatsAcc>> perTableCaller) throws IOException {
        // 对表名排序，稳定可读
        List<String> tables = new ArrayList<>(perTableCaller.keySet());
        Collections.sort(tables);
        // 为避免重复或超长 sheet 名，做去非法字符+截断+去重
        Set<String> usedNames = new HashSet<>();

        for (String table : tables) {
            String sheetName = safeSheetName(table, usedNames);
            usedNames.add(sheetName);

            w.write("  <Worksheet ss:Name=\"" + xml(sheetName) + "\">\n    <Table>\n");
            // 标题 & 表头
            writeRow(w, List.of("Table:", table), true, false);
            writeRow(w, List.of("caller", "count", "avg_ms"), true, false);

            // 汇总：仅保留 count>0（已自动剔除0ms）
            var callerMap = perTableCaller.get(table);
            List<RowAgg> rows = new ArrayList<>();
            for (var e : callerMap.entrySet()) {
                long c = e.getValue().c();
                if (c <= 0) continue; // 全是0ms的被剔除
                long s = e.getValue().s();
                double avg = (double) s / c;
                rows.add(new RowAgg(e.getKey(), c, avg));
            }
            rows.sort((a,b) -> Long.compare(b.count, a.count));

            for (RowAgg r : rows) {
                writeRow(w, List.of(r.caller, String.valueOf(r.count), fmt3(r.avgMs)), false, false);
            }

            w.write("    </Table>\n  </Worksheet>\n");
        }
    }

    private static class RowAgg { String caller; long count; double avgMs; RowAgg(String c,long n,double a){caller=c;count=n;avgMs=a;} }

    private static void writeRow(Writer w, List<String> cells, boolean header, boolean monospace) throws IOException {
        w.write("      <Row>\n");
        for (String v : cells) {
            String style = header ? " ss:StyleID=\"header\"" : (monospace ? " ss:StyleID=\"monospace\"" : "");
            // 简单判断数字
            if (!header && isNumeric(v)) {
                w.write("        <Cell" + style + "><Data ss:Type=\"Number\">" + xml(v) + "</Data></Cell>\n");
            } else {
                w.write("        <Cell" + style + "><Data ss:Type=\"String\">" + xml(v) + "</Data></Cell>\n");
            }
        }
        w.write("      </Row>\n");
    }

    private static void writeEmptyRow(Writer w) throws IOException { w.write("      <Row/>\n"); }

    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i=0;i<s.length();i++){char c=s.charAt(i); if(!((c>='0'&&c<='9')||c=='.'||c=='-'||c=='+')) return false;} return true;
    }

    private static String fmt3(double d){ return String.format(Locale.ROOT, "%.3f", d); }

    private static String safeSheetName(String table, Set<String> used){
        String name = table.replaceAll("[\\\\/\\?\\*\\[\\]:]", "_");
        if (name.length()>31) name = name.substring(0,31);
        String base = name; int idx=1;
        while (used.contains(name)) { String suf = "_" + idx++; int limit = Math.max(1, 31 - suf.length()); name = base.substring(0, Math.min(base.length(), limit)) + suf; }
        return name;
    }

    // ================= 文件扫描与解析（通用） =================
    @FunctionalInterface interface TriConsumer<A,B,C>{ void accept(A a,B b,C c); }

    private static void processAll(List<Path> files, int threads, TriConsumer<String, Long, String> handler) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Path f : files) {
                futures.add(pool.submit(() -> {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(openMaybeGzip(f), StandardCharsets.UTF_8), 1<<20)) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            if (!line.contains("[SQL:")) continue;
                            String sql = matchGroup(line, SQL_P, 1);
                            if (sql == null) continue;
                            String caller = matchGroup(line, CALLER_P, 1);
                            Long timeMs = 0L; Matcher t = TIME_P.matcher(line); if (t.find()) { try{ timeMs = Long.parseLong(t.group(1)); }catch(NumberFormatException ignored){} }
                            handler.accept(caller==null?"<unknown>":caller, timeMs, sql);
                        }
                    } catch (IOException e) { System.err.println("读取失败: " + f + " -> " + e.getMessage()); }
                }));
            }
            for (Future<?> fu : futures) try { fu.get(); } catch (ExecutionException | InterruptedException ex) { throw new RuntimeException(ex); }
        } finally { pool.shutdown(); }
    }

    private static Op detectOp(String sql){
        Matcher m = OP_P.matcher(sql);
        if (m.find()) {
            String op = m.group(1).toLowerCase(Locale.ROOT);
            return switch (op){ case "select" -> Op.SELECT; case "insert" -> Op.INSERT; case "update" -> Op.UPDATE; case "delete" -> Op.DELETE; default -> null; };
        }
        return null;
    }

    private static List<String> extractTables(String sql, Op op){
        Set<String> set = new LinkedHashSet<>();
        switch (op){
            case SELECT -> { findAll(FROM_P, sql, set); findAll(JOIN_P, sql, set); }
            case INSERT -> { findAll(INSERT_P, sql, set); }
            case UPDATE -> { findAll(UPDATE_P, sql, set); }
            case DELETE -> { findAll(DELETE_P, sql, set); }
        }
        // 归一化表名：取最后一段 schema.table -> table；去引号/反引号；转小写；去别名
        return set.stream().map(LogAnalyzer::normalizeTable).filter(s -> !s.isEmpty()).toList();
    }

    private static void findAll(Pattern p, String sql, Set<String> out){
        Matcher m = p.matcher(sql);
        while (m.find()) {
            String raw = m.group(1);
            if (raw == null) continue;
            raw = raw.trim();
            if (raw.startsWith("(")) continue; // 派生表/子查询
            out.add(raw);
        }
    }

    private static String normalizeTable(String raw){
        String s = raw.trim();
        // 去掉尾随逗号/分号
        s = s.replaceAll("[,;].*$", "");
        // 去引号/反引号
        s = s.replaceAll("^[`\\\"]|[`\\\"]$", "");
        // schema.table -> 取最后一段
        int i = s.lastIndexOf('.'); if (i>=0 && i < s.length()-1) s = s.substring(i+1);
        // 去别名衔接：table alias -> 只取 table
        s = s.replaceAll("[\\t\\n\\r ]+.*$", "");
        return s.toLowerCase(Locale.ROOT);
    }

    private static InputStream openMaybeGzip(Path p) throws IOException {
        InputStream in = Files.newInputStream(p);
        if (p.toString().toLowerCase(Locale.ROOT).endsWith(".gz")) return new GZIPInputStream(in, 1<<16);
        return in;
    }

    private static String matchGroup(String text, Pattern p, int idx) {
        Matcher m = p.matcher(text); return m.find()? m.group(idx) : null;
    }

    private static List<Path> collectFiles(List<Path> ins) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path in : ins) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> st = Files.walk(in)) {
                    st.filter(p -> !Files.isDirectory(p)).filter(LogAnalyzer::isLogLike).forEach(out::add);
                }
            } else if (Files.isRegularFile(in) && isLogLike(in)) { out.add(in); }
        }
        return out;
    }

    private static boolean isLogLike(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".log") || n.endsWith(".log.gz") || n.endsWith(".gz");
    }

    // 旧 CSV 模式（原版）
    private static void runStatsCsv(List<Path> files, Args a) throws Exception {
        ConcurrentHashMap<String, LongAdder> countMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, LongAdder> sumMap = new ConcurrentHashMap<>();
        processAll(files, a.threads, (caller, timeMs, sql) -> {
            countMap.computeIfAbsent(caller, k -> new LongAdder()).increment();
            sumMap.computeIfAbsent(caller, k -> new LongAdder()).add(timeMs);
        });
        try (BufferedWriter w = Files.newBufferedWriter(a.out, StandardCharsets.UTF_8)) {
            w.write("caller,count,avg_ms\n");
            for (String caller : countMap.keySet()) {
                long c = countMap.get(caller).sum(); long s = sumMap.getOrDefault(caller, new LongAdder()).sum();
                double avg = c==0?0:(double)s/c;
                w.write(csv(caller)+","+c+","+String.format(Locale.ROOT,"%.3f",avg)+"\n");
            }
        }
        System.out.println("统计完成 => " + a.out.toAbsolutePath());
    }

    private static void runWhereCsv(List<Path> files, Args a) throws Exception {
        ConcurrentLinkedQueue<String[]> rows = new ConcurrentLinkedQueue<>();
        processAll(files, a.threads, (caller, timeMs, sql) -> {
            String where = extractWhere(sql);
            if (where != null && !where.isBlank()) rows.add(new String[]{caller, normalizeSpace(where)});
        });
        try (BufferedWriter w = Files.newBufferedWriter(a.out, StandardCharsets.UTF_8)) {
            w.write("caller,where_clause\n");
            for (String[] r : rows) w.write(csv(r[0])+","+csv(r[1])+"\n");
        }
        System.out.println("WHERE 提取完成 => " + a.out.toAbsolutePath());
    }

    private static String extractWhere(String sql) { Matcher m = WHERE_P.matcher(sql); return m.find()? m.group(1) : null; }
    private static String normalizeSpace(String s) { return s.replaceAll("\\s+", " ").trim(); }
    private static String csv(String v){ if (v==null) return ""; String s=v.replace("\r"," ").replace("\n"," "); if(s.contains(",")||s.contains("\"")||s.contains("\n")){ s=s.replace("\"","\"\""); return "\""+s+"\"";} return s; }

    // 参数/帮助
    private static Args parseArgs(String[] args){
        Args a = new Args();
        for (int i=0;i<args.length;i++){
            String s=args[i];
            switch (s){
                case "--mode"     -> { if (i+1>=args.length) return null; String m=args[++i].toLowerCase(Locale.ROOT); a.mode = switch (m){ case "stats"->Mode.STATS; case "where"->Mode.WHERE; default->null; }; }
                case "--out"      -> { if (i+1>=args.length) return null; a.out=Paths.get(args[++i]); }
                case "--threads"  -> { if (i+1>=args.length) return null; a.threads=Integer.parseInt(args[++i]); }
                case "--workbook" -> { if (i+1>=args.length) return null; a.workbook=Paths.get(args[++i]); }
                default           -> a.inputs.add(Paths.get(s));
            }
        }
        // 新模式：--workbook 优先；旧模式需 mode+out
        if (a.workbook != null && !a.inputs.isEmpty()) return a;
        if (a.mode != null && a.out != null && !a.inputs.isEmpty()) return a;
        return null;
    }

    private static void usage(){
        System.out.println(
            "用法：\n"+
            "  # 生成多 sheet 工作簿（Summary + 每表）：\n"+
            "  java LogAnalyzer --workbook analysis.xls [--threads N] <文件或目录...>\n\n"+
            "  # 旧 CSV 模式：\n"+
            "  java LogAnalyzer --mode stats --out stats.csv [--threads N] <文件或目录...>\n"+
            "  java LogAnalyzer --mode where --out where.csv [--threads N] <文件或目录...>\n"
        );
    }
}
