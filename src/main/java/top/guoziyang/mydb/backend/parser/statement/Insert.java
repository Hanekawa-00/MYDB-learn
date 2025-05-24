package top.guoziyang.mydb.backend.parser.statement;

/**
 * INSERT语句对象
 *
 * 功能说明：
 * 表示SQL中的INSERT语句，用于向数据库表中插入新记录。
 * 这是数据库中最基本的数据写入操作。
 *
 * 支持的语法格式：
 * INSERT INTO table_name VALUES value1, value2, value3, ...
 *
 * 语法特点：
 * 1. 必须指定目标表名
 * 2. 必须提供所有字段的值（按表定义顺序）
 * 3. 值的数量必须与表的字段数量匹配
 * 4. 值的类型必须与对应字段的类型兼容
 *
 * 与MySQL对比：
 * - MySQL支持多种INSERT语法：
 *   * INSERT INTO table (field1, field2) VALUES (value1, value2)
 *   * INSERT INTO table SET field1=value1, field2=value2
 *   * INSERT INTO table SELECT ... FROM other_table
 * - MYDB只支持简化的全字段VALUES语法
 * - MySQL支持ON DUPLICATE KEY UPDATE等高级特性
 *
 * 插入处理流程：
 * 1. 验证表是否存在
 * 2. 验证值的数量和类型
 * 3. 为记录分配唯一ID
 * 4. 将记录写入数据页
 * 5. 更新相关索引
 * 6. 记录事务日志
 *
 * 事务性：
 * INSERT操作是事务性的，支持：
 * - 原子性：要么全部成功，要么全部失败
 * - 隔离性：并发插入不会相互干扰
 * - 持久性：提交后数据永久保存
 *
 * 性能考虑：
 * - 索引更新开销：每次插入都需要更新索引
 * - 页面分裂：当页面满时可能触发B+树分裂
 * - 锁争用：高并发插入可能产生锁等待
 *
 * 使用示例：
 * INSERT INTO users VALUES 1, 'Alice', 25
 *
 * @author guoziyang
 * @see top.guoziyang.mydb.backend.tbm.Table 表对象
 */
public class Insert {
    /** 要插入数据的表名 */
    public String tableName;
    
    /**
     * 插入的值数组
     * 按照表字段定义的顺序排列，必须为每个字段提供值
     */
    public String[] values;
}
