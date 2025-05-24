package top.guoziyang.mydb.backend.parser.statement;

/**
 * SELECT查询语句对象
 *
 * 功能说明：
 * 表示SQL中的SELECT语句，用于从数据库表中查询数据。
 * 这是数据库系统中最常用的操作之一，MYDB实现了基本的单表查询功能。
 *
 * 支持的查询语法：
 * 1. SELECT * FROM table_name - 查询所有字段
 * 2. SELECT field1, field2 FROM table_name - 查询指定字段
 * 3. SELECT * FROM table_name WHERE conditions - 带条件查询
 * 4. SELECT field1, field2 FROM table_name WHERE conditions - 指定字段带条件查询
 *
 * 查询处理流程：
 * 1. 根据表名定位到具体的表
 * 2. 根据WHERE条件筛选符合条件的记录
 * 3. 根据字段列表投影需要的列
 * 4. 返回查询结果
 *
 * 与MySQL对比：
 * - MySQL支持复杂查询：JOIN、子查询、聚合函数、GROUP BY、ORDER BY、LIMIT等
 * - MYDB只支持基本的单表查询，包括字段投影和WHERE条件筛选
 * - MySQL查询优化器更复杂，MYDB使用简单的顺序扫描或索引扫描
 *
 * 性能考虑：
 * - 如果WHERE条件涉及索引字段，会使用索引快速定位
 * - 否则进行全表扫描，对于大表性能较差
 * - 字段投影可以减少网络传输的数据量
 *
 * @author guoziyang
 * @see Where WHERE条件对象
 * @see top.guoziyang.mydb.backend.tbm.Table 表对象
 */
public class Select {
    /** 查询的表名 */
    public String tableName;
    
    /**
     * 查询的字段列表
     * 如果包含"*"表示查询所有字段，否则只查询指定字段
     */
    public String[] fields;
    
    /**
     * WHERE条件对象
     * null表示无条件查询（全表扫描）
     */
    public Where where;
}
