package top.guoziyang.mydb.backend.parser.statement;

/**
 * ABORT事务语句对象
 *
 * 功能说明：
 * 表示SQL中的ABORT语句，用于回滚当前事务的所有修改操作。
 * ABORT与ROLLBACK语义相同，是事务处理中的重要操作。
 *
 * 支持的语法：
 * ABORT - 回滚当前事务
 *
 * 事务回滚过程：
 * 1. 撤销事务中的所有修改操作
 * 2. 恢复数据到事务开始前的状态
 * 3. 释放事务持有的所有锁
 * 4. 清理事务相关的临时数据
 *
 * 与COMMIT的区别：
 * - COMMIT：提交事务，使修改永久化
 * - ABORT：回滚事务，撤销所有修改
 *
 * 回滚机制：
 * MYDB使用版本管理(MVCC)来实现事务回滚：
 * - 每个修改操作都有版本信息
 * - 回滚时标记事务为已终止状态
 * - 其他事务看不到已回滚事务的修改
 *
 * 使用场景：
 * 1. 手动回滚：用户主动执行ABORT
 * 2. 异常回滚：系统检测到错误自动回滚
 * 3. 死锁回滚：解决死锁时回滚某个事务
 * 4. 冲突回滚：并发冲突时回滚冲突事务
 *
 * 与MySQL对比：
 * - MySQL使用ROLLBACK关键字
 * - MySQL支持部分回滚(SAVEPOINT)
 * - MYDB只支持完整事务回滚
 *
 * @author guoziyang
 * @see Begin BEGIN事务语句
 * @see Commit COMMIT事务语句
 * @see top.guoziyang.mydb.backend.vm.VersionManager 版本管理器
 */
public class Abort {
    // 空类 - ABORT语句不需要额外的参数
}
