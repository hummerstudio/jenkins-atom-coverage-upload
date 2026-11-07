package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The coverage-upload atom, started the way the runner starts it: a report in the workspace, percentages and a
 * threshold out, plus the HTML the next step publishes.
 *
 * <p>These are the checks the old {@code tools/smoke.sh} made for every format the atom reads.
 */
class CoverageUploadIT {

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("JaCoCo counters give the percentages, and the HTML report shows the number")
    void readsJacocoCounters() throws Exception {
        AtomResult run = atom.file("target/site/jacoco/jacoco.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                        + "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\">\n"
                        + "<report name=\"parcel\">\n"
                        + "  <package name=\"demo\"><class name=\"demo/A\">"
                        + "<counter type=\"LINE\" missed=\"10\" covered=\"30\"/></class></package>\n"
                        + "  <counter type=\"LINE\" missed=\"10\" covered=\"30\"/>\n"
                        + "  <counter type=\"BRANCH\" missed=\"0\" covered=\"4\"/>\n"
                        + "</report>\n")
                .input("{\"pattern\": \"**/jacoco.xml\", \"label\": \"Coverage\", \"minLine\": \"50\","
                        + " \"minBranch\": \"50\"}")
                .run();

        run.assertOk();
        assertEquals("success", run.status());
        assertEquals("75.00", run.field("lineCoverage"));
        assertEquals("100.00", run.field("branchCoverage"));
        String html = run.file("out/coverage/index.html");
        assertTrue(html != null, "no HTML report");
        assertTrue(html.contains("75.00%"), "report does not show the number");
    }

    @Test
    @DisplayName("a threshold failure fails the step, and the report is still published")
    void failsBelowTheThreshold() throws Exception {
        AtomResult run = atom.file("reports/jacoco.xml",
                        "<report name=\"x\"><counter type=\"LINE\" missed=\"40\" covered=\"60\"/>"
                        + "<counter type=\"BRANCH\" missed=\"0\" covered=\"0\"/></report>\n")
                .input("{\"pattern\": \"reports/jacoco.xml\", \"minLine\": \"80\"}")
                .run();

        run.assertRefused();
        assertEquals("failure", run.status());
        assertTrue(run.exists("out/coverage/index.html"), "the report must still be published on failure");
    }

    @Test
    @DisplayName("Cobertura rates are read from the root element")
    void readsCoberturaRates() throws Exception {
        AtomResult run = atom.file("coverage/cobertura-coverage.xml",
                        "<!DOCTYPE coverage SYSTEM \"http://cobertura.sourceforge.net/xml/coverage-04.dtd\">\n"
                        + "<coverage line-rate=\"0.8\" branch-rate=\"0.5\" version=\"1\"><packages/></coverage>\n")
                .input("{\"pattern\": \"**/cobertura-coverage.xml\", \"minLine\": \"70\"}")
                .run();

        run.assertOk();
        assertEquals("80.00", run.field("lineCoverage"));
        assertEquals("50.00", run.field("branchCoverage"));
    }

    @Test
    @DisplayName("no report at all is a clear error")
    void refusesWithoutAReport() throws Exception {
        AtomResult run = atom.input("{\"pattern\": \"**/jacoco.xml\"}").run();

        run.assertRefused();
        assertTrue(run.file("output.json").contains("no coverage report matched"), "message not readable");
    }

    @Test
    @DisplayName("lcov records are summed, whatever order LF/LH come in")
    void readsLcov() throws Exception {
        AtomResult run = atom.file("build/coverage/lcov.info",
                        "TN:\nSF:src/calc.c\nLF:10\nLH:8\nBRF:4\nBRH:2\nend_of_record\n"
                        + "TN:\nSF:src/main.c\nLH:5\nLF:5\nBRF:0\nBRH:0\nend_of_record\n")
                .input("{\"pattern\": \"**/lcov.info\"}")
                .run();

        run.assertOk();
        assertEquals("86.67", run.field("lineCoverage"), "lcov line coverage must be 13/15");
        assertEquals("50.00", run.field("branchCoverage"));
    }

