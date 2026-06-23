package one.sanji.ferry.shipper;

import one.sanji.ferry.base.BaseTaskHandler;
import one.sanji.ferry.base.DataFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

import static one.sanji.ferry.shipper.AppConfig.*;

public class TaskHandler extends BaseTaskHandler<Task> {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskHandler.class);

    public TaskHandler(Integer thread) {
        super(thread);
    }

    @Override
    protected List<DataFile> handler(Task task, Connection connection) {
        List<DataFile> dataFiles = null;
        switch (task.getSyncMode()) {
            case ADD -> dataFiles = handlerAdd(task, connection);
            case DIFF -> dataFiles = handlerDiff(task, connection);
        }
        if (dataFiles == null || dataFiles.isEmpty()) {
            return dataFiles;
        }
        return dataFiles.stream().map(dataFile -> {
            List<Map<String, Object>> data = dataFile.getData();
            if (data == null || data.isEmpty()) {
                return dataFile;
            }
            List<Map<String, Object>> newData = data.stream().map(
                    rowMap -> convertRowByMapping(rowMap, task.getFieldMappings())
            ).toList();
            dataFile.setData(newData);
            return dataFile;
        }).toList();
    }

    private Map<String, Object> convertRowByMapping(Map<String, Object> rowMap, List<Mapping> mappings) {
        Map<String, Object> newRowMap = new LinkedHashMap<>();
        for (Mapping mapping : mappings) {
            String source = mapping.getSource();
            String target = mapping.getTarget();
            newRowMap.put(target, rowMap.getOrDefault(source, null));
        }
        return newRowMap;
    }

    private List<DataFile> handlerDiff(Task task, Connection connection) {
        String limit = "";
        if (task.getMaxDataSize() != null && task.getMaxDataSize() > 0) {
            limit = " LIMIT " + task.getMaxDataSize();
        }

        Execute execute = task.getExecute();

        String insertDql = execute.getInsertSql() + limit;

        LOGGER.debug("插入数据处理开始: {}", task.getName());

        List<Map<String, Object>> insertData = fetchDqlData(insertDql, connection);
        List<DataFile> dataFiles = new ArrayList<>();
        if (insertData != null && !insertData.isEmpty()) {
            insertStageData(insertData, task, connection);
            dataFiles.add(
                    new DataFile()
                            .setType("insert")
                            .setTableName(task.getTargetTableName())
                            .setPrimaryKey(task.getTargetPK())
                            .setData(insertData)
                            .setTaskName(task.getName())
            );
        }

        LOGGER.debug("插入数据处理完成: {}", task.getName());

        LOGGER.debug("更新数据处理开始: {}", task.getName());

        String updateDql = execute.getUpdateSql() + limit;
        List<Map<String, Object>> updateData = fetchDqlData(updateDql, connection);
        if (updateData != null && !updateData.isEmpty()) {
            updateStageData(updateData, task, connection);
            dataFiles.add(
                    new DataFile()
                            .setType("update")
                            .setTableName(task.getTargetTableName())
                            .setPrimaryKey(task.getTargetPK())
                            .setData(updateData)
                            .setTaskName(task.getName())
            );
        }

        LOGGER.debug("更新数据处理完成: {}", task.getName());

        LOGGER.debug("删除数据处理开始: {}", task.getName());

        String deleteDql = execute.getDeleteSql() + limit;
        List<Map<String, Object>> deleteData = fetchDqlData(deleteDql, connection);
        if (deleteData != null && !deleteData.isEmpty()) {
            deleteStageData(deleteData, task, connection);
            dataFiles.add(
                    new DataFile()
                            .setType("delete")
                            .setTableName(task.getTargetTableName())
                            .setPrimaryKey(task.getTargetPK())
                            .setData(deleteData)
                            .setTaskName(task.getName())
            );
        }

        LOGGER.debug("删除数据处理完成: {}", task.getName());

        return dataFiles;
    }

    private void insertStageData(List<Map<String, Object>> data, Task task, Connection connection) {
        if (data == null || data.isEmpty()) {
            return;
        }
        String tableName = task.getStageTableName();

        List<String> columns = new ArrayList<>(data.getFirst().keySet());
        String columnStr = "`" + String.join("`,`", columns) + "`";
        String placeholders = columns.stream().map(row -> "?").collect(Collectors.joining(","));

        String insertDml = "INSERT INTO %s (%s) VALUES (%s)".formatted(tableName, columnStr, placeholders);

        try (PreparedStatement preparedStatement = connection.prepareStatement(insertDml)) {
            for (Map<String, Object> datum : data) {
                for (int i = 0; i < columns.size(); i++) {
                    String column = columns.get(i);
                    Object value = datum.get(column);
                    preparedStatement.setObject(i + 1, value);
                }
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } catch (SQLException e) {
            LOGGER.error("插入暂存表资源出错：{}", insertDml, e);
            throw new RuntimeException("插入暂存表资源出错");
        }
    }

    private void updateStageData(List<Map<String, Object>> data, Task task, Connection connection) {
        if (data == null || data.isEmpty()) {
            return;
        }

        String tableName = task.getStageTableName();
        String tablePrimary = task.getStagePK();

        List<String> updateColumns = data.getFirst().keySet().stream().filter(e -> !e.equals(task.getStagePK())).toList();

        String setSql = updateColumns.stream()
                .map(col -> "`" + col + "` = ?")
                .collect(Collectors.joining(", "));

        String updateDml = "UPDATE %s SET %s WHERE `%s` = ?".formatted(tableName, setSql, tablePrimary);

        try (PreparedStatement preparedStatement = connection.prepareStatement(updateDml)) {
            for (Map<String, Object> datum : data) {
                for (int i = 0; i < updateColumns.size(); i++) {
                    String column = updateColumns.get(i);
                    Object value = datum.get(column);
                    preparedStatement.setObject(i + 1, value);
                }
                preparedStatement.setObject(updateColumns.size() + 1, datum.get(tablePrimary));
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } catch (SQLException e) {
            LOGGER.error("更新暂存表资源出错：{}", updateDml, e);
            throw new RuntimeException("更新暂存表资源出错");
        }
    }

    private void deleteStageData(List<Map<String, Object>> data, Task task, Connection connection) {
        if (data == null || data.isEmpty()) {
            return;
        }

        String tableName = task.getStageTableName();
        String tablePrimary = task.getStagePK();

        String deleteDml = "DELETE FROM %s WHERE `%s` = ?".formatted(tableName, tablePrimary);

        try (PreparedStatement preparedStatement = connection.prepareStatement(deleteDml)) {
            for (Map<String, Object> datum : data) {
                preparedStatement.setObject(1, datum.get(tablePrimary));
                preparedStatement.addBatch();
            }
            preparedStatement.executeBatch();
        } catch (SQLException e) {
            LOGGER.error("删除暂存表资源出错：{}", deleteDml, e);
            throw new RuntimeException("删除暂存表资源出错");
        }
    }

    private List<Map<String, Object>> fetchDqlData(String dql, Connection connection) {
        List<Map<String, Object>> data = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(dql)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> rowData = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    rowData.put(metaData.getColumnName(i), rs.getObject(i));
                }
                data.add(rowData);
            }
            return data;
        } catch (Exception e) {
            LOGGER.error("执行DQL语句出现异常：{}", dql, e);
            return null;
        }
    }

    private String getWatermark(Task task, Connection connection) {
        String dql = "SELECT watermark FROM ferry_add_record WHERE name = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(dql)) {
            pstmt.setString(1, task.getName());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (Exception e) {
            LOGGER.error("无法获取增量数据值，任务名：{}", task.getName(), e);
        }
        return null;
    }

    private boolean updateWatermark(String watermark, Task task, Connection connection) {
        String dml = "UPDATE ferry_add_record SET watermark = ? WHERE name = ?";
        try (PreparedStatement pstmt = connection.prepareStatement(dml)) {
            pstmt.setString(1, watermark);
            pstmt.setString(2, task.getName());
            int rowsUpdated = pstmt.executeUpdate();
            return rowsUpdated > 0;
        } catch (Exception e) {
            LOGGER.error("无法更新增量数据值：{}", dml, e);
            return false;
        }
    }

    private boolean insertWatermark(String watermark, Task task, Connection connection) {
        String dml = "INSERT INTO ferry_add_record (watermark, name) VALUES (?, ?);";
        try (PreparedStatement pstmt = connection.prepareStatement(dml)) {
            pstmt.setString(1, watermark);
            pstmt.setString(2, task.getName());
            int rowsUpdated = pstmt.executeUpdate();
            return rowsUpdated > 0;
        } catch (Exception e) {
            LOGGER.error("无法更新增量数据值：{}", dml, e);
            return false;
        }
    }


    private List<DataFile> handlerAdd(Task task, Connection connection) {
        String source = task.getSourceTableName();
        String primaryKey = task.getSourcePK();
        String columnStr = "`" + task.getFieldMappings().stream().map(Mapping::getSource).collect(Collectors.joining("`,`")) + "`";

        String limit = "";
        if (task.getMaxDataSize() != null && task.getMaxDataSize() > 0) {
            limit = " LIMIT " + task.getMaxDataSize();
        }
        String orderBy = " ORDER BY " + primaryKey + " ASC ";

        String where = "";
        String watermark = getWatermark(task, connection);
        if (watermark != null && !watermark.isBlank()) {
            where = " WHERE " + primaryKey + " > ?";
        }

        String dataDql = "SELECT " + columnStr + " FROM " + source + where + orderBy + limit;

        List<Map<String, Object>> insertData = new ArrayList<>();

        try (PreparedStatement pstmt = connection.prepareStatement(dataDql)) {
            pstmt.setString(1, watermark);
            try (ResultSet rs = pstmt.executeQuery()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();

                while (rs.next()) {
                    Map<String, Object> rowData = new HashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        rowData.put(metaData.getColumnName(i), rs.getObject(i));
                    }
                    insertData.add(rowData);
                }
            }
        } catch (Exception e) {
            LOGGER.error("执行DQL语句出现异常：{}", dataDql, e);
            return null;
        }

        List<DataFile> dataFiles = new ArrayList<>();
        if (!insertData.isEmpty()) {

            Object o = insertData.getLast().get(primaryKey);
            if (watermark != null && !watermark.isBlank()) {
                updateWatermark(o.toString(), task, connection);
            } else {
                insertWatermark(o.toString(), task, connection);
            }

            dataFiles.add(
                    new DataFile()
                            .setType("insert")
                            .setTableName(task.getTargetTableName())
                            .setPrimaryKey(task.getTargetPK())
                            .setData(insertData)
                            .setTaskName(task.getName())
            );
        }

        return dataFiles;
    }
}
