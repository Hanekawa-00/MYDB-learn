package top.guoziyang.mydb.backend.tm;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.common.Error;

/**
 * TransactionManagerImpl - 事务管理器的具体实现
 *
 * 这是TransactionManager接口的核心实现，负责管理数据库中所有事务的生命周期。
 *
 * 与MySQL InnoDB事务实现的对比：
 * - MySQL InnoDB: 复杂的事务系统，包含回滚段、事务链表、锁管理等
 * - MYDB: 简化实现，专注于事务状态的持久化管理
 * - 共同点: 都使用唯一的事务ID标识每个事务
 *
 * 核心设计理念：
 * 1. 简单持久化：将事务状态直接存储在文件中
 * 2. 快速访问：通过事务ID计算文件偏移量，实现O(1)访问
 * 3. 原子更新：每次状态变更都立即刷新到磁盘
 * 4. 并发安全：使用锁保护XID计数器的原子性
 *
 * 文件存储布局详解：
 * ┌─────────────────┬─────────┬─────────┬─────────┬─────┐
 * │   XID Header    │  XID 1  │  XID 2  │  XID 3  │ ... │
 * │    (8字节)       │ (1字节)  │ (1字节)  │ (1字节)  │     │
 * │  最大事务ID      │  状态    │  状态    │  状态    │     │
 * └─────────────────┴─────────┴─────────┴─────────┴─────┘
 *
 * 事务状态编码：
 * - 0 (ACTIVE): 事务正在执行中
 * - 1 (COMMITTED): 事务已成功提交
 * - 2 (ABORTED): 事务已被回滚
 */
public class TransactionManagerImpl implements TransactionManager {

    /**
     * XID文件头长度（8字节）
     * 存储当前数据库中最大的事务ID，用于分配新事务ID,新分配的要大于当前最大ID
     */
    static final int LEN_XID_HEADER_LENGTH = 8;

    /**
     * 每个事务在文件中占用的字节数（1字节）
     * 一个字节足以表示事务的三种状态
     */
    private static final int XID_FIELD_SIZE = 1;

    /**
     * 事务状态常量定义
     *
     * 事务状态转换图：
     *     begin()
     *        ↓
     *   ┌─────────┐    commit()     ┌───────────┐
     *   │ ACTIVE  ├─────────────────→ COMMITTED │
     *   │   (0)   │                 │    (1)    │
     *   └────┬────┘                 └───────────┘
     *        │         abort()      ┌───────────┐
     *        └──────────────────────→  ABORTED  │
     *                               │    (2)    │
     *                               └───────────┘
     */
    private static final byte FIELD_TRAN_ACTIVE   = 0;  // 活跃状态：事务正在执行
	private static final byte FIELD_TRAN_COMMITTED = 1;  // 已提交：事务成功完成
	private static final byte FIELD_TRAN_ABORTED  = 2;   // 已回滚：事务被撤销

    /**
     * 超级事务XID（特殊事务）
     *
     * 超级事务是一个特殊的事务，XID为0，永远处于COMMITTED状态。
     * 设计目的：
     * 1. 为系统初始化数据提供事务上下文
     * 2. 简化边界条件处理
     * 3. 避免空指针和特殊情况判断
     *
     * 类似于MySQL中的系统事务或启动事务
     */
    public static final long SUPER_XID = 0;

    /**
     * XID文件的后缀名
     * 事务管理器使用.xid文件存储所有事务状态
     */
    static final String XID_SUFFIX = ".xid";

    /**
     * XID文件的随机访问文件对象
     * 支持在文件任意位置读写，用于快速定位事务状态
     */
    private RandomAccessFile file;

    /**
     * 文件通道，提供高效的文件I/O操作
     * 支持强制刷新(force)，确保数据持久化
     */
    private FileChannel fc;

    /**
     * XID计数器，记录当前事务数量
     * 新事务的XID = xidCounter + 1
     */
    private long xidCounter;

    /**
     * 并发控制锁，保护XID计数器的原子性
     * 防止多线程同时分配事务ID导致冲突
     */
    private Lock counterLock;

    /**
     * 构造函数 - 初始化事务管理器
     *
     * @param raf 随机访问文件对象
     * @param fc 文件通道
     *
     * 初始化过程：
     * 1. 保存文件句柄引用
     * 2. 创建线程安全的锁对象
     * 3. 检查并恢复XID计数器状态
     */
    TransactionManagerImpl(RandomAccessFile raf, FileChannel fc) {
        this.file = raf;
        this.fc = fc;
        counterLock = new ReentrantLock();
        checkXIDCounter();  // 验证文件完整性并恢复状态
    }

