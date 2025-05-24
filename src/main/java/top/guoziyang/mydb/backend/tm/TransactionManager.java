package top.guoziyang.mydb.backend.tm;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.common.Error;

/**
 * TransactionManager接口 - 事务管理器
 * 
 * TransactionManager是数据库事务管理的核心接口，负责事务的生命周期管理。
 * 事务是数据库系统的基本工作单位，保证数据的ACID特性。
 * 
 * 与MySQL InnoDB事务管理的对比：
 * - MySQL InnoDB: 复杂的事务系统，支持多级事务隔离、行级锁、MVCC等
 * - MYDB TransactionManager: 简化的事务管理，专注于核心的事务状态管理
 * - 都实现了事务的基本生命周期：begin -> active -> commit/abort
 * 
 * 事务管理核心概念：
 * 1. 事务标识符(XID): 每个事务的唯一标识，类似MySQL的事务ID
 * 2. 事务状态: ACTIVE(活跃)、COMMITTED(已提交)、ABORTED(已回滚)
 * 3. 事务持久化: 将事务状态持久化到磁盘，确保重启后状态不丢失
 * 4. 超级事务: XID=0的特殊事务，永远处于已提交状态
 * 
 * 文件存储格式：
 * [XID Header: 8字节] + [XID1状态: 1字节] + [XID2状态: 1字节] + ...
 * - XID Header: 存储当前最大的事务ID
 * - 每个事务状态用1字节表示：0=ACTIVE, 1=COMMITTED, 2=ABORTED
 * 
 * 设计优势：
 * 1. 简单高效：最小化存储开销，每个事务只用1字节
 * 2. 快速查询：通过XID直接计算文件偏移量，O(1)时间复杂度
 * 3. 持久化保证：所有状态变更立即写入磁盘并强制刷新
 * 4. 崩溃恢复：重启时可以准确恢复所有事务的状态
 */
public interface TransactionManager {
    
    /**
     * 开始一个新事务
     * 
     * @return 新事务的XID（事务标识符）
     * 
     * 开始事务的过程：
     * 1. 生成新的XID（当前最大XID + 1）
     * 2. 在XID文件中为新事务分配空间
     * 3. 将事务状态设置为ACTIVE（活跃状态）
     * 4. 更新XID计数器并持久化到磁盘
     * 5. 返回新事务的XID供后续操作使用
     * 
     * 类似于MySQL的START TRANSACTION或BEGIN语句
     */
    long begin();
    
    /**
     * 提交指定事务
     * 
     * @param xid 要提交的事务ID
     * 
     * 提交事务的过程：
     * 1. 将指定XID的事务状态更改为COMMITTED
     * 2. 立即写入磁盘并强制刷新，确保持久化
     * 3. 提交后事务的所有修改变为永久有效
     * 
     * 注意：MYDB的提交是简化版本，只管理事务状态
     * 实际的数据持久化由数据管理模块(dm)负责
     * 
     * 类似于MySQL的COMMIT语句
     */
    void commit(long xid);
    
    /**
     * 回滚指定事务
     * 
     * @param xid 要回滚的事务ID
     * 
     * 回滚事务的过程：
     * 1. 将指定XID的事务状态更改为ABORTED
     * 2. 立即写入磁盘并强制刷新，确保持久化
     * 3. 回滚后事务的所有修改都被撤销
     * 
     * 注意：MYDB的回滚只管理状态，实际的数据回滚
     * 由版本管理模块(vm)和数据管理模块(dm)配合完成
     * 
     * 类似于MySQL的ROLLBACK语句
     */
    void abort(long xid);
    
    /**
     * 检查事务是否处于活跃状态
     * 
     * @param xid 事务ID
     * @return true表示事务正在执行中，false表示已结束
     * 
     * 活跃状态的含义：
     * - 事务已经开始但尚未提交或回滚
     * - 可以继续执行读写操作
     * - 持有的锁和资源尚未释放
     * 
     * 用于：
     * 1. 崩溃恢复时判断哪些事务需要回滚
     * 2. 并发控制中检查事务有效性
     * 3. 版本可见性判断
     */
    boolean isActive(long xid);
    
