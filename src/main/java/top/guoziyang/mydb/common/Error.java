package top.guoziyang.mydb.common;

/**
 * Error - 错误定义和异常管理类
 * 
 * 这个类集中定义了MYDB系统中所有可能出现的错误和异常。
 * 通过预定义异常对象，系统可以实现统一的错误处理机制，
 * 便于错误分类、日志记录和问题诊断。
 * 
 * 与MySQL的对比：
 * - MySQL有完整的错误码体系，如1062（主键冲突）、1146（表不存在）等
 * - MySQL错误分为多个类别：连接错误、SQL错误、存储引擎错误等
 * - InnoDB有自己的错误处理机制，包括死锁检测、约束检查等
 * - MySQL通过SHOW ERRORS和错误日志提供详细的错误信息
 * - MYDB简化了这一体系，使用Java异常机制统一处理
 * 
 * 设计原则：
 * 1. 预定义异常：避免在代码中直接创建异常对象
 * 2. 分类管理：按模块组织异常定义
 * 3. 描述清晰：异常信息要能准确描述问题
 * 4. 便于定位：异常名称要能快速定位到问题模块
 * 
 * 异常分类说明：
 * - common: 通用异常（缓存、文件操作等）
 * - dm: 数据管理模块异常
 * - tm: 事务管理模块异常  
 * - vm: 版本管理模块异常
 * - tbm: 表管理模块异常
 * - parser: 解析器模块异常
 * - transport: 传输层异常
 * - server: 服务器异常
 * - launcher: 启动器异常
 * 
 * 使用建议：
 * - 不要直接修改这些异常对象
 * - 抛出异常时使用预定义的对象
 * - 可以通过异常类型进行精确的catch处理
 */
public class Error {
    
    // ========== 通用异常 (Common Errors) ==========
    
    /**
     * 缓存已满异常
     * 
     * 当系统缓存达到最大容量限制时抛出此异常。
     * 
     * 与MySQL的对比：
     * - 类似MySQL的"table cache is full"错误
     * - InnoDB缓冲池满时的处理机制
     * 
     * 触发场景：
     * - AbstractCache达到maxResource限制
     * - 页面缓存无法分配新的缓存项
     */
    public static final Exception CacheFullException = new RuntimeException("Cache is full!");
    
    /**
     * 文件已存在异常
     * 
     * 当尝试创建已存在的文件时抛出此异常。
     * 
     * 触发场景：
     * - 创建数据库文件时文件已存在
     * - 创建日志文件时发生冲突
     */
    public static final Exception FileExistsException = new RuntimeException("File already exists!");
    
    /**
     * 文件不存在异常
     * 
     * 当尝试访问不存在的文件时抛出此异常。
     * 
     * 触发场景：
     * - 打开数据库文件失败
     * - 读取日志文件时文件缺失
     */
    public static final Exception FileNotExistsException = new RuntimeException("File does not exists!");
    
    /**
     * 文件读写权限异常
     * 
     * 当文件无法正常读取或写入时抛出此异常。
     * 
     * 触发场景：
     * - 文件权限不足
     * - 磁盘空间不足
     * - 文件被其他进程锁定
     */
    public static final Exception FileCannotRWException = new RuntimeException("File cannot read or write!");

    // ========== 数据管理模块异常 (Data Manager Errors) ==========
    
    /**
     * 日志文件损坏异常
     * 
     * 当日志文件格式不正确或内容损坏时抛出此异常。
     * 
     * 与MySQL的对比：
     * - 类似InnoDB的redo log corruption
     * - MySQL会尝试修复或回滚到最后一个有效点
     */
    public static final Exception BadLogFileException = new RuntimeException("Bad log file!");
    
    /**
     * 内存不足异常
     * 
     * 当可用内存小于系统要求时抛出此异常。
     */
    public static final Exception MemTooSmallException = new RuntimeException("Memory too small!");
    
    /**
     * 数据过大异常
     * 
     * 当单条数据超过系统限制时抛出此异常。
     * 
     * 与MySQL的对比：
     * - 类似MySQL的"row size too large"错误
     * - InnoDB页面大小限制导致的错误
     */
    public static final Exception DataTooLargeException = new RuntimeException("Data too large!");
    
    /**
     * 数据库繁忙异常
     * 
     * 当数据库正在执行其他操作无法响应请求时抛出此异常。
     * 
     * 与MySQL的对比：
     * - 类似MySQL的"database is locked"错误
     * - 表级锁定或元数据锁定场景
     */
    public static final Exception DatabaseBusyException = new RuntimeException("Database is busy!");

