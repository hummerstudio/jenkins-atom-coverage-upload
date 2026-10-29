package com.jenkinschina.atom.coverage;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Coverage as an atom: read JaCoCo or Cobertura XML, show the numbers, and gate the build on a threshold.
 *
 * <p>The gate is the point: a coverage report nobody reads changes nothing. Thresholds default to off, so an
 * existing job that just wants the number is not broken by an atom upgrade.
 */
public class CoverageUploadAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String pattern = context.getParam("pattern", "**/jacoco.xml, **/cobertura-coverage.xml, **/coverage.xml");
        String label = context.getParam("label", "Coverage");
        String outDir = context.getParam("outputDir", "out/coverage");
        double minLine = toDouble(context.getParam("minLine", "0"), 0);
        double minBranch = toDouble(context.getParam("minBranch", "0"), 0);

        List<Path> files = find(workspace, pattern);
        if (files.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40001, "no coverage report matched " + pattern);
        }

        Totals totals = new Totals();
        List<Row> rows = new ArrayList<>();
        for (Path file : files) {
            Row row = parse(file);
            if (row == null) {
                totals.unreadable++;
                continue;
            }
            rows.add(row);
            totals.lineCovered += row.lineCovered;
            totals.lineMissed += row.lineMissed;
            totals.branchCovered += row.branchCovered;
            totals.branchMissed += row.branchMissed;
        }
        if (rows.isEmpty()) {
            return AtomResult.error(ErrorType.USER, 40002, "no readable coverage report in " + pattern);
        }

        double linePercent = totals.linePercent();
        double branchPercent = totals.branchPercent();
        Path dir = workspace.resolve(outDir);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), render(label, pattern, totals, rows, linePercent, branchPercent),
                StandardCharsets.UTF_8);

        context.log(String.format(Locale.ROOT, "[coverage] line %.2f%% (%d/%d), branch %.2f%% (%d/%d) from %d file(s)",
                linePercent, totals.lineCovered, totals.lineCovered + totals.lineMissed,
                branchPercent, totals.branchCovered, totals.branchCovered + totals.branchMissed, rows.size()));

        AtomResult result = AtomResult.success()
                .data("lineCoverage", String.format(Locale.ROOT, "%.2f", linePercent))
                .data("branchCoverage", String.format(Locale.ROOT, "%.2f", branchPercent))
                .data("files", String.valueOf(rows.size()))
                .report("report", label, dir.toString(), "index.html");

        if (linePercent + 1e-9 < minLine || branchPercent + 1e-9 < minBranch) {
            return AtomResult.failure(String.format(Locale.ROOT,
                    "coverage below the threshold: line %.2f%% (min %.2f%%), branch %.2f%% (min %.2f%%)",
                    linePercent, minLine, branchPercent, minBranch))
                    .data("lineCoverage", String.format(Locale.ROOT, "%.2f", linePercent))
                    .data("branchCoverage", String.format(Locale.ROOT, "%.2f", branchPercent))
                    .report("report", label, dir.toString(), "index.html");
        }
        return result;
    }

    private static List<Path> find(Path workspace, String pattern) throws IOException {
        List<PathMatcher> matchers = new ArrayList<>();
        for (String part : pattern.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:"
                        + (trimmed.contains("/") ? trimmed : "**/" + trimmed)));
            }
        }
        List<Path> found = new ArrayList<>();
        try (var walk = Files.walk(workspace, 12)) {
            walk.filter(Files::isRegularFile)
                    .filter(path -> matchers.stream().anyMatch(m -> m.matches(workspace.relativize(path))
                            || m.matches(path.getFileName())))
                    .sorted()
                    .limit(500)
                    .forEach(found::add);
        }
        return found;
    }

    /** One report file. JaCoCo counts counters; Cobertura states rates on the root element. */
    private static Row parse(Path file) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(file.toFile());
            Element root = document.getDocumentElement();
            if (root == null) {
                return null;
            }
            Row row = new Row(file.getFileName().toString());

            if ("report".equals(root.getTagName())) { // JaCoCo
                NodeList counters = root.getElementsByTagName("counter");
                for (int i = 0; i < counters.getLength(); i++) {
                    Element counter = (Element) counters.item(i);
                    if (!"report".equals(counter.getParentNode().getNodeName())) {
                        continue; // only the report-level counters; the nested ones are per class
                    }
                    switch (counter.getAttribute("type")) {
                        case "LINE" -> {
                            row.lineMissed = toInt(counter.getAttribute("missed"));
                            row.lineCovered = toInt(counter.getAttribute("covered"));
                        }
                        case "BRANCH" -> {
                            row.branchMissed = toInt(counter.getAttribute("missed"));
                            row.branchCovered = toInt(counter.getAttribute("covered"));
                        }
                        default -> { /* METHOD/CLASS/INSTRUCTION are not what we gate on */ }
                    }
                }
                return row;
            }

            if ("coverage".equals(root.getTagName())) { // Cobertura
                double lineRate = toDouble(root.getAttribute("line-rate"), -1);
                double branchRate = toDouble(root.getAttribute("branch-rate"), -1);
                if (lineRate >= 0) {
                    row.lineCovered = (int) Math.round(lineRate * 10000);
                    row.lineMissed = 10000 - row.lineCovered;
                }
                if (branchRate >= 0) {
                    row.branchCovered = (int) Math.round(branchRate * 10000);
                    row.branchMissed = 10000 - row.branchCovered;
                }
                return row.lineCovered + row.lineMissed == 0 && row.branchCovered + row.branchMissed == 0
                        ? null : row;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String render(String label, String pattern, Totals totals, List<Row> rows,
                                 double line, double branch) {
        StringBuilder html = new StringBuilder("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\">");
        html.append("<title>").append(escape(label)).append("</title><style>")
                .append("body{font-family:-apple-system,'Segoe UI',sans-serif;margin:0;padding:28px;background:#f6f7f9;color:#1f2328}")
                .append("h1{font-size:22px;margin:0 0 4px}p.meta{color:#6b7280;font-size:13px;margin:0 0 20px}")
                .append(".cards{display:flex;gap:10px;flex-wrap:wrap;margin-bottom:22px}")
                .append(".card{background:#fff;border:1px solid #e5e7eb;border-radius:10px;padding:14px 18px;min-width:130px}")
                .append(".card b{display:block;font-size:26px;line-height:1.2}.card span{font-size:12px;color:#6b7280}")
                .append("table{width:100%;border-collapse:collapse;background:#fff;border:1px solid #e5e7eb}")
                .append("th,td{text-align:left;padding:9px 12px;border-bottom:1px solid #eef0f3;font-size:13px}")
                .append("th{background:#fafbfc;color:#6b7280;font-weight:600}td.n{text-align:right;font-variant-numeric:tabular-nums}")
                .append("</style></head><body>");
        html.append("<h1>").append(escape(label)).append("</h1><p class=\"meta\">")
                .append(rows.size()).append(" report(s) matched <code>").append(escape(pattern)).append("</code></p>");
        html.append("<div class=\"cards\"><div class=\"card\"><b>").append(String.format(Locale.ROOT, "%.2f%%", line))
                .append("</b><span>lines</span></div><div class=\"card\"><b>")
                .append(String.format(Locale.ROOT, "%.2f%%", branch))
                .append("</b><span>branches</span></div></div>");
        html.append("<table><tr><th>Report</th><th class=\"n\">Lines</th><th class=\"n\">Branches</th></tr>");
        for (Row row : rows) {
            html.append("<tr><td>").append(escape(row.name)).append("</td><td class=\"n\">")
                    .append(String.format(Locale.ROOT, "%.2f%%", row.linePercent())).append("</td><td class=\"n\">")
                    .append(row.branchPercent() < 0 ? "—" : String.format(Locale.ROOT, "%.2f%%", row.branchPercent()))
                    .append("</td></tr>");
        }
        html.append("</table></body></html>");
        return html.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static int toInt(String value) {
        try {
            return value == null || value.isBlank() ? 0 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double toDouble(String value, double fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class Totals {
        long lineCovered;
        long lineMissed;
        long branchCovered;
        long branchMissed;
        int unreadable;

        double linePercent() {
            long total = lineCovered + lineMissed;
            return total == 0 ? 0 : 100.0 * lineCovered / total;
        }

        double branchPercent() {
            long total = branchCovered + branchMissed;
            return total == 0 ? -1 : 100.0 * branchCovered / total;
        }
    }

    private static final class Row {
        final String name;
        long lineCovered;
        long lineMissed;
        long branchCovered;
        long branchMissed;

        Row(String name) {
            this.name = name;
        }

        double linePercent() {
            long total = lineCovered + lineMissed;
            return total == 0 ? 0 : 100.0 * lineCovered / total;
        }

        double branchPercent() {
            long total = branchCovered + branchMissed;
            return total == 0 ? -1 : 100.0 * branchCovered / total;
        }
    }
}
