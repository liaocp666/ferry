# Ferry - 高性能数据库同步工具

[![License](https://img.shields.io/badge/license-Apache%20License%202.0-red)](LICENSE)
[![Java](https://img.shields.io/badge/java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Maven](https://img.shields.io/badge/maven-3.8+-red.svg)](https://maven.apache.org/)

Ferry（渡轮）是一个基于 Java 21 开发的高性能、可配置的数据库同步工具，采用 Shipper-Receiver 架构模式，支持增量同步、差异同步等多种数据同步策略。

## ✨ 特性

- 🚀 **高性能**: 基于 Java 21 虚拟线程（Virtual Threads），实现高并发数据处理
- 🔄 **多种同步模式**: 支持差异同步（DIFF）和增量追加（ADD）两种模式
- ⏰ **定时调度**: 基于 Cron 表达式的灵活任务调度
- 📦 **数据压缩**: 使用 Kryo 序列化 + Deflate 压缩，减少传输开销
- 🔧 **配置驱动**: XML 配置文件定义同步通道和任务，无需修改代码
- 🗄️ **数据库支持**: 基于 MariaDB JDBC，支持兼容 MySQL 的数据库
- 🛡️ **事务安全**: 完整的事务管理和异常回滚机制
- 🔒 **并发控制**: 信号量（Semaphore）控制并发执行，防止资源竞争

## 🏗️ 架构设计

Ferry 采用分布式 Shipper-Receiver 架构：

```
┌─────────────────┐                    ┌──────────────────┐
│   Ferry Shipper │                    │ Ferry Receiver   │
│   (发货端)       │                    │ (收货端)          │
│                 │                    │                  │
│ • 读取源数据库   │  ──────►          │ • 接收数据文件    │
│ • 提取变更数据   │   数据文件         │ • 写入目标数据库  │
│ • 序列化压缩     │   (压缩)           │ • 反序列化解压    │
│ • 生成数据文件   │                    │ • 应用数据变更    │
└─────────────────┘                    └──────────────────┘
```

### 核心模块

- **base**: 基础框架模块，提供核心抽象类和工具类
- **shipper**: 发货端，负责从源数据库提取数据并生成数据文件
- **receiver**: 收货端，负责读取数据文件并写入目标数据库

## 📋 前置要求

- JDK 21 或更高版本
- Maven 3.8+
- MariaDB / MySQL 数据库

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/your-username/ferry.git
cd ferry
```

### 2. 编译打包

```bash
mvn clean package
```

编译完成后，会在 `shipper/target` 和 `receiver/target` 目录下生成可执行的 JAR 文件。

### 3. 配置数据源

编辑 `datasource.properties` 文件：

```properties
jdbcUrl=jdbc:mariadb://localhost:3306/ferry-man?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
username=root
password=root
```

### 4. 配置同步任务

编辑 `config.xml` 文件，定义同步通道和任务（详见下方配置说明）。

### 5. 启动应用

**启动 Shipper（发货端）:**
```bash
java -jar shipper/target/shipper-1.0-SNAPSHOT.jar
```

**启动 Receiver（收货端）:**
```bash
java -jar receiver/target/receiver-1.0-SNAPSHOT.jar
```

## 📖 配置说明

### config.xml 配置示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<config>
    <!-- 定义同步通道 -->
    <channel name="fast_sync_job">
        <!-- Cron 表达式，每 5 秒执行一次 -->
        <cron>0/5 * * * * ?</cron>
        
        <!-- 并发线程数 -->
        <concurrency>1</concurrency>
        
        <!-- 数据文件存储目录 -->
        <dir>D:\test\</dir>

        <!-- 定义同步任务 -->
        <task name="order_sync">
            <!-- 同步模式: DIFF(差异同步) 或 ADD(增量追加) -->
            <syncMode>DIFF</syncMode>
            
            <!-- 每次处理的最大数据量 -->
            <maxDataSize>1000</maxDataSize>

            <!-- 源表配置 -->
            <source table="t_order" pk="order_id"/>
            
            <!-- 影子表配置（仅 DIFF 模式需要）-->
            <stage table="sync_t_order" pk="order_id"/>
            
            <!-- 目标表配置 -->
            <target table="orders" pk="id"/>

            <!-- SQL 执行配置（仅 DIFF 模式）-->
            <execute>
                <!-- 插入操作 SQL -->
                <insert sourceKey="order_id" stageKey="id">
                    <sql><![CDATA[
                        SELECT order_no, customer_name, customer_phone, 
                               total_amount, order_status, create_time
                        FROM t_order o 
                        LEFT JOIN sync_t_order so ON o.order_id = so.order_id
                        WHERE so.order_id IS NULL
                    ]]></sql>
                </insert>

                <!-- 更新操作 SQL -->
                <update sourceKey="order_id" stageKey="id">
                    <sql><![CDATA[
                        SELECT order_no, customer_name, customer_phone,
                               total_amount, order_status, update_time
                        FROM t_order o 
                        LEFT JOIN sync_t_order so ON o.order_id = so.order_id
                        WHERE so.order_id IS NOT NULL 
                        AND o.order_status != so.order_status
                    ]]></sql>
                </update>

                <!-- 删除操作 SQL -->
                <delete sourceKey="order_id" stageKey="id">
                    <sql><![CDATA[
                        SELECT so.order_no, so.customer_name, so.total_amount,
                               so.order_status, so.create_time
                        FROM t_order o 
                        RIGHT JOIN sync_t_order so ON o.order_id = so.order_id
                        WHERE o.order_id IS NULL
                    ]]></sql>
                </delete>
            </execute>

            <!-- 字段映射配置 -->
            <fieldMappings>
                <mapping source="order_id" target="id"/>
                <mapping source="order_no" target="order_number"/>
                <mapping source="customer_name" target="customer_name"/>
                <mapping source="customer_phone" target="customer_phone"/>
                <mapping source="total_amount" target="total_amount"/>
                <mapping source="order_status" target="status"/>
                <mapping source="create_time" target="created_at"/>
                <mapping source="update_time" target="updated_at"/>
            </fieldMappings>
        </task>
    </channel>
</config>
```

### 配置项说明

#### Channel（通道）配置

| 配置项 | 说明 | 示例 |
|--------|------|------|
| name | 通道名称 | `fast_sync_job` |
| cron | Cron 表达式，控制执行频率 | `0/5 * * * * ?` |
| concurrency | 并发线程数 | `1` |
| dir | 数据文件存储目录 | `D:\test\` |

#### Task（任务）配置

| 配置项 | 说明 | 可选值 |
|--------|------|--------|
| name | 任务名称 | - |
| syncMode | 同步模式 | `DIFF`, `ADD` |
| maxDataSize | 每次处理的最大记录数 | - |
| source | 源表配置 | `table`: 表名, `pk`: 主键 |
| stage | 影子表配置（仅 DIFF 模式） | `table`: 表名, `pk`: 主键 |
| target | 目标表配置 | `table`: 表名, `pk`: 主键 |
| fieldMappings | 字段映射关系 | `source`: 源字段, `target`: 目标字段 |

### 同步模式说明

#### DIFF（差异同步模式）

通过影子表对比源数据和目标数据的差异，支持增、删、改操作：
- **INSERT**: 源表有但影子表没有的记录（新增）
- **UPDATE**: 源表和影子表都有但数据不同的记录（更新）
- **DELETE**: 影子表有但源表没有的记录（删除）

适用于需要保持目标表与源表完全一致的场景。

#### ADD（增量追加模式）

仅追加新数据到目标表，不处理更新和删除操作。

适用于日志记录、历史数据归档等只增不减的场景。

## 🛠️ 技术栈

- **核心框架**: Java 21
- **构建工具**: Maven
- **数据库连接池**: HikariCP 7.0.2
- **JDBC 驱动**: MariaDB Client 3.5.9
- **序列化**: Kryo 5.6.2
- **日志框架**: SLF4J + Logback
- **Cron 解析**: cron-utils 9.2.1
- **代码简化**: Lombok 1.18.46

## 📂 项目结构

```
ferry/
├── base/                       # 基础模块
│   ├── src/main/java/
│   │   └── one/sanji/ferry/base/
│   │       ├── AppContext.java           # 应用上下文
│   │       ├── BaseChannel.java          # 通道接口
│   │       ├── BaseChannelHandler.java   # 通道处理器
│   │       ├── BaseTask.java             # 任务接口
│   │       ├── BaseTaskHandler.java      # 任务处理器
│   │       ├── DataFile.java             # 数据文件模型
│   │       ├── DbContext.java            # 数据库上下文
│   │       └── SyncMode.java             # 同步模式枚举
│   └── pom.xml
├── shipper/                    # 发货端模块
│   ├── src/main/
│   │   ├── java/one/sanji/ferry/shipper/
│   │   │   ├── AppConfig.java            # 应用配置
│   │   │   ├── ChannelHandler.java       # 通道处理器实现
│   │   │   ├── FerryShipper.java         # 主入口
│   │   │   └── TaskHandler.java          # 任务处理器实现
│   │   └── resources/
│   │       ├── config.xml                # 同步配置
│   │       ├── datasource.properties     # 数据源配置
│   │       └── logback.xml               # 日志配置
│   └── pom.xml
├── receiver/                   # 收货端模块
│   ├── src/main/
│   │   ├── java/one/sanji/ferry/receiver/
│   │   │   ├── AppConfig.java            # 应用配置
│   │   │   ├── ChannelHandler.java       # 通道处理器实现
│   │   │   ├── FerryReceiver.java        # 主入口
│   │   │   └── TaskHandler.java          # 任务处理器实现
│   │   └── resources/
│   │       ├── config.xml                # 同步配置
│   │       ├── datasource.properties     # 数据源配置
│   │       └── logback.xml               # 日志配置
│   └── pom.xml
├── pom.xml                     # 父 POM
└── README.md
```

## 🔍 工作原理

### Shipper 工作流程

1. **加载配置**: 读取 `config.xml` 和 `datasource.properties`
2. **定时调度**: 根据 Cron 表达式触发同步任务
3. **数据提取**: 执行配置的 SQL 查询，从源数据库提取变更数据
4. **数据处理**: 根据同步模式（DIFF/ADD）识别增删改操作
5. **序列化压缩**: 使用 Kryo 序列化 + Deflate 压缩生成数据文件
6. **文件输出**: 将数据文件保存到指定目录

### Receiver 工作流程

1. **监控目录**: 监听配置的数据文件目录
2. **文件读取**: 检测新生成的数据文件
3. **解压反序列化**: 解压缩并使用 Kryo 反序列化数据
4. **数据应用**: 根据操作类型（INSERT/UPDATE/DELETE）应用到目标数据库
5. **事务管理**: 确保数据一致性，失败时自动回滚

## 🎯 使用场景

- 📊 **数据仓库同步**: 将业务数据库数据同步到数据仓库
- 🔄 **跨库数据迁移**: 在不同数据库实例间同步数据
- 📈 **读写分离**: 主从数据库之间的数据同步
- 🗂️ **数据备份**: 定期备份关键业务数据
- 🔀 **多环境同步**: 开发、测试、生产环境间的数据同步

## ⚙️ 高级特性

### 并发控制

通过信号量（Semaphore）控制通道和任务的并发执行：
- 通道级别：同一通道不会并发执行
- 任务级别：可配置并发线程数，充分利用虚拟线程优势

### 异常处理

- 完整的异常捕获和日志记录
- 数据库事务自动回滚
- 调度器崩溃后自动恢复（5 秒后重启）

### 性能优化

- **虚拟线程**: Java 21 Virtual Threads，轻量级高并发
- **数据压缩**: Deflate 压缩算法，减少 I/O 开销
- **高效序列化**: Kryo 序列化，比 Java 原生序列化快 10 倍+
- **连接池**: HikariCP 高性能数据库连接池

## 📝 日志配置

日志配置文件位于 `src/main/resources/logback.xml`，默认配置：

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

可根据需要调整日志级别和输出方式。

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📄 许可证

本项目采用 Apache License 2.0 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🙏 致谢

感谢以下开源项目：
- [Kryo](https://github.com/EsotericSoftware/kryo) - 高效的 Java 序列化框架
- [HikariCP](https://github.com/brettwooldridge/HikariCP) - 极速 JDBC 连接池
- [cron-utils](https://github.com/jmrozanec/cron-utils) - Cron 表达式解析库

## 📧 联系方式

如有问题或建议，请通过以下方式联系：
- 提交 [Issue](https://github.com/your-username/ferry/issues)

---

**Made with ❤️ by Ferry Team**
