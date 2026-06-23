package one.sanji.ferry.receiver;

import one.sanji.ferry.base.BaseTaskHandler;
import one.sanji.ferry.base.DataFile;
import one.sanji.ferry.receiver.AppConfig.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TaskHandler extends BaseTaskHandler<Task> {

    private final static Logger LOGGER = LoggerFactory.getLogger(TaskHandler.class);

    private final List<DataFile> dataFiles;

    public TaskHandler(Integer thread, List<DataFile> dataFiles) {
        super(thread);
        this.dataFiles = dataFiles;
    }

    @Override
    protected List<DataFile> handler(Task task, Connection connection) {

        List<DataFile> readyToProcessDataFiles = dataFiles.stream().filter(e -> e.getTaskName().equals(task.getName())).toList();

        Map<String, List<DataFile>> collect = readyToProcessDataFiles.stream().collect(Collectors.groupingBy(DataFile::getType));

        if (collect.containsKey("insert")) {
            insertData(collect.get("insert"), task, connection);
        }

        if (collect.containsKey("update")) {
            updateData(dataFiles, task, connection);
        }

        if (collect.containsKey("delete")) {
            deleteData(dataFiles, task, connection);
        }

        return readyToProcessDataFiles;
    }

    private void deleteData(List<DataFile> dataFiles, Task task, Connection connection) {
        if (dataFiles == null || dataFiles.isEmpty()) {
            return;
        }

        List<Map<String, Object>> deleteDatas = dataFiles.stream()
                .filter(file -> file.getData() != null)
                .flatMap(file -> file.getData().stream())
                .toList();

        if (deleteDatas.isEmpty()) {
            return;
        }
        String tableName = dataFiles.getFirst().getTableName();
        String tablePrimary = dataFiles.getFirst().getPrimaryKey();

        String deleteDml = "DELETE FROM %s WHERE `%s` = ?".formatted(tableName, tablePrimary);

        try (PreparedStatement preparedStatement = connection.prepareStatement(deleteDml)) {

            int count = 0;
            int batchSize = task.getMaxDataSize();

            for (Map<String, Object> deleteData : deleteDatas) {
                preparedStatement.setObject(1, deleteData.get(tablePrimary));
                preparedStatement.addBatch();
                if (++count % batchSize == 0) {
                    preparedStatement.executeBatch();
                    preparedStatement.clearBatch(); // 显式清理批处理缓存，对部分数据库驱动更友好
                }
            }
            if (count % batchSize != 0) {
                preparedStatement.executeBatch();
            }
        } catch (SQLException e) {
            LOGGER.error("删除暂存表资源出错：{}", deleteDml, e);
            throw new RuntimeException("删除暂存表资源出错");
        }
    }

    private void updateData(List<DataFile> dataFiles, Task task, Connection connection) {
        if (dataFiles == null || dataFiles.isEmpty()) {
            return;
        }

        List<Map<String, Object>> updateDatas = dataFiles.stream()
                .filter(file -> file.getData() != null)
                .flatMap(file -> file.getData().stream())
                .toList();

        if (updateDatas.isEmpty()) {
            return;
        }

        String tableName = dataFiles.getFirst().getTableName();
        String tablePrimary = dataFiles.getFirst().getPrimaryKey();

        List<String> updateColumns = updateDatas.getFirst().keySet().stream().filter(e -> !e.equals(tablePrimary)).toList();

        String setSql = updateColumns.stream()
                .map(col -> "`" + col + "` = ?")
                .collect(Collectors.joining(", "));

        String updateDml = "UPDATE %s SET %s WHERE `%s` = ?".formatted(tableName, setSql, tablePrimary);

        try (PreparedStatement preparedStatement = connection.prepareStatement(updateDml)) {
            int count = 0;
            int batchSize = task.getMaxDataSize();
            for (Map<String, Object> updateData : updateDatas) {
                for (int i = 0; i < updateColumns.size(); i++) {
                    String column = updateColumns.get(i);
                    Object value = updateData.get(column);
                    preparedStatement.setObject(i + 1, value);
                }
                preparedStatement.setObject(updateColumns.size() + 1, updateData.get(tablePrimary));
                preparedStatement.addBatch();
                if (++count % batchSize == 0) {
                    preparedStatement.executeBatch();
                    preparedStatement.clearBatch(); // 显式清理批处理缓存，对部分数据库驱动更友好
                }
            }
            if (count % batchSize != 0) {
                preparedStatement.executeBatch();
            }
        } catch (SQLException e) {
            LOGGER.error("更新暂存表资源出错：{}", updateDml, e);
            throw new RuntimeException("更新暂存表资源出错");
        }

    }

    private void insertData(List<DataFile> dataFiles, Task task, Connection connection) {
        if (dataFiles == null || dataFiles.isEmpty()) {
            return;
        }

        List<Map<String, Object>> insertDatas = dataFiles.stream()
                .filter(file -> file.getData() != null)
                .flatMap(file -> file.getData().stream())
                .toList();

        if (insertDatas.isEmpty()) {
            return;
        }

        String tableName = dataFiles.getFirst().getTableName();

        List<String> columns = new ArrayList<>(dataFiles.getFirst().getData().getFirst().keySet());
        String columnStr = "`" + String.join("`,`", columns) + "`";
        String placeholders = columns.stream().map(row -> "?").collect(Collectors.joining(","));

        String insertDml = "INSERT INTO %s (%s) VALUES (%s)".formatted(tableName, columnStr, placeholders);

        try (PreparedStatement preparedStatement = connection.prepareStatement(insertDml)) {
            int count = 0;
            int batchSize = task.getMaxDataSize();
            for (Map<String, Object> insertData : insertDatas) {
                for (int j = 0; j < columns.size(); j++) {
                    String column = columns.get(j);
                    Object value = insertData.get(column);
                    preparedStatement.setObject(j + 1, value);
                }
                preparedStatement.addBatch();
                if (++count % batchSize == 0) {
                    preparedStatement.executeBatch();
                    preparedStatement.clearBatch(); // 显式清理批处理缓存，对部分数据库驱动更友好
                }
            }
            if (count % batchSize != 0) {
                preparedStatement.executeBatch();
            }
        } catch (SQLException e) {
            LOGGER.error("插入暂存表资源出错：{}", insertDml, e);
            throw new RuntimeException("插入暂存表资源出错");
        }
    }
}
