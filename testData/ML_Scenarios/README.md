# Additional synthetic storage scenarios

These scenarios model artificial cold-storage operating conditions for the CS5010 risk estimation experiment. The generator uses the seven configured zone limits from `src/main/resources/application.yaml`. The existing demo mapping has four nominal product types. The generator creates ten independently seeded sessions for each of seven regions. It varies the initial conditions, event start, warming amplitude, hold and recovery times, and correlated low-amplitude measurement noise. Every region includes normal operation, a brief excursion, a sustained excursion, gradual warming, slow recovery, repeated excursions, and an outage during a warm interval.

## Generate with Java 21

Run from the repository root.

```powershell
.\mvnw.cmd -B compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.icu.smart_cold_storage_monitor.ml.RiskScenarioGenerationMain"
```

The default configuration is [generator-config.json](generator-config.json). The generator accepts a configuration path and output directory through `-Dexec.args`. For example, append `"-Dexec.args=testData/ML_Scenarios/generator-config.json target/ml-scenarios-copy"` to the command to generate another copy. Keep the generator version, seed, configuration, Java runtime and zone defaults with any reproduced dataset. The default cutoff pair of 120 and 600 seconds is configurable and defines a simulated policy.

The command writes two files under `target/ml-scenarios/`.

| File | Contents |
| --- | --- |
| `generated-readings.jsonl` | One record per observed tick, including the five Weka inputs, rule label, provenance keys, temperature limits and quality flags. |
| `scenario-manifest.json` | Dataset SHA-256, aggregate counts, one entry per scenario with its parameters and knots, thresholds, source, individual seed, record count and timestamp range. Join to rows by `scenarioId`. |

`gapFromPrevious` identifies the first reading after an outage longer than 30 seconds. `incompleteHistory` stays true for the remainder of that session. The shared Java accumulator skips the entire unobserved interval. Elapsed storage time includes the outage. Independent validation checks the planned grid, excursions, sensor bounds, exposure, gap flags, risk labels and duplicate temperature traces.

The original fixture remains at `data/monitoring_timestamped.jsonl`. The synthetic extension uses a separate JSONL schema and is ready for a later data-loading and grouped evaluation step. Preserve whole `scenarioId` groups in that step. Consider reserving whole curve families when evaluating transfer to new operating patterns. These scenario definitions and generated samples contain no physical sensor observations or measured spoilage outcomes.
