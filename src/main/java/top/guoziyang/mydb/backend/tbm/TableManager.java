package top.guoziyang.mydb.backend.tbm;

import top.guoziyang.mydb.backend.dm.DataManager;
import top.guoziyang.mydb.backend.parser.statement.Begin;
import top.guoziyang.mydb.backend.parser.statement.Create;
import top.guoziyang.mydb.backend.parser.statement.Delete;
import top.guoziyang.mydb.backend.parser.statement.Insert;
import top.guoziyang.mydb.backend.parser.statement.Select;
import top.guoziyang.mydb.backend.parser.statement.Update;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.backend.vm.VersionManager;

/**
 * TableManager接口 - 表管理器的核心接口
 * 
 * TableManager是MYDB表管理模块的顶层接口，定义了所有表级别的操作。
 * 它承担着数据库系统中DDL（数据定义语言）和DML（数据操作语言）的处理职责。
 * 
 * 与MySQL的对比：
 * - MySQL中类似的功能分散在多个组件中：Handler接口、Storage Engine、SQL Layer等
 * - MYDB将这些功能统一在TableManager中，简化了架构但保留了核心功能
 * 
 * 核心职责：
 * 1. 事务管理：BEGIN、COMMIT、ABORT操作
 * 2. DDL操作：CREATE TABLE、SHOW TABLES
 * 3. DML操作：INSERT、SELECT、UPDATE、DELETE
 * 4. 表结构管理：维护表和字段的元信息
 * 5. 索引管理：协调字段索引的创建和维护
 * 
 * 设计模式：
 * - 外观模式：为复杂的表管理操作提供统一的接口
 * - 工厂模式：通过静态方法创建TableManager实例
 */
public interface TableManager {
    
    /**
     * 开始事务
     * 
     * @param begin BEGIN语句的解析结果
     * @return BeginRes对象，包含新事务的ID和结果信息
     * 
     * 功能：
     * 1. 创建新的事务
     * 2. 根据隔离级别设置事务属性
     * 3. 返回事务ID供后续操作使用
     * 
     * 对应MySQL中的START TRANSACTION或BEGIN操作
     */
    BeginRes begin(Begin begin);
    
    /**
     * 提交事务
     * 
     * @param xid 事务ID
     * @return 操作结果的字节数组
     * @throws Exception 提交失败
     * 
     * 功能：
     * 1. 将事务的所有修改持久化
     * 2. 释放事务持有的锁资源
     * 3. 更新事务状态为已提交
     * 
     * 对应MySQL中的COMMIT操作
     */
    byte[] commit(long xid) throws Exception;
    
    /**
     * 回滚事务
     * 
     * @param xid 事务ID
     * @return 操作结果的字节数组
     * 
     * 功能：
     * 1. 撤销事务的所有修改
     * 2. 释放事务持有的锁资源
     * 3. 更新事务状态为已回滚
     * 
     * 对应MySQL中的ROLLBACK操作
     */
    byte[] abort(long xid);

    /**
     * 显示所有表
     * 
     * @param xid 事务ID
     * @return 表列表的字节数组
     * 
     * 功能：
     * 1. 遍历所有已创建的表
     * 2. 返回表名和表结构信息
     * 3. 根据事务的可见性规则过滤结果
     * 
     * 对应MySQL中的SHOW TABLES操作
     */
    byte[] show(long xid);
    
    /**
     * 创建表
     * 
     * @param xid 事务ID
     * @param create CREATE TABLE语句的解析结果
     * @return 操作结果的字节数组
     * @throws Exception 创建失败
     * 
     * 功能：
     * 1. 验证表名是否已存在
     * 2. 创建表结构和字段定义
     * 3. 为指定字段创建索引
     * 4. 更新表链表结构
     * 
     * 对应MySQL中的CREATE TABLE操作
     */
    byte[] create(long xid, Create create) throws Exception;

    /**
     * 插入数据
     * 
     * @param xid 事务ID
     * @param insert INSERT语句的解析结果
     * @return 操作结果的字节数组
     * @throws Exception 插入失败
     * 
     * 功能：
     * 1. 验证数据类型和约束
     * 2. 插入记录到表中
     * 3. 更新相关索引
     * 4. 检查并处理并发冲突
     * 
     * 对应MySQL中的INSERT操作
     */
    byte[] insert(long xid, Insert insert) throws Exception;
    
    /**
     * 查询数据
     * 
     * @param xid 事务ID
     * @param select SELECT语句的解析结果
     * @return 查询结果的字节数组
     * @throws Exception 查询失败
     * 
     * 功能：
     * 1. 解析WHERE条件
     * 2. 选择合适的索引进行查询
     * 3. 根据事务隔离级别过滤可见记录
     * 4. 格式化并返回查询结果
     * 
     * 对应MySQL中的SELECT操作
     */
    byte[] read(long xid, Select select) throws Exception;
    
    /**
     * 更新数据
     * 
     * @param xid 事务ID
     * @param update UPDATE语句的解析结果
     * @return 操作结果的字节数组
     * @throws Exception 更新失败
     * 
     * 功能：
     * 1. 根据WHERE条件找到要更新的记录
     * 2. 执行"删除-插入"操作（MVCC特性）
     * 3. 更新相关索引
     * 4. 处理并发更新冲突
     * 
     * 对应MySQL中的UPDATE操作
     */
    byte[] update(long xid, Update update) throws Exception;
    
    /**
     * 删除数据
     * 
     * @param xid 事务ID
     * @param delete DELETE语句的解析结果
     * @return 操作结果的字节数组
     * @throws Exception 删除失败
     * 
     * 功能：
     * 1. 根据WHERE条件找到要删除的记录
     * 2. 标记记录为已删除（MVCC特性）
     * 3. 处理相关索引
     * 4. 处理并发删除冲突
     * 
     * 对应MySQL中的DELETE操作
     */
    byte[] delete(long xid, Delete delete) throws Exception;

    /**
     * 创建新的表管理器实例
     * 
     * @param path 数据库文件路径
     * @param vm 版本管理器
     * @param dm 数据管理器
     * @return 新的TableManager实例
     * 
     * 创建过程：
     * 1. 创建新的Booter文件
     * 2. 初始化第一个表的UID为0（表示暂无表）
     * 3. 创建TableManagerImpl实例
     * 
     * 用于全新数据库的初始化
     */
    public static TableManager create(String path, VersionManager vm, DataManager dm) {
        Booter booter = Booter.create(path);
        booter.update(Parser.long2Byte(0)); // 初始化：暂无表
        return new TableManagerImpl(vm, dm, booter);
    }

    /**
     * 打开已存在的表管理器实例
     * 
     * @param path 数据库文件路径
     * @param vm 版本管理器
     * @param dm 数据管理器
     * @return 已存在的TableManager实例
     * 
     * 打开过程：
     * 1. 打开已存在的Booter文件
     * 2. 从Booter中读取第一个表的UID
     * 3. 重建表链表结构
     * 4. 创建TableManagerImpl实例
     * 
     * 用于重新启动已存在的数据库
     */
    public static TableManager open(String path, VersionManager vm, DataManager dm) {
        Booter booter = Booter.open(path);
        return new TableManagerImpl(vm, dm, booter);
    }
}
