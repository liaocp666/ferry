package one.sanji.ferry.base;

import com.cronutils.model.Cron;
import com.cronutils.model.time.ExecutionTime;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public abstract class BaseChannelHandler<T extends BaseChannel> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseChannelHandler.class);
    private static final ThreadLocal<Kryo> kryoThreadLocal = ThreadLocal.withInitial(() -> {
        Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.setReferences(true);
        return kryo;
    });
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
    protected final T channel;

    public BaseChannelHandler(T channel) {
        this.channel = channel;
    }

    public static void serializeToFile(Object o, String filePath) {
        Kryo kryo = kryoThreadLocal.get();

        try (FileOutputStream fos = new FileOutputStream(filePath);
             BufferedOutputStream bos = new BufferedOutputStream(fos);
             DeflaterOutputStream dos = new DeflaterOutputStream(bos, new Deflater(3));
             Output output = new Output(dos)) {

            kryo.writeClassAndObject(output, o);
            output.flush();

        } catch (Exception e) {
            throw new RuntimeException("生成文件失败: " + filePath, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> T deserializeFromFile(String filePath) {
        Kryo kryo = kryoThreadLocal.get();

        try (FileInputStream fis = new FileInputStream(filePath);
             BufferedInputStream bis = new BufferedInputStream(fis);
             InflaterInputStream iis = new InflaterInputStream(bis);
             Input input = new Input(iis)) {

            return (T) kryo.readClassAndObject(input);

        } catch (Exception e) {
            throw new RuntimeException("反序列化失败，路径: " + filePath, e);
        }
    }

    public void start() {
        Cron cron = channel.getCron();
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        ZonedDateTime firstBaseTime = ZonedDateTime.now();
        scheduleNext(channel, scheduler, virtualExecutor, executionTime, firstBaseTime);
    }

    private void scheduleNext(T channel,
                              ScheduledExecutorService scheduler,
                              ExecutorService virtualExecutor,
                              ExecutionTime executionTime,
                              ZonedDateTime lastTargetTime) {
        try {
            Optional<ZonedDateTime> nextExecution = executionTime.nextExecution(lastTargetTime);

            if (nextExecution.isPresent()) {
                ZonedDateTime targetTime = nextExecution.get();

                long delay = Duration.between(ZonedDateTime.now(), targetTime).toNanos();

                long safeDelay = Math.max(0, delay);

                scheduler.schedule(() -> {

                    virtualExecutor.submit(() -> {
                        Thread currentThread = Thread.currentThread();
                        String oldName = currentThread.getName();
                        currentThread.setName("channel-" + channel.getName());

                        Instant channelStart = Instant.now();

                        Semaphore lock = channel.getLock();
                        String channelName = channel.getName();

                        if (lock.tryAcquire()) {

                            try {
                                handler();
                            } catch (Exception e) {
                                LOGGER.error("通道调度异常：{}", channelName, e);
                            } finally {

                                lock.release();
                                Instant channelEnd = Instant.now();
                                Duration timeElapsed = Duration.between(channelStart, channelEnd);

                                LOGGER.info("{} 通道调度，耗时: {} 秒",
                                        channelName,
                                        String.format("%.2f", timeElapsed.toMillis() / 1000.0));

                                currentThread.setName(oldName);
                            }

                        } else {
                            LOGGER.info("上次通道任务未完成，本次调度跳过: {} ", channelName);
                        }

                    });

                    scheduleNext(channel, scheduler, virtualExecutor, executionTime, targetTime);

                }, safeDelay, TimeUnit.NANOSECONDS);
            }
        } catch (Exception e) {
            // 9. 调度核心层崩溃，使用 ERROR 级别，方便监控告警系统（如 ELK）抓取
            LOGGER.error("调度环崩溃，5秒后尝试强制重启：{}", channel.getName(), e);
            scheduler.schedule(() -> scheduleNext(channel, scheduler, virtualExecutor, executionTime, ZonedDateTime.now()), 5, TimeUnit.SECONDS);
        }
    }

    protected T getChannel() {
        return channel;
    }

    protected abstract void handler() throws IOException;

}
