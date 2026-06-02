create or replace
PACKAGE BODY pkg_liquibase_demo AS

  FUNCTION get_employee_count RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*)
      INTO l_count
      FROM employees;
    RETURN l_count;
  END get_employee_count;

  FUNCTION get_department_count RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*)
      INTO l_count
      FROM departments;
    RETURN l_count;
  END get_department_count;

  FUNCTION get_order_count RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*)
      INTO l_count
      FROM lb_demo_orders;
    RETURN l_count;
  EXCEPTION
    WHEN OTHERS THEN
      RETURN -1;
  END get_order_count;

  FUNCTION get_order_total_sum RETURN NUMBER IS
    l_total NUMBER;
  BEGIN
    SELECT NVL(SUM(order_total), 0)
      INTO l_total
      FROM lb_demo_orders;
    RETURN l_total;
  EXCEPTION
    WHEN OTHERS THEN
      RETURN -1;
  END get_order_total_sum;

  FUNCTION get_order_status_count(p_status IN VARCHAR2) RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*)
      INTO l_count
      FROM lb_demo_orders
     WHERE UPPER(order_status) = UPPER(TRIM(p_status));
    RETURN l_count;
  EXCEPTION
    WHEN OTHERS THEN
      RETURN -1;
  END get_order_status_count;

  FUNCTION get_employee_name(p_employee_id IN NUMBER) RETURN VARCHAR2 IS
    lc_name VARCHAR2(200);
  BEGIN
    SELECT first_name || ' ' || last_name
      INTO lc_name
      FROM employees
     WHERE employee_id = p_employee_id;

    RETURN lc_name;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RETURN 'NOT_FOUND';
  END get_employee_name;

  FUNCTION get_demo_message RETURN VARCHAR2 IS
  BEGIN
    RETURN 'PKG_LIQUIBASE_DEMO is deployed';
  END get_demo_message;

  PROCEDURE write_test_log(p_message IN VARCHAR2) IS
  BEGIN
    DBMS_OUTPUT.PUT_LINE(
      '[PKG_LIQUIBASE_DEMO][SIT-UPDATED][' ||
      TO_CHAR(SYSTIMESTAMP, 'YYYY-MM-DD HH24:MI:SS.FF3') ||
      '] ' || p_message
    );
  END write_test_log;

END pkg_liquibase_demo;

/
