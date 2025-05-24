package top.guoziyang.mydb.backend.parser.statement;

/**
 * BEGIN事务语句对象
 *
 * 功能说明：
 * 表示SQL中的BEGIN TRANSACTION语句，用于开启一个新的数据库事务。
 * 在MYDB中，事务是保证数据一致性的重要机制。
 *
 * 支持的语法：
 * 1. BEGIN - 开启默认隔离级别的事务
 * 2. BEGIN ISOLATION LEVEL READ COMMITTED - 开启读已提交隔离级别的事务
 * 3. BEGIN ISOLATION LEVEL REPEATABLE READ - 开启可重复读隔离级别的事务
 *
 * 事务隔离级别：
 * - READ COMMITTED（读已提交）：默认级别，能读取到其他事务已提交的修改
 * - REPEATABLE READ（可重复读）：在同一事务中多次读取同一数据结果一致
 *
 * 与MySQL对比：
 * - MySQL支持四种隔离级别：READ UNCOMMITTED, READ COMMITTED, REPEATABLE READ, SERIALIZABLE
 * - MYDB简化为两种：READ COMMITTED和REPEATABLE READ
 * - MySQL默认是REPEATABLE READ，MYDB默认是READ COMMITTED
 *
 * 设计要点：
 * 该类采用简单的数据传输对象(DTO)模式，只包含必要的属性，
 * 具体的事务逻辑由版本管理模块(VM)处理。
 *
 * @author guoziyang
 * @see top.guoziyang.mydb.backend.vm.VersionManager 版本管理器
 * @see top.guoziyang.mydb.backend.tm.TransactionManager 事务管理器
 */
public class Begin {
    /**
     * 是否为可重复读隔离级别
     * true: REPEATABLE READ, false: READ COMMITTED
     */
    public boolean isRepeatableRead;
}
