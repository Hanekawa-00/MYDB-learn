package top.guoziyang.mydb.backend.parser.statement;

/**
 * WHERE条件语句对象
 *
 * 功能说明：
 * 表示SQL中的WHERE子句，用于指定查询、更新、删除操作的筛选条件。
 * WHERE子句是数据库查询中最重要的组成部分之一，决定了哪些记录会被操作。
 *
 * 支持的条件语法：
 * 1. 单一条件：WHERE field = value
 * 2. 复合条件：WHERE field1 = value1 AND field2 > value2
 * 3. 复合条件：WHERE field1 = value1 OR field2 < value2
 *
 * 条件表达式结构：
 * WHERE子句由一个或两个单一表达式组成，通过逻辑操作符连接。
 * - singleExp1：第一个条件表达式（必须存在）
 * - logicOp：逻辑操作符（AND/OR，可选）
 * - singleExp2：第二个条件表达式（当有逻辑操作符时存在）
 *
 * 与MySQL对比：
 * - MySQL支持复杂的WHERE条件：IN、BETWEEN、LIKE、EXISTS、子查询等
 * - MYDB只支持基本的比较条件和简单的逻辑组合
 * - MySQL支持任意层次的条件嵌套，MYDB最多支持两个条件的组合
 *
 * 查询优化：
 * - 如果条件涉及索引字段，查询引擎会尝试使用索引
 * - AND条件：两个条件都要满足，可能使用索引交集
 * - OR条件：任一条件满足即可，可能使用索引并集
 *
 * 使用示例：
 * - WHERE id = 1
 * - WHERE name = 'John' AND age > 18
 * - WHERE status = 'active' OR priority > 5
 *
 * @author guoziyang
 * @see SingleExpression 单一比较表达式
 */
public class Where {
    /** 第一个条件表达式，必须存在 */
    public SingleExpression singleExp1;
    
    /**
     * 逻辑操作符：AND 或 OR
     * 空字符串表示只有一个条件表达式
     */
    public String logicOp;
    
    /**
     * 第二个条件表达式
     * 只有当logicOp不为空时才存在
     */
    public SingleExpression singleExp2;
}
