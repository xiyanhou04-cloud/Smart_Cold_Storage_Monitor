# Manual Inventory Database

This SQLite database stores operator-entered warehouse information only. It contains 12 products, 24 lots, 72 movements, four operators, three suppliers and five storage zones. All identities, stock transactions and dates are fictional course examples.

Products include static reference temperature/humidity conditions, source links and reference storage life. Lots include supplier, location, received time, packaging time, synthetic use-by date, packaging and quality status. Movements record quantity, unit, business time, submission time, operator, reference and reason. The application should obtain operator identity from authentication and set recorded_at on the server. No new entry UI is implemented here.

Stock is calculated from append-only IN/OUT movements; overdrafts are rejected. HOLD lots remain physical stock but are excluded from available-stock queries. Do not add quantities across different units. Enable PRAGMA foreign_keys=ON on every connection.

Use v_inventory and v_movement_history for queries. schema.sql defines structure; database_dump.sql restores structure and data; queries.sql provides examples. The snapshot is 2026-11-05T17:30:00Z. This file is not a drop-in replacement for the application database.

Reference conditions: https://extension.sdstate.edu/storage-life-vegetables and https://www.foodsafety.gov/food-safety-charts/cold-food-storage-charts . Produce temperatures are converted from Fahrenheit. The latter source is household guidance, not commercial shelf-life validation. Frozen durations concern quality. Unspecified reference temperature bounds remain NULL; synthetic expiry dates do not establish safety. Location operating settings are distinct from product reference conditions.

There are no sensor readings, sensor sessions or raw sensor frames in this database. Sensor data lives independently in ../SHT45_30min. Sensor sampling never changes stock. Column names use name_en.

Product storage guidance stores numeric shelf-life ranges and units, their reference or quality-only basis, and humidity provenance. Each SKU has a humidity band for replay. The raw meat, egg and freezer bands are simulation-only ambient targets. The cited references did not specify those humidity limits. Batch packing and use-by dates remain synthetic, independently entered values.
