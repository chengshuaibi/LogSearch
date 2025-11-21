    package org.atguigu.plugintest;

public enum DbType {
    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver", "jdbc:mysql://localhost:3306/your_db?useSSL=false&serverTimezone=UTC"),
    DAMENG("Dameng (达梦)", "dm.jdbc.driver.DmDriver", "jdbc:dm://localhost:5236?schema=SYSDBA");

    private final String displayName;
    private final String driverClass;
    private final String defaultUrl;

    DbType(String displayName, String driverClass, String defaultUrl) {
        this.displayName = displayName;
        this.driverClass = driverClass;
        this.defaultUrl = defaultUrl;
    }

    public String getDriverClass() { return driverClass; }
    public String getDefaultUrl() { return defaultUrl; }

    @Override
    public String toString() { return displayName; } //用于 ComboBox 显示
}
