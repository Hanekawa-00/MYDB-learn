package top.guoziyang.mydb.backend.parser.statement;

/**
 * CREATE TABLE语句对象
 *
 * 功能说明：
 * 表示SQL中的CREATE TABLE语句，用于创建新的数据库表。
 * 包含表的基本结构定义：表名、字段定义、数据类型和索引信息。
 *
 * 支持的语法格式：
 * CREATE TABLE table_name field1 type1, field2 type2, ... (index field1, field2, ...)
 *
 * 语法要求：
 * 1. 必须指定表名（符合标识符规则）
 * 2. 必须定义至少一个字段及其数据类型
 * 3. 必须定义索引（MYDB强制要求）
 * 4. 字段名和类型必须一一对应
 *
 * 支持的数据类型：
 * - int32：32位整数，占用4字节
 * - int64：64位长整数，占用8字节
 * - string：变长字符串，长度前缀编码
 *
 * 索引要求：
 * MYDB要求每个表都必须有索引，这是一个简化的设计决策：
 * - 提高查询性能，避免全表扫描
 * - 简化查询优化器的实现
 * - 索引字段必须是已定义的字段
 *
 * 与MySQL对比：
 * - MySQL支持更多数据类型：DECIMAL、DATE、TIME、BLOB等
 * - MySQL支持约束：PRIMARY KEY、FOREIGN KEY、UNIQUE、NOT NULL等
 * - MySQL索引是可选的，MYDB强制要求
 * - MySQL支持表选项：存储引擎、字符集、排序规则等
 *
 * 存储实现：
 * 创建表时会：
 * 1. 在表管理器中注册表的元数据
 * 2. 为索引字段创建B+树索引结构
 * 3. 分配表的存储空间
 *
 * 使用示例：
 * CREATE TABLE users id int32, name string, age int32 (index id, name)
 *
 * @author guoziyang
 * @see top.guoziyang.mydb.backend.tbm.TableManager 表管理器
 * @see top.guoziyang.mydb.backend.im.BPlusTree B+树索引
 */
public class Create {
    /** 要创建的表名 */
    public String tableName;
    
    /**
     * 字段名数组
     * 与fieldType数组一一对应
     */
    public String[] fieldName;
    
    /**
     * 字段类型数组
     * 支持的类型：int32, int64, string
     * 与fieldName数组一一对应
     */
    public String[] fieldType;
    
    /**
     * 索引字段数组
     * 必须是fieldName中已定义的字段
     * MYDB要求每个表都必须有索引
     */
    public String[] index;
}