    /**
     * 检查事务是否已提交
     * 
     * @param xid 事务ID
     * @return true表示事务已成功提交
     * 
     * 已提交状态的含义：
     * - 事务的所有修改已经永久生效
     * - 对其他事务可见（取决于隔离级别）
     * - 所有资源已释放
     * 
     * 用于：
     * 1. 版本可见性判断（MVCC）
     * 2. 崩溃恢复时的重做操作
     * 3. 事务状态查询
     */
    boolean isCommitted(long xid);
    
    /**
     * 检查事务是否已回滚
     * 
     * @param xid 事务ID
     * @return true表示事务已被回滚
     * 
     * 已回滚状态的含义：
     * - 事务的所有修改都被撤销
     * - 数据恢复到事务开始前的状态
     * - 所有资源已释放
     * 
     * 用于：
     * 1. 版本可见性判断
     * 2. 崩溃恢复时的撤销操作
     * 3. 事务状态查询
     */
    boolean isAborted(long xid);
    
    /**
     * 关闭事务管理器
     * 
     * 执行清理操作：
     * 1. 刷新所有缓冲的数据到磁盘
     * 2. 关闭XID文件句柄
     * 3. 释放相关系统资源
     * 
     * 通常在数据库关闭时调用
     */
    void close();

    /**
     * 创建新的事务管理器实例
     * 
     * @param path 事务文件的路径前缀
     * @return TransactionManagerImpl实例
     * 
     * 创建新数据库时调用，执行初始化操作：
     * 1. 创建新的XID文件
     * 2. 写入初始的文件头（XID计数器=0）
     * 3. 建立文件通道用于后续操作
     * 4. 返回配置好的TransactionManager实例
     * 
     * XID文件格式：
     * [0-7字节]: XID计数器，记录当前最大事务ID
     * [8字节开始]: 每个事务的状态，每个事务占用1字节
     */
    public static TransactionManagerImpl create(String path) {
        File f = new File(path+TransactionManagerImpl.XID_SUFFIX);
        try {
            if(!f.createNewFile()) {
                Panic.panic(Error.FileExistsException);
            }
        } catch (Exception e) {
            Panic.panic(e);
        }
        if(!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }

        FileChannel fc = null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            fc = raf.getChannel();
        } catch (FileNotFoundException e) {
           Panic.panic(e);
        }

        // 写入初始的XID文件头，计数器初始值为0
        ByteBuffer buf = ByteBuffer.wrap(new byte[TransactionManagerImpl.LEN_XID_HEADER_LENGTH]);
        try {
            fc.position(0);
            fc.write(buf);
        } catch (IOException e) {
            Panic.panic(e);
        }
        
        return new TransactionManagerImpl(raf, fc);
    }

    /**
     * 打开已存在的事务管理器
     * 
     * @param path 事务文件的路径前缀
     * @return TransactionManagerImpl实例
     * 
     * 打开已存在数据库时调用：
     * 1. 打开已有的XID文件
     * 2. 验证文件完整性和格式正确性
     * 3. 读取XID计数器，恢复事务管理器状态
     * 4. 返回可用的TransactionManager实例
     * 
     * 完整性检查：
     * - 验证文件大小与XID计数器的一致性
     * - 确保文件格式正确，避免数据损坏
     * - 如果检查失败，数据库拒绝启动
     */
    public static TransactionManagerImpl open(String path) {
        File f = new File(path+TransactionManagerImpl.XID_SUFFIX);
        if(!f.exists()) {
            Panic.panic(Error.FileNotExistsException);
        }
        if(!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }

        FileChannel fc = null;
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(f, "rw");
            fc = raf.getChannel();
        } catch (FileNotFoundException e) {
           Panic.panic(e);
        }

        return new TransactionManagerImpl(raf, fc);
    }
}
