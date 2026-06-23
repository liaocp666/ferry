package one.sanji.ferry.receiver;


import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import lombok.Data;
import lombok.experimental.Accessors;
import one.sanji.ferry.base.BaseChannel;
import one.sanji.ferry.base.BaseTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class AppConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(AppConfig.class);

    private final static CronParser cronParser = new CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ));

    private List<Channel> channels;


    public static AppConfig load(String configXmlPath) {
        AppConfig config = new AppConfig();
        Path path = Paths.get(configXmlPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            LOGGER.error("未找到配置文件：{}", configXmlPath);
            throw new RuntimeException("未找到配置文件");
        }
        try {
            Document doc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(path.toFile());
            XPath xPath = XPathFactory.newInstance().newXPath();

            List<Channel> channels = new ArrayList<>();
            NodeList channelNodes = (NodeList) xPath.compile("/config/channel")
                    .evaluate(doc, XPathConstants.NODESET);
            for (int i = 0; i < channelNodes.getLength(); i++) {
                Element channelEle = (Element) channelNodes.item(i);

                String channelName = xPath.compile("@name").evaluate(channelEle).trim();
                String cronExpression = xPath.compile("cron").evaluate(channelEle).trim();

                String outputDir = xPath.compile("dir").evaluate(channelEle).trim();
                String successDir = xPath.compile("successDir").evaluate(channelEle).trim();
                String failDir = xPath.compile("failDir").evaluate(channelEle).trim();

                String concurrencyStr = xPath.compile("concurrency").evaluate(channelEle).trim();
                Integer concurrency = concurrencyStr.isEmpty() ? 1 : Integer.parseInt(concurrencyStr);

                String sequentialStr = xPath.compile("sequential").evaluate(channelEle).trim();
                Boolean sequential = Boolean.parseBoolean(sequentialStr);

                List<Task> tasks = new ArrayList<>();
                NodeList taskNodes = (NodeList) xPath.compile("task")
                        .evaluate(channelEle, XPathConstants.NODESET);

                for (int j = 0; j < taskNodes.getLength(); j++) {
                    Element taskEle = (Element) taskNodes.item(j);

                    String taskName = xPath.compile("@name").evaluate(taskEle).trim();

                    String maxDataSizeStr = xPath.compile("maxDataSize").evaluate(taskEle).trim();
                    int maxDataSize = maxDataSizeStr.isEmpty() ? 100 : Integer.parseInt(maxDataSizeStr);

                    tasks.add(
                            new Task()
                                    .setName(taskName)
                                    .setMaxDataSize(maxDataSize)
                    );
                }

                channels.add(
                        new Channel()
                                .setName(channelName)
                                .setCron(cronParser.parse(cronExpression))
                                .setConcurrency(concurrency)
                                .setSequential(sequential)
                                .setTasks(tasks)
                                .setDir(outputDir)
                                .setSuccessDir(successDir)
                                .setFailDir(failDir)
                );
            }
            config.setChannels(channels);
            return config;
        } catch (Exception e) {
            LOGGER.error("读取程序配置失败:" + configXmlPath, e);
            throw new RuntimeException("读取程序配置文件失败", e);
        }
    }

    @Data
    @Accessors(chain = true)
    public static class Channel implements BaseChannel {

        private String name;

        private Cron cron;

        private Integer concurrency;

        private Boolean sequential;

        private String dir;

        private String successDir;

        private String failDir;

        private List<Task> tasks;

    }

    @Data
    @Accessors(chain = true)
    public static class Task implements BaseTask {

        private String name;

        private Integer maxDataSize;

    }

}