    @Test
    @DisplayName("Sonar generic format is counted per line, and the gate still applies")
    void readsSonarGeneric() throws Exception {
        Atom atom = this.atom.file("generic-coverage.xml",
                "<coverage version=\"1\">\n"
                + "  <file path=\"src/app.py\">\n"
                + "    <lineToCover lineNumber=\"1\" covered=\"true\"/>\n"
                + "    <lineToCover lineNumber=\"2\" covered=\"true\" branchesToCover=\"2\" coveredBranches=\"1\"/>\n"
                + "    <lineToCover lineNumber=\"3\" covered=\"false\"/>\n"
                + "    <lineToCover lineNumber=\"4\" covered=\"false\" branchesToCover=\"2\" coveredBranches=\"0\"/>\n"
                + "  </file>\n"
                + "</coverage>\n");

        AtomResult gated = atom.input("{\"pattern\": \"**/generic-coverage.xml\", \"minLine\": \"60\"}").run();
        gated.assertRefused();
        assertTrue(gated.file("output.json").contains("below the threshold"), "gate message:\n" + gated.file("output.json"));

        AtomResult run = atom.input("{\"pattern\": \"**/generic-coverage.xml\"}").run();
        run.assertOk();
        assertEquals("50.00", run.field("lineCoverage"));
        assertEquals("25.00", run.field("branchCoverage"));
    }

    @Test
    @DisplayName("a truncated report fails by default and is never averaged in, unless it is allowed")
    void refusesATruncatedReport() throws Exception {
        Atom atom = this.atom.file("jacoco.xml", "<report name=\"good\"><counter type=\"LINE\" missed=\"0\" covered=\"10\"/></report>")
                .file("jacoco-old.xml", "<report name=\"half\"><counter type=\"LINE\" missed=\"0\"");

        AtomResult run = atom.input("{\"pattern\": \"*.xml\", \"minLine\": \"50\"}").run();
        run.assertRefused();
        assertEquals("40003", run.field("errorCode"), "expected errorCode 40003:\n" + run.file("output.json"));
        assertTrue(run.file("output.json").contains("jacoco-old.xml"), "the failure must name the file:\n" + run.file("output.json"));

        AtomResult tolerated = atom
                .input("{\"pattern\": \"*.xml\", \"minLine\": \"50\", \"allowUnreadableReports\": \"true\"}")
                .run();
        tolerated.assertOk();
    }

    @Test
    @DisplayName("Clover counts statements, and the per-file metrics are not the project total")
    void readsClover() throws Exception {
        AtomResult run = atom.file("clover.xml",
                        "<coverage generated=\"1793600000000\" clover=\"4.5.2\">\n"
                        + "  <project timestamp=\"1793600000000\" name=\"demo\">\n"
                        + "    <metrics statements=\"200\" coveredstatements=\"150\" conditionals=\"40\""
                        + " coveredconditionals=\"30\" methods=\"20\" coveredmethods=\"15\" elements=\"260\""
                        + " coveredelements=\"195\" complexity=\"0\"/>\n"
                        + "    <package name=\"demo\">\n"
                        + "      <file name=\"Calc.java\">\n"
                        + "        <metrics statements=\"10\" coveredstatements=\"1\" conditionals=\"2\""
                        + " coveredconditionals=\"0\"/>\n"
                        + "      </file>\n"
                        + "    </package>\n"
                        + "  </project>\n"
                        + "</coverage>\n")
                .input("{\"pattern\": \"*.xml\", \"minLine\": \"70\"}")
                .run();

        run.assertOk();
        assertEquals("75.00", run.field("lineCoverage"));
        assertEquals("75.00", run.field("branchCoverage"));
        // the per-file <metrics> must not be mistaken for the project total
        assertTrue(run.file("output.json").contains("\"lineCoverage\":{\"type\":\"string\",\"value\":\"75.00\"}"),
                "a file-level metrics node was used as the total:\n" + run.file("output.json"));
    }
}
