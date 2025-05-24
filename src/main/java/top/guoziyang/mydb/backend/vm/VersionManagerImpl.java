package top.guoziyang.mydb.backend.vm;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.backend.common.AbstractCache;
import top.guoziyang.mydb.backend.dm.DataManager;
import top.guoziyang.mydb.backend.tm.TransactionManager;
import top.guoziyang.mydb.backend.tm.TransactionManagerImpl;
import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.common.Error;

/**
 * VersionManagerImpl类 - 版本管理器的具体实现
 * 
 * VersionManagerImpl是MYDB多版本并发控制(MVCC)的核心实现类，
 * 继承AbstractCache实现Entry的缓存管理，协调事务管理器和数据管理器，
 * 提供完整的事务隔离和并发控制功能。
 * 
 * 与MySQL InnoDB MVCC实现的对比：
 * - MySQL InnoDB: 基于undo log的版本链 + Read View机制
 * - MYDB VersionManagerImpl: 基于Entry版本记录 + 事务快照机制
 * 
 * 架构设计：
 * 1. 继承AbstractCache：提供Entry的LRU缓存管理
 * 2. 集成TransactionManager：管理事务生命周期
 * 3. 集成DataManager：处理底层数据存储
 * 4. 集成LockTable：提供死锁检测和锁管理
 * 
 * 核心功能：
 * 1. 事务管理：开始、提交、回滚事务
 * 2. MVCC读取：根据隔离级别判断版本可见性
 * 3. 并发控制：通过锁机制防止冲突和死锁
 * 4. 缓存管理：高效的Entry缓存和释放
 */
public class VersionManagerImpl extends AbstractCache<Entry> implements VersionManager {

    /**
     * 事务管理器 - 管理事务状态和生命周期
     */
    TransactionManager tm;
    
    /**
     * 数据管理器 - 负责底层数据的存储和读取
     */
    DataManager dm;
    
    /**
     * 活跃事务映射表 - 记录当前所有活跃的事务
     * Key: 事务ID, Value: 事务对象
     * 
     * 用途：
     * 1. 快速查找事务对象和状态
     * 2. 创建新事务时生成快照
     * 3. 管理事务的生命周期
     */
    Map<Long, Transaction> activeTransaction;
    
    /**
     * 全局锁 - 保护活跃事务表的并发安全
     * 在事务开始、提交、回滚时需要获取此锁
     */
    Lock lock;
    
    /**
     * 锁表 - 死锁检测和资源锁管理
     * 负责检测事务间的死锁并管理资源竞争
     */
    LockTable lt;

    /**
     * 构造函数 - 初始化版本管理器
     * 
     * @param tm 事务管理器
     * @param dm 数据管理器
     * 
     * 初始化过程：
     * 1. 调用父类构造函数，禁用缓存限制（参数0）
     * 2. 注入事务管理器和数据管理器
     * 3. 初始化活跃事务表
     * 4. 创建超级事务（XID=0），用于系统初始化
     * 5. 初始化全局锁和死锁检测表
     */
    public VersionManagerImpl(TransactionManager tm, DataManager dm) {
        super(0);                                     // 禁用缓存大小限制
        this.tm = tm;
        this.dm = dm;
        this.activeTransaction = new HashMap<>();
        
        // 创建超级事务，用于系统初始化和特殊操作
        activeTransaction.put(TransactionManagerImpl.SUPER_XID, 
            Transaction.newTransaction(TransactionManagerImpl.SUPER_XID, 0, null));
            
        this.lock = new ReentrantLock();
        this.lt = new LockTable();
    }

    /**
     * 读取数据 - MVCC的核心读取操作
     * 
     * @param xid 读取事务的ID
     * @param uid 要读取的数据项UID
     * @return 可见的数据内容，不可见则返回null
     * @throws Exception 读取异常
     * 
     * 读取流程：
     * 1. 获取事务对象，检查事务状态
     * 2. 通过缓存系统获取Entry
     * 3. 根据隔离级别判断版本可见性
     * 4. 返回可见数据或null
     * 5. 释放Entry引用
     * 
     * MVCC特性：
     * - 读操作不加锁，不阻塞写操作
     * - 根据事务快照判断版本可见性
     * - 支持READ_COMMITTED和REPEATABLE_READ隔离级别
     */
    @Override
    public byte[] read(long xid, long uid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        // 检查事务是否有错误
        if(t.err != null) {
            throw t.err;
        }

        Entry entry = null;
        try {
            // 通过缓存系统获取Entry
            entry = super.get(uid);
        } catch(Exception e) {
            if(e == Error.NullEntryException) {
                return null;                          // Entry不存在
            } else {
                throw e;
            }
        }
        
        try {
            // 判断版本可见性
            if(Visibility.isVisible(tm, t, entry)) {
                return entry.data();                  // 返回可见数据
            } else {
                return null;                          // 版本不可见
            }
        } finally {
            entry.release();                          // 释放Entry引用
        }
    }

