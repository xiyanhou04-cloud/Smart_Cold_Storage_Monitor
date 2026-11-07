# Extensible Cold Storage Zoning

## Status and scope

This is the target design for extending the five-zone course prototype. The [current integration contract](STORAGE_ZONE_INTEGRATION.md) is authoritative for the running API and SQLite schema. The catalog codes, schema extensions, and endpoints marked **proposed** below are implementation requirements for a future change. The original demonstration has five locations. The [seven-zone replay fixture](../testData/SevenZone_Demo/README.md) adds berries and tropical fruit using the current schema and API.

A physical location is a room, cabinet or independently monitored compartment. A storage profile describes the conditions required by a particular SKU, variety, pack state and storage stage. A segregation policy governs which products may share a room, shelf, enclosure or air circuit. One profile can be approved for several locations. Multiple profiles may share a location when their environmental ranges overlap, the room's configured operating range fits each profile, and the segregation policy permits the combination.

## Proposed zone catalog

These are reusable zone types. They are distinct from location codes and product profiles. The produce temperature and humidity bands are screening values for zoning. Use a documented commodity profile for acceptance and alerts. A blank humidity requirement is represented by null.

| Zone type | Candidate products | Screening temperature (C) | Screening humidity (%RH) | Placement requirements |
| --- | --- | --- | --- | --- |
| PRODUCE-COLD-HUMID | Broccoli, leafy greens, carrots, cauliflower | 0 to 2 | 90 to 98 | Keep ethylene-sensitive goods away from incompatible emitters. Preserve commodity-specific humidity bands. |
| FRUIT-COLD-BERRY | Strawberries, blueberries, other suitable berries | 0 to 2 | 90 to 98 | Verify the commodity profile, packaging and storage duration. |
| FRUIT-COLD-POME | Apples and pears with compatible variety profiles | Profile-specific near 0 | Profile-specific | Segregate ethylene emitters from sensitive produce. Use a cultivar-specific profile for long storage. |
| PRODUCE-COOL-HUMID | Cucumbers and eligible eggplant varieties | 10 to 12.5 for cucumber | About 95 for cucumber | Set commodity-specific minimums for chilling-sensitive produce. |
| FRUIT-MODERATE-COOL | Citrus and compatible subtropical fruit | Profile-specific | Profile-specific | Match species, variety, origin and expected storage duration. |
| FRUIT-TROPICAL | Green bananas and other compatible chilling-sensitive fruit | Profile and maturity stage | Profile-specific | Verify maturity, ethylene behavior and chilling sensitivity. |
| PRODUCE-DRY-VENTILATED | Cured onions, garlic and other compatible dry produce | Profile-specific | Profile-specific | Use ventilation and separate from high-humidity zones. |
| PRODUCE-RIPENING | Bananas, avocados and other produce undergoing intentional ripening | Recipe-specific | Recipe-specific | Dedicated air circuit and controlled process. Store ethylene setpoint and exposure only when supported. |
| CHILLED-RAW-MEAT | Raw beef, pork, lamb | 0 to 4 demo target | Profile-specific | Segregate raw species and protect other stock from leaks. |
| CHILLED-RAW-POULTRY | Raw chicken and turkey | 0 to 4 demo target | Profile-specific | Apply dedicated segregation and containment policy. |
| CHILLED-SEAFOOD | Fresh finfish and compatible chilled seafood | Profile-specific | Profile-specific | Validate ice, drainage, packaging and product-specific rules. Live shellfish requires its own approved profile. |
| CHILLED-EGGS | Shell eggs | 0 to 4 demo target | Profile-specific | Follow label, packaging and facility policy. |
| CHILLED-DAIRY | Milk, yogurt and compatible cheeses | 0 to 4 demo target | Profile-specific | Check supplier labels. Specialty cheese aging requires its own profile. |
| CHILLED-READY-TO-EAT | Prepared meals, cooked deli products, cut fruit | 0 to 4 demo target | Profile-specific | Segregate from raw animal products and apply the product date-marking policy. |
| CHILLED-BEVERAGE | Products sold with a refrigerated storage requirement | Label-specific | Profile-specific | Store packaging and open-state requirements. |
| FROZEN-RAW | Frozen raw meat, poultry and seafood | At or below -18 demo limit | Profile-specific | Apply raw-product handling and packaging rules. |
| FROZEN-PACKAGED | Frozen produce, frozen meals, desserts and ice cream | At or below -18 baseline, subject to SKU limits | Profile-specific | Maintain compatible packaging and temperature stability. |
| SPECIALTY-VALIDATED | Controlled-atmosphere produce, specialty aging, flowers and other approved nonfood goods | Validated profile | Validated profile | Require the appropriate separate domain and equipment. Monitor any additional required metrics. |
| MEDICAL-VALIDATED | Approved medicines, vaccines and biologics | Label and protocol | Label and protocol | Dedicated validated storage and monitoring. Manual disposition under the applicable protocol. |

