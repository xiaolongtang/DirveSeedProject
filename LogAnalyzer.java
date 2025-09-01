
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
 * 生成 Excel 2003 XML 工作簿（.xls）：
 *  - Summary：分 SELECT/INSERT/UPDATE/DELETE 四个区块，按表用量降序
 *  - PerTable：将原本“每表一个 sheet”的内容合并到一个 sheet 中：
 *      每个表作为一个分类块（先输出一行 "Table: <name>" 作为小标题，
 *      再输出表头：caller,count,avg_ms,max_ms,min_ms，随后是按 count 降序的行）
 *      统计均排除 0ms 样本（count/avg/max/min 均只基于 >0ms 的样本）
 *
 * 兼容旧 CSV：--mode stats/where 仍可用
 */
public class LogAnalyzer {
    enum Mode { STATS, WHERE }

    private static final Pattern TIME_P   = Pattern.compile("\\[Time:\\s*(\\d+)\\s*ms\\]");
    private static final Pattern CALLER_P = Pattern.compile("\\[Caller:\\s*([^\\]]+)\\]");
    private static final Pattern SQL_P    = Pattern.compile("\\[SQL:\\s*(.+?)\\]\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern WHERE_P = Pattern.compile(
            "(?is)\\bwhere\\b(.*?)(?=\\b(group\\s+by|order\\s+by|having|limit|offset|fetch|for\\s+update)\\b|$)"
    );

    private static final Pattern OP_P     = Pattern.compile("(?is)\\b(select|insert|update|delete)\\b");
    private static final Pattern FROM_P   = Pattern.compile("(?is)\\bfrom\\s+(?!\\()([\\w`\\\".$#]+)");
    private static final Pattern JOIN_P   = Pattern.compile("(?is)\\bjoin\\s+(?!\\()([\\w`\\\".$#]+)");
    private static final Pattern INSERT_P = Pattern.compile("(?is)\\binsert\\s+into\\s+([\\w`\\\".$#]+)");
    private static final Pattern UPDATE_P = Pattern.compile("(?is)\\bupdate\\s+([\\w`\\\".$#]+)");
    private static final Pattern DELETE_P = Pattern.compile("(?is)\\bdelete\\s+(?:\\w+\\s+)?from\\s+([\\w`\\\".$#]+)");

    private static class Args {
        Mode mode;
        Path out;
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        List<Path> inputs = new ArrayList<>();
        Path workbook;
    }

    enum Op { SELECT, INSERT, UPDATE, DELETE }

    private static class StatsAcc {
        final LongAdder count = new LongAdder();
        final LongAdder sumMs = new LongAdder();
        long minMs = Long.MAX_VALUE;
        long maxMs = Long.MIN_VALUE;
        void add(long ms) {
            if (ms > 0) {
                count.increment();
                sumMs.add(ms);
                if (ms < minMs) minMs = ms;
                if (ms > maxMs) maxMs = ms;
            }
        }
        long c(){ return count.sum(); }
        long s(){ return sumMs.sum(); }
        long min(){ return c()>0 ? minMs : 0L; }
        long max(){ return c()>0 ? maxMs : 0L; }
    }

    public static void main(String[] args) throws Exception {
        Args a = parseArgs(args);
        if (a == null) { usage(); System.exit(1); }
        List<Path> files = collectFiles(a.inputs);
        if (files.isEmpty()) { System.err.println("未找到可处理的 .log / .log.gz 文件。"); System.exit(2); }
        System.out.printf("处理 %,d 个文件，线程=%d%n", files.size(), a.threads);

        if (a.workbook != null) { runWorkbook(files, a); return; }
        if (a.mode == Mode.STATS && a.out != null) { runStatsCsv(files, a); return; }
        if (a.mode == Mode.WHERE && a.out != null) { runWhereCsv(files, a); return; }
        usage(); System.exit(3);
    }

