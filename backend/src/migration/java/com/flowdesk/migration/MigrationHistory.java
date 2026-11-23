package com.flowdesk.migration;

import java.sql.Connection;
import java.sql.SQLException;

/** Exact installed application schema. Does not migrate or repair. */
public final class MigrationHistory {
    public static void requireCurrent(Connection connection) throws SQLException {
        try (var st = connection.createStatement(); var rs = st.executeQuery(
                "SELECT version,success FROM flowdesk.flyway_schema_history ORDER BY installed_rank")) {
            for (String version : new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11"}) {
                if (!rs.next() || !version.equals(rs.getString(1)) || !rs.getBoolean(2)) {
                    throw new IllegalArgumentException("schema/version");
                }
            }
            if (rs.next()) throw new IllegalArgumentException("schema/version");
        }
    }
    private MigrationHistory() { }
}
