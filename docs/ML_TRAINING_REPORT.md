# Offline ML risk training report

## Purpose and data

This experiment implements the five-feature, three-class supervised task in Section 6 of the CS5010 design PDF. It estimates the current simulated risk level for a storage session. The experiment uses Java 21 and Weka 3.8.6.

The sole input is [the seven-zone JSONL fixture](../data/monitoring_timestamped.jsonl). It contains 2,527 synthetic SHT45 readings, with 361 observations each from seven device traces at five-second intervals from 17:00:00 to 17:30:00 UTC on 2026-11-05. The seven-zone copy is identical and the five-zone file and standalone SHT45 data contain subsets of the same observations. The adapter checks the raw frames against the supplied measurements and uses the original measured timestamps. It uses the first reading of each trace as the demo storage-session start. The fixture contains no actual storage-session start, measured arrival times, or observed acquisition gaps.

The adapter uses the version of the [backend location defaults](../src/main/resources/application.yaml) checked into this branch as a constant threshold snapshot per trace. The fixture has region codes but no recorded product categories. The explicit demo mapping is RAW-MEAT and FROZEN to `RAW_MEAT`, VEG-COLD and VEG-COOL to `PRODUCE`, FRUIT-COLD-BERRY and FRUIT-TROPICAL to `FRUIT`, and EGGS to `EGGS`. These are coarse demo categories. Device ID, session ID, region, and timestamp identify the data and its split.

## Additional artificial scenarios for future experiments

The [offline scenario generator](../testData/ML_Scenarios/README.md) adds a separate
40,528-row dataset across 70 independently seeded storage sessions, with ten
sessions per configured region. It uses the same feature calculator and
`simulated-exposure-v1` label policy. The default generation run yielded 11,732
Low rows across 70 sessions, 11,603 Medium rows across 63 sessions, and 17,193 High
rows across 45 sessions. High appeared in all seven regions, all four product
types, and four scenario types. The audit found seven planned outages and no
identical temperature trajectories.

The additional traces are entirely artificial. They come from parameterized
warming and recovery curves, bounded noise, and planned outages. They contain
no physical sensor observations or measured food spoilage outcomes. The existing
2,527-row fixture is preserved and is the sole input to ML-1. ML-2 uses only the
additional traces. Both experiments are reported separately below.

## Features and labels

The features, in schema order, are current temperature in °C, current relative humidity in %RH, observed cumulative seconds above the temperature upper limit, storage seconds since session start, and nominal product type. The sample timestamp ends the calculation. Each interval adds its full elapsed time if the left reading exceeds the upper limit recorded on that reading and the interval is at most 30 seconds. Equality is in range. Intervals over 30 seconds contribute no observed exposure and flag incomplete history. The implementation also flags an unobserved session prefix or an unavailable threshold version. Elapsed storage time includes gaps. Quality is carried with the feature snapshot outside the Weka attributes.

The versioned `simulated-exposure-v1` policy labels a snapshot High at 600 or more observed seconds above the upper temperature limit. Otherwise it labels Medium at 120 or more seconds, while the current temperature is outside either temperature bound, or when exposure history is incomplete. All other snapshots are Low. The cutoffs are explicit course experiment parameters. The fixture provides complete histories, so the incomplete-history branch is exercised by tests. Humidity enters both learned classifiers and has no direct effect on this label policy.

## Split and experiment

The fixed test region is **VEG-COLD** (`ESP32-SIM-002/SIM-BOOT-20261105-002`). It provides Low and Medium on an intact trajectory, and VEG-COOL supplies the shared `PRODUCE` category in training. All six other regions are assigned to training. The run log lists their device and boot identifiers. This is a regional holdout within a shared synthetic fixture. Every trace and every continuous event within a trace stays on one side. No row-level shuffling, resampling, held-out tuning, or independent validation fold is used. The experiment uses fixed model settings because all High observations are concentrated in one training trajectory. The 85.7/14.3 split follows the available whole-region granularity and keeps all three labels available for fitting.

