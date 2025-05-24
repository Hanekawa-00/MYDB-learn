package top.guoziyang.mydb.backend.tbm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.backend.dm.DataManager;
import top.guoziyang.mydb.backend.parser.statement.Begin;
import top.guoziyang.mydb.backend.parser.statement.Create;
import top.guoziyang.mydb.backend.parser.statement.Delete;
import top.guoziyang.mydb.backend.parser.statement.Insert;
import top.guoziyang.mydb.backend.parser.statement.Select;
import top.guoziyang.mydb.backend.parser.statement.Update;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.backend.vm.VersionManager;
import top.guoziyang.mydb.common.Error;

/**
 * TableManagerImpl类 - 表管理器的具体实现
 * 
 * TableManagerImpl是TableManager接口的具体实现，负责管理数据库中的所有表和表操作。
 * 它协调了底层的数据管理、版本管理和上层的SQL操作，是MYDB架构中的关键组件。
 * 
 * 与MySQL的对比：
 * - MySQL中类似功能分布在Server Layer、Storage Engine Layer等多个层次
 * - MYDB将这些功能集中在TableManagerImpl中，简化了架构
 * 
 * 核心功能：
 * 1. 表缓存管理：维护表名到表对象的映射
 * 2. 表链表管理：通过Booter维护表的链表结构
 * 3. 事务隔离：处理不同事务创建的表的可见性
 * 4. 并发控制：通过锁保证表操作的线程安全
 * 5. SQL操作路由：将SQL操作转发给具体的表处理
 */
public class TableManagerImpl implements TableManager {
    
    /** 版本管理器，用于事务管理和MVCC */
    VersionManager vm;
    
    /** 数据管理器，用于底层数据存储 */
    DataManager dm;
    
    /** 启动器，管理第一个表的UID */
    private Booter booter;
    
    /** 
     * 表缓存：表名 -> 表对象
     * 缓存所有已提交的表，提高查找性能
     * 对应MySQL中的Table Cache
     */
    private Map<String, Table> tableCache;
    
    /** 
     * 事务表缓存：事务ID -> 该事务创建的表列表
     * 用于管理事务创建但尚未提交的表的可见性
     * 实现事务级别的表隔离
     */
    private Map<Long, List<Table>> xidTableCache;
    
    /** 
     * 读写锁，保护表缓存的并发访问
     * 在表的创建、查找等操作时需要加锁
     */
    private Lock lock;
    
    /**
     * 构造函数
     * 
     * @param vm 版本管理器
     * @param dm 数据管理器  
     * @param booter 启动器
     * 
     * 初始化过程：
     * 1. 保存各个管理器的引用
     * 2. 初始化表缓存和事务表缓存
     * 3. 创建读写锁
     * 4. 加载所有已存在的表到缓存中
     */
    TableManagerImpl(VersionManager vm, DataManager dm, Booter booter) {
        this.vm = vm;
        this.dm = dm;
        this.booter = booter;
        this.tableCache = new HashMap<>();
        this.xidTableCache = new HashMap<>();
        lock = new ReentrantLock();
        loadTables(); // 启动时加载所有表
    }

    /**
     * 加载所有已存在的表到缓存中
     * 
     * 加载过程：
     * 1. 从Booter获取第一个表的UID
     * 2. 沿着表链表逐一加载每个表
     * 3. 将表加入到缓存中
     * 
     * 表链表结构：Table1 -> Table2 -> Table3 -> ... -> 0
     * 每个表的nextUid指向下一个表，0表示链表结束
     */
    private void loadTables() {
        long uid = firstTableUid();
        while(uid != 0) {
            Table tb = Table.loadTable(this, uid);
            uid = tb.nextUid; // 移动到下一个表
            tableCache.put(tb.name, tb);
        }
    }

    /**
     * 获取第一个表的UID
     * 
     * @return 第一个表的UID，0表示没有表
     * 
     * 从Booter文件中读取8字节的long值，
     * 这个值指向表链表的头部
     */
    private long firstTableUid() {
        byte[] raw = booter.load();
        return Parser.parseLong(raw);
    }

    /**
     * 更新第一个表的UID
     * 
     * @param uid 新的第一个表的UID
     * 
     * 当创建新表时，新表会成为链表的头部，
     * 需要更新Booter中记录的第一个表UID
     */
    private void updateFirstTableUid(long uid) {
        byte[] raw = Parser.long2Byte(uid);
        booter.update(raw);
    }

    /**
     * 开始事务
     * 
     * @param begin BEGIN语句解析结果
     * @return BeginRes对象，包含事务ID和结果信息
     * 
     * 实现过程：
     * 1. 根据语句确定隔离级别
     * 2. 通过版本管理器创建新事务
     * 3. 返回事务信息给客户端
     */
    @Override
    public BeginRes begin(Begin begin) {
        BeginRes res = new BeginRes();
        // 确定隔离级别：1=REPEATABLE_READ, 0=READ_COMMITTED
        int level = begin.isRepeatableRead?1:0;
        res.xid = vm.begin(level);
        res.result = "begin".getBytes();
        return res;
    }
    
    /**
     * 提交事务
     * 
     * @param xid 事务ID
     * @return 操作结果
     * @throws Exception 提交失败
     * 
     * 提交过程：
     * 1. 通过版本管理器提交事务
     * 2. 清理事务相关的临时数据
     * 3. 返回成功信息
     */
    @Override
    public byte[] commit(long xid) throws Exception {
        vm.commit(xid);
        return "commit".getBytes();
    }
    
