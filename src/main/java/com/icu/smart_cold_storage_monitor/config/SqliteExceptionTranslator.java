package com.icu.smart_cold_storage_monitor.config;

import java.sql.SQLException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * Maps SQLite result codes to Spring's {@link DataAccessException} hierarchy.
 *
 * <p>The Xerial driver reports no SQL state, so Spring's default translation turns constraint violations into
 * {@code UncategorizedSQLException}. Services rely on {@link DuplicateKeyException} for UNIQUE conflicts (e.g. a
 * retried reading) and {@link DataIntegrityViolationException} for other constraint failures.
 */
public class SqliteExceptionTranslator implements SQLExceptionTranslator {

    private final SQLExceptionTranslator fallback = new SQLStateSQLExceptionTranslator();

    @Override
    public DataAccessException translate(String task, String sql, SQLException ex) {
        if (ex instanceof SQLiteException sqlite) {
            SQLiteErrorCode code = sqlite.getResultCode();
            String message = task + "; " + (sql == null ? "" : "SQL [" + sql + "]; ") + ex.getMessage();
            if (code == SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE || code == SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY) {
                return new DuplicateKeyException(message, ex);
            }
            if (code.name().startsWith("SQLITE_CONSTRAINT")) {
                return new DataIntegrityViolationException(message, ex);
            }
            if (code.name().startsWith("SQLITE_BUSY") || code.name().startsWith("SQLITE_LOCKED")) {
                return new CannotAcquireLockException(message, ex);
            }
        }
        return fallback.translate(task, sql, ex);
    }
}