| Split | Low | Medium | High | Total |
| --- | ---: | ---: | ---: | ---: |
| Train | 1,568 | 403 | 195 | 2,166 |
| Test | 147 | 214 | 0 | 361 |
| Total | 1,715 | 617 | 195 | 2,527 |

Random Forest uses 100 trees, seed 5010 and one execution slot, with other Weka defaults. Multinomial Logistic Regression uses Weka `Logistic` defaults, including ridge regularization. Both receive the same five raw attributes. Product vocabulary is derived from training. Each fitted classifier is saved with the training header and reloaded. Every held-out classification is checked for agreement before and after reloading. The fixed rules baseline is the label policy and uses the same snapshots plus their current configured temperature bounds and quality status.

## Reproduction

Run from the repository root with Java 21 selected. The training command uses Maven and a standalone Java entry point.

```powershell
.\mvnw.cmd -B test
.\mvnw.cmd -B compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.icu.smart_cold_storage_monitor.ml.RiskTrainingMain"
```

The default Medium and High cutoffs are 120 and 600 seconds. To override them, append
`"-Dexec.args=data/monitoring_timestamped.jsonl target/ml-risk-custom 90 480"` to the
training command. Supply both positive cutoffs in increasing order. The run log and
both model manifests record the effective values. Treat an override as a separate
experiment because it can change class support and the meaning of the labels.

The output directory `target/ml-risk-training/` contains the labeled `dataset.json`, separate train and test ARFF files, the two `.model` artifacts and their JSON manifests, and `evaluation.txt`. The manifests describe feature order, units, class and product mappings, classifier settings, Java and Weka versions, source hash and rule policy. The compiled model loader expects project-generated artifacts.

## ML-1 measured results

The standalone run completed on Windows with Temurin Java 21.0.12.1 and Weka 3.8.6. The data SHA-256 was `f0864221ccb96cf47bf3a685d327c5f685b4657e0cb1ec00bd49c35cbb908d9a`. The recorded results below come from `target/ml-risk-training/evaluation.txt`.

| Method | Accuracy | Training (ms) | Mean inference (µs) | p95 inference (µs) |
| --- | ---: | ---: | ---: | ---: |
| Random Forest | 99.7230% | 675.097 | 9.627 | 12.000 |
| Multinomial Logistic Regression | 0.0000% | 205.672 | 1.994 | 2.800 |
| Rules Baseline | 100.0000% | N/A | 0.106 | 0.200 |

Training time covers classifier fitting. Latency was measured after one evaluation pass, with 3,610 in-process predictions per method. It includes Weka instance encoding for the classifiers and excludes history aggregation, artifact I/O and application or network latency. These are single-run timings and depend on JVM warm-up and machine load.

| Method | Class | Test support | Predicted count | Precision | Recall | F1 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Random Forest | Low | 147 | 148 | 0.993243 | 1.000000 | 0.996610 |
| Random Forest | Medium | 214 | 213 | 1.000000 | 0.995327 | 0.997658 |
| Random Forest | High | 0 | 0 | N/A | N/A | N/A |
| Multinomial Logistic Regression | Low | 147 | 0 | N/A | 0.000000 | 0.000000 |
| Multinomial Logistic Regression | Medium | 214 | 0 | N/A | 0.000000 | 0.000000 |
| Multinomial Logistic Regression | High | 0 | 361 | N/A | N/A | N/A |
| Rules Baseline | Low | 147 | 147 | 1.000000 | 1.000000 | 1.000000 |
| Rules Baseline | Medium | 214 | 214 | 1.000000 | 1.000000 | 1.000000 |
| Rules Baseline | High | 0 | 0 | N/A | N/A | N/A |

Confusion matrices use actual classes as rows and predicted classes as columns. Both axes use Low, Medium, High in that order.

