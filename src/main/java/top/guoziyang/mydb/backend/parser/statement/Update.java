package top.guoziyang.mydb.backend.parser.statement;

/**
 * UPDATE语句对象
 *
 * 功能说明：
 * 表示SQL中的UPDATE语句，用于修改数据库表中现有记录的字段值。
 * 这是数据库中最重要的数据修改操作之一。
 *
 * 支持的语法格式：
 * UPDATE table_name SET field_name = value [WHERE conditions]
 *
 * 语法特点：
 * 1. 必须指定目标表名
 * 2. 必须指定要更新的字段名和新值
 * 3. 支持可选的WHERE条件来限制更新范围
 * 4. 没有WHERE条件时会更新全表（危险操作）
 *
 * 与MySQL对比：
 * - MySQL支持多字段同时更新：SET field1=value1, field2=value2
 * - MySQL支持表达式更新：SET count = count + 1
 * - MySQL支持多表UPDATE：UPDATE t1, t2 SET ...
 * - MYDB只支持单字段的简单更新
 *
 * 更新处理流程：
 * 1. 验证表和字段是否存在
 * 2. 根据WHERE条件定位要更新的记录
 * 3. 检查新值的类型兼容性
 * 4. 执行字段值更新
 * 5. 更新相关索引
 * 6. 记录事务日志
 *
 * 事务性保证：
 * - 原子性：更新操作要么全部成功，要么全部失败
 * - 一致性：更新后数据保持一致性约束
 * - 隔离性：并发更新操作相互隔离
 * - 持久性：提交后的更新永久保存
 *
 * 性能考虑：
 * - WHERE条件使用索引可以快速定位记录
 * - 无WHERE条件的全表更新性能较差
 * - 索引字段的更新需要维护索引结构
 * - 并发更新可能产生锁争用
 *
 * 使用示例：
 * UPDATE users SET age = 26 WHERE id = 1
 *
 * @author guoziyang
 * @see Where WHERE条件对象
 */
public class Update {
    /** 要更新的表名 */
    public String tableName;
    
    /** 要更新的字段名 */
    public String fieldName;
    
    /** 新的字段值 */
    public String value;
    
    /**
     * WHERE条件对象
     * null表示无条件更新（更新全表）
     */
    public Where where;
}
