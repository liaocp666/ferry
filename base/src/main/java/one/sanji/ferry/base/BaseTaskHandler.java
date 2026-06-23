package one.sanji.ferry.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

public abstract class BaseTaskHandler<T extends BaseTask> implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseTaskHandler.class);

    protected final Semaphore lock;

    protected final ExecutorService virtualExecutor;

    public BaseTaskHandler(Integer thread) {
        lock = new Semaphore(thread);
        virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public List<DataFile> start(List<T> tasks) {
        List<DataFile> allDataFiles = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(tasks.size());
        for (T task : tasks) {
            virtualExecutor.submit(() -> {

                Thread currentThread = Thread.currentThread();
                String oldName = currentThread.getName();
                currentThread.setName("task-" + task.getName());

                Instant taskStart = Instant.now();

                Connection connection = null;
                boolean hasLock = false;
                try {
                    lock.acquire();
                    hasLock = true;

                    LOGGER.debug("开始执行任务：{}", task.getName());

                    connection = DbContext.getConnection();
                    connection.setAutoCommit(false);

                    List<DataFile> taskDataFiles = handler(task, connection);
                    if (taskDataFiles != null) {
                        allDataFiles.addAll(taskDataFiles);
                    }

                    connection.commit();
                    LOGGER.debug("结束执行任务：{}", task.getName());
                } catch (Exception e) {
                    LOGGER.error("处理表数据发生异常，并回滚：{}", task.getName(), e);
                    try {
                        if (connection != null) {
                            connection.rollback();
                        }
                    } catch (SQLException ex) {
                        LOGGER.error("回滚发生异常：{}", task.getName(), e);
                    }
                } finally {
                    if (connection != null) {
                        try {
                            connection.close();
                        } catch (SQLException e) {
                            LOGGER.error("关闭数据链接发生异常：{}", task.getName(), e);
                        }
                    }
                    if (hasLock) {
                        lock.release();
                    }
                    latch.countDown();

                    Instant channelEnd = Instant.now();
                    Duration timeElapsed = Duration.between(taskStart, channelEnd);
                    LOGGER.info("{} 任务执行，耗时: {} 秒",
                            task.getName(),
                            String.format("%.2f", timeElapsed.toMillis() / 1000.0));

                    currentThread.setName(oldName);
                }
            });

        }
        try {
            LOGGER.debug("开始等待所有任务完成：{}", tasks.size());
            latch.await();
            LOGGER.debug("结束等待所有任务完成：{}", tasks.size());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("等待虚拟线程执行被中断", e);
            return null;
        }
        return new ArrayList<>(allDataFiles);
    }

    protected abstract List<DataFile> handler(T t, Connection c);

    @Override
    public void close() {
        if (this.virtualExecutor != null) {
            this.virtualExecutor.close();
        }
    }
}