```text
Random Forest          Multinomial Logistic      Rules Baseline
147   0   0              0   0 147               147   0   0
  1 213   0              0   0 214                 0 214   0
  0   0   0              0   0   0                 0   0   0
```

High performance is unevaluable because the held-out region has no true High observations. Logistic Regression classified every held-out observation as High, yielding 361 High false alarms. Low and Medium precision for that model have zero denominators. Random Forest made one Medium-to-Low error. The rules match the labels by construction. Neither the forest's regional score nor the rules' exact agreement establishes three-class generalization.

The Maven test run passed all 321 tests, including seven new JUnit tests. The new cases cover timestamp and value validation, exactly 30 seconds and longer gaps, threshold-version changes, rule cutoffs, shared Weka feature encoding, serialization of both classifiers and the complete fixture experiment.

## Limitations and future work

- The fixture provides seven short synthetic traces on one shared timeline and contains no real hardware readings or observed spoilage outcomes. Synthetic labels represent a course policy and cannot establish true food spoilage risk.
- All 195 High snapshots come from one RAW-MEAT trace. The cutoff is first crossed during one sustained high-temperature episode at 17:13:50 UTC after earlier intervals contributed exposure. Because exposure is cumulative, High persists through the rest of the trace, including recovery. These correlated observations cannot provide independent training and test evidence for High.
- The single regional holdout and coarse product mappings limit generalization. The scoped experiment measures policy imitation. The rules score reflects their use as the label generator, and learned models may reproduce the synthetic rule or exploit correlations in the fixtures.
- Future experiments can add independent runs and multiple sustained High events across products and threshold profiles, including clock gaps and threshold changes. Label provenance and ground truth would need to be established for any study of actual spoilage. Replicate the evaluation across independently grouped runs before selecting a model for broader use.
- Live work remains a later phase. It requires agreement on product codes and feature quality, loading the chosen artifact, after-commit inference, restart catch-up and reading retention, and a dashboard contract for risk source and quality.

## ML-2 expanded-data experiment

ML-2 used only the 40,528 artificial readings described above. The original
2,527 readings and their model artifacts and evaluation log were preserved.
The source for ML-2 was `target/ml-scenarios/generated-readings.jsonl`, SHA-256
`22167df49ae5c13ca445e55ac1a25169e48a15eba4db6bdfb6750f9c3bdfda02`.
The reader verified the dataset hash, row metadata, exposure and label audit,
distinct scenario seeds, and distinct temperature traces against the manifest.
The curves and bounded noise model storage conditions for a course experiment.
They provide no measured spoilage outcomes.

### Split and model selection

The fixed split `region-high-presence-v1` uses seed 5010. Within each region,
scenario IDs are sorted and shuffled separately in two strata, with and
without an observed High label. One scenario from each stratum goes to the
test set and a second to validation. The remaining scenarios are used for
initial fitting. This gives 56 development and 14 test scenarios (80/20),
with 14 validation scenarios held out from those 56. All rows from a scenario
stay together. The algorithm rejects a split without class support or multiple
High scenarios before fitting. `split.json` lists all IDs and per-scenario
label distributions, including the subset with complete exposure history.

| Partition | Scenarios | All records | Low / Medium / High | Complete records | Complete Low / Medium / High |
| --- | ---: | ---: | --- | ---: | --- |
| Training | 42 | 24,391 | 6,192 / 6,319 / 11,880 | 23,231 | 6,192 / 5,159 / 11,880 |
| Validation | 14 | 7,953 | 3,343 / 2,010 / 2,600 | 7,459 | 3,343 / 1,516 / 2,600 |
| Test | 14 | 8,184 | 2,197 / 3,274 / 2,713 | 6,535 | 2,197 / 1,625 / 2,713 |

The independent test partition contains seven distinct High scenarios, one
per region. All nominal categories are fitted from training. The Weka schema
and class order are the same as in ML-1. No rows with incomplete history enter
the Weka fit or validation score. Weka Logistic fits its own preprocessing on
the fitting set, including missing-value replacement, removal of useless
attributes, nominal encoding and numeric standardization during fitting.

