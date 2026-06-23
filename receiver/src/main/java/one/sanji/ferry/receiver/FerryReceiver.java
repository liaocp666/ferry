package one.sanji.ferry.receiver;

import one.sanji.ferry.base.AppContext;
import one.sanji.ferry.base.DbContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static one.sanji.ferry.receiver.AppConfig.*;

public class FerryReceiver extends AppContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(FerryReceiver.class);

    public FerryReceiver() {
        super(FerryReceiver.class);
    }

    public static void main(String[] args) {
        FerryReceiver ferryReceiver = new FerryReceiver();
        ferryReceiver.start();
    }

    private void start() {
        // 加载配置
        String configXmlPath = getAppHomePath(FerryReceiver.class, "config.xml");
        AppConfig appConfig = load(configXmlPath);
        List<Channel> channels = appConfig.getChannels();

        if (channels == null || channels.isEmpty()) {
            LOGGER.error("[ERROR] 配置文件 config.xml 中没有定义任何通道！");
            return;
        }

        String datasourcePath = getAppHomePath(FerryReceiver.class, "datasource.properties");
        DbContext.loadConfig(datasourcePath);

        for (Channel channel : channels) {
            ChannelHandler channelHandler = new ChannelHandler(channel);
            channelHandler.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(DbContext::shutdown, "Shutdown-Hook-Thread"));

        LOGGER.info(">>> 程序启动成功！ <<<");
        LOGGER.info("运行主路径: {}", getAppHomePath(FerryReceiver.class));
        LOGGER.info("已读取配置: {}", configXmlPath);
        LOGGER.info("已加载通道数: {} 个", appConfig.getChannels().size());
    }

}
