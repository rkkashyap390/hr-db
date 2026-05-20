CREATE OR REPLACE PACKAGE pkg_liquibase_demo AS
  FUNCTION get_employee_count RETURN NUMBER;
  FUNCTION get_employee_name(p_employee_id IN NUMBER) RETURN VARCHAR2;
  PROCEDURE write_test_log(p_message IN VARCHAR2);
END pkg_liquibase_demo;
/