import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Price Alert Tool - Wissen Technology Assessment.
 *
 * Reads historical instrument prices from CSV and configurable alert rules from JSON,
 * evaluating level crossings and moving-average crossovers with deterministic ordering.
 */
public class Alerts {

    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }
    }

    public record PriceRecord(LocalDate date, double close) {}

    public record Rule(
            String id,
            String instrument,
            String type,
            String direction,
            Double threshold,
            Integer shortWindow,
            Integer longWindow
    ) {}

    public record Alert(LocalDate date, String ruleId, String instrument, String description) {
        public String toOutputLine() {
            return String.format(Locale.US, "%s | %s | %s | %s", date, ruleId, instrument, description);
        }
    }

    // =========================================================================
    // 1. CSV Loading and Validation
    // =========================================================================

    public static Map<String, List<PriceRecord>> loadAndValidatePrices(String csvPath) throws ValidationException {
        File file = new File(csvPath);
        if (!file.exists() || !file.isFile()) {
            throw new ValidationException("File not found: '" + csvPath + "'.");
        }
        if (!file.canRead()) {
            throw new ValidationException("Permission denied reading file: '" + csvPath + "'.");
        }

        Map<String, List<PriceRecord>> recordsByInstrument = new HashMap<>();
        Map<String, Integer> seenEntries = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new ValidationException("CSV file '" + csvPath + "' is empty.");
            }

            // Remove UTF-8 BOM if present
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }

            List<String> header = parseCsvLine(headerLine);
            Map<String, Integer> colIndices = new HashMap<>();
            for (int i = 0; i < header.size(); i++) {
                colIndices.put(header.get(i).trim().toLowerCase(Locale.ROOT), i);
            }

            for (String req : List.of("date", "instrument", "close")) {
                if (!colIndices.containsKey(req)) {
                    throw new ValidationException("CSV file '" + csvPath + "' is missing required column: '" + req + "'.");
                }
            }

            int dateIdx = colIndices.get("date");
            int instIdx = colIndices.get("instrument");
            int closeIdx = colIndices.get("close");

            String line;
            int lineNo = 1;
            int rowCount = 0;

            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.trim().isEmpty()) {
                    continue;
                }

                rowCount++;
                List<String> fields = parseCsvLine(line);
                int maxRequiredIdx = Math.max(dateIdx, Math.max(instIdx, closeIdx));
                if (fields.size() <= maxRequiredIdx) {
                    throw new ValidationException("CSV line " + lineNo + " in '" + csvPath + "' has insufficient columns.");
                }

                String dateStr = fields.get(dateIdx).trim();
                String instrument = fields.get(instIdx).trim();
                String closeStr = fields.get(closeIdx).trim();

                if (dateStr.isEmpty()) {
                    throw new ValidationException("CSV line " + lineNo + " in '" + csvPath + "' has an empty date.");
                }
                if (instrument.isEmpty()) {
                    throw new ValidationException("CSV line " + lineNo + " in '" + csvPath + "' has an empty instrument.");
                }

                LocalDate recordDate;
                try {
                    recordDate = LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
                } catch (DateTimeParseException e) {
                    throw new ValidationException(
                            "CSV line " + lineNo + " in '" + csvPath + "' has invalid date '" + dateStr + "'. Expected format YYYY-MM-DD.");
                }

                double closePrice;
                try {
                    closePrice = Double.parseDouble(closeStr);
                    if (Double.isNaN(closePrice) || Double.isInfinite(closePrice) || closePrice < 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException e) {
                    throw new ValidationException(
                            "CSV line " + lineNo + " in '" + csvPath + "' has invalid price '" + closeStr + "'. Must be a non-negative finite number.");
                }

                // Check duplicate (instrument, date) explicitly
                String entryKey = instrument + "#" + dateStr;
                if (seenEntries.containsKey(entryKey)) {
                    int prevLine = seenEntries.get(entryKey);
                    throw new ValidationException(
                            "Duplicate record for instrument '" + instrument + "' on date '" + dateStr + "' at line " + lineNo + " (previously defined at line " + prevLine + ").");
                }
                seenEntries.put(entryKey, lineNo);

                recordsByInstrument.computeIfAbsent(instrument, k -> new ArrayList<>())
                        .add(new PriceRecord(recordDate, closePrice));
            }

            if (rowCount == 0) {
                throw new ValidationException("CSV file '" + csvPath + "' contains no data rows.");
            }

        } catch (IOException e) {
            throw new ValidationException("Error reading CSV file '" + csvPath + "': " + e.getMessage());
        }

        // Sort chronologically for each instrument
        for (List<PriceRecord> records : recordsByInstrument.values()) {
            records.sort(Comparator.comparing(PriceRecord::date));
        }

        return recordsByInstrument;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens;
    }

    // =========================================================================
    // 2. JSON Loading and Rule Validation (Zero external dependencies)
    // =========================================================================

    public static List<Rule> loadAndValidateRules(String jsonPath) throws ValidationException {
        File file = new File(jsonPath);
        if (!file.exists() || !file.isFile()) {
            throw new ValidationException("File not found: '" + jsonPath + "'.");
        }
        if (!file.canRead()) {
            throw new ValidationException("Permission denied reading file: '" + jsonPath + "'.");
        }

        String jsonContent;
        try {
            byte[] bytes = new FileInputStream(file).readAllBytes();
            jsonContent = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ValidationException("Error reading JSON file '" + jsonPath + "': " + e.getMessage());
        }

        Object parsedJson = SimpleJson.parse(jsonContent, jsonPath);
        if (!(parsedJson instanceof List<?> rawList)) {
            throw new ValidationException("Rules in '" + jsonPath + "' must be a JSON array at top level.");
        }

        Set<String> seenRuleIds = new HashSet<>();
        List<Rule> validatedRules = new ArrayList<>();

        for (int idx = 0; idx < rawList.size(); idx++) {
            int ruleNum = idx + 1;
            Object item = rawList.get(idx);
            if (!(item instanceof Map<?, ?> map)) {
                throw new ValidationException("Rule #" + ruleNum + " in '" + jsonPath + "' must be a JSON object.");
            }

            for (String req : List.of("id", "instrument", "type", "direction")) {
                if (!map.containsKey(req) || map.get(req) == null) {
                    throw new ValidationException("Rule #" + ruleNum + " in '" + jsonPath + "' is missing required field '" + req + "'.");
                }
            }

            String ruleId = String.valueOf(map.get("id")).trim();
            if (ruleId.isEmpty()) {
                throw new ValidationException("Rule #" + ruleNum + " in '" + jsonPath + "' has an empty 'id'.");
            }
            if (!seenRuleIds.add(ruleId)) {
                throw new ValidationException("Duplicate rule ID '" + ruleId + "' found at rule #" + ruleNum + " in '" + jsonPath + "'.");
            }

            String instrument = String.valueOf(map.get("instrument")).trim();
            if (instrument.isEmpty()) {
                throw new ValidationException("Rule '" + ruleId + "' has an empty 'instrument'.");
            }

            String type = String.valueOf(map.get("type")).trim().toLowerCase(Locale.ROOT);
            if (!type.equals("level") && !type.equals("ma_cross")) {
                throw new ValidationException("Rule '" + ruleId + "' has invalid type '" + map.get("type") + "'. Allowed types: 'level', 'ma_cross'.");
            }

            String direction = String.valueOf(map.get("direction")).trim().toLowerCase(Locale.ROOT);
            if (!direction.equals("above") && !direction.equals("below")) {
                throw new ValidationException("Rule '" + ruleId + "' has invalid direction '" + map.get("direction") + "'. Allowed directions: 'above', 'below'.");
            }

            Double threshold = null;
            Integer shortWindow = null;
            Integer longWindow = null;

            if (type.equals("level")) {
                if (!map.containsKey("threshold") || map.get("threshold") == null) {
                    throw new ValidationException("Rule '" + ruleId + "' of type 'level' requires 'threshold'.");
                }
                Object thObj = map.get("threshold");
                if (!(thObj instanceof Number num)) {
                    throw new ValidationException("Rule '" + ruleId + "' threshold '" + thObj + "' must be a valid finite number.");
                }
                double val = num.doubleValue();
                if (Double.isNaN(val) || Double.isInfinite(val)) {
                    throw new ValidationException("Rule '" + ruleId + "' threshold must be a finite number.");
                }
                threshold = val;

            } else { // ma_cross
                for (String winKey : List.of("short_window", "long_window")) {
                    if (!map.containsKey(winKey) || map.get(winKey) == null) {
                        throw new ValidationException("Rule '" + ruleId + "' of type 'ma_cross' requires '" + winKey + "'.");
                    }
                    Object valObj = map.get(winKey);
                    if (!(valObj instanceof Number num) || (valObj instanceof Boolean) || num.doubleValue() != num.longValue() || num.longValue() <= 0) {
                        throw new ValidationException("Rule '" + ruleId + "' " + winKey + " must be a positive integer, got: " + valObj + ".");
                    }
                }

                shortWindow = ((Number) map.get("short_window")).intValue();
                longWindow = ((Number) map.get("long_window")).intValue();

                if (shortWindow >= longWindow) {
                    throw new ValidationException(
                            "Rule '" + ruleId + "' short_window (" + shortWindow + ") must be strictly less than long_window (" + longWindow + ").");
                }
            }

            validatedRules.add(new Rule(ruleId, instrument, type, direction, threshold, shortWindow, longWindow));
        }

        return validatedRules;
    }

    // =========================================================================
    // 3. Rule Evaluation Engine
    // =========================================================================

    public static List<Alert> evaluateLevelRule(Rule rule, List<PriceRecord> prices) {
        List<Alert> alerts = new ArrayList<>();
        double threshold = rule.threshold();
        String direction = rule.direction();
        String ruleId = rule.id();
        String instrument = rule.instrument();

        // -1: previously below, +1: previously above, null: no confirmed prior side
        Integer lastSide = null;

        for (PriceRecord rec : prices) {
            double price = rec.close();
            if (Math.abs(price - threshold) < 1e-9) {
                // Touching boundary exactly; retains previous side state
                continue;
            }

            if (price > threshold) {
                if (direction.equals("above") && lastSide != null && lastSide == -1) {
                    String desc = String.format(Locale.US, "Close price %.2f crossed above threshold %.2f", price, threshold);
                    alerts.add(new Alert(rec.date(), ruleId, instrument, desc));
                }
                lastSide = 1;
            } else { // price < threshold
                if (direction.equals("below") && lastSide != null && lastSide == 1) {
                    String desc = String.format(Locale.US, "Close price %.2f crossed below threshold %.2f", price, threshold);
                    alerts.add(new Alert(rec.date(), ruleId, instrument, desc));
                }
                lastSide = -1;
            }
        }

        return alerts;
    }

    public static List<Alert> evaluateMaCrossRule(Rule rule, List<PriceRecord> prices) {
        List<Alert> alerts = new ArrayList<>();
        int shortW = rule.shortWindow();
        int longW = rule.longWindow();
        String direction = rule.direction();
        String ruleId = rule.id();
        String instrument = rule.instrument();

        if (prices.size() < longW) {
            return alerts;
        }

        // -1: short < long, +1: short > long, null: initial baseline or equal
        Integer lastDiffState = null;

        for (int i = longW - 1; i < prices.size(); i++) {
            double shortSum = 0.0;
            for (int k = i - shortW + 1; k <= i; k++) {
                shortSum += prices.get(k).close();
            }
            double shortMa = shortSum / shortW;

            double longSum = 0.0;
            for (int k = i - longW + 1; k <= i; k++) {
                longSum += prices.get(k).close();
            }
            double longMa = longSum / longW;

            LocalDate recDate = prices.get(i).date();

            if (Math.abs(shortMa - longMa) < 1e-9) {
                // Equal averages preserve previous state; no alert
                continue;
            }

            if (shortMa > longMa) {
                if (direction.equals("above") && lastDiffState != null && lastDiffState == -1) {
                    String desc = String.format(Locale.US, "%d-day MA (%.2f) crossed above %d-day MA (%.2f)",
                            shortW, shortMa, longW, longMa);
                    alerts.add(new Alert(recDate, ruleId, instrument, desc));
                }
                lastDiffState = 1;
            } else { // shortMa < longMa
                if (direction.equals("below") && lastDiffState != null && lastDiffState == 1) {
                    String desc = String.format(Locale.US, "%d-day MA (%.2f) crossed below %d-day MA (%.2f)",
                            shortW, shortMa, longW, longMa);
                    alerts.add(new Alert(recDate, ruleId, instrument, desc));
                }
                lastDiffState = -1;
            }
        }

        return alerts;
    }

    public static List<Alert> evaluateRules(Map<String, List<PriceRecord>> pricesByInstrument, List<Rule> rules) {
        List<Alert> allAlerts = new ArrayList<>();

        for (Rule rule : rules) {
            List<PriceRecord> prices = pricesByInstrument.get(rule.instrument());
            if (prices == null || prices.isEmpty()) {
                continue;
            }

            if (rule.type().equals("level")) {
                allAlerts.addAll(evaluateLevelRule(rule, prices));
            } else if (rule.type().equals("ma_cross")) {
                allAlerts.addAll(evaluateMaCrossRule(rule, prices));
            }
        }

        // Sort deterministically by date ascending, then rule_id ascending
        allAlerts.sort(Comparator.comparing(Alert::date).thenComparing(Alert::ruleId));
        return allAlerts;
    }

    // =========================================================================
    // 4. CLI Entry Point
    // =========================================================================

    public static void main(String[] args) {
        if (args.length != 2 || args[0].equals("-h") || args[0].equals("--help")) {
            System.err.println("Usage: java Alerts <prices.csv> <rules.json>");
            System.exit(2);
        }

        String pricesPath = args[0];
        String rulesPath = args[1];

        try {
            Map<String, List<PriceRecord>> pricesByInstrument = loadAndValidatePrices(pricesPath);
            List<Rule> rules = loadAndValidateRules(rulesPath);
            List<Alert> alerts = evaluateRules(pricesByInstrument, rules);

            for (Alert alert : alerts) {
                System.out.println(alert.toOutputLine());
            }
            System.exit(0);

        } catch (ValidationException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            System.exit(1);
        }
    }

    // =========================================================================
    // 5. Embedded Lightweight JSON Parser (Standard Java Only)
    // =========================================================================

    public static class SimpleJson {
        private final String src;
        private final String filename;
        private int pos = 0;

        public SimpleJson(String src, String filename) {
            this.src = src;
            this.filename = filename;
        }

        public static Object parse(String src, String filename) throws ValidationException {
            SimpleJson parser = new SimpleJson(src, filename);
            parser.skipWhitespace();
            if (parser.pos >= parser.src.length()) {
                throw new ValidationException("JSON file '" + filename + "' is empty.");
            }
            Object result = parser.parseValue();
            parser.skipWhitespace();
            if (parser.pos < parser.src.length()) {
                throw new ValidationException("Invalid JSON syntax in '" + filename + "': unexpected trailing data.");
            }
            return result;
        }

        private void skipWhitespace() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isWhitespace(c)) {
                    pos++;
                } else {
                    break;
                }
            }
        }

        private Object parseValue() throws ValidationException {
            skipWhitespace();
            if (pos >= src.length()) {
                throw new ValidationException("Invalid JSON syntax in '" + filename + "': unexpected end of JSON.");
            }
            char c = src.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == '-' || Character.isDigit(c)) return parseNumber();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();

            throw new ValidationException("Invalid JSON syntax in '" + filename + "' near character '" + c + "' at position " + pos + ".");
        }

        private Map<String, Object> parseObject() throws ValidationException {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++; // skip '{'
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '}') {
                pos++;
                return map;
            }

            while (pos < src.length()) {
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != '"') {
                    throw new ValidationException("Invalid JSON syntax in '" + filename + "': expected string key in object.");
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= src.length() || src.charAt(pos) != ':') {
                    throw new ValidationException("Invalid JSON syntax in '" + filename + "': expected ':' after key '" + key + "'.");
                }
                pos++; // skip ':'
                Object val = parseValue();
                map.put(key, val);

                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                    continue;
                } else if (pos < src.length() && src.charAt(pos) == '}') {
                    pos++;
                    return map;
                } else {
                    throw new ValidationException("Invalid JSON syntax in '" + filename + "': expected ',' or '}' in object.");
                }
            }
            throw new ValidationException("Invalid JSON syntax in '" + filename + "': unterminated object.");
        }

        private List<Object> parseArray() throws ValidationException {
            List<Object> list = new ArrayList<>();
            pos++; // skip '['
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ']') {
                pos++;
                return list;
            }

            while (pos < src.length()) {
                Object val = parseValue();
                list.add(val);
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                    continue;
                } else if (pos < src.length() && src.charAt(pos) == ']') {
                    pos++;
                    return list;
                } else {
                    throw new ValidationException("Invalid JSON syntax in '" + filename + "': expected ',' or ']' in array.");
                }
            }
            throw new ValidationException("Invalid JSON syntax in '" + filename + "': unterminated array.");
        }

        private String parseString() throws ValidationException {
            pos++; // skip opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                } else if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new ValidationException("Invalid JSON syntax in '" + filename + "': unterminated escape sequence.");
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) {
                                throw new ValidationException("Invalid JSON syntax in '" + filename + "': invalid unicode escape.");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw new ValidationException("Invalid JSON syntax in '" + filename + "': invalid unicode hex '" + hex + "'.");
                            }
                        }
                        default -> sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new ValidationException("Invalid JSON syntax in '" + filename + "': unterminated string.");
        }

        private Number parseNumber() throws ValidationException {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String numStr = src.substring(start, pos);
            try {
                if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                    return Double.parseDouble(numStr);
                } else {
                    return Long.parseLong(numStr);
                }
            } catch (NumberFormatException e) {
                throw new ValidationException("Invalid JSON syntax in '" + filename + "': invalid number '" + numStr + "'.");
            }
        }

        private Boolean parseBoolean() throws ValidationException {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new ValidationException("Invalid JSON syntax in '" + filename + "': invalid boolean at position " + pos + ".");
        }

        private Object parseNull() throws ValidationException {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new ValidationException("Invalid JSON syntax in '" + filename + "': invalid null at position " + pos + ".");
        }
    }
}
