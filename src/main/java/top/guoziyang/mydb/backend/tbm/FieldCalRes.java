package top.guoziyang.mydb.backend.tbm;

/**
 * FieldCalRes类 - 字段计算结果
 * 
 * 这是一个数据容器类，用于封装字段表达式计算的结果。
 * 主要用于WHERE子句中的条件表达式计算，返回满足条件的数据记录UID范围。
 * 
 * 与MySQL的对比：
 * - MySQL在查询优化时会计算选择性和统计信息来确定查询范围
 * - MYDB简化了这个过程，直接返回满足条件的记录ID范围
 * 
 * 应用场景：
 * 1. WHERE子句中的条件计算（如 age > 18 AND age < 65）
 * 2. 范围查询的结果封装
 * 3. 索引扫描的边界确定
 */
public class FieldCalRes {
    
    /**
     * 左边界
     * 
     * 表示满足条件的记录UID的最小值（包含）。
     * 用于确定范围查询的起始位置。
     * 
     * 示例：
     * - 对于条件 "age >= 18"，left可能是第一个年龄>=18的记录UID
     * - 对于精确匹配 "id = 100"，left和right可能都是同一个UID
     */
    public long left;
    
    /**
     * 右边界
     * 
     * 表示满足条件的记录UID的最大值（包含）。
     * 用于确定范围查询的结束位置。
     * 
     * 示例：
     * - 对于条件 "age <= 65"，right可能是最后一个年龄<=65的记录UID
     * - 如果没有找到匹配记录，left > right表示空结果集
     */
    public long right;
}