    /**
     * 插入数据 - 创建新的数据版本
     * 
     * @param xid 插入事务的ID
     * @param data 要插入的数据内容
     * @return 新数据项的UID
     * @throws Exception 插入异常
     * 
     * 插入流程：
     * 1. 检查事务状态
     * 2. 包装数据为Entry格式（设置XMIN为当前事务）
     * 3. 通过DataManager存储到磁盘
     * 4. 返回新数据的UID
     * 
     * 插入特点：
     * - 新版本的XMIN设置为当前事务ID
     * - XMAX初始化为0（未删除）
     * - 数据立即可见于当前事务
     * - 其他事务根据隔离级别和事务状态判断可见性
     */
    @Override
    public long insert(long xid, byte[] data) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        // 检查事务状态
        if(t.err != null) {
            throw t.err;
        }

        // 包装数据为Entry格式
        byte[] raw = Entry.wrapEntryRaw(xid, data);
        return dm.insert(xid, raw);
    }

    /**
     * 删除数据 - 逻辑删除实现
     * 
     * @param xid 删除事务的ID
     * @param uid 要删除的数据项UID
     * @return true表示删除成功，false表示删除失败
     * @throws Exception 删除异常或死锁异常
     * 
     * 删除流程：
     * 1. 检查事务状态
     * 2. 获取要删除的Entry
     * 3. 检查版本可见性（只能删除可见的版本）
     * 4. 尝试获取资源锁（可能触发死锁检测）
     * 5. 检查版本跳过条件（防止重复删除）
     * 6. 设置XMAX为当前事务ID（逻辑删除）
     * 
     * 删除特点：
     * - 逻辑删除：设置XMAX而不物理删除数据
     * - 死锁检测：通过LockTable检测和处理死锁
     * - 版本控制：其他事务可能仍能看到删除前的版本
     * - 并发安全：通过锁机制保证删除操作的原子性
     */
    @Override
    public boolean delete(long xid, long uid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        // 检查事务状态
        if(t.err != null) {
            throw t.err;
        }
        
        Entry entry = null;
        try {
            // 获取要删除的Entry
            entry = super.get(uid);
        } catch(Exception e) {
            if(e == Error.NullEntryException) {
                return false;                         // Entry不存在
            } else {
                throw e;
            }
        }
        
        try {
            // 检查版本是否可见（只能删除可见的版本）
            if(!Visibility.isVisible(tm, t, entry)) {
                return false;
            }
            
            Lock l = null;
            try {
                // 尝试获取资源锁，可能触发死锁检测
                l = lt.add(xid, uid);
            } catch(Exception e) {
                // 死锁异常处理
                t.err = Error.ConcurrentUpdateException;
                internAbort(xid, true);               // 自动回滚事务
                t.autoAborted = true;
                throw t.err;
            }
            
            // 如果需要等待锁
            if(l != null) {
                l.lock();                             // 阻塞等待
                l.unlock();                           // 获得锁后立即释放
            }

            // 检查是否已被当前事务删除
            if(entry.getXmax() == xid) {
                return false;                         // 已删除，避免重复删除
            }

            // 检查版本跳过条件（防止幻读等问题）
            if(Visibility.isVersionSkip(tm, t, entry)) {
                t.err = Error.ConcurrentUpdateException;
                internAbort(xid, true);               // 自动回滚事务
                t.autoAborted = true;
                throw t.err;
            }

            // 执行逻辑删除：设置XMAX
            entry.setXmax(xid);
            return true;

        } finally {
            entry.release();                          // 释放Entry引用
        }
    }

    /**
     * 开始新事务
     * 
     * @param level 隔离级别 (0=READ_COMMITTED, 1=REPEATABLE_READ)
     * @return 新事务的XID
     * 
     * 开始流程：
     * 1. 获取全局锁保护活跃事务表
     * 2. 通过事务管理器分配新的XID
     * 3. 创建事务对象，根据隔离级别设置快照
     * 4. 将事务加入活跃事务表
     * 5. 释放全局锁
     * 
     * 快照创建：
     * - READ_COMMITTED: 不创建快照，每次读取看最新状态
     * - REPEATABLE_READ: 创建快照，记录当前所有活跃事务
     */
    @Override
    public long begin(int level) {
        lock.lock();
        try {
            long xid = tm.begin();                    // 分配新事务ID
            Transaction t = Transaction.newTransaction(xid, level, activeTransaction);
            activeTransaction.put(xid, t);            // 加入活跃事务表
            return xid;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 提交事务
     * 
     * @param xid 要提交的事务ID
     * @throws Exception 提交异常
     * 
     * 提交流程：
     * 1. 检查事务是否有错误
     * 2. 从活跃事务表中移除事务
     * 3. 释放事务持有的所有锁
     * 4. 通过事务管理器标记事务为已提交
     * 
     * 提交后效果：
     * - 事务的所有修改对其他事务可见（根据隔离级别）
     * - 释放所有资源和锁
     * - 事务状态变为COMMITTED
     */
    @Override
    public void commit(long xid) throws Exception {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        lock.unlock();

        try {
            // 检查事务是否有错误
            if(t.err != null) {
                throw t.err;
            }
        } catch(NullPointerException n) {
            // 调试信息：如果事务不存在
            System.out.println(xid);
            System.out.println(activeTransaction.keySet());
            Panic.panic(n);
        }

        lock.lock();
        activeTransaction.remove(xid);                // 从活跃事务表移除
        lock.unlock();

        lt.remove(xid);                               // 释放所有锁
        tm.commit(xid);                               // 标记为已提交
    }

    /**
     * 回滚事务（外部调用）
     * 
     * @param xid 要回滚的事务ID
     * 
     * 调用内部回滚方法，autoAborted参数为false表示手动回滚
     */
    @Override
    public void abort(long xid) {
        internAbort(xid, false);
    }

    /**
     * 内部回滚实现
     * 
     * @param xid 要回滚的事务ID
     * @param autoAborted 是否为自动回滚（死锁检测触发）
     * 
     * 回滚流程：
     * 1. 从活跃事务表中移除事务（如果非自动回滚）
     * 2. 检查是否已经自动回滚过
     * 3. 释放事务持有的所有锁
     * 4. 通过事务管理器标记事务为已回滚
     * 
     * 自动回滚：
     * - 由死锁检测或并发冲突触发
     * - 事务已经从活跃表中移除
     * - 避免重复处理
     */
    private void internAbort(long xid, boolean autoAborted) {
        lock.lock();
        Transaction t = activeTransaction.get(xid);
        if(!autoAborted) {
            activeTransaction.remove(xid);            // 手动回滚才移除
        }
        lock.unlock();

        if(t.autoAborted) return;                     // 避免重复回滚
        lt.remove(xid);                               // 释放所有锁
        tm.abort(xid);                                // 标记为已回滚
    }

    /**
     * 释放Entry到缓存系统
     * 
     * @param entry 要释放的Entry
     * 
     * 将Entry返回给AbstractCache管理，
     * 支持LRU缓存和自动回收
     */
    public void releaseEntry(Entry entry) {
        super.release(entry.getUid());
    }

    /**
     * 缓存未命中时的加载方法
     * 
     * @param uid 要加载的数据项UID
     * @return 加载的Entry对象
     * @throws Exception 加载异常
     * 
     * AbstractCache框架的回调方法：
     * 1. 当缓存中没有请求的Entry时调用
     * 2. 通过Entry.loadEntry()从存储加载
     * 3. 如果Entry不存在，抛出NullEntryException
     */
    @Override
    protected Entry getForCache(long uid) throws Exception {
        Entry entry = Entry.loadEntry(this, uid);
        if(entry == null) {
            throw Error.NullEntryException;
        }
        return entry;
    }

    /**
     * 缓存回收时的清理方法
     * 
     * @param entry 要清理的Entry
     * 
     * AbstractCache框架的回调方法：
     * 当Entry被LRU算法选中回收时调用，
     * 负责清理Entry占用的资源
     */
    @Override
    protected void releaseForCache(Entry entry) {
        entry.remove();
    }
    
}