Use receiving, staging and quarantine as location purposes. Assign each a compatible storage profile and a maximum dwell time where applicable. A quality hold affects lot availability and workflow. It does not define a temperature band. Additional domains such as laboratory or hazardous materials require a dedicated facility policy before they can be admitted. Unknown products enter manual review pending profile approval.

The current VEG-COLD and VEG-COOL locations correspond to the proposed PRODUCE-COLD-HUMID and PRODUCE-COOL-HUMID types. RAW-MEAT is a legacy mixed demo location. Preserve its existing code and scope while modeling future raw meat and poultry segregation. EGGS maps to CHILLED-EGGS, and the current FROZEN fixture maps to FROZEN-RAW. New type codes create no devices or physical locations automatically.

### Classification rules

1. Identify SKU, variety, storage state, packaging and storage stage. For example, whole strawberries, cut fruit and frozen strawberries use distinct profiles.
2. Select an approved, effective profile and find candidate locations in the same storage domain. A room's entire operating band for each required metric must fit within the profile's allowed band. A missing bound imposes no bound on that side. Missing required location limits or monitoring capabilities require review.
3. Evaluate segregation using location capabilities and existing stock. Include ethylene production and sensitivity, raw and ready-to-eat separation, species, allergens, leak containment, odor and shared air where relevant. Apply the site's documented rules to each pair of groups. For example, an apple profile and a leafy-green profile can overlap in temperature while still requiring separate air space.
4. Report ELIGIBLE, INELIGIBLE or REVIEW_REQUIRED with stable reason codes. Keep placement eligibility separate from the environmental status of an existing lot.
5. On intake or transfer, validate against the approved profile and current occupants within the write transaction. Record the applied profile version and the operator. Keep the lot's entered use-by date separate from the profile's reference storage life.

## Proposed database contract

Reuse products, locations, lots, movements and sources. Inventory stays independent from the raw sensor store. Use immutable codes for API joins and numeric keys for SQLite relations. Preserve the existing columns during the migration.

| Proposed entity | Minimum fields and constraints |
| --- | --- |
| zone_types | code primary key, name_en, domain, description, enabled |
| location extension | zone_type_code foreign key, parent_location_id nullable foreign key, purpose, enabled, air_circuit_code nullable. Reuse existing operating temperature and RH fields. |
| storage_profiles | id primary key, code, version, product_id foreign key, variety nullable, storage_state, packaging_state, stage, source_id foreign key, basis, effective_from, effective_to nullable, approval_status. Unique code plus version. Published versions are immutable. |
| storage_profile_limits | profile_id foreign key, metric, min_value nullable, max_value nullable, required. Primary key profile_id plus metric. At least one bound for each bounded metric, ordered bounds, and RH in 0 to 100. Initial metrics TEMPERATURE_C and HUMIDITY_PCT. |
| profile_storage_guidance | profile_id primary key, shelf_life_min nullable, shelf_life_max nullable, shelf_life_unit nullable, shelf_life_basis nullable, notes. Keep calendar months as months. |
| segregation_groups and profile_group_memberships | Group code and profile membership. Support multiple groups per profile. Examples ETHYLENE_EMITTER, ETHYLENE_SENSITIVE, RAW_POULTRY and READY_TO_EAT. |
| segregation_rules | Group pair, outcome, separation scope, source_id, effective interval. Canonicalize pair ordering. An unresolved rule or unverified required separation yields REVIEW_REQUIRED. |
| lot placement history | lot_id, location_id, profile_id, effective_from, effective_to nullable, operator_id. No overlapping intervals for a lot. Location and profile changes share the inventory transaction. |
| location device bindings | location_id, device_id, metric capability, effective_from, effective_to nullable. One active primary source per location and required metric. Keep demo configuration as a fallback during migration. |

