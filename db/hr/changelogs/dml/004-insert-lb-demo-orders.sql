--liquibase formatted sql
--changeset hr-db:004-insert-lb-demo-orders labels:004-insert-lb-demo-orders

INSERT INTO LB_DEMO_ORDERS (ORDER_ID, CUSTOMER_NAME, ORDER_STATUS, ORDER_TOTAL)
VALUES (1008, 'Liam Anderson', 'NEW', 315.25);

INSERT INTO LB_DEMO_ORDERS (ORDER_ID, CUSTOMER_NAME, ORDER_STATUS, ORDER_TOTAL)
VALUES (1009, 'Sophia Clark', 'PAID', 640.10);

--rollback DELETE FROM LB_DEMO_ORDERS WHERE ORDER_ID IN (1008, 1009);