    // ===== 生成工作簿 =====
    private static void runWorkbook(List<Path> files, Args a) throws Exception {
        Map<Op, ConcurrentHashMap<String, LongAdder>> usage = new EnumMap<>(Op.class);
        for (Op op : Op.values()) usage.put(op, new ConcurrentHashMap<>());
        ConcurrentHashMap<String, ConcurrentHashMap<String, StatsAcc>> perTableCaller = new ConcurrentHashMap<>();

        processAll(files, a.threads, (caller, timeMs, sql) -> {
            Op op = detectOp(sql);
            if (op == null) return;
            List<String> tables = extractTables(sql, op);
            if (tables.isEmpty()) return;
            ConcurrentHashMap<String, LongAdder> bucket = usage.get(op);
            for (String t : tables) bucket.computeIfAbsent(t, k -> new LongAdder()).increment();
            for (String t : tables) perTableCaller
                    .computeIfAbsent(t, k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(caller==null?"<unknown>":caller, k -> new StatsAcc())
                    .add(timeMs);
        });

        try (BufferedWriter w = Files.newBufferedWriter(a.workbook, StandardCharsets.UTF_8)) {
            writeXmlHeader(w);
            writeStyles(w);
            writeSummarySheet(w, usage);
            writeUnifiedPerTableSheet(w, perTableCaller);
            w.write("</Workbook>\n");
        }
        System.out.println("已生成工作簿 => " + a.workbook.toAbsolutePath());
    }

    private static void writeXmlHeader(Writer w) throws IOException {
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        w.write("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" "+
                "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n");
    }

    private static void writeStyles(Writer w) throws IOException {
        w.write("  <Styles>\n");
        w.write("    <Style ss:ID=\"header\"><Font ss:Bold=\"1\"/><Interior ss:Color=\"#D9D9D9\" ss:Pattern=\"Solid\"/></Style>\n");
        w.write("  </Styles>\n");
    }

    private static void writeSummarySheet(Writer w, Map<Op, ConcurrentHashMap<String, LongAdder>> usage) throws IOException {
        w.write("  <Worksheet ss:Name=\"Summary\">\n    <Table>\n");
        for (Op op : Op.values()) {
            writeRow(w, List.of(op.name() + " 表用量 (降序)"), true);
            writeRow(w, List.of("table", "usage_count"), true);
            List<Map.Entry<String, Long>> rows = usage.get(op).entrySet().stream()
                    .map(e -> Map.entry(e.getKey(), e.getValue().sum()))
                    .sorted((a,b) -> Long.compare(b.getValue(), a.getValue()))
                    .toList();
            for (var e : rows) writeRow(w, List.of(e.getKey(), String.valueOf(e.getValue())), false);
            writeEmptyRow(w);
        }
        w.write("    </Table>\n  </Worksheet>\n");
    }

    // 合并所有表到一个 sheet：PerTable
    private static void writeUnifiedPerTableSheet(Writer w,
            ConcurrentHashMap<String, ConcurrentHashMap<String, StatsAcc>> perTableCaller) throws IOException {
        List<String> tables = new ArrayList<>(perTableCaller.keySet());
        Collections.sort(tables);
        w.write("  <Worksheet ss:Name=\"PerTable\">\n    <Table>\n");
        for (String table : tables) {
            writeRow(w, List.of("Table:", table), true);
            writeRow(w, List.of("caller", "count", "avg_ms", "max_ms", "min_ms"), true);
            var callerMap = perTableCaller.get(table);
            List<RowAgg> rows = new ArrayList<>();
            for (var e : callerMap.entrySet()) {
                StatsAcc acc = e.getValue();
                long c = acc.c();
                if (c <= 0) continue; // 全是0ms则跳过
                double avg = (double) acc.s() / c;
                rows.add(new RowAgg(e.getKey(), c, avg, acc.max(), acc.min()));
            }
            rows.sort((a,b) -> Long.compare(b.count, a.count));
            for (RowAgg r : rows) {
                writeRow(w, List.of(r.caller, String.valueOf(r.count), fmt3(r.avgMs),
                        String.valueOf(r.maxMs), String.valueOf(r.minMs)), false);
            }
            writeEmptyRow(w);
        }
        w.write("    </Table>\n  </Worksheet>\n");
    }

    private static class RowAgg { String caller; long count; double avgMs; long maxMs; long minMs; RowAgg(String c,long n,double a,long mx,long mn){caller=c;count=n;avgMs=a;maxMs=mx;minMs=mn;} }

    private static void writeRow(Writer w, List<String> cells, boolean header) throws IOException {
        w.write("      <Row>\n");
        for (String v : cells) {
            if (!header && isNumeric(v)) {
                w.write("        <Cell><Data ss:Type=\"Number\">" + xml(v) + "</Data></Cell>\n");
            } else {
                w.write("        <Cell><Data ss:Type=\"String\">" + xml(v) + "</Data></Cell>\n");
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
        s = s.replaceAll("[,;].*$", "");
        s = s.replaceAll("^[`\\\"]|[`\\\"]$", "");
        int i = s.lastIndexOf('.'); if (i>=0 && i < s.length()-1) s = s.substring(i+1);
        s = s.replaceAll("[\\t\\n\\r ]+.*$", "");
        return s.toLowerCase(Locale.ROOT);
    }

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
            for (Future<?> fu : futures) { try { fu.get(); } catch (ExecutionException | InterruptedException ex) { throw new RuntimeException(ex); } }
        } finally { pool.shutdown(); }
    }

    private static InputStream openMaybeGzip(Path p) throws IOException {
        InputStream in = Files.newInputStream(p);
        if (p.toString().toLowerCase(Locale.ROOT).endsWith(".gz")) return new GZIPInputStream(in, 1<<16);
        return in;
    }

    private static String matchGroup(String text, Pattern p, int idx) { Matcher m = p.matcher(text); return m.find()? m.group(idx) : null; }

    private static List<Path> collectFiles(List<Path> ins) throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path in : ins) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> st = Files.walk(in)) { st.filter(p -> !Files.isDirectory(p)).filter(LogAnalyzer::isLogLike).forEach(out::add); }
            } else if (Files.isRegularFile(in) && isLogLike(in)) { out.add(in); }
        }
        return out;
    }

    private static boolean isLogLike(Path p) { String n = p.getFileName().toString().toLowerCase(Locale.ROOT); return n.endsWith(".log") || n.endsWith(".log.gz") || n.endsWith(".gz"); }

    private static void runStatsCsv(List<Path> files, Args a) throws Exception {
        ConcurrentHashMap<String, LongAdder> countMap = new ConcurrentHashMap<>();
        ConcurrentHashMap<String, LongAdder> sumMap = new ConcurrentHashMap<>();
        processAll(files, a.threads, (caller, timeMs, sql) -> { countMap.computeIfAbsent(caller, k -> new LongAdder()).increment(); sumMap.computeIfAbsent(caller, k -> new LongAdder()).add(timeMs); });
        try (BufferedWriter w = Files.newBufferedWriter(a.out, StandardCharsets.UTF_8)) { w.write("caller,count,avg_ms\n"); for (String caller : countMap.keySet()) { long c = countMap.get(caller).sum(); long s = sumMap.getOrDefault(caller, new LongAdder()).sum(); double avg = c==0?0:(double)s/c; w.write(csv(caller)+","+c+","+String.format(Locale.ROOT,"%.3f",avg)+"\n"); } }
        System.out.println("统计完成 => " + a.out.toAbsolutePath());
    }

    private static void runWhereCsv(List<Path> files, Args a) throws Exception {
        ConcurrentLinkedQueue<String[]> rows = new ConcurrentLinkedQueue<>();
        processAll(files, a.threads, (caller, timeMs, sql) -> { String where = extractWhere(sql); if (where != null && !where.isBlank()) rows.add(new String[]{caller, normalizeSpace(where)}); });
        try (BufferedWriter w = Files.newBufferedWriter(a.out, StandardCharsets.UTF_8)) { w.write("caller,where_clause\n"); for (String[] r : rows) w.write(csv(r[0])+","+csv(r[1])+"\n"); }
        System.out.println("WHERE 提取完成 => " + a.out.toAbsolutePath());
    }

    private static String extractWhere(String sql) { Matcher m = WHERE_P.matcher(sql); return m.find()? m.group(1) : null; }
    private static String normalizeSpace(String s) { return s.replaceAll("\\s+", " ").trim(); }
    private static String csv(String v){ if (v==null) return ""; String s=v.replace("\r"," ").replace("\n"," "); if(s.contains(",")||s.contains("\"")||s.contains("\n")){ s=s.replace("\"","\"\""); return "\""+s+"\"";} return s; }

    private static Args parseArgs(String[] args){ Args a = new Args(); for (int i=0;i<args.length;i++){ String s=args[i]; switch (s){ case "--mode" -> { if (i+1>=args.length) return null; String m=args[++i].toLowerCase(Locale.ROOT); a.mode = switch (m){ case "stats"->Mode.STATS; case "where"->Mode.WHERE; default->null; }; } case "--out" -> { if (i+1>=args.length) return null; a.out=Paths.get(args[++i]); } case "--threads" -> { if (i+1>=args.length) return null; a.threads=Integer.parseInt(args[++i]); } case "--workbook" -> { if (i+1>=args.length) return null; a.workbook=Paths.get(args[++i]); } default -> a.inputs.add(Paths.get(s)); } } if (a.workbook != null && !a.inputs.isEmpty()) return a; if (a.mode != null && a.out != null && !a.inputs.isEmpty()) return a; return null; }

    private static void usage(){ System.out.println("用法：\n"+
            "  生成工作簿（Summary + PerTable）：\n"+
            "  java LogAnalyzer --workbook analysis.xls [--threads N] <文件或目录...>\n\n"+
            "  旧 CSV 模式：\n"+
            "  java LogAnalyzer --mode stats --out stats.csv [--threads N] <文件或目录...>\n"+
            "  java LogAnalyzer --mode where --out where.csv [--threads N] <文件或目录...>\n"); }
}
