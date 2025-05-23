package top.guoziyang.mydb.backend.dm.pageCache;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

import top.guoziyang.mydb.backend.dm.page.Page;
import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.common.Error;

/**
 * PageCache接口 - 页面缓存管理
 * 
 * PageCache是数据库存储引擎的核心组件，负责管理内存中的页面缓存。
 * 
 * 与MySQL InnoDB Buffer Pool的对比：
 * - MySQL Buffer Pool默认128MB，支持多个实例
 * - 都使用LRU算法管理页面替换
 * - 都支持脏页检查点机制
 * - MySQL有更复杂的预读和刷新策略
 * 
 * 核心功能：
 * 1. 页面缓存：将磁盘页面缓存到内存中，减少磁盘I/O
 * 2. 引用计数：管理页面的引用状态，防止正在使用的页面被替换
 * 3. 脏页管理：跟踪被修改的页面，定期刷新到磁盘
 * 4. 并发控制：支持多线程安全的页面访问
 * 
 * 缓存策略：
 * - 基于引用计数的缓存替换
 * - 当缓存满时，替换引用计数为0的页面
 * - 类似于MySQL Buffer Pool的LRU链表管理
 */
public interface PageCache {
    
    /**
     * 页面大小常量 - 8KB (1 << 13)
     * 
     * MYDB使用8KB页面，而MySQL InnoDB默认使用16KB页面。
     * 较小的页面size减少内存占用，但可能增加I/O次数。
     */
    public static final int PAGE_SIZE = 1 << 13;

    /**
     * 创建新页面
     * 
     * @param initData 页面的初始化数据
     * @return 新页面的页面号
     * 
     * 在数据库文件末尾分配新页面，类似于MySQL中扩展表空间。
     * 新页面会被添加到缓存中，避免立即的磁盘I/O。
     */
    int newPage(byte[] initData);
    
    /**
     * 获取指定页面
     * 
     * @param pgno 页面编号
     * @return 页面对象
     * @throws Exception 如果页面不存在或I/O错误
     * 
     * 缓存查找流程：
     * 1. 检查页面是否在缓存中
     * 2. 如果在缓存中，增加引用计数并返回
     * 3. 如果不在缓存中，从磁盘读取并加入缓存
     * 4. 如果缓存已满，根据引用计数选择页面进行替换
     * 
     * 这个过程类似于MySQL Buffer Pool的页面查找机制。
     */
    Page getPage(int pgno) throws Exception;
    
    /**
     * 关闭页面缓存
     * 
     * 执行清理操作：
     * 1. 将所有脏页刷新到磁盘
     * 2. 关闭文件句柄
     * 3. 清理缓存内容
     * 
     * 类似于MySQL关闭时的checkpoint操作。
     */
    void close();
    
    /**
     * 释放页面引用
     * 
     * @param page 要释放的页面
     * 
     * 减少页面的引用计数，当引用计数为0时，
     * 页面可以被缓存替换算法选中进行替换。
     * 
     * 这类似于MySQL Buffer Pool中页面的unpin操作。
     */
    void release(Page page);

    /**
     * 截断数据库文件
     * 
     * @param maxPgno 保留的最大页面号
     * 
     * 删除指定页面号之后的所有页面，用于数据库恢复过程。
     */
    void truncateByBgno(int maxPgno);
    
    /**
     * 获取数据库文件的总页面数
     * 
     * @return 页面总数
     */
    int getPageNumber();
    
    /**
     * 强制刷新页面到磁盘
     * 
     * @param pg 要刷新的页面
     * 
     * 立即将指定页面写入磁盘，不等待正常的检查点。
     * 用于关键操作（如事务提交）后确保数据持久化。
     */
    void flushPage(Page pg);

    /**
     * 创建新的页面缓存实例
     * 
     * @param path 数据库文件路径
     * @param memory 缓存大小（字节）
     * @return 页面缓存实例
     * 
     * 创建新数据库时调用，初始化空的页面缓存和数据库文件。
     */
    public static PageCacheImpl create(String path, long memory) {
        File f = new File(path+PageCacheImpl.DB_SUFFIX);
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
        return new PageCacheImpl(raf, fc, (int)memory/PAGE_SIZE);
    }

    /**
     * 打开已存在的页面缓存
     * 
     * @param path 数据库文件路径
     * @param memory 缓存大小（字节）
     * @return 页面缓存实例
     * 
     * 打开已存在的数据库时调用，连接到已有的数据库文件。
     */
    public static PageCacheImpl open(String path, long memory) {
        File f = new File(path+PageCacheImpl.DB_SUFFIX);
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
        return new PageCacheImpl(raf, fc, (int)memory/PAGE_SIZE);
    }
}
