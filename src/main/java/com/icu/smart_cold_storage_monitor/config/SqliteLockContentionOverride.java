package com.icu.smart_cold_storage_monitor.config;

import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.SQLException;
import org.sqlite.SQLiteException;

/**
 * Tells Hikari to discard a connection that failed on SQLite's write lock (BUSY or LOCKED) instead of reusing it.
 *
 * <p>When {@code BEGIN IMMEDIATE} times out, the Xerial driver has already recorded {@code autoCommit=false} on the
 * connection although SQLite never started the transaction. Reused from the pool, that connection makes the next
 * Spring transaction skip its own {@code BEGIN}: its statements commit one by one and its commit fails with
 * "no transaction is active", so the transaction is no longer atomic. A new connection costs only opening the file.
 */
public class SqliteLockContentionOverride implements SQLExceptionOverride {

    @java.lang.Override
    public SQLExceptionOverride.Override adjudicate(SQLException ex) {
        if (ex instanceof SQLiteException sqlite) {
            String code = sqlite.getResultCode().name();
            if (code.startsWith("SQLITE_BUSY") || code.startsWith("SQLITE_LOCKED")) {
                return SQLExceptionOverride.Override.MUST_EVICT;
            }
        }
        return SQLExceptionOverride.Override.CONTINUE_EVICT;
    }
}
