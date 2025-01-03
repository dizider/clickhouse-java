package com.clickhouse.jdbc.internal;

import com.clickhouse.jdbc.JdbcIntegrationTest;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import java.util.Properties;

import static org.testng.Assert.assertEquals;

public class JdbcConfigurationTest extends JdbcIntegrationTest {
    @Test(groups = { "integration" })
    public void testCleanUrl() {
        JdbcConfiguration configuration = new JdbcConfiguration("jdbc:clickhouse://localhost:8123/clickhouse?param1=value1&param2=value2", new Properties());

        String url = "jdbc:clickhouse://localhost:8123/clickhouse?param1=value1&param2=value2";
        String cleanUrl = configuration.cleanUrl(url);
        assertEquals(cleanUrl, "http://localhost:8123/clickhouse?param1=value1&param2=value2");

        // we do not support guessing by port numbers
        url = "jdbc:clickhouse://localhost:8443/clickhouse?param1=value1&param2=value2";
        cleanUrl = configuration.cleanUrl(url);
        assertEquals(cleanUrl, "http://localhost:8443/clickhouse?param1=value1&param2=value2");

        Properties info = new Properties();
        configuration = new JdbcConfiguration("jdbc:clickhouse:https://localhost:8123/clickhouse?param1=value1&param2=value2", info);
        url = "jdbc:clickhouse:https://localhost:8443/clickhouse?param1=value1&param2=value2";
        cleanUrl = configuration.cleanUrl(url);
        assertEquals(cleanUrl, "https://localhost:8443/clickhouse?param1=value1&param2=value2");
    }
}
