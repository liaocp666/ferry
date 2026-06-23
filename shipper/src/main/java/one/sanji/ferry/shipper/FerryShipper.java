package one.sanji.ferry.shipper;

import one.sanji.ferry.base.AppContext;
import one.sanji.ferry.base.DbContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static one.sanji.ferry.shipper.AppConfig.Channel;
import static one.sanji.ferry.shipper.AppConfig.load;

public class FerryShipper extends AppContext {

    private static final Logger LOGGER = LoggerFactory.getLogger(FerryShipper.class);

    public FerryShipper() {
        super(FerryShipper.class);
    }

    public static void main(String[] args) {
        FerryShipper ferryShipper = new FerryShipper();
        ferryShipper.start();
    }

    private void start() {
        // 加载配置
        String configXmlPath = getAppHomePath(FerryShipper.class, "config.xml");
        AppConfig appConfig = load(configXmlPath);
        List<Channel> channels = appConfig.getChannels();

        if (channels == null || channels.isEmpty()) {
            LOGGER.error("[ERROR] 配置文件 config.xml 中没有定义任何通道！");
            return;
        }

        String datasourcePath = getAppHomePath(FerryShipper.class, "datasource.properties");
        DbContext.loadConfig(datasourcePath);

        for (Channel channel : channels) {
            ChannelHandler channelHandler = new ChannelHandler(channel);
            channelHandler.start();
        }

        Runtime.getRuntime().addShutdownHook(new Thread(DbContext::shutdown, "Shutdown-Hook-Thread"));

        LOGGER.info(">>> 程序启动成功！ <<<");
        LOGGER.info("运行主路径: {}", getAppHomePath(FerryShipper.class));
        LOGGER.info("已读取配置: {}", configXmlPath);
        LOGGER.info("已加载通道数: {} 个", appConfig.getChannels().size());
    }

}
