# Data Boundaries

ColdStorage_Demo contains only manual inventory business records and static product storage requirements. SHT45_30min contains an independent synthetic sensor stream. Sensor measurements do not create inventory transactions.

The intended sensor subsystem runs automatically 24/7 with UTC acquisition timestamps assigned by a synchronized ESP32 clock. The existing files are a finite 30-minute replay; continuous firmware and NTP synchronization have not been implemented.

Inventory and sensor data can be correlated at the query layer through location and UTC intervals, without copying measurements into the inventory database. The demo maps five simulated devices to the five example zones. The feed is separate from stock lots.

All project filenames, documentation, code comments and stored example text must be English.
