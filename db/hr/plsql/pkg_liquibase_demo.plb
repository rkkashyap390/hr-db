CREATE OR REPLACE PACKAGE BODY pkg_liquibase_demo AS

  FUNCTION get_employee_count RETURN NUMBER IS
    l_count NUMBER;
  BEGIN
    SELECT COUNT(*)
      INTO l_count
      FROM employees;
    RETURN l_count;
  END get_employee_count;

  FUNCTION get_employee_name(p_employee_id IN NUMBER) RETURN VARCHAR2 IS
    l_name VARCHAR2(200);
  BEGIN
    SELECT first_name || ' ' || last_name
      INTO l_name
      FROM employees
     WHERE employee_id = p_employee_id;

    RETURN l_name;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RETURN 'NOT_FOUND';
  END get_employee_name;

  PROCEDURE write_test_log(p_message IN VARCHAR2) IS
  BEGIN
    DBMS_OUTPUT.PUT_LINE('[PKG_LIQUIBASE_DEMO] ' || p_message);
  END write_test_log;

END pkg_liquibase_demo;
/