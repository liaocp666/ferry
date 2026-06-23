package one.sanji.ferry.base;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;

public class DbContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(DbContext.class);

    private static HikariDataSource dataSource;

    private static String propertiesPath;

    // 私有化构造，防止外部 new
    private DbContext() {
    }

    public static void loadConfig(String propertiesPath) {
        // 校验文件是否存在
        Path path = Paths.get(propertiesPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            LOGGER.error("数据库配置文件不存在：{}", propertiesPath);
            throw new RuntimeException("数据库配置文件不存在");
        }
        DbContext.propertiesPath = propertiesPath;
    }

    private static synchronized void init() {
        if (dataSource == null) {
            HikariConfig dbConfig = new HikariConfig(propertiesPath);
            dataSource = new HikariDataSource(dbConfig);
        }
    }

    /**
     * 全局获取数据库连接（核心：每次获取都是从池子里借一个）
     */
    public static Connection getConnection() throws SQLException {
        if (dataSource == null) {
            init();
        }
        return dataSource.getConnection();
    }

    /**
     * 系统关闭时释放连接池
     */
    public static void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

}
