import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Automated test suite for Price Alert Tool (Java).
 *
 * Runs all unit tests and subprocess CLI end-to-end tests using only standard Java.
 */
public class TestAlerts {

    private static int testsRun = 0;
    private static int testsPassed = 0;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        System.out.println("Running automated tests for Price Alert Tool (Java)...");

        // Input Validation Tests
        runTest("testLoadValidPricesAndChronologicalSort", TestAlerts::testLoadValidPricesAndChronologicalSort);
        runTest("testCsvFileNotFound", TestAlerts::testCsvFileNotFound);
        runTest("testCsvEmptyFile", TestAlerts::testCsvEmptyFile);
        runTest("testCsvMissingHeaderColumns", TestAlerts::testCsvMissingHeaderColumns);
        runTest("testCsvNoDataRows", TestAlerts::testCsvNoDataRows);
        runTest("testCsvInvalidDateFormat", TestAlerts::testCsvInvalidDateFormat);
        runTest("testCsvNonNumericPrice", TestAlerts::testCsvNonNumericPrice);
        runTest("testCsvNegativePrice", TestAlerts::testCsvNegativePrice);
        runTest("testCsvDuplicateRecords", TestAlerts::testCsvDuplicateRecords);

        runTest("testLoadValidRules", TestAlerts::testLoadValidRules);
        runTest("testJsonFileNotFound", TestAlerts::testJsonFileNotFound);
        runTest("testJsonInvalidSyntax", TestAlerts::testJsonInvalidSyntax);
        runTest("testJsonNotAList", TestAlerts::testJsonNotAList);
        runTest("testRulesDuplicateId", TestAlerts::testRulesDuplicateId);
        runTest("testRulesInvalidTypeAndDirection", TestAlerts::testRulesInvalidTypeAndDirection);
        runTest("testRulesMaWindowValidation", TestAlerts::testRulesMaWindowValidation);

        // Level Crossing Tests
        runTest("testCrossAboveBasic", TestAlerts::testCrossAboveBasic);
        runTest("testCrossBelowBasic", TestAlerts::testCrossBelowBasic);
        runTest("testNoRepeatedAlertsWhileRemainingAboveOrBelow", TestAlerts::testNoRepeatedAlertsWhileRemainingAboveOrBelow);
        runTest("testEqualityTouchAndCross", TestAlerts::testEqualityTouchAndCross);
        runTest("testEqualityBounceDoesNotCross", TestAlerts::testEqualityBounceDoesNotCross);
        runTest("testFirstObservationNeverAlerts", TestAlerts::testFirstObservationNeverAlerts);

        // Moving Average Crossover Tests
        runTest("testInsufficientHistory", TestAlerts::testInsufficientHistory);
        runTest("testMaCrossoverAboveAndBelow", TestAlerts::testMaCrossoverAboveAndBelow);

        // Deterministic Sorting & Scenarios
        runTest("testEvaluateRulesDeterministicOrdering", TestAlerts::testEvaluateRulesDeterministicOrdering);
        runTest("testNoAlertsFire", TestAlerts::testNoAlertsFire);
        runTest("testUnsortedDatesAndMultipleInstrumentsEndToEnd", TestAlerts::testUnsortedDatesAndMultipleInstrumentsEndToEnd);

        // Subprocess CLI Tests
        runTest("testCliSampleExecution", TestAlerts::testCliSampleExecution);
        runTest("testCliUsageErrorOnMissingArgs", TestAlerts::testCliUsageErrorOnMissingArgs);
        runTest("testCliValidationErrorRoutedToStderr", TestAlerts::testCliValidationErrorRoutedToStderr);

