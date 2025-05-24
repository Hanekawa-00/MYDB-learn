package top.guoziyang.mydb.backend.parser.statement;

/**
 * 单一比较表达式对象
 *
 * 功能说明：
 * 表示WHERE子句中的基本比较表达式，形如 "field operator value"。
 * 这是构成复杂查询条件的基本单元，多个单一表达式可以通过逻辑操作符组合。
 *
 * 表达式格式：
 * field compareOp value
 * 例如：id = 1, name = 'John', age > 18, price < 100.5
 *
 * 支持的比较操作符：
 * - = (等于)：field = value
 * - > (大于)：field > value
 * - < (小于)：field < value
 *
 * 数据类型处理：
 * MYDB支持以下数据类型的比较：
 * - int32：32位整数
 * - int64：64位长整数
 * - string：字符串（支持引号包围）
 *
 * 与MySQL对比：
 * - MySQL支持更多比较操作符：>=, <=, !=, <>, LIKE, IN, BETWEEN等
 * - MYDB只支持基本的三种比较操作符
 * - MySQL支持更复杂的数据类型和函数调用
 * - MYDB保持简单，只支持基本的字面量比较
 *
 * 索引优化：
 * - 等值比较(=)：最适合B+树索引查找
 * - 范围比较(>, <)：可以使用索引范围扫描
 * - 如果field是索引字段，查询引擎会优先使用索引
 *
 * 使用示例：
 * - id = 123
 * - name = 'Alice'
 * - age > 18
 * - price < 99.99
 *
 * @author guoziyang
 * @see Where WHERE条件对象
 */
public class SingleExpression {
    /** 字段名，表示要比较的列 */
    public String field;
    
    /**
     * 比较操作符
     * 支持的值：=, >, <
     */
    public String compareOp;
    
    /**
     * 比较值
     * 可以是数字或字符串字面量
     */
    public String value;
}
