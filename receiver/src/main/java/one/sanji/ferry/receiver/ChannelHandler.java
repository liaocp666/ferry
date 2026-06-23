package one.sanji.ferry.receiver;

import one.sanji.ferry.base.BaseChannelHandler;
import one.sanji.ferry.base.DataFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static one.sanji.ferry.receiver.AppConfig.Channel;

public class ChannelHandler extends BaseChannelHandler<Channel> {

    private final static Logger LOGGER = LoggerFactory.getLogger(ChannelHandler.class);

    private final static Map<String, AtomicInteger> channelVersionCache = new ConcurrentHashMap<>();
    private final static Map<String, AtomicBoolean> channelRetryCache = new ConcurrentHashMap<>();


    public ChannelHandler(Channel channel) {
        super(channel);
        String channelName = channel.getName();
        if (!channelVersionCache.containsKey(channelName)) {
            channelVersionCache.put(channelName, new AtomicInteger(-1));
        }
        if (!channelRetryCache.containsKey(channelName)) {
            channelRetryCache.put(channelName, new AtomicBoolean(true));
        }
    }

    @Override
    protected void handler() throws IOException {

        String channelName = channel.getName();
        String dir = channel.getDir();

        AtomicInteger lastVersion = channelVersionCache.get(channelName);
        AtomicBoolean retryAtomic = channelRetryCache.get(channelName);

        Path successPath = Path.of(channel.getSuccessDir());
        Path failPath = Path.of(channel.getFailDir());
        boolean sequentialByVersion = channel.getSequential();

        Path dirPath = Path.of(dir);

        if (!Files.exists(dirPath)) {
            LOGGER.error("扫描目录不存在：{}", dir);
            return;
        }

        try (Stream<Path> stream = Files.list(dirPath)) {
            List<Path> readyToProcessFiles = stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith(channelName + "-") && fileName.endsWith(".data");
                    })
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();

            if (readyToProcessFiles.isEmpty()) {
                LOGGER.debug("暂无待处理的数据文件: {}", channelName);
                return;
            }

            for (Path filePath : readyToProcessFiles) {
                String absolutePath = filePath.toAbsolutePath().toString();
                try {

                    if (sequentialByVersion) {
                        String fileName = filePath.getFileName().toString();

                        int lastDashIndex = fileName.lastIndexOf("-");
                        int lastDotIndex = fileName.lastIndexOf(".");

                        int currentVersion = Integer.parseInt(fileName.substring(lastDashIndex + 1, lastDotIndex));
                        if (lastVersion.incrementAndGet() != currentVersion && retryAtomic.getAndSet(false)) {
                            LOGGER.warn("检测到版本号不连续，开始等待 30 秒，交给下次调度处理，期望版本：{}，实际版本：{}，文件：{}", lastVersion, currentVersion, absolutePath);
                            TimeUnit.SECONDS.sleep(30);
                            return;
                        }
                        lastVersion.set(currentVersion);
                        retryAtomic.set(true);
                    }

                    List<DataFile> dataFiles = deserializeFromFile(absolutePath);
                    if (dataFiles == null || dataFiles.isEmpty()) {
                        moveToDir(filePath, failPath);
                        return;
                    }
                    try (TaskHandler taskHandler = new TaskHandler(channel.getConcurrency(), dataFiles)) {

                        List<DataFile> handled = taskHandler.start(channel.getTasks());

                        LOGGER.info("处理文件成功：{}，数量：{}", absolutePath, handled.size());

                        moveToDir(filePath, successPath);
                    }
                } catch (Exception e) {
                    LOGGER.error("处理文件失败：{}", absolutePath);
                    moveToDir(filePath, failPath);
                }
            }
        }

    }

    private void moveToDir(Path sourceFile, Path targetDir) {
        try {

            // 如果成功目录不存在，自动创建它
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }

            // 拼接出目标文件的全路径：D:\test\success\文件名.data
            Path targetFile = targetDir.resolve(sourceFile.getFileName());

            // 移动文件（如果成功目录有同名文件则覆盖）
            Files.move(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            LOGGER.error("移动文件失败: {}", sourceFile.getFileName(), e);
        }
    }
}
