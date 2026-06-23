package one.sanji.ferry.shipper;

import one.sanji.ferry.base.BaseChannelHandler;
import one.sanji.ferry.base.DataFile;
import one.sanji.ferry.shipper.AppConfig.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class ChannelHandler extends BaseChannelHandler<Channel> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelHandler.class);

    public ChannelHandler(Channel channel) {
        super(channel);
    }

    @Override
    protected void handler() {
        String channelName = channel.getName();
        String filePath = channel.getDir() + channelName + "-" + System.currentTimeMillis();
        try (TaskHandler taskHandler = new TaskHandler(channel.getConcurrency())) {
            List<DataFile> dataFiles = taskHandler.start(channel.getTasks());
            if (dataFiles.isEmpty()) {
                return;
            }
            int currentVersion = channel.getVersion().getAndIncrement();
            String dataFilePath = filePath + "-" + currentVersion + ".data";
            serializeToFile(dataFiles, dataFilePath);
            LOGGER.info("生成文件成功：{}", dataFilePath);
        } catch (Exception e) {
            LOGGER.error("通道任务执行失败: {}", channelName, e);
        }
    }
}
