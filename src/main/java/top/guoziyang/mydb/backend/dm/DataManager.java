package top.guoziyang.mydb.backend.dm;

import top.guoziyang.mydb.backend.dm.dataItem.DataItem;
import top.guoziyang.mydb.backend.dm.logger.Logger;
import top.guoziyang.mydb.backend.dm.page.PageOne;
import top.guoziyang.mydb.backend.dm.pageCache.PageCache;
import top.guoziyang.mydb.backend.tm.TransactionManager;

/**
 * DataManager接口 - 数据管理器
 * 
 * DataManager是数据管理模块的核心门面，协调和管理所有底层存储组件，
 * 为上层模块提供统一的数据访问接口。
 * 
 * 与MySQL InnoDB存储引擎的对比：
 * - MySQL InnoDB是完整的存储引擎，包含buffer pool、redo log、undo log等
 * - MYDB DataManager简化了这些概念，但保留了核心功能
 * - 都负责数据的持久化存储、缓存管理和崩溃恢复
 * 
 * 主要职责：
 * 1. 数据存储：管理数据在磁盘上的组织和存储
 * 2. 缓存管理：协调页面缓存，提高访问性能
 * 3. 事务支持：配合事务管理器实现ACID特性
 * 4. 故障恢复：通过WAL日志实现崩溃恢复
 * 5. 空间管理：管理页面的分配和空闲空间
 * 
 * 架构层次：
 * DataManager
 * ├── PageCache（页面缓存管理）
 * ├── Logger（WAL日志管理）
 * ├── PageIndex（页面空间索引）
 * └── TransactionManager（事务管理协调）
 */
public interface DataManager {
    /**
     * 根据UID读取数据项
     * 
     * @param uid 数据项的唯一标识符（由页面号和偏移量组成）
     * @return 数据项对象，如果不存在返回null
     * @throws Exception 如果读取过程中发生I/O错误或数据损坏
     * 
     * 读取流程：
     * 1. 解析UID获取页面号和偏移量
     * 2. 通过PageCache获取对应页面
     * 3. 在页面中定位并解析DataItem
     * 4. 返回包装好的DataItem对象
     * 
     * 这类似于MySQL InnoDB中通过主键或rowid读取记录的过程。
     */
    DataItem read(long uid) throws Exception;

    /**
     * 插入新的数据项
     * 
     * @param xid 事务ID，用于事务管理和日志记录
     * @param data 要插入的原始数据
     * @return 新插入数据项的UID
     * @throws Exception 如果插入失败（空间不足、I/O错误等）
     * 
     * 插入流程：
     * 1. 根据数据大小查找合适的页面（通过PageIndex）
     * 2. 将数据包装成DataItem格式
     * 3. 记录插入操作到WAL日志
     * 4. 在页面中写入数据
     * 5. 更新页面空间索引
     * 6. 返回新数据项的UID
     * 
     * WAL原则：先写日志，再修改数据页，确保事务的持久性。
     */
    long insert(long xid, byte[] data) throws Exception;

    /**
     * 关闭数据管理器
     * 
     * 执行清理和收尾工作：
     * 1. 将所有脏页刷新到磁盘
     * 2. 关闭日志文件
     * 3. 设置数据库正常关闭标记（PageOne ValidCheck）
     * 4. 释放所有资源
     * 
     * 这类似于MySQL正常关闭时的checkpoint操作。
     */
    void close();

    /**
     * 创建新的数据管理器实例
     * 
     * @param path 数据库文件路径前缀
     * @param mem 页面缓存大小（字节）
     * @param tm 事务管理器实例
     * @return 新的DataManager实例
     * 
     * 创建新数据库时调用，执行初始化操作：
     * 1. 创建页面缓存和日志文件
     * 2. 初始化第一页（PageOne）
     * 3. 设置初始的ValidCheck标记
     * 4. 建立各组件间的协调关系
     */
    public static DataManager create(String path, long mem, TransactionManager tm) {
        PageCache pc = PageCache.create(path, mem);
        Logger lg = Logger.create(path);

        DataManagerImpl dm = new DataManagerImpl(pc, lg, tm);
        dm.initPageOne();  // 初始化第一页
        return dm;
    }

    /**
     * 打开已存在的数据管理器
     * 
     * @param path 数据库文件路径前缀
     * @param mem 页面缓存大小（字节）
     * @param tm 事务管理器实例
     * @return 已存在的DataManager实例
     * 
     * 打开已存在数据库时调用，执行启动检查和恢复：
     * 1. 打开页面缓存和日志文件
     * 2. 检查第一页的ValidCheck标记
     * 3. 如果检测到异常关闭，启动崩溃恢复流程
     * 4. 重建页面空间索引
     * 5. 设置数据库为正常运行状态
     * 
     * 崩溃恢复流程：
     * - 如果ValidCheck检查失败，调用Recover.recover()
     * - 通过WAL日志重做已提交事务，撤销未提交事务
     * - 确保数据库恢复到一致状态
     */
    public static DataManager open(String path, long mem, TransactionManager tm) {
        PageCache pc = PageCache.open(path, mem);
        Logger lg = Logger.open(path);
        DataManagerImpl dm = new DataManagerImpl(pc, lg, tm);
        
        // 检查数据库是否正常关闭
        if (!dm.loadCheckPageOne()) {
            // 如果异常关闭，启动恢复流程
            Recover.recover(tm, lg, pc);
        }
        
        // 重建页面索引
        dm.fillPageIndex();
        
        // 设置数据库为运行状态
        PageOne.setVcOpen(dm.pageOne);
        dm.pc.flushPage(dm.pageOne);

        return dm;
    }
}