    // ========== 事务管理模块异常 (Transaction Manager Errors) ==========
    
    /**
     * XID文件损坏异常
     * 
     * 当事务ID文件格式不正确时抛出此异常。
     * XID文件用于记录事务状态，对事务恢复至关重要。
     */
    public static final Exception BadXIDFileException = new RuntimeException("Bad XID file!");

    // ========== 版本管理模块异常 (Version Manager Errors) ==========
    
    /**
     * 死锁异常
     * 
     * 当检测到事务间死锁时抛出此异常。
     * 
     * 与MySQL的对比：
     * - MySQL也有类似的死锁检测机制
     * - InnoDB会自动选择代价最小的事务进行回滚
     */
    public static final Exception DeadlockException = new RuntimeException("Deadlock!");
    
    /**
     * 并发更新异常
     * 
     * 当发生并发更新冲突时抛出此异常。
     * 
     * 与MySQL的对比：
     * - 类似MySQL的"concurrent update"错误
     * - MVCC机制中的写冲突检测
     */
    public static final Exception ConcurrentUpdateException = new RuntimeException("Concurrent update issue!");
    
    /**
     * 空记录异常
     * 
     * 当尝试访问不存在的记录时抛出此异常。
     */
    public static final Exception NullEntryException = new RuntimeException("Null entry!");

    // ========== 表管理模块异常 (Table Manager Errors) ==========
    
    /**
     * 无效字段类型异常
     * 
     * 当字段类型不被系统支持时抛出此异常。
     */
    public static final Exception InvalidFieldException = new RuntimeException("Invalid field type!");
    
    /**
     * 字段不存在异常
     * 
     * 当查询的字段在表结构中不存在时抛出此异常。
     */
    public static final Exception FieldNotFoundException = new RuntimeException("Field not found!");
    
    /**
     * 字段未建索引异常
     * 
     * 当操作需要索引但字段未建索引时抛出此异常。
     */
    public static final Exception FieldNotIndexedException = new RuntimeException("Field not indexed!");
    
    /**
     * 无效逻辑操作异常
     * 
     * 当SQL中的逻辑操作不被支持时抛出此异常。
     */
    public static final Exception InvalidLogOpException = new RuntimeException("Invalid logic operation!");
    
    /**
     * 无效值异常
     * 
     * 当插入或更新的值不符合字段要求时抛出此异常。
     */
    public static final Exception InvalidValuesException = new RuntimeException("Invalid values!");
    
    /**
     * 重复表名异常
     * 
     * 当尝试创建已存在的表时抛出此异常。
     */
    public static final Exception DuplicatedTableException = new RuntimeException("Duplicated table!");
    
    /**
     * 表不存在异常
     * 
     * 当操作的表在数据库中不存在时抛出此异常。
     */
    public static final Exception TableNotFoundException = new RuntimeException("Table not found!");

    // ========== 解析器模块异常 (Parser Errors) ==========
    
    /**
     * 无效命令异常
     * 
     * 当SQL命令格式不正确时抛出此异常。
     */
    public static final Exception InvalidCommandException = new RuntimeException("Invalid command!");
    
    /**
     * 表无索引异常
     * 
     * 当查询的表没有任何索引时抛出此异常。
     */
    public static final Exception TableNoIndexException = new RuntimeException("Table has no index!");

    // ========== 传输层异常 (Transport Errors) ==========
    
    /**
     * 无效数据包异常
     * 
     * 当网络数据包格式不正确时抛出此异常。
     */
    public static final Exception InvalidPkgDataException = new RuntimeException("Invalid package data!");

    // ========== 服务器异常 (Server Errors) ==========
    
    /**
     * 嵌套事务异常
     * 
     * 当尝试在事务内部开启新事务时抛出此异常。
     * MYDB不支持嵌套事务。
     */
    public static final Exception NestedTransactionException = new RuntimeException("Nested transaction not supported!");
    
    /**
     * 非事务状态异常
     * 
     * 当在非事务状态下执行需要事务的操作时抛出此异常。
     */
    public static final Exception NoTransactionException = new RuntimeException("Not in transaction!");

    // ========== 启动器异常 (Launcher Errors) ==========
    
    /**
     * 无效内存配置异常
     * 
     * 当内存配置参数不合法时抛出此异常。
     */
    public static final Exception InvalidMemException = new RuntimeException("Invalid memory!");
}
