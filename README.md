# kafbat-ui-serde-asterix

A [kafbat-ui](https://github.com/kafbat/kafka-ui) Serde plugin that decodes
**ASTERIX** (All-purpose Structured EUROCONTROL Surveillance Information
Exchange) binary messages into human-readable JSON.

ASTERIX is the standard binary protocol used across European (and global) Air
Traffic Management for radar surveillance, flight data, and meteorological
data exchange.

---

## Features

- Decodes all standard ASTERIX categories (24 categories, 51 editions bundled)
- Spec-driven: parsers are generated automatically from the
  [zoranbosnjak/asterix-specs](https://github.com/zoranbosnjak/asterix-specs)
  reference definitions — no hand-written parsers to maintain
- Supports all ASTERIX item types: Fixed, Variable (FX-extended), Extended,
  Repetitive, Compound, Explicit (SP/RE)
- Content encoding: Quantity (scaled integers), Table (enumerated values),
  ICAO 6-bit callsigns, ASCII strings, BDS/CF register addresses
- Monthly CI auto-updates specs and creates a new release only when upstream
  specs change

---

## Supported Categories

| Category | Name | Latest Edition |
|----------|------|---------------|
| CAT001 | Monoradar Target Reports | 1.4 |
| CAT002 | Monoradar Service Messages | 1.2 |
| CAT007 | Monoradar Service Messages (simplified) | 1.12 |
| CAT008 | Monoradar Derived Weather Information | 1.3 |
| CAT010 | Monoradar Target Reports (surface) | 1.1 |
| CAT011 | ATC Track Messages | 1.2 |
| CAT015 | Sensor Network Messages | 1.1 |
| CAT016 | Transmission of Multilateration System Data | 1.0 |
| CAT017 | MLAT System Track Messages | 1.3 |
| CAT018 | Ground Station Service Messages | 1.8 |
| CAT019 | Multilateration System Status Messages | 1.3 |
| CAT020 | Multilateration Target Reports | 1.11 |
| CAT021 | ADS-B Target Reports | 2.6 |
| CAT023 | CNS/ATM Ground Station Service Messages | 1.3 |
| CAT025 | CNS/ATM System Status Messages | 1.6 |
| CAT032 | Miniplan Record Messages | 1.2 |
| CAT034 | Monoradar Service Messages (expanded) | 1.29 |
| CAT048 | Monoradar Target Reports (expanded) | 1.32 |
| CAT062 | SDPS Track Messages | 1.20 |
| CAT063 | Sensor Status Messages | 1.7 |
| CAT065 | SDPS Service Messages | 1.6 |
| CAT205 | Surveillance Data Exchange | 1.0 |
| CAT240 | Radar Video Transmission | 1.3 |
| CAT247 | Version Number Exchange | 1.3 |

---

## Building

Requires JDK 21+ and Maven 3.9+.

```bash
# Build the uber JAR (includes Jackson; excludes kafbat-ui serde-api)
mvn package -DskipTests

# The plugin JAR is at:
ls target/kafbat-ui-serde-asterix-*-uber.jar
```

---

## Installation

Copy the uber JAR to a location accessible to kafbat-ui, then add the
following to your kafbat-ui configuration:

```yaml
kafka:
  clusters:
    - name: my-cluster
      # ...
      serde:
        - name: ASTERIX
          className: io.airlab.kafbat.serde.asterix.AsterixSerde
          filePath: /opt/kafbat/plugins/kafbat-ui-serde-asterix-uber.jar
          properties:
            # Optional: restrict to topics matching a regex
            # topicKeysPattern: ".*"        # match keys (default: none)
            # topicValuesPattern: "asterix.*" # match values (default: all)
```

After restarting kafbat-ui, select **ASTERIX** as the value deserializer for
any topic that carries ASTERIX binary messages.

---

## Output Format

Each Kafka message is decoded as a JSON array — one element per ASTERIX data
block. A data block contains one or more records.

```json
[
  {
    "category": 48,
    "name": "Monoradar Target Reports",
    "records": [
      {
        "I010": { "SAC": 0, "SIC": 1 },
        "I140": { "ToD": 36000.0 },
        "I040": { "RHO": 100.0, "THETA": 90.0 },
        "I070": { "V": 0, "G": 0, "L": 0, "Mode3A": 4032, "squawk": "7700" },
        "I090": { "V": 0, "G": 0, "FL": 350.0 }
      }
    ]
  }
]
```

Unknown categories are returned as a hex dump:

```json
[{ "category": 99, "unknown": true, "hex": "112233" }]
```

---

## Updating Specs

ASTERIX specs are bundled as JSON resources generated from the upstream
`.ast` files. To pull the latest specs:

```bash
# Requires Python 3.9+ and internet access
python3 tools/update-specs.py

# Or download all historical editions:
python3 tools/update-specs.py --all-editions

# Use GITHUB_TOKEN to avoid API rate limits:
GITHUB_TOKEN=ghp_... python3 tools/update-specs.py
```

Exit codes:
- `0` — specs were updated (rebuild required)
- `42` — no changes detected

The script writes to `src/main/resources/asterix-specs/` and updates
`manifest.json`. Commit the result and rebuild.

---

## Automated Monthly Releases

The GitHub Actions workflow `.github/workflows/update-specs.yml` runs on the
1st of every month and:

1. Runs `tools/update-specs.py` with `GITHUB_TOKEN` set
2. If specs changed: builds the uber JAR and creates a GitHub Release with
   the updated JAR attached
3. If nothing changed: exits cleanly without creating a release

You can also trigger it manually from the Actions tab (with an option to
download all editions).

---

## Architecture

```
AsterixSerde              kafbat-ui plugin entry point
└── AsterixParser         top-level binary framing (CAT + LEN + data blocks)
    └── CategoryRegistry  maps category number → CategoryDefinition
        ├── SpecLoader    reads JSON specs from classpath at startup
        │   └── SpecItemParser  bit-level parser driven by JSON rule trees
        └── Cat002/010/021/034/048  hand-written fallback parsers
```

### Spec loading priority

At startup `CategoryRegistry.withBuiltins()`:
1. Calls `SpecLoader.loadAll()` — loads the latest edition of every category
   listed in `asterix-specs/manifest.json`
2. For any category not covered by the spec files, falls back to the
   hand-written parser

### Spec JSON format

Each spec JSON file contains:

```json
{
  "category": 48,
  "edition": "1.32",
  "name": "Monoradar Target Reports",
  "uap": { "type": "flat", "items": ["I010", "I020", "I040", ...] },
  "items": {
    "I010": { "rule": { "type": "Group", "items": [...] } },
    "I040": { "rule": { "type": "Group", "items": [
      { "type": "Item", "name": "RHO",   "rule": { "type": "Element", "size": 16,
        "content": { "type": "Quantity", "signed": false, "lsb": 0.00390625 } } },
      { "type": "Item", "name": "THETA", "rule": { "type": "Element", "size": 16,
        "content": { "type": "Quantity", "signed": false, "lsb": 0.0054931640625 } } }
    ] } }
  }
}
```

---

## Tests

```bash
mvn test
```

Tests live in `src/test/java/io/airlab/kafbat/serde/asterix/`:

| Class | Covers |
|-------|--------|
| `AsterixParserTest` | Hand-written CAT002/048 parsers; edge cases (empty input, unknown category, multiple blocks) |
| `SpecLoaderTest` | Spec loading from classpath; spec-driven CAT002/048 decoding; `withBuiltins()` fallback behaviour |

---

## Contributing

Pull requests are welcome. When adding support for a new category:

1. Run `tools/update-specs.py` — if the category exists in
   `zoranbosnjak/asterix-specs` it will be picked up automatically.
2. If the category is not in that repo, add a hand-written parser in
   `src/main/java/io/airlab/kafbat/serde/asterix/category/` following the
   pattern of `Cat048.java`.
3. Add tests in `AsterixParserTest` with hand-crafted byte sequences.

---

## License

Apache 2.0. ASTERIX specifications are published by
[EUROCONTROL](https://www.eurocontrol.int/asterix) and are freely available.
