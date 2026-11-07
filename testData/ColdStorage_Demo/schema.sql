PRAGMA foreign_keys=ON;
CREATE TABLE metadata(key TEXT PRIMARY KEY,value TEXT NOT NULL);
CREATE TABLE sources(id INTEGER PRIMARY KEY,title TEXT,url TEXT,scope_note TEXT,accessed_on TEXT);
CREATE TABLE operators(id INTEGER PRIMARY KEY,employee_code TEXT UNIQUE NOT NULL,name TEXT NOT NULL,role TEXT CHECK(role IN ('ADMIN','OPERATOR')),active INTEGER CHECK(active IN(0,1)));
CREATE TABLE suppliers(id INTEGER PRIMARY KEY,name TEXT NOT NULL,contact_note TEXT);
CREATE TABLE products(id INTEGER PRIMARY KEY,sku TEXT UNIQUE NOT NULL,name_en TEXT NOT NULL,category TEXT,unit TEXT NOT NULL,temp_min_c REAL,temp_max_c REAL,rh_min_pct REAL,rh_max_pct REAL,reference_storage_life TEXT,source_id INTEGER REFERENCES sources(id),condition_note TEXT NOT NULL,CHECK(temp_min_c IS NULL OR temp_max_c IS NULL OR temp_min_c<=temp_max_c),CHECK(rh_min_pct IS NULL OR rh_min_pct BETWEEN 0 AND 100),CHECK(rh_max_pct IS NULL OR rh_max_pct BETWEEN 0 AND 100),CHECK(rh_min_pct IS NULL OR rh_max_pct IS NULL OR rh_min_pct<=rh_max_pct));
CREATE TABLE locations(id INTEGER PRIMARY KEY,code TEXT UNIQUE,name_en TEXT,temp_min_c REAL,temp_max_c REAL,rh_min_pct REAL,rh_max_pct REAL,purpose TEXT);
CREATE TABLE lots(id INTEGER PRIMARY KEY,lot_code TEXT UNIQUE NOT NULL,product_id INTEGER NOT NULL REFERENCES products(id),supplier_id INTEGER REFERENCES suppliers(id),location_id INTEGER NOT NULL REFERENCES locations(id),received_at TEXT NOT NULL,packed_at TEXT NOT NULL,demo_use_by_at TEXT NOT NULL,quality_status TEXT CHECK(quality_status IN ('AVAILABLE','HOLD')),packaging TEXT,notes TEXT);
CREATE TABLE movements(id INTEGER PRIMARY KEY,reference_no TEXT UNIQUE NOT NULL,lot_id INTEGER NOT NULL REFERENCES lots(id),operation TEXT NOT NULL CHECK(operation IN('IN','OUT')),quantity INTEGER NOT NULL CHECK(quantity>0),operated_at TEXT NOT NULL,recorded_at TEXT NOT NULL,operator_id INTEGER NOT NULL REFERENCES operators(id),counterparty TEXT,reason TEXT,synthetic INTEGER NOT NULL CHECK(synthetic=1));
CREATE TABLE product_storage_guidance (
        product_id INTEGER PRIMARY KEY REFERENCES products(id),
        shelf_life_min INTEGER NOT NULL CHECK(shelf_life_min > 0),
        shelf_life_max INTEGER NOT NULL CHECK(shelf_life_max >= shelf_life_min),
        shelf_life_unit TEXT NOT NULL CHECK(shelf_life_unit IN ('days','weeks','months')),
        shelf_life_basis TEXT NOT NULL CHECK(shelf_life_basis IN ('REFERENCE','QUALITY_ONLY')),
        humidity_basis TEXT NOT NULL CHECK(humidity_basis IN ('REFERENCE','SIMULATION_ONLY'))
    );
CREATE INDEX idx_movements_lot_time ON movements(lot_id,operated_at);
CREATE TRIGGER movement_time BEFORE INSERT ON movements BEGIN SELECT CASE WHEN NEW.operated_at<(SELECT received_at FROM lots WHERE id=NEW.lot_id) THEN RAISE(ABORT,'movement predates lot receipt') END; END;
CREATE TRIGGER no_negative_stock BEFORE INSERT ON movements WHEN NEW.operation='OUT' BEGIN SELECT CASE WHEN NEW.quantity>COALESCE((SELECT SUM(CASE operation WHEN 'IN' THEN quantity ELSE -quantity END) FROM movements WHERE lot_id=NEW.lot_id),0) THEN RAISE(ABORT,'insufficient stock') END; END;
CREATE TRIGGER movements_no_delete BEFORE DELETE ON movements BEGIN SELECT RAISE(ABORT,'append-only movement ledger'); END;
CREATE TRIGGER movements_no_update BEFORE UPDATE ON movements BEGIN SELECT RAISE(ABORT,'append-only movement ledger'); END;
CREATE VIEW v_inventory AS SELECT l.id AS lot_id,l.lot_code,p.name_en,p.sku,p.unit,lc.code AS location,l.quality_status,l.received_at,l.demo_use_by_at,COALESCE(SUM(CASE m.operation WHEN 'IN' THEN m.quantity ELSE -m.quantity END),0) AS quantity_on_hand,MAX(CASE WHEN m.operation='IN' THEN m.operated_at END) AS last_in_at,MAX(CASE WHEN m.operation='OUT' THEN m.operated_at END) AS last_out_at FROM lots l JOIN products p ON p.id=l.product_id JOIN locations lc ON lc.id=l.location_id LEFT JOIN movements m ON m.lot_id=l.id GROUP BY l.id;
CREATE VIEW v_movement_history AS SELECT m.reference_no,l.lot_code,p.name_en,m.operation,m.quantity,p.unit,m.operated_at,m.recorded_at,o.employee_code,o.name AS operator_name,m.counterparty,m.reason FROM movements m JOIN lots l ON l.id=m.lot_id JOIN products p ON p.id=l.product_id JOIN operators o ON o.id=m.operator_id;
