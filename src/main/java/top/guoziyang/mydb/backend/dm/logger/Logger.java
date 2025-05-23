package top.guoziyang.mydb.backend.dm.logger;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.common.Error;

/**
 * Logger接口 - Write-Ahead Logging（WAL）日志管理
 * 
 * Logger是数据库系统中最重要的组件之一，实现Write-Ahead Logging机制，
 * 确保数据的持久性和一致性。
 * 
 * 与MySQL redo log的对比：
 * - MySQL InnoDB使用循环的redo log文件，支持高并发写入
 * - MYDB实现了简化的WAL，易于理解核心原理
 * - 都遵循WAL的基本原则：日志先于数据写入磁盘
 * 
 * WAL原理：
 * 1. 在修改数据页之前，必须先写日志记录到磁盘
 * 2. 事务提交前，相关的日志记录必须已经持久化
 * 3. 这样即使系统崩溃，也能通过日志恢复未写入磁盘的数据
 * 
 * 日志格式：
 * [日志头(4字节)] + [日志记录1] + [日志记录2] + ...
 * 每个日志记录：[长度(4字节)] + [数据(变长)]
 */
public interface Logger {
    /**
     * 写入日志记录
     * 
     * @param data 要记录的日志数据
     * 
     * 这是WAL机制的核心操作：
     * 1. 将日志数据追加到日志文件末尾
     * 2. 强制刷新到磁盘（fsync）确保持久化
     * 3. 更新日志文件头，记录当前日志位置
     * 
     * 类似于MySQL中写入redo log的过程，但MYDB的实现更直接简单。
     */
    void log(byte[] data);
    
    /**
     * 截断日志文件
     * 
     * @param x 截断位置
     * @throws Exception 如果截断操作失败
     * 
     * 在数据库恢复过程中使用，删除指定位置之后的所有日志记录。
     * 这通常发生在发现损坏的日志记录时，确保日志文件的完整性。
     */
    void truncate(long x) throws Exception;
    
    /**
     * 读取下一条日志记录
     * 
     * @return 日志记录的数据，如果到达文件末尾返回null
     * 
     * 用于数据库恢复过程中顺序读取日志记录：
     * 1. 读取记录长度（4字节）
     * 2. 根据长度读取实际的日志数据
     * 3. 返回完整的日志记录供恢复逻辑处理
     * 
     * 这类似于MySQL恢复时解析redo log的过程。
     */
    byte[] next();
    
    /**
     * 重置日志读取位置到文件开头
     * 
     * 用于重新开始读取日志文件，通常在恢复流程开始时调用。
     * 确保从日志文件的第一条记录开始顺序读取。
     */
    void rewind();
    
    /**
     * 关闭日志文件
     * 
     * 执行清理操作：
     * 1. 刷新所有未写入的数据到磁盘
     * 2. 关闭文件句柄
     * 3. 释放相关资源
     */
    void close();

    /**
     * 创建新的日志文件
     * 
     * @param path 日志文件路径前缀
     * @return Logger实例
     * 
     * 创建新数据库时调用，初始化空的日志文件：
     * 1. 创建新的日志文件
     * 2. 写入初始的文件头（日志长度为0）
     * 3. 强制刷新到磁盘确保文件创建成功
     */
    public static Logger create(String path) {
        File f = new File(path+LoggerImpl.LOG_SUFFIX);
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

        // 写入初始日志头：日志长度为0
        ByteBuffer buf = ByteBuffer.wrap(Parser.int2Byte(0));
        try {
            fc.position(0);
            fc.write(buf);
            fc.force(false);  // 强制刷新到磁盘
        } catch (IOException e) {
            Panic.panic(e);
        }

        return new LoggerImpl(raf, fc, 0);
    }

    /**
     * 打开已存在的日志文件
     * 
     * @param path 日志文件路径前缀
     * @return Logger实例
     * 
     * 打开已存在的数据库时调用：
     * 1. 打开已有的日志文件
     * 2. 读取并验证日志文件头
     * 3. 初始化读取位置，准备进行恢复或正常操作
     * 
     * 如果日志文件损坏或不完整，会触发错误处理流程。
     */
    public static Logger open(String path) {
        File f = new File(path+LoggerImpl.LOG_SUFFIX);
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

        LoggerImpl lg = new LoggerImpl(raf, fc);
        lg.init();  // 初始化日志读取状态

        return lg;
    }
}
