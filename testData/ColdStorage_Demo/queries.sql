PRAGMA foreign_keys=ON;
SELECT * FROM v_inventory ORDER BY demo_use_by_at;
SELECT * FROM v_movement_history ORDER BY operated_at;
SELECT p.*,g.shelf_life_min,g.shelf_life_max,g.shelf_life_unit,g.shelf_life_basis,
       g.humidity_basis,s.url
FROM products p JOIN product_storage_guidance g ON g.product_id=p.id
JOIN sources s ON s.id=p.source_id;
SELECT sku,name_en,unit,SUM(quantity_on_hand) FROM v_inventory WHERE quality_status='AVAILABLE' GROUP BY sku,name_en,unit;