        System.out.println("----------------------------------------------------------------------");
        System.out.println("Ran " + testsRun + " tests.");
        if (failures.isEmpty()) {
            System.out.println("OK");
            System.exit(0);
        } else {
            System.err.println("FAILED (" + failures.size() + " failures)");
            for (String f : failures) {
                System.err.println(" - " + f);
            }
            System.exit(1);
        }
    }

    private static void runTest(String testName, TestRunnable test) {
        testsRun++;
        try {
            test.run();
            testsPassed++;
            System.out.println(testName + " ... ok");
        } catch (Throwable t) {
            failures.add(testName + ": " + t.getMessage());
            System.err.println(testName + " ... FAILED");
            t.printStackTrace(System.err);
        }
    }

    @FunctionalInterface
    interface TestRunnable {
        void run() throws Throwable;
    }

    // =========================================================================
    // Assertions & Helpers
    // =========================================================================

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed: " + message + " (Expected: " + expected + ", Got: " + actual + ")");
    }

    private static File createTempFile(String prefix, String suffix, String content) throws IOException {
        File temp = File.createTempFile(prefix, suffix);
        temp.deleteOnExit();
        try (FileWriter writer = new FileWriter(temp)) {
            writer.write(content);
        }
        return temp;
    }

    // =========================================================================
    // 1. Input Validation Tests
    // =========================================================================

    private static void testLoadValidPricesAndChronologicalSort() throws Throwable {
        String csv = """
                date,instrument,close
                2026-01-03,AAPL,155.00
                2026-01-01,AAPL,140.00
                2026-01-02,AAPL,150.00
                """;
        File f = createTempFile("prices", ".csv", csv);
        Map<String, List<Alerts.PriceRecord>> records = Alerts.loadAndValidatePrices(f.getAbsolutePath());

        assertTrue(records.containsKey("AAPL"), "AAPL missing from records");
        List<Alerts.PriceRecord> aapl = records.get("AAPL");
        assertEquals(3, aapl.size(), "Expected 3 records");
        assertEquals(LocalDate.of(2026, 1, 1), aapl.get(0).date(), "Date sort 0");
        assertEquals(LocalDate.of(2026, 1, 2), aapl.get(1).date(), "Date sort 1");
        assertEquals(LocalDate.of(2026, 1, 3), aapl.get(2).date(), "Date sort 2");
        assertEquals(140.0, aapl.get(0).close(), "Price sort 0");
    }

    private static void testCsvFileNotFound() {
        try {
            Alerts.loadAndValidatePrices("non_existent_file.csv");
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("File not found"), "Error message check");
        }
    }

    private static void testCsvEmptyFile() throws Throwable {
        File f = createTempFile("empty", ".csv", "");
        try {
            Alerts.loadAndValidatePrices(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("is empty"), "Error message check");
        }
    }

    private static void testCsvMissingHeaderColumns() throws Throwable {
        File f = createTempFile("bad_header", ".csv", "date,close\n2026-01-01,100.0\n");
        try {
            Alerts.loadAndValidatePrices(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("missing required column"), "Error message check");
        }
    }

    private static void testCsvNoDataRows() throws Throwable {
        File f = createTempFile("no_data", ".csv", "date,instrument,close\n");
        try {
            Alerts.loadAndValidatePrices(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("contains no data rows"), "Error message check");
        }
    }

    private static void testCsvInvalidDateFormat() throws Throwable {
        File f = createTempFile("bad_date", ".csv", "date,instrument,close\n01-01-2026,AAPL,100\n");
        try {
            Alerts.loadAndValidatePrices(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("invalid date"), "Error message check");
        }
    }

    private static void testCsvNonNumericPrice() throws Throwable {
        File f = createTempFile("bad_price", ".csv", "date,instrument,close\n2026-01-01,AAPL,abc\n");
        try {
            Alerts.loadAndValidatePrices(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("invalid price"), "Error message check");
        }
    }

    private static void testCsvNegativePrice() throws Throwable {
        File f = createTempFile("neg_price", ".csv", "date,instrument,close\n2026-01-01,AAPL,-5.0\n");
        try {
            Alerts.loadAndValidatePrices(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("Must be a non-negative finite number"), "Error message check");
        }
    }

    private static void testCsvDuplicateRecords() throws Throwable {
        String csv = """
                date,instrument,close
                2026-01-01,AAPL,100.0
                2026-01-01,AAPL,105.0
                """;
        File f = createTempFile("duplicates", ".csv", csv);
        try {
            Alerts.loadAndValidatePrices(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("Duplicate record for instrument 'AAPL' on date '2026-01-01'"), "Error message check");
        }
    }

    private static void testLoadValidRules() throws Throwable {
        String json = """
                [
                    {"id": "R1", "instrument": "AAPL", "type": "level", "direction": "above", "threshold": 150.0},
                    {"id": "R2", "instrument": "MSFT", "type": "ma_cross", "direction": "below", "short_window": 5, "long_window": 20}
                ]
                """;
        File f = createTempFile("rules", ".json", json);
        List<Alerts.Rule> rules = Alerts.loadAndValidateRules(f.getAbsolutePath());
        assertEquals(2, rules.size(), "Rule count");
        assertEquals("R1", rules.get(0).id(), "R1 id");
        assertEquals(150.0, rules.get(0).threshold(), "R1 threshold");
        assertEquals("R2", rules.get(1).id(), "R2 id");
        assertEquals(5, rules.get(1).shortWindow(), "R2 short window");
        assertEquals(20, rules.get(1).longWindow(), "R2 long window");
    }

    private static void testJsonFileNotFound() {
        try {
            Alerts.loadAndValidateRules("missing_rules.json");
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("File not found"), "Error message check");
        }
    }

    private static void testJsonInvalidSyntax() throws Throwable {
        File f = createTempFile("bad_json", ".json", "[{bad json");
        try {
            Alerts.loadAndValidateRules(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("Invalid JSON syntax"), "Error message check");
        }
    }

    private static void testJsonNotAList() throws Throwable {
        File f = createTempFile("not_list", ".json", "{\"id\": \"R1\"}");
        try {
            Alerts.loadAndValidateRules(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("must be a JSON array"), "Error message check");
        }
    }

    private static void testRulesDuplicateId() throws Throwable {
        String json = """
                [
                    {"id": "R1", "instrument": "AAPL", "type": "level", "direction": "above", "threshold": 100},
                    {"id": "R1", "instrument": "GOOG", "type": "level", "direction": "below", "threshold": 200}
                ]
                """;
        File f = createTempFile("dup_id", ".json", json);
        try {
            Alerts.loadAndValidateRules(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("Duplicate rule ID 'R1'"), "Error message check");
        }
    }

    private static void testRulesInvalidTypeAndDirection() throws Throwable {
        String badType = "[{\"id\": \"R1\", \"instrument\": \"AAPL\", \"type\": \"rsi\", \"direction\": \"above\", \"threshold\": 50}]";
        File f1 = createTempFile("bad_type", ".json", badType);
        try {
            Alerts.loadAndValidateRules(f1.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("invalid type 'rsi'"), "Error message check");
        }

        String badDir = "[{\"id\": \"R2\", \"instrument\": \"AAPL\", \"type\": \"level\", \"direction\": \"sideways\", \"threshold\": 50}]";
        File f2 = createTempFile("bad_dir", ".json", badDir);
        try {
            Alerts.loadAndValidateRules(f2.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("invalid direction 'sideways'"), "Error message check");
        }
    }

    private static void testRulesMaWindowValidation() throws Throwable {
        String badWin = "[{\"id\": \"R1\", \"instrument\": \"AAPL\", \"type\": \"ma_cross\", \"direction\": \"above\", \"short_window\": 10, \"long_window\": 5}]";
        File f = createTempFile("bad_win", ".json", badWin);
        try {
            Alerts.loadAndValidateRules(f.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("short_window (10) must be strictly less than long_window (5)"), "Error message check");
        }

        String boolWin = "[{\"id\": \"R2\", \"instrument\": \"AAPL\", \"type\": \"ma_cross\", \"direction\": \"above\", \"short_window\": true, \"long_window\": 10}]";
        File f2 = createTempFile("bool_win", ".json", boolWin);
        try {
            Alerts.loadAndValidateRules(f2.getAbsolutePath());
            throw new AssertionError("Expected ValidationException");
        } catch (Alerts.ValidationException e) {
            assertTrue(e.getMessage().contains("must be a positive integer"), "Error message check");
        }
    }

    // =========================================================================
    // 2. Level Crossing Tests
    // =========================================================================

    private static void testCrossAboveBasic() {
        Alerts.Rule rule = new Alerts.Rule("R1", "AAPL", "level", "above", 100.0, null, null);
        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 90.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 110.0)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateLevelRule(rule, prices);
        assertEquals(1, alerts.size(), "Alert count");
        assertEquals(LocalDate.of(2026, 1, 2), alerts.get(0).date(), "Alert date");
        assertEquals("R1", alerts.get(0).ruleId(), "Rule ID");
        assertTrue(alerts.get(0).description().contains("crossed above threshold 100.00"), "Description check");
    }

    private static void testCrossBelowBasic() {
        Alerts.Rule rule = new Alerts.Rule("R1", "AAPL", "level", "below", 100.0, null, null);
        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 110.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 95.0)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateLevelRule(rule, prices);
        assertEquals(1, alerts.size(), "Alert count");
        assertEquals(LocalDate.of(2026, 1, 2), alerts.get(0).date(), "Alert date");
        assertEquals("R1", alerts.get(0).ruleId(), "Rule ID");
        assertTrue(alerts.get(0).description().contains("crossed below threshold 100.00"), "Description check");
    }

    private static void testNoRepeatedAlertsWhileRemainingAboveOrBelow() {
        Alerts.Rule rule = new Alerts.Rule("R_ABV", "AAPL", "level", "above", 100.0, null, null);
        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 90.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 105.0), // cross above (alert 1)
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 3), 110.0), // remains above (no alert)
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 4), 108.0), // remains above (no alert)
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 5), 80.0),  // drops below
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 6), 115.0)  // cross above again (alert 2)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateLevelRule(rule, prices);
        assertEquals(2, alerts.size(), "Alert count");
        assertEquals(LocalDate.of(2026, 1, 2), alerts.get(0).date(), "Alert 1 date");
        assertEquals(LocalDate.of(2026, 1, 6), alerts.get(1).date(), "Alert 2 date");
    }

    private static void testEqualityTouchAndCross() {
        // 95 -> 100 (touch) -> 105 (cross above from below)
        Alerts.Rule rule = new Alerts.Rule("R1", "AAPL", "level", "above", 100.0, null, null);
        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 95.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 100.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 3), 105.0)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateLevelRule(rule, prices);
        assertEquals(1, alerts.size(), "Alert count");
        assertEquals(LocalDate.of(2026, 1, 3), alerts.get(0).date(), "Alert date");
    }

    private static void testEqualityBounceDoesNotCross() {
        // 105 -> 100 (touch) -> 105 (bounce, was already above, did not come from below)
        Alerts.Rule rule = new Alerts.Rule("R1", "AAPL", "level", "above", 100.0, null, null);
        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 105.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 100.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 3), 105.0)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateLevelRule(rule, prices);
        assertEquals(0, alerts.size(), "Bounce must not trigger alert");
    }

    private static void testFirstObservationNeverAlerts() {
        Alerts.Rule rule = new Alerts.Rule("R1", "AAPL", "level", "above", 100.0, null, null);
        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 150.0)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateLevelRule(rule, prices);
        assertEquals(0, alerts.size(), "First observation never alerts");
    }

    // =========================================================================
    // 3. Moving Average Crossover Tests
    // =========================================================================

    private static void testInsufficientHistory() {
        Alerts.Rule rule = new Alerts.Rule("MA1", "MSFT", "ma_cross", "above", null, 2, 5);
        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 101.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 102.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 3), 103.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 4), 104.0)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateMaCrossRule(rule, prices);
        assertEquals(0, alerts.size(), "Insufficient history yields no alerts");
    }

    private static void testMaCrossoverAboveAndBelow() {
        Alerts.Rule ruleAbove = new Alerts.Rule("MA_ABV", "MSFT", "ma_cross", "above", null, 2, 3);
        Alerts.Rule ruleBelow = new Alerts.Rule("MA_BLW", "MSFT", "ma_cross", "below", null, 2, 3);

        List<Alerts.PriceRecord> prices = List.of(
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 100.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 102.0),
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 3), 98.0),   // 2-SMA=100.0, 3-SMA=100.0 (equal baseline)
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 4), 92.0),   // 2-SMA=95.0, 3-SMA=97.33 (short < long)
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 5), 110.0),  // 2-SMA=101.0, 3-SMA=100.0 (cross ABOVE!)
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 6), 112.0),  // 2-SMA=111.0, 3-SMA=104.67 (remains above)
                new Alerts.PriceRecord(LocalDate.of(2026, 1, 7), 90.0)    // 2-SMA=101.0, 3-SMA=104.0 (cross BELOW!)
        );

        List<Alerts.Alert> alertsAbv = Alerts.evaluateMaCrossRule(ruleAbove, prices);
        assertEquals(1, alertsAbv.size(), "MA cross above count");
        assertEquals(LocalDate.of(2026, 1, 5), alertsAbv.get(0).date(), "MA cross above date");
        assertTrue(alertsAbv.get(0).description().contains("crossed above"), "MA cross above text");

        List<Alerts.Alert> alertsBlw = Alerts.evaluateMaCrossRule(ruleBelow, prices);
        assertEquals(1, alertsBlw.size(), "MA cross below count");
        assertEquals(LocalDate.of(2026, 1, 7), alertsBlw.get(0).date(), "MA cross below date");
        assertTrue(alertsBlw.get(0).description().contains("crossed below"), "MA cross below text");
    }

    // =========================================================================
    // 4. Deterministic Sorting & Edge Cases
    // =========================================================================

    private static void testEvaluateRulesDeterministicOrdering() {
        Map<String, List<Alerts.PriceRecord>> pricesByInstrument = Map.of(
                "AAPL", List.of(
                        new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 100.0),
                        new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 105.0)
                ),
                "GOOG", List.of(
                        new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 200.0),
                        new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 195.0)
                )
        );

        List<Alerts.Rule> rules = List.of(
                new Alerts.Rule("RULE_Z", "AAPL", "level", "above", 102.0, null, null),
                new Alerts.Rule("RULE_A", "GOOG", "level", "below", 198.0, null, null)
        );

        List<Alerts.Alert> alerts = Alerts.evaluateRules(pricesByInstrument, rules);
        assertEquals(2, alerts.size(), "Alert count");
        assertEquals("RULE_A", alerts.get(0).ruleId(), "Rule ID sort 0");
        assertEquals("RULE_Z", alerts.get(1).ruleId(), "Rule ID sort 1");
    }

    private static void testNoAlertsFire() {
        Map<String, List<Alerts.PriceRecord>> pricesByInstrument = Map.of(
                "AAPL", List.of(
                        new Alerts.PriceRecord(LocalDate.of(2026, 1, 1), 100.0),
                        new Alerts.PriceRecord(LocalDate.of(2026, 1, 2), 101.0)
                )
        );
        List<Alerts.Rule> rules = List.of(
                new Alerts.Rule("R1", "AAPL", "level", "above", 200.0, null, null)
        );
        List<Alerts.Alert> alerts = Alerts.evaluateRules(pricesByInstrument, rules);
        assertEquals(0, alerts.size(), "No alerts expected");
    }

    private static void testUnsortedDatesAndMultipleInstrumentsEndToEnd() throws Throwable {
        String csv = """
                date,instrument,close
                2026-01-03,AAPL,110.0
                2026-01-01,MSFT,50.0
                2026-01-01,AAPL,90.0
                2026-01-02,MSFT,60.0
                2026-01-02,AAPL,95.0
                2026-01-03,MSFT,40.0
                """;
        File f = createTempFile("shuffled", ".csv", csv);
        Map<String, List<Alerts.PriceRecord>> records = Alerts.loadAndValidatePrices(f.getAbsolutePath());

        assertEquals(LocalDate.of(2026, 1, 1), records.get("AAPL").get(0).date(), "AAPL sorted 0");
        assertEquals(LocalDate.of(2026, 1, 2), records.get("AAPL").get(1).date(), "AAPL sorted 1");
        assertEquals(LocalDate.of(2026, 1, 3), records.get("AAPL").get(2).date(), "AAPL sorted 2");

        List<Alerts.Rule> rules = List.of(
                new Alerts.Rule("R_AAPL", "AAPL", "level", "above", 100.0, null, null),
                new Alerts.Rule("R_MSFT", "MSFT", "level", "below", 45.0, null, null)
        );

        List<Alerts.Alert> alerts = Alerts.evaluateRules(records, rules);
        assertEquals(2, alerts.size(), "Alert count");
        assertEquals("R_AAPL", alerts.get(0).ruleId(), "Rule ID 0");
        assertEquals("R_MSFT", alerts.get(1).ruleId(), "Rule ID 1");
    }

    // =========================================================================
    // 5. Subprocess CLI Tests
    // =========================================================================

    private static void testCliSampleExecution() throws Throwable {
        File dir = new File(".");
        File pricesFile = new File(dir, "prices.csv");
        File rulesFile = new File(dir, "rules.json");

        Process process = new ProcessBuilder("java", "Alerts", pricesFile.getAbsolutePath(), rulesFile.getAbsolutePath())
                .redirectErrorStream(false)
                .start();

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int exitCode = process.waitFor();

        assertEquals(0, exitCode, "Exit code 0");
        assertEquals("", stderr, "Stderr should be empty on success");

        List<String> expectedLines = List.of(
                "2026-01-03 | RULE_AAPL_LVL_ABV | AAPL | Close price 155.00 crossed above threshold 150.00",
                "2026-01-05 | RULE_MSFT_MA_ABV | MSFT | 2-day MA (101.00) crossed above 3-day MA (100.00)",
                "2026-01-07 | RULE_AAPL_LVL_BLW | AAPL | Close price 148.00 crossed below threshold 150.00",
                "2026-01-07 | RULE_MSFT_MA_BLW | MSFT | 2-day MA (101.00) crossed below 3-day MA (104.00)"
        );

        List<String> actualLines = stdout.lines().filter(l -> !l.trim().isEmpty()).toList();
        assertEquals(expectedLines, actualLines, "Expected alert output");
    }

    private static void testCliUsageErrorOnMissingArgs() throws Throwable {
        Process process = new ProcessBuilder("java", "Alerts")
                .redirectErrorStream(false)
                .start();

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int exitCode = process.waitFor();

        assertEquals(2, exitCode, "Exit code 2 for usage");
        assertTrue(stderr.contains("Usage: java Alerts <prices.csv> <rules.json>"), "Usage message check");
        assertEquals("", stdout, "Stdout should be empty");
    }

    private static void testCliValidationErrorRoutedToStderr() throws Throwable {
        File csv = createTempFile("invalid", ".csv", "date,instrument,close\n2026-01-01,AAPL,-10.0\n");
        File json = createTempFile("empty_rules", ".json", "[]");

        Process process = new ProcessBuilder("java", "Alerts", csv.getAbsolutePath(), json.getAbsolutePath())
                .redirectErrorStream(false)
                .start();

        String stdout = new String(process.getInputStream().readAllBytes());
        String stderr = new String(process.getErrorStream().readAllBytes());
        int exitCode = process.waitFor();

        assertEquals(1, exitCode, "Exit code 1 for validation error");
        assertTrue(stderr.contains("Error: CSV line 2"), "Error message check");
        assertEquals("", stdout, "Stdout should be empty");
    }
}