    /**
     * 检查XID文件的完整性和一致性
     *
     * 完整性检查原理：
     * 1. 从文件头读取XID计数器值
     * 2. 根据计数器计算理论文件大小
     * 3. 对比实际文件大小，确保一致
     *
     * 理论文件大小计算：
     * 文件大小 = 文件头长度 + (事务数量 × 每事务大小)
     *         = 8字节 + (xidCounter × 1字节)
     *
     * 如果不一致，说明文件损坏，数据库拒绝启动
     */
    private void checkXIDCounter() {
        long fileLen = 0;
        try {
            fileLen = file.length();
        } catch (IOException e1) {
            Panic.panic(Error.BadXIDFileException);
        }

        // 检查文件最小长度（至少包含文件头）
        if(fileLen < LEN_XID_HEADER_LENGTH) {
            Panic.panic(Error.BadXIDFileException);
        }

        // 读取XID计数器
        ByteBuffer buf = ByteBuffer.allocate(LEN_XID_HEADER_LENGTH);
        try {
            fc.position(0);  // 定位到文件开头
            fc.read(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        // 解析XID数量
        this.xidCounter = Parser.parseLong(buf.array());

        // 验证文件大小一致性
        long end = getXidPosition(this.xidCounter + 1);
        if(end != fileLen) {
            Panic.panic(Error.BadXIDFileException);
        }
    }

    /**
     * 根据事务XID计算其在文件中的存储位置（事务数据开头位置）
     *
     * @param xid 事务ID
     * @return 该事务状态在文件中的字节偏移量
     *
     * 位置计算公式：
     * position = 文件头长度 + (xid - 1) × 每事务大小
     *          = 8 + (xid - 1) × 1
     *
     * 注意：XID从1开始，所以需要减1
     * 例如：XID=1的事务存储在第8字节位置
     *      XID=2的事务存储在第9字节位置
     */
    private long getXidPosition(long xid) {
        return LEN_XID_HEADER_LENGTH + (xid-1)*XID_FIELD_SIZE;
    }

    /**
     * 更新指定事务的状态
     *
     * @param xid 事务ID
     * @param status 新的事务状态
     *
     * 更新过程：
     * 1. 计算事务在文件中的位置
     * 2. 将新状态写入对应位置
     * 3. 强制刷新到磁盘，确保持久化
     *
     * 强制刷新的重要性：
     * - 确保事务状态立即持久化
     * - 防止系统崩溃导致状态丢失
     * - 满足事务持久性(Durability)要求
     */
    private void updateXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        byte[] tmp = new byte[XID_FIELD_SIZE];
        tmp[0] = status;
        ByteBuffer buf = ByteBuffer.wrap(tmp);
        try {
            fc.position(offset);  // 定位到事务状态位置
            fc.write(buf);        // 写入/更新新状态
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            fc.force(false);      // 强制刷新到磁盘
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    /**
     * 递增XID计数器并更新文件头
     *
     * 在分配新事务ID后调用，执行以下操作：
     * 1. 将内存中的XID计数器加1
     * 2. 将新的计数器值写入文件头
     * 3. 强制刷新确保持久化
     *
     * 这确保了系统重启后能够正确恢复XID计数器
     */
    private void incrXIDCounter() {
        xidCounter ++;
        ByteBuffer buf = ByteBuffer.wrap(Parser.long2Byte(xidCounter));
        try {
            fc.position(0);       // 定位到文件头
            fc.write(buf);        // 写入新的计数器值
        } catch (IOException e) {
            Panic.panic(e);
        }
        try {
            fc.force(false);      // 强制刷新到磁盘
        } catch (IOException e) {
            Panic.panic(e);
        }
    }

    /**
     * 开始一个新事务
     *
     * @return 新事务的XID
     *
     * 新事务创建过程：
     * 1. 获取计数器锁（确保线程安全）
     * 2. 计算新事务ID（当前最大ID + 1）
     * 3. 为新事务分配存储空间并设置为ACTIVE状态
     * 4. 更新XID计数器并持久化
     * 5. 释放锁并返回新XID
     *
     * 并发安全性：
     * - 使用ReentrantLock保证多线程安全
     * - 原子性操作：要么完全成功，要么完全失败
     * - 避免重复分配相同的XID
     */
    public long begin() {
        counterLock.lock();
        try {
            long xid = xidCounter + 1;
            updateXID(xid, FIELD_TRAN_ACTIVE);  // 设置为活跃状态
            incrXIDCounter();                   // 更新计数器
            return xid;
        } finally {
            counterLock.unlock();
        }
    }

    /**
     * 提交指定的事务
     *
     * @param xid 要提交的事务ID
     *
     * 提交操作：
     * 1. 将事务状态从ACTIVE更改为COMMITTED
     * 2. 立即持久化到磁盘
     *
     * 注意：这里只更新事务状态，实际的数据提交
     * 由其他模块（如数据管理器）负责处理
     */
    public void commit(long xid) {
        updateXID(xid, FIELD_TRAN_COMMITTED);
    }

    /**
     * 回滚指定的事务
     *
     * @param xid 要回滚的事务ID
     *
     * 回滚操作：
     * 1. 将事务状态从ACTIVE更改为ABORTED
     * 2. 立即持久化到磁盘
     *
     * 注意：这里只更新事务状态，实际的数据回滚
     * 由版本管理器和数据管理器配合完成
     */
    public void abort(long xid) {
        updateXID(xid, FIELD_TRAN_ABORTED);
    }

    /**
     * 检查指定事务是否处于指定状态
     *
     * @param xid 事务ID
     * @param status 要检查的状态
     * @return true表示事务处于指定状态
     *
     * 状态检查过程：
     * 1. 计算事务在文件中的位置
     * 2. 读取该位置的状态字节
     * 3. 与期望状态进行比较
     *
     * 这是所有状态查询方法的基础实现
     */
    private boolean checkXID(long xid, byte status) {
        long offset = getXidPosition(xid);
        ByteBuffer buf = ByteBuffer.wrap(new byte[XID_FIELD_SIZE]);
        try {
            fc.position(offset);  // 定位到事务状态位置
            fc.read(buf);         // 读取状态字节
        } catch (IOException e) {
            Panic.panic(e);
        }
        return buf.array()[0] == status;
    }

    /**
     * 检查事务是否处于活跃状态
     *
     * @param xid 事务ID
     * @return true表示事务正在执行中
     *
     * 特殊处理：
     * - 超级事务(XID=0)永远不是活跃状态
     * - 其他事务检查文件中的实际状态
     *
     * 用途：
     * 1. 崩溃恢复时识别需要回滚的事务
     * 2. 并发控制中的事务有效性检查
     * 3. MVCC中的版本可见性判断
     */
    public boolean isActive(long xid) {
        if(xid == SUPER_XID) return false;  // 超级事务特殊处理
        return checkXID(xid, FIELD_TRAN_ACTIVE);
    }

    /**
     * 检查事务是否已提交
     *
     * @param xid 事务ID
     * @return true表示事务已成功提交
     *
     * 特殊处理：
     * - 超级事务(XID=0)永远处于已提交状态
     * - 其他事务检查文件中的实际状态
     *
     * 用途：
     * 1. MVCC中判断版本是否对当前事务可见
     * 2. 崩溃恢复时的重做操作判断
     * 3. 事务状态查询和监控
     */
    public boolean isCommitted(long xid) {
        if(xid == SUPER_XID) return true;   // 超级事务特殊处理
        return checkXID(xid, FIELD_TRAN_COMMITTED);
    }

    /**
     * 检查事务是否已回滚
     *
     * @param xid 事务ID
     * @return true表示事务已被回滚
     *
     * 特殊处理：
     * - 超级事务(XID=0)永远不会被回滚
     * - 其他事务检查文件中的实际状态
     *
     * 用途：
     * 1. MVCC中判断版本是否应该被忽略
     * 2. 崩溃恢复时的撤销操作判断
     * 3. 事务状态查询和调试
     */
    public boolean isAborted(long xid) {
        if(xid == SUPER_XID) return false;  // 超级事务特殊处理
        return checkXID(xid, FIELD_TRAN_ABORTED);
    }

    /**
     * 关闭事务管理器
     *
     * 清理操作：
     * 1. 关闭文件通道
     * 2. 关闭随机访问文件
     * 3. 释放系统资源
     *
     * 注意：关闭前所有待处理的写操作都已完成，
     * 因为每次状态更新都会立即强制刷新到磁盘
     */
    public void close() {
        try {
            fc.close();
            file.close();
        } catch (IOException e) {
            Panic.panic(e);
        }
    }
}
