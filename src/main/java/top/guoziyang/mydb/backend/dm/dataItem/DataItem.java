package top.guoziyang.mydb.backend.dm.dataItem;

import java.util.Arrays;

import com.google.common.primitives.Bytes;

import top.guoziyang.mydb.backend.common.SubArray;
import top.guoziyang.mydb.backend.dm.DataManagerImpl;
import top.guoziyang.mydb.backend.dm.page.Page;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.backend.utils.Types;

/**
 * DataItem接口 - 数据项管理的核心抽象
 * 
 * DataItem是MYDB中数据存储的基本单位，每个DataItem代表一条记录。
 * 
 * 与MySQL InnoDB的对比：
 * - MySQL中对应的概念是"记录(Record)"或"行(Row)"
 * - InnoDB使用复杂的行格式（Compact、Dynamic等）
 * - MYDB简化了行格式：[ValidFlag][DataSize][Data]
 * 
 * 数据项结构详解：
 * - ValidFlag（1字节）：0表示有效，1表示已删除（类似MySQL的delete mark）
 * - DataSize（2字节）：数据长度，最大支持64KB的单条记录
 * - Data（变长）：实际的用户数据
 * 
 * 并发控制：
 * - 读写锁：支持多读单写的并发访问
 * - 事务支持：before/after机制支持事务的回滚和提交
 * - 版本管理：配合MVCC实现多版本并发控制
 */
public interface DataItem {
    /**
     * 获取数据项的实际数据部分
     * 
     * @return 数据内容的SubArray表示（不包含ValidFlag和DataSize）
     * 
     * 返回的数据不包含头部信息，只包含用户存储的实际数据。
     * 这类似于MySQL InnoDB中跳过行头直接访问列数据。
     */
    SubArray data();
    
    /**
     * 事务开始前的准备操作
     * 
     * 在修改数据项之前调用，执行以下操作：
     * 1. 获取写锁，确保独占访问
     * 2. 标记页面为脏页
     * 3. 保存修改前的数据副本（用于回滚）
     * 
     * 这类似于MySQL中事务开始时创建undo log记录
     */
    void before();
    
    /**
     * 撤销事务修改
     * 
     * 当事务需要回滚时调用，执行以下操作：
     * 1. 恢复修改前的数据内容
     * 2. 释放写锁
     * 
     * 这相当于MySQL中应用undo log进行回滚
     */
    void unBefore();
    
    /**
     * 事务提交后的操作
     * 
     * @param xid 事务ID
     * 
     * 当事务提交时调用，执行以下操作：
     * 1. 记录数据修改日志（用于恢复）
     * 2. 释放写锁
     * 
     * 这类似于MySQL中写入redo log并释放锁
     */
    void after(long xid);
    
    /**
     * 释放数据项的引用
     * 
     * 当不再需要访问数据项时调用，减少引用计数。
     * 类似于MySQL Buffer Pool中的页面引用管理。
     */
    void release();

    /**
     * 获取独占写锁
     * 
     * 用于需要修改数据项的操作，确保独占访问。
     * 类似于MySQL中的行级排他锁。
     */
    void lock();
    
    /**
     * 释放写锁
     * 
     * 与lock()配对使用，完成修改后释放锁。
     */
    void unlock();
    
    /**
     * 获取共享读锁
     * 
     * 用于只读操作，支持多个事务同时读取。
     * 类似于MySQL中的行级共享锁。
     */
    void rLock();
    
    /**
     * 释放读锁
     * 
     * 与rLock()配对使用，完成读取后释放锁。
     */
    void rUnLock();

    /**
     * 获取数据项所在的页面
     * 
     * @return 包含此数据项的页面对象
     */
    Page page();
    
    /**
     * 获取数据项的唯一标识符
     * 
     * @return 数据项的UID（由页面号和页面内偏移量组成）
     * 
     * UID的组成：
     * - 高32位：页面号
     * - 低32位：页面内偏移量
     * 这类似于MySQL中的(space_id, page_no, heap_no)组合
     */
    long getUid();
    
    /**
     * 获取修改前的原始数据
     * 
     * @return 事务开始前的数据副本
     * 
     * 用于事务回滚时恢复数据，类似于MySQL的undo log
     */
    byte[] getOldRaw();
    
    /**
     * 获取当前的原始数据
     * 
     * @return 当前数据的SubArray表示（包含完整的数据项结构）
     */
    SubArray getRaw();

    /**
     * 将用户数据包装成数据项格式
     * 
     * @param raw 用户原始数据
     * @return 包装后的数据项字节数组 [ValidFlag][DataSize][Data]
     * 
     * 数据项格式：
     * - ValidFlag: 1字节，初始为0（有效）
     * - DataSize: 2字节，存储数据长度
     * - Data: 变长，存储实际数据
     */
    public static byte[] wrapDataItemRaw(byte[] raw) {
        byte[] valid = new byte[1];
        byte[] size = Parser.short2Byte((short)raw.length);
        return Bytes.concat(valid, size, raw);
    }

    /**
     * 从页面中解析数据项
     * 
     * @param pg 包含数据项的页面
     * @param offset 数据项在页面中的偏移量
     * @param dm 数据管理器实例
     * @return 解析出的数据项对象
     * 
     * 解析过程：
     * 1. 读取DataSize字段，确定数据项总长度
     * 2. 计算UID（页面号+偏移量）
     * 3. 创建SubArray表示数据项范围
     * 4. 构造DataItemImpl对象
     */
    public static DataItem parseDataItem(Page pg, short offset, DataManagerImpl dm) {
        byte[] raw = pg.getData();
        short size = Parser.parseShort(Arrays.copyOfRange(raw, offset+DataItemImpl.OF_SIZE, offset+DataItemImpl.OF_DATA));
        short length = (short)(size + DataItemImpl.OF_DATA);
        long uid = Types.addressToUid(pg.getPageNumber(), offset);
        return new DataItemImpl(new SubArray(raw, offset, offset+length), new byte[length], pg, uid, dm);
    }

    /**
     * 将数据项标记为无效（删除）
     * 
     * @param raw 数据项的原始字节数组
     * 
     * 通过设置ValidFlag为1来标记数据项已删除。
     * 这类似于MySQL中的delete mark，实现逻辑删除。
     * 物理删除由后台的purge线程处理。
     */
    public static void setDataItemRawInvalid(byte[] raw) {
        raw[DataItemImpl.OF_VALID] = (byte)1;
    }
}