Model selection maximized mean per-scenario validation Macro-F1. Each
scenario's Macro-F1 averages F1 only over classes actually present in that
scenario. The candidate grid was fixed before test evaluation. The selected
model for each family was refitted on the 30,690 complete development rows.

| Candidate | Setting | Validation mean scenario Macro-F1 |
| --- | --- | ---: |
| Random Forest | 60 trees, max depth 8 | **0.942786** |
| Random Forest | 100 trees, max depth 16 | 0.941877 |
| Random Forest | 100 trees, unlimited depth | 0.941877 |
| Logistic | ridge 1e-8 | 0.868901 |
| Logistic | ridge 1e-4 | 0.868901 |
| Logistic | ridge 0.01 | **0.868941** |
| Logistic | ridge 1.0 | 0.853023 |

### Measured ML-2 results

The complete-history comparison uses the same 6,535 observations for all
methods. The full-set comparison uses the same 8,184 observations and
routes the 1,649 incomplete-history samples through the rules for both ML
methods. All 14 scenes contribute to both comparisons. The full-set scores
include that offline fallback. The rules baseline generated the labels, which
accounts for its exact agreement.

| Scope | Method | Accuracy | Macro-F1 | Scene mean accuracy | Scene mean Macro-F1 |
| --- | --- | ---: | ---: | ---: | ---: |
| Complete | Random Forest | 0.981637 | 0.978733 | 0.979774 | 0.943153 |
| Complete | Logistic | 0.940627 | 0.933155 | 0.916330 | 0.786214 |
| Complete | Rules | 1.000000 | 1.000000 | 1.000000 | 1.000000 |
| Full | Random Forest + rules | 0.985337 | 0.984678 | 0.981181 | 0.946188 |
| Full | Logistic + rules | 0.952590 | 0.952481 | 0.941978 | 0.882011 |
| Full | Rules | 1.000000 | 1.000000 | 1.000000 | 1.000000 |

| Complete-history method | Class | Precision | Recall | F1 |
| --- | --- | ---: | ---: | ---: |
| Random Forest | Low | 0.993349 | 0.951752 | 0.972106 |
| Random Forest | Medium | 0.938264 | 0.991385 | 0.964093 |
| Random Forest | High | 1.000000 | 1.000000 | 1.000000 |
| Logistic | Low | 0.931346 | 0.938553 | 0.934935 |
| Logistic | Medium | 0.862757 | 0.905231 | 0.883483 |
| Logistic | High | 0.999235 | 0.963509 | 0.981047 |
| Rules | All three classes | 1.000000 | 1.000000 | 1.000000 |

Rows are actual, columns are predicted. Both axes are Low, Medium, High.

```text
Complete history (6,535)
Random Forest          Logistic              Rules
2091  106    0         2062  135    0        2197    0    0
  14 1611    0          152 1471    2           0 1625    0
   0    0 2713            0   99 2614           0    0 2713

All observations (8,184), including fallback
Random Forest          Logistic              Rules
2091  106    0         2062  135    0        2197    0    0
  14 3260    0          152 3120    2           0 3274    0
   0    0 2713            0   99 2614           0    0 2713
```

| Method | Final refit (ms) | Offline full-path inference p95 (µs) |
| --- | ---: | ---: |
| Random Forest | 1,026.032 | 6.000 |
| Logistic | 653.248 | 0.700 |
| Rules | N/A | 0.100 |

Times were measured in one Temurin Java 21.0.12.1 / Weka 3.8.6 Windows run.
Inference timing uses five passes over the full test set after scoring. It
includes snapshot construction and classifier input encoding or offline
fallback dispatch. It excludes history aggregation, I/O, and network latency.
The separate scene JSON files contain accuracy and Macro-F1 for every test
scene, with equal weighting used for the scene means above. Both reloaded
classifiers reproduced the class and probability array exactly on every
complete test row.

