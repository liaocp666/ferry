package one.sanji.ferry.base;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

public abstract class AppContext {

    /**
     * 当前程序运行时，Jar 包（或 IDE 运行 Class）所在目录
     */
    protected static String APP_HOME = "";

    protected Class<?> runClass;

    public AppContext(Class<?> runClass) {
        this.runClass = runClass;
    }

    /**
     * 获取当前程序运行的 Jar 包（或 IDE 编译 Class）所在的同级目录绝对路径
     * 末尾自带文件分隔符（如 / 或 \）
     */
    public String getAppHomePath(Class<?> runClass) {
        if (!APP_HOME.isBlank()) {
            return APP_HOME;
        }
        try {
            // 获取当前类的编译或运行绝对 URL 路径
            String path = runClass.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .getPath();

            // URL 解码，防止路径中包含空格或中文时变成 %20 等乱码
            path = URLDecoder.decode(path, StandardCharsets.UTF_8);

            File file = new File(path);
            String homePath;

            // 如果是运行于 Jar 包中，file.isFile() 为 true（因为路径指向的是 xxx.jar 文件本身）
            if (file.isFile()) {
                homePath = file.getParentFile().getAbsolutePath();
            } else {
                // 如果是在 IDE (IDEA) 中直接点 Run 运行，路径指向的是 target/classes/ 目录
                homePath = file.getAbsolutePath();
            }

            // 规范化路径：确保末尾带有斜杠
            if (!homePath.endsWith(File.separator)) {
                homePath += File.separator;
            }
            APP_HOME = homePath;
            return homePath;
        } catch (Exception e) {
            // 降级兜底方案：返回当前系统工作目录
            return System.getProperty("user.dir") + File.separator;
        }
    }

    /**
     * 【新增重载方法】传入文件名（如 "config.xml"），直接返回该文件的绝对物理路径
     * 内部会自动处理斜杠，并进行【鲁棒性检查】
     *
     * @param fileName 文件名或相对路径（如 "config.xml" 或 "config/config.xml"）
     * @return 完整的绝对路径
     */
    public String getAppHomePath(Class<?> runClass, String fileName) {
        // 1. 获取基础根目录
        String homePath = getAppHomePath(runClass);

        // 2. 规范化传入的文件名（防止运维人员手抖在文件名开头多写了斜杠 / 或 \）
        String cleanFileName = fileName.trim();
        if (cleanFileName.startsWith("/") || cleanFileName.startsWith("\\")) {
            cleanFileName = cleanFileName.substring(1);
        }

        // 3. 拼接完整的绝对路径
        return homePath + cleanFileName;
    }

}
