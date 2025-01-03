package com.clickhouse.jdbc.internal;

/**
 * JDBC driver specific properties. Do not include any ClickHouse client properties here.
 */
public enum DriverProperties {

    PLACEHOLDER("placeholder", "Placeholder for unknown properties");

    private final String key;

    private final String description;

    DriverProperties(String key, String description) {
        this.key = key;
        this.description = description;
    }

}
