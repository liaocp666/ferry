package one.sanji.ferry.base;

/**
 * 数据同步模式枚举
 * 定义不同的数据同步策略
 */
public enum SyncMode {

    /**
     * 差异同步模式
     * 使用影子表对比差异，支持增删改操作
     */
    DIFF,

    /**
     * 增量追加模式
     * 仅追加新数据，只处理新增记录
     */
    ADD
}