    /**
     * 回滚事务
     * 
     * @param xid 事务ID
     * @return 操作结果
     * 
     * 回滚过程：
     * 1. 通过版本管理器回滚事务
     * 2. 清理事务相关的临时数据
     * 3. 返回回滚信息
     */
    @Override
    public byte[] abort(long xid) {
        vm.abort(xid);
        return "abort".getBytes();
    }
    
    /**
     * 显示所有表
     * 
     * @param xid 事务ID
     * @return 表信息的字节数组
     * 
     * 显示逻辑：
     * 1. 显示所有已提交的表（tableCache中的表）
     * 2. 显示当前事务创建的未提交表（xidTableCache中的表）
     * 3. 不显示其他事务创建的未提交表（事务隔离）
     * 
     * 这实现了表级别的事务隔离，类似MySQL中的表可见性规则
     */
    @Override
    public byte[] show(long xid) {
        lock.lock();
        try {
            StringBuilder sb = new StringBuilder();
            
            // 显示所有已提交的表
            for (Table tb : tableCache.values()) {
                sb.append(tb.toString()).append("\n");
            }
            
            // 显示当前事务创建的表
            List<Table> t = xidTableCache.get(xid);
            if(t == null) {
                return "\n".getBytes();
            }
            for (Table tb : t) {
                sb.append(tb.toString()).append("\n");
            }
            return sb.toString().getBytes();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 创建表
     * 
     * @param xid 事务ID
     * @param create CREATE TABLE语句解析结果
     * @return 操作结果
     * @throws Exception 创建失败
     * 
     * 创建过程：
     * 1. 检查表名是否已存在
     * 2. 创建新表对象
     * 3. 更新表链表结构（新表成为链表头部）
     * 4. 将表加入缓存
     * 5. 记录到事务表缓存中（事务隔离）
     * 
     * 并发控制：
     * - 使用锁保证表名检查和缓存更新的原子性
     * - 防止同时创建同名表的竞争条件
     */
    @Override
    public byte[] create(long xid, Create create) throws Exception {
        lock.lock();
        try {
            // 检查表名是否已存在
            if(tableCache.containsKey(create.tableName)) {
                throw Error.DuplicatedTableException;
            }
            
            // 创建新表，传入当前第一个表的UID作为nextUid
            Table table = Table.createTable(this, firstTableUid(), xid, create);
            
            // 新表成为链表的头部
            updateFirstTableUid(table.uid);
            
            // 加入表缓存
            tableCache.put(create.tableName, table);
            
            // 记录到事务表缓存（用于事务隔离）
            if(!xidTableCache.containsKey(xid)) {
                xidTableCache.put(xid, new ArrayList<>());
            }
            xidTableCache.get(xid).add(table);
            
            return ("create " + create.tableName).getBytes();
        } finally {
            lock.unlock();
        }
    }
    
    /**
     * 插入数据
     * 
     * @param xid 事务ID
     * @param insert INSERT语句解析结果
     * @return 操作结果
     * @throws Exception 插入失败
     * 
     * 插入过程：
     * 1. 从缓存中查找目标表
     * 2. 委托给表对象处理具体的插入逻辑
     * 3. 返回操作结果
     * 
     * 注意：表查找使用短锁，避免长时间持有锁影响并发性能
     */
    @Override
    public byte[] insert(long xid, Insert insert) throws Exception {
        lock.lock();
        Table table = tableCache.get(insert.tableName);
        lock.unlock(); // 尽快释放锁
        
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        table.insert(xid, insert);
        return "insert".getBytes();
    }
    
    /**
     * 查询数据
     * 
     * @param xid 事务ID
     * @param read SELECT语句解析结果
     * @return 查询结果
     * @throws Exception 查询失败
     * 
     * 查询过程：
     * 1. 从缓存中查找目标表
     * 2. 委托给表对象处理具体的查询逻辑
     * 3. 返回查询结果
     */
    @Override
    public byte[] read(long xid, Select read) throws Exception {
        lock.lock();
        Table table = tableCache.get(read.tableName);
        lock.unlock();
        
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        return table.read(xid, read).getBytes();
    }
    
    /**
     * 更新数据
     * 
     * @param xid 事务ID
     * @param update UPDATE语句解析结果
     * @return 操作结果，包含更新的记录数
     * @throws Exception 更新失败
     * 
     * 更新过程：
     * 1. 从缓存中查找目标表
     * 2. 委托给表对象处理具体的更新逻辑
     * 3. 返回更新的记录数
     */
    @Override
    public byte[] update(long xid, Update update) throws Exception {
        lock.lock();
        Table table = tableCache.get(update.tableName);
        lock.unlock();
        
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        int count = table.update(xid, update);
        return ("update " + count).getBytes();
    }
    
    /**
     * 删除数据
     * 
     * @param xid 事务ID
     * @param delete DELETE语句解析结果
     * @return 操作结果，包含删除的记录数
     * @throws Exception 删除失败
     * 
     * 删除过程：
     * 1. 从缓存中查找目标表
     * 2. 委托给表对象处理具体的删除逻辑
     * 3. 返回删除的记录数
     */
    @Override
    public byte[] delete(long xid, Delete delete) throws Exception {
        lock.lock();
        Table table = tableCache.get(delete.tableName);
        lock.unlock();
        
        if(table == null) {
            throw Error.TableNotFoundException;
        }
        int count = table.delete(xid, delete);
        return ("delete " + count).getBytes();
    }
}
