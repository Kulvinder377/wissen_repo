# Price Alert Tool (Java)

A deterministic, lightweight command-line tool written in pure Java (standard library only) that evaluates level-crossing and moving-average crossover alerts from historical price data for the Wissen Technology Campus Assessment.

---

## 1. Project Structure

Only the essential files are present:
* `Alerts.java`: Input loading, validation, embedded JSON parser, rule engine, and CLI entry point.
* `TestAlerts.java`: Automated test suite (30 tests covering validation, state machine, and subprocess CLI).
* `prices.csv`: Representative sample price history with shuffled dates and multiple instruments.
* `rules.json`: Configurable alert rules for level and moving-average crossovers in both directions.
* `README.md`: Setup, execution, architecture, and AI-assisted development documentation.

---

## 2. Setup and Execution

### Prerequisites
* Java 17+ (Zero external dependencies or build tools required).

### Running the Tool
```bash
# Option A: Direct single-file execution (Java 11+)
java Alerts.java prices.csv rules.json

# Option B: Standard compile & run
javac Alerts.java
java Alerts prices.csv rules.json
```

### Running Automated Tests
```bash
javac Alerts.java TestAlerts.java
java TestAlerts
```

---

## 3. Architecture and Design Decisions

* **Pure Standard Library & Embedded JSON Parser:**
  * Uses only standard `java.time`, `java.io`, and `java.util`. Contains a lightweight, zero-dependency recursive-descent parser (`Alerts.SimpleJson`) to read rules without third-party libraries (no Jackson/Gson).
* **Input Validation & Duplicate Prevention:**
  * Validates required CSV columns (`date`, `instrument`, `close`), `YYYY-MM-DD` date format, and non-negative finite prices.
  * Rejects duplicate `(instrument, date)` entries with line numbers; does not silently guess prices.
  * Enforces valid rule types (`level`, `ma_cross`), directions (`above`, `below`), and positive windows with `short_window < long_window`.
* **Chronological Invariance:**
  * Groups prices per instrument and sorts by date ascending, ensuring correct evaluation regardless of row ordering in the CSV.
* **Level-Crossing Semantics & Equality Policy:**
  * Uses a 3-state machine tracking previous confirmed side (`-1` below, `+1` above).
  * Triggers only when transitioning from the opposite side into the target zone.
  * Boundary equality ($P == T$) preserves the previous side and never alerts, preventing false triggers on bounces ($105 \rightarrow 100 \rightarrow 105$).
  * The first observation establishes baseline state and never fires an alert.
* **Moving-Average Crossover Semantics:**
  * Requires complete windows before SMAs are valid. Evaluation begins at index $L - 1$.
  * Detects sign changes in $\text{SMA}_S - \text{SMA}_L$ using a floating-point tolerance (`1e-9`).
* **Deterministic Output:**
  * Alerts are sorted deterministically by `date` (ascending), then `ruleId` (ascending).
  * Normal alerts go to `stdout`; errors and usage guides go to `stderr` with appropriate non-zero exit codes.

---

## 4. Sample Verification

```bash
java Alerts.java prices.csv rules.json
```
**Output:**
```
2026-01-03 | RULE_AAPL_LVL_ABV | AAPL | Close price 155.00 crossed above threshold 150.00
2026-01-05 | RULE_MSFT_MA_ABV | MSFT | 2-day MA (101.00) crossed above 3-day MA (100.00)
2026-01-07 | RULE_AAPL_LVL_BLW | AAPL | Close price 148.00 crossed below threshold 150.00
2026-01-07 | RULE_MSFT_MA_BLW | MSFT | 2-day MA (101.00) crossed below 3-day MA (104.00)
```

---

## 5. AI-Assisted Development & Discovered Mistakes

* **Prompts Used:**
  1. *Prompt 1 (Planning):* Requirement inspection and proposed crossing semantics, edge cases, and roadmap.
  2. *Prompt 2 (Execution):* "yes" approving proposed semantics and staged implementation.
  3. *Prompt 3 (Migration):* Complete project conversion to pure Java standard library and removal of Python files.
  4. *Prompt 4 (Documentation):* Update `README.md` to reflect the Java project structure and setup.
* **Genuine Mistakes Discovered & Corrected:**
  1. **JSON Parser Error Message Consistency:** The custom recursive-descent JSON parser initially raised syntax exceptions in nested objects without the `"Invalid JSON syntax in 'filename': "` prefix, causing `testJsonInvalidSyntax` to fail. Standardized all internal syntax error messages to include the uniform prefix.
  2. **MA Equality State Tracking:** If short and long SMAs were identical on the initial valid day ($t = L - 1$), setting state to neutral without setting `lastDiffState = null` could allow a subsequent shift on day $L$ to trigger an alert without having crossed from the opposite side. Fixed by requiring an explicit prior opposite state before triggering.