Define domain values FOOD, MEDICAL and SPECIALTY. Keep purpose values STORAGE, RECEIVING, STAGING, QUARANTINE and PROCESSING separate from domain. Metric names carry their units. Extra metrics such as CO2 or ethylene require defined units and supported devices before a profile can use them. A sensor reading supplies an observation. It cannot establish compatibility, automatically authorize a transfer or edit stock.

Backfill current locations and copy existing product bounds and guidance into explicit legacy profiles. Preserve source references and SIMULATION_ONLY flags. Backfill lot placement from the available snapshot with documented provenance. Historical placement before the known snapshot remains unknown unless reliable history exists. Preserve lot and movement identifiers and retain the existing replay endpoint. Use the effective placement, profile and device binding at the replay instant once historical versions exist.

## Proposed API contract for teammates

All routes below are proposals except the two existing assessment routes. Reuse current session authentication and permission checks. Expose a dedicated catalog so empty zones can appear in the frontend. Use UTC ISO 8601 timestamps, Celsius, percentage RH and JSON null for unspecified values.

| Method and path | Contract |
| --- | --- |
| GET /api/warehouse/locations | Return physical locations including empty locations. Each item contains code, name, zoneTypeCode, domain, purpose, operatingLimits, enabled and monitoredMetrics. |
| GET /api/warehouse/products/{sku}/storage-profiles | Return profile code, version, storage state, packaging state, stage, bounds, guidance, provenance and approval state. Filter effective profiles with optional at. |
| POST /api/warehouse/placement-checks | Accept locationCode, sku, profileCode, profileVersion and optional lotCode. Evaluate current configuration and occupants. Return decision, evaluatedAt and findings. This query does not create an inventory movement. |
| GET /api/monitoring/assessments | Existing live endpoint. Preserve its response. Add optional profileCode and profileVersion to lot metadata after migration. |
| GET /api/monitoring/replay/assessments?at=... | Existing replay endpoint. After migration, resolve profile, placement and device binding at the requested time. |

Represent each operating or product bound as an object with metric, min, max and required. Findings use code, severity and message, matching the assessment response convention. Proposed placement codes include PROFILE_UNAPPROVED, DOMAIN_MISMATCH, OPERATING_BAND_INCOMPATIBLE, METRIC_UNSUPPORTED, SEGREGATION_REQUIRED and STORAGE_STATE_MISMATCH. A valid check that finds incompatibility returns a normal decision response. Invalid inputs use 400, unknown codes use 404, and unavailable inventory uses 503. A placement check is advisory until the actual write transaction revalidates it. Show environmentStatus separately from status and from the placement decision.

## Implementation handoff

1. Read [Storage Zones and Integration Contract](STORAGE_ZONE_INTEGRATION.md), the SQLite fixture schema and the existing monitoring records before editing. The current response model is MonitoringModels.java and the current routing is application-monitoring-demo.yaml.
2. Add versioned migrations and a read-only catalog endpoint first. Retain support for the five-zone fixture during the transition. Add one berry and one tropical fruit fixture to exercise distinct fruit profiles.
3. Implement compatibility and effective-time resolution before enabling placement writes. Keep configuration out of frontend hardcoded rules. Avoid relying on a single category-to-zone enum.
4. Test a berry and a leafy vegetable with overlapping limits, an ethylene-emitting fruit with a sensitive neighbor, a tropical fruit in a near-freezing room, raw poultry near ready-to-eat stock, an empty catalog location, a medical profile with missing monitoring capability, and replay across a profile or location change. Preserve the original five-zone replay test.

## References and interpretation

- [UC Davis short-term produce compatibility chart](https://postharvest.ucdavis.edu/compatibility-chart-short-term-transport-or-storage) provides produce screening groups and compatibility context.
- [UC Davis cucumber fact sheet](https://postharvest.ucdavis.edu/produce-facts-sheets/cucumber) provides cucumber-specific storage and chilling-injury guidance.
- [FDA Food Code 2026](https://www.fda.gov/media/194741/download) provides a US model-code reference for separation and cold holding. Record the applicable local rule and site policy before configuring real stock.
- [USDA FSIS freezer guidance](https://ask.fsis.usda.gov/article/What-is-the-right-temperature-for-the-freezer) provides a freezer quality benchmark. Use tighter limits where the SKU requires them.
- [CDC Vaccine Storage and Handling Toolkit](https://www.cdc.gov/vaccines/hcp/downloads/storage-handling-toolkit.pdf) describes dedicated vaccine equipment and handling. Use the product label and relevant protocol for medical profiles.
