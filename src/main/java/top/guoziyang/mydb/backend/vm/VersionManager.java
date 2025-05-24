package top.guoziyang.mydb.backend.vm;

import top.guoziyang.mydb.backend.dm.DataManager;
import top.guoziyang.mydb.backend.tm.TransactionManager;

/**
 * VersionManager接口 - 版本管理器（多版本并发控制MVCC）
 * 
 * VersionManager是MYDB实现多版本并发控制(MVCC)的核心接口，负责管理数据的多个版本，
 * 实现事务隔离和并发控制。
 * 
 * 与MySQL InnoDB MVCC系统的对比：
 * - MySQL InnoDB: 复杂的MVCC实现，包含Read View、版本链、undo log等
 * - MYDB VersionManager: 简化的MVCC实现，专注于核心的版本可见性控制
 * 
 * MVCC核心概念：
 * 1. 版本控制：每条数据可能有多个版本，由不同事务创建
 * 2. 可见性判断：根据事务隔离级别和事务快照判断版本是否可见
 * 3. 读写分离：读操作不阻塞写操作，写操作不阻塞读操作
 * 4. 事务快照：事务开始时记录当前活跃事务，用于可见性判断
 * 
 * 支持的隔离级别：
 * - READ COMMITTED (0): 读已提交，可以读取已提交事务的最新版本
 * - REPEATABLE READ (1): 可重复读，只能读取事务开始前已提交的版本
 */
public interface VersionManager {
    
    /**
     * 读取指定版本的数据
     * 
     * @param xid 读取事务的ID
     * @param uid 数据项的唯一标识符
     * @return 可见的数据内容，如果不可见则返回null
     * @throws Exception 读取异常
     * 
     * 读取流程：
     * 1. 获取事务对象和隔离级别
     * 2. 加载数据项的Entry
     * 3. 根据可见性规则判断是否可见
     * 4. 返回可见数据或null
     * 
     * 类似于MySQL InnoDB的一致性读(Consistent Read)
     */
    byte[] read(long xid, long uid) throws Exception;
    
    /**
     * 插入新数据
     * 
     * @param xid 插入事务的ID
     * @param data 要插入的数据内容
     * @return 新数据项的UID
     * @throws Exception 插入异常
     * 
     * 插入流程：
     * 1. 检查事务状态
     * 2. 创建新的Entry，设置XMIN为当前事务
     * 3. 通过DataManager存储到磁盘
     * 4. 返回新数据的UID
     * 
     * 对应MySQL InnoDB的INSERT操作
     */
    long insert(long xid, byte[] data) throws Exception;
    
    /**
     * 删除数据（逻辑删除）
     * 
     * @param xid 删除事务的ID
     * @param uid 要删除的数据项UID
     * @return true表示删除成功，false表示删除失败
     * @throws Exception 删除异常或死锁异常
     * 
     * 删除流程：
     * 1. 检查事务状态和数据可见性
     * 2. 尝试获取数据项的锁
     * 3. 检查是否会造成死锁
     * 4. 设置XMAX为当前事务（逻辑删除）
     * 
     * MVCC删除特点：
     * - 逻辑删除：不物理删除数据，而是标记删除事务
     * - 版本控制：其他事务仍可能看到删除前的版本
     * - 死锁检测：通过LockTable检测和处理死锁
     * 
     * 对应MySQL InnoDB的DELETE操作
     */
    boolean delete(long xid, long uid) throws Exception;

    /**
     * 开始新事务
     * 
     * @param level 事务隔离级别 (0=READ_COMMITTED, 1=REPEATABLE_READ)
     * @return 新事务的XID
     * 
     * 事务开始流程：
     * 1. 通过TransactionManager分配新的XID
     * 2. 创建Transaction对象，设置隔离级别
     * 3. 如果是REPEATABLE_READ，创建快照记录当前活跃事务
     * 4. 将事务添加到活跃事务表
     * 
     * 快照机制：
     * - READ_COMMITTED: 不创建快照，每次读取都看最新已提交版本
     * - REPEATABLE_READ: 创建快照，整个事务期间只看快照时刻的数据
     * 
     * 类似于MySQL的START TRANSACTION语句
     */
    long begin(int level);
    
    /**
     * 提交事务
     * 
     * @param xid 要提交的事务ID
     * @throws Exception 提交异常
     * 
     * 提交流程：
     * 1. 检查事务是否有错误
     * 2. 从活跃事务表中移除
     * 3. 释放事务持有的所有锁
     * 4. 通过TransactionManager标记事务为已提交
     * 
     * 提交后效果：
     * - 事务的所有修改对其他事务可见（根据隔离级别）
     * - 释放所有资源和锁
     * - 事务状态变为COMMITTED
     * 
     * 类似于MySQL的COMMIT语句
     */
    void commit(long xid) throws Exception;
    
    /**
     * 回滚事务
     * 
     * @param xid 要回滚的事务ID
     * 
     * 回滚流程：
     * 1. 从活跃事务表中移除事务
     * 2. 释放事务持有的所有锁
     * 3. 通过TransactionManager标记事务为已回滚
     * 
     * 回滚后效果：
     * - 事务的所有修改对任何事务都不可见
     * - 释放所有资源和锁
     * - 事务状态变为ABORTED
     * 
     * 类似于MySQL的ROLLBACK语句
     */
    void abort(long xid);

    /**
     * 创建版本管理器实例
     * 
     * @param tm 事务管理器
     * @param dm 数据管理器
     * @return VersionManager实例
     * 
     * 工厂方法，创建VersionManagerImpl实例并初始化：
     * 1. 注入事务管理器和数据管理器
     * 2. 初始化活跃事务表
     * 3. 创建超级事务(XID=0)
     * 4. 初始化死锁检测表
     */
    public static VersionManager newVersionManager(TransactionManager tm, DataManager dm) {
        return new VersionManagerImpl(tm, dm);
    }

}
