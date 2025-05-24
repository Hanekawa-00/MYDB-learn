package top.guoziyang.mydb.backend.vm;

import java.util.HashMap;
import java.util.Map;

import top.guoziyang.mydb.backend.tm.TransactionManagerImpl;

/**
 * Transaction类 - 事务对象抽象
 * 
 * Transaction代表版本管理器中的一个事务，包含事务的基本信息和快照数据。
 * 这是MVCC实现的核心数据结构之一。
 * 
 * 与MySQL InnoDB事务对象的对比：
 * - MySQL InnoDB: 复杂的事务对象，包含锁信息、回滚段、状态等
 * - MYDB Transaction: 简化的事务对象，专注于快照和可见性
 * 
 * 核心概念：
 * 1. 事务快照：记录事务开始时的活跃事务列表
 * 2. 隔离级别：控制事务的可见性行为
 * 3. 错误处理：记录事务执行过程中的异常
 * 4. 自动回滚：检测到死锁时自动标记回滚
 */
public class Transaction {
    
    /**
     * 事务ID，唯一标识一个事务
     * 对应MySQL中的事务ID概念
     */
    public long xid;
    
    /**
     * 事务隔离级别
     * 0 = READ_COMMITTED (读已提交)
     * 1 = REPEATABLE_READ (可重复读)
     * 
     * 隔离级别决定了快照行为：
     * - READ_COMMITTED: 不创建快照，每次读取看最新已提交版本
     * - REPEATABLE_READ: 创建快照，整个事务期间保持一致的读取视图
     */
    public int level;
    
    /**
     * 事务快照 - REPEATABLE_READ隔离级别的核心
     * 
     * 快照内容：记录事务开始时所有活跃（未提交）的事务ID
     * 快照作用：用于判断版本的可见性
     * 
     * 可见性规则：
     * - 如果版本的创建事务在快照中，则对当前事务不可见
     * - 如果版本的创建事务不在快照中且已提交，则可见
     * 
     * 对应MySQL InnoDB的Read View概念，但更简化
     */
    public Map<Long, Boolean> snapshot;
    
    /**
     * 事务执行过程中的错误
     * 
     * 错误类型：
     * - 死锁异常：检测到死锁时设置
     * - 并发更新异常：并发冲突时设置
     * - 其他数据库异常
     * 
     * 错误处理：一旦设置错误，事务的后续操作都会失败
     */
    public Exception err;
    
    /**
     * 自动回滚标记
     * 
     * 设置为true的情况：
     * - 检测到死锁，系统自动选择当前事务回滚
     * - 发生不可恢复的并发冲突
     * 
     * 自动回滚事务会被立即标记为已回滚状态
     */
    public boolean autoAborted;

    /**
     * 创建新的事务对象
     * 
     * @param xid 事务ID
     * @param level 隔离级别 (0=READ_COMMITTED, 1=REPEATABLE_READ)
     * @param active 当前所有活跃的事务映射
     * @return 新的Transaction对象
     * 
     * 创建过程：
     * 1. 初始化基本属性
     * 2. 根据隔离级别决定是否创建快照
     * 3. 如果是REPEATABLE_READ，复制当前活跃事务作为快照
     * 
     * 快照创建原理：
     * - 记录创建时刻所有活跃（未提交）的事务
     * - 这些事务的修改对当前事务不可见
     * - 保证整个事务期间读取的一致性
     */
    public static Transaction newTransaction(long xid, int level, Map<Long, Transaction> active) {
        Transaction t = new Transaction();
        t.xid = xid;
        t.level = level;
        
        // 只有REPEATABLE_READ级别才创建快照
        if(level != 0) {
            t.snapshot = new HashMap<>();
            // 将所有当前活跃的事务ID加入快照
            // 这些事务的修改对当前事务不可见
            for(Long x : active.keySet()) {
                t.snapshot.put(x, true);
            }
        }
        return t;
    }

    /**
     * 检查指定事务是否在当前事务的快照中
     * 
     * @param xid 要检查的事务ID
     * @return true表示在快照中（不可见），false表示不在快照中（可能可见）
     * 
     * 用途：
     * 1. 版本可见性判断的核心方法
     * 2. 实现REPEATABLE_READ隔离级别的一致性读
     * 
     * 特殊处理：
     * - 超级事务(XID=0)永远不在快照中，因为它永远是已提交状态
     * - 只在REPEATABLE_READ级别有意义，READ_COMMITTED不使用快照
     * 
     * 对应MySQL InnoDB中检查事务ID是否在Read View中的逻辑
     */
    public boolean isInSnapshot(long xid) {
        // 超级事务特殊处理：永远不在快照中
        if(xid == TransactionManagerImpl.SUPER_XID) {
            return false;
        }
        // 检查事务是否在快照中
        return snapshot.containsKey(xid);
    }
}
