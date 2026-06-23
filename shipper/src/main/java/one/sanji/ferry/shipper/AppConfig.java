package one.sanji.ferry.shipper;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import lombok.Data;
import lombok.experimental.Accessors;
import one.sanji.ferry.base.BaseChannel;
import one.sanji.ferry.base.BaseTask;
import one.sanji.ferry.base.SyncMode;
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
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

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

                String concurrencyStr = xPath.compile("concurrency").evaluate(channelEle).trim();
                Integer concurrency = concurrencyStr.isEmpty() ? 1 : Integer.parseInt(concurrencyStr);

                List<Task> tasks = new ArrayList<>();
                NodeList taskNodes = (NodeList) xPath.compile("task")
                        .evaluate(channelEle, XPathConstants.NODESET);

                for (int j = 0; j < taskNodes.getLength(); j++) {
                    Element taskEle = (Element) taskNodes.item(j);

                    String taskName = xPath.compile("@name").evaluate(taskEle).trim();

                    String sourceTable = xPath.compile("source/@table").evaluate(taskEle).trim();
                    String stageTable = xPath.compile("stage/@table").evaluate(taskEle).trim();
                    String targetTable = xPath.compile("target/@table").evaluate(taskEle).trim();

                    String sourcePK = xPath.compile("source/@pk").evaluate(taskEle).trim();
                    String stagePK = xPath.compile("stage/@pk").evaluate(taskEle).trim();
                    String targetPK = xPath.compile("target/@pk").evaluate(taskEle).trim();

                    String syncMode = xPath.compile("syncMode").evaluate(taskEle).trim();
                    String maxDataSizeStr = xPath.compile("maxDataSize").evaluate(taskEle).trim();
                    int maxDataSize = maxDataSizeStr.isEmpty() ? 100 : Integer.parseInt(maxDataSizeStr);

                    String insertSourceKey = xPath.compile("execute/insert/@sourceKey").evaluate(taskEle).trim();
                    String insertStageKey = xPath.compile("execute/insert/@stageKey").evaluate(taskEle).trim();
                    String insertSql = xPath.compile("execute/insert/sql").evaluate(taskEle).trim();

                    String updateSourceKey = xPath.compile("execute/update/@sourceKey").evaluate(taskEle).trim();
                    String updateStageKey = xPath.compile("execute/update/@stageKey").evaluate(taskEle).trim();
                    String updateSql = xPath.compile("execute/update/sql").evaluate(taskEle).trim();

                    String deleteSourceKey = xPath.compile("execute/delete/@sourceKey").evaluate(taskEle).trim();
                    String deleteStageKey = xPath.compile("execute/delete/@stageKey").evaluate(taskEle).trim();
                    String deleteSql = xPath.compile("execute/delete/sql").evaluate(taskEle).trim();

                    Execute execute = new Execute()
                            .setInsertSql(insertSql)
                            .setInsertStageKey(insertStageKey)
                            .setInsertSourceKey(insertSourceKey)
                            .setUpdateSql(updateSql)
                            .setUpdateStageKey(updateStageKey)
                            .setUpdateSourceKey(updateSourceKey)
                            .setDeleteSql(deleteSql)
                            .setDeleteStageKey(deleteStageKey)
                            .setDeleteSourceKey(deleteSourceKey);

                    List<Mapping> mappings = new ArrayList<>();
                    NodeList fieldMappings = (NodeList) xPath.compile("fieldMappings/mapping")
                            .evaluate(taskEle, XPathConstants.NODESET);

                    for (int k = 0; k < fieldMappings.getLength(); k++) {
                        Element fieldMapping = (Element) fieldMappings.item(k);
                        String source = xPath.compile("@source").evaluate(fieldMapping).trim();
                        String target = xPath.compile("@target").evaluate(fieldMapping).trim();

                        mappings.add(
                                new Mapping()
                                        .setSource(source)
                                        .setTarget(target)
                        );
                    }

                    tasks.add(
                            new Task()
                                    .setName(taskName)
                                    .setSyncMode(SyncMode.valueOf(syncMode))
                                    .setMaxDataSize(maxDataSize)
                                    .setSourceTableName(sourceTable)
                                    .setSourcePK(sourcePK)
                                    .setTargetTableName(targetTable)
                                    .setTargetPK(targetPK)
                                    .setStageTableName(stageTable)
                                    .setStagePK(stagePK)
                                    .setExecute(execute)
                                    .setFieldMappings(mappings)
                    );
                }

                channels.add(
                        new Channel()
                                .setName(channelName)
                                .setCron(cronParser.parse(cronExpression))
                                .setConcurrency(concurrency)
                                .setTasks(tasks)
                                .setDir(outputDir)
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

        private AtomicInteger version = new AtomicInteger(0);

        private Semaphore lock = new Semaphore(1);

        private String name;

        private Cron cron;

        private Integer concurrency;

        private String dir;

        private List<Task> tasks;

    }

    @Data
    @Accessors(chain = true)
    public static class Task implements BaseTask {

        private String name;

        private SyncMode syncMode;

        private Integer maxDataSize;

        private String sourceTableName;

        private String stageTableName;

        private String targetTableName;

        private String sourcePK;

        private String stagePK;

        private String targetPK;

        private Execute execute;

        private List<Mapping> fieldMappings;

    }

    @Data
    @Accessors(chain = true)
    public static class Execute {

        private String insertSourceKey;

        private String insertStageKey;

        private String insertSql;

        private String updateSourceKey;

        private String updateStageKey;

        private String updateSql;

        private String deleteSourceKey;

        private String deleteStageKey;

        private String deleteSql;

    }

    @Data
    @Accessors(chain = true)
    public static class Mapping {

        private String source;

        private String target;

    }


}
