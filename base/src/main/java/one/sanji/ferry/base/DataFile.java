package one.sanji.ferry.base;

import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
@ToString
public class DataFile implements Serializable {

    private String type;

    private String tableName;

    private String primaryKey;

    private String taskName;

    private List<Map<String, Object>> data;

}
