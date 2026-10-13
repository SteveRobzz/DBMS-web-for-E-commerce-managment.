-- =====================================================================
--  E-Commerce Management System  |  03_dml_queries.sql
--  DML (SELECT / UPDATE / DELETE) and TCL (SAVEPOINT / ROLLBACK / COMMIT)
--  examples. These are the same kinds of statements the Java application
--  runs through JDBC. Safe to run after 02_insert_data.sql.
-- =====================================================================

-- ---------- SELECT ----------------------------------------------------
-- All active products with category and GST
SELECT p.PRODUCT_ID, p.PRODUCT_NAME, c.CATEGORY_NAME, p.PRICE, c.GST_RATE, p.STOCK
FROM   PRODUCTS p JOIN CATEGORIES c ON c.CATEGORY_ID = p.CATEGORY_ID
WHERE  p.IS_ACTIVE = 'Y'
ORDER  BY c.CATEGORY_NAME, p.PRODUCT_NAME;

-- Low-stock alert (fewer than 10 units)
SELECT PRODUCT_NAME, STOCK FROM PRODUCTS WHERE STOCK < 10 AND IS_ACTIVE = 'Y';

-- Valid coupons as on today
SELECT * FROM COUPONS WHERE IS_ACTIVE = 'Y' AND VALID_TILL >= TRUNC(SYSDATE);

-- Revenue by category (JOIN + GROUP BY)
SELECT c.CATEGORY_NAME, SUM(oi.QUANTITY) AS UNITS, SUM(oi.LINE_TOTAL) AS SALES
FROM   ORDER_ITEMS oi
JOIN   ORDERS o     ON o.ORDER_ID = oi.ORDER_ID
JOIN   PRODUCTS p   ON p.PRODUCT_ID = oi.PRODUCT_ID
JOIN   CATEGORIES c ON c.CATEGORY_ID = p.CATEGORY_ID
WHERE  o.ORDER_STATUS <> 'CANCELLED'
GROUP  BY c.CATEGORY_NAME
ORDER  BY SALES DESC;

-- Customers whose total spend is above the average order value (sub-query + HAVING)
SELECT u.FULL_NAME, COUNT(o.ORDER_ID) AS ORDERS, SUM(o.GRAND_TOTAL) AS TOTAL_SPENT
FROM   APP_USERS u JOIN ORDERS o ON o.USER_ID = u.USER_ID
WHERE  o.ORDER_STATUS <> 'CANCELLED'
GROUP  BY u.FULL_NAME
HAVING SUM(o.GRAND_TOTAL) > (SELECT AVG(GRAND_TOTAL) FROM ORDERS WHERE ORDER_STATUS <> 'CANCELLED');

-- Top selling products using the view
SELECT * FROM (SELECT * FROM V_PRODUCT_SALES ORDER BY UNITS_SOLD DESC) WHERE ROWNUM <= 5;

-- ---------- UPDATE ----------------------------------------------------
-- Restock a product
UPDATE PRODUCTS SET STOCK = STOCK + 20 WHERE PRODUCT_NAME = 'Green Tea 100 Bags';

-- 5% price increase for every Electronics product
UPDATE PRODUCTS SET PRICE = ROUND(PRICE * 1.05, 2)
WHERE  CATEGORY_ID = (SELECT CATEGORY_ID FROM CATEGORIES WHERE CATEGORY_NAME = 'Electronics');

-- Move an order to the next status
UPDATE ORDERS SET ORDER_STATUS = 'SHIPPED'
WHERE  ORDER_STATUS = 'PLACED'
AND    USER_ID = (SELECT USER_ID FROM APP_USERS WHERE USERNAME = 'priya');

-- ---------- DELETE ----------------------------------------------------
-- Remove expired coupons
DELETE FROM COUPONS WHERE VALID_TILL < TRUNC(SYSDATE);

-- ---------- TCL: SAVEPOINT / ROLLBACK / COMMIT ------------------------
SAVEPOINT before_price_cut;
UPDATE PRODUCTS SET PRICE = PRICE - 100 WHERE PRODUCT_NAME = 'Denim Jeans';
ROLLBACK TO before_price_cut;      -- price cut is undone

-- Undo the demo updates above so the app data stays as inserted,
-- or replace ROLLBACK with COMMIT to keep them.
ROLLBACK;