### Why the original Logistic result failed

The retrospective audit loaded the original saved model and reproduced its
0% score and 361 false High predictions. The train and test raw headers
matched, with class order `LOW, MEDIUM, HIGH` and the same nominal category
order. `PRODUCE` was supported by VEG-COOL in training. No separate fit of
preprocessing occurred on the test data. The original test humidity range
was 95.4668–96.7409 %RH, entirely above the maximum of 93.3000 %RH in
training. High was exclusive to RAW-MEAT in training and VEG-COOL supplied
only Low for the shared produce category. The fitted default-ridge model
had a maximum absolute coefficient of 838.219166 and assigned probability
1.0 to High for the first actual Low test row. A retrospective refit at ridge
1.0 on the same old training data predicted every test row Low and reached
40.7202% accuracy without recovering Medium. These observations support
distribution shift and unstable extrapolation under weak regularization.
They do not isolate a single causal feature. The retrospective refit never
participated in ML-2 selection.

### Reproduce and interpret

Generate the scenario files first if they are absent. From the repository
root with Java 21 selected, run this command. The default output is
`target/ml-risk-expanded`. A rerun requires a fresh directory, which can be
set with `"-Dexec.args=target/ml-scenarios target/ml-risk-expanded-rerun"`.

```powershell
.\mvnw.cmd -B compile org.codehaus.mojo:exec-maven-plugin:3.5.0:java "-Dexec.mainClass=com.icu.smart_cold_storage_monitor.ml.ExpandedRiskTrainingMain"
.\mvnw.cmd -B test "-Dtest=ExpandedRiskTrainingTest,RiskScenarioGenerationTest,RiskFeaturesTest,WekaRiskModelTest"
```

The run writes `evaluation.txt`, `legacy-logistic-audit.txt`, `split.json`,
scenario score JSON files, project-generated Weka model files, standalone
ARFF headers and model manifests with settings, version, feature metadata
and category mappings. The 12 selected Maven/JUnit tests passed with zero
failures, errors or skips.

The scores cannot be compared as a controlled improvement over ML-1 because
the scenarios, threshold event patterns and class support differ. ML-2 holds
out complete traces and shares curve families and thresholds across the
partitions. Many observations within each session remain correlated, and
High persists after its cumulative duration cutoff. A future evaluation can
hold out whole curve families or independent hardware runs and report
event-level behavior. Real food-spoilage inference requires ground truth
beyond these synthetic rules. No real-time prediction service was enabled
for ML-2.

## ML-3 backend integration

The backend ships the reviewed ML-2 Random Forest with its original Weka
header and metadata. The integration reuses the feature accumulator and
rule policy. It treats a session prefix longer than 30 seconds as incomplete
history. Shorter prefixes contribute zero observed exposure, allowing the
first reading of a newly started simulator session to use the model. The
offline results above are unaffected because the training scenarios begin
at their first reading.

Predictions run after ingestion commits. An append-only prediction record
captures the reading and its recorded threshold version, feature snapshot,
risk level, source, model or rule version, calculation time and quality.
Startup recovery processes pending readings in session and measurement order.
Historical predictions stay associated with the reading that produced them.
The local [demo instructions](../README.md#local-risk-prediction-demonstration)
show the authenticated latest-reading endpoint and the missing-artifact
fallback.

In the recovery audit, a 700-second monitoring gap followed by a valid
five-second interval at 4.0032 °C restored the MODEL source. The model
reported Medium from five seconds of observed exposure. If the upper limit
had been exceeded continuously throughout all 705 seconds, the simulation
rules for that hypothetical trajectory would report High. The history flag
stays incomplete and the independent temperature alert stays active.
This demonstrates possible underestimation after a gap. It does not establish
what happened in the unobserved interval or alter the ML-2 test metrics.
