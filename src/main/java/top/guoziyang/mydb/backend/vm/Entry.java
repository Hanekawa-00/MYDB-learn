package top.guoziyang.mydb.backend.vm;

import java.util.Arrays;

import com.google.common.primitives.Bytes;

import top.guoziyang.mydb.backend.common.SubArray;
import top.guoziyang.mydb.backend.dm.dataItem.DataItem;
import top.guoziyang.mydb.backend.utils.Parser;

/**
 * Entry类 - 版本管理的数据条目
 * 
 * Entry是MYDB版本管理的核心数据结构，代表数据的一个版本。
 * 每个Entry包含版本控制信息和实际数据，是MVCC实现的基础。
 * 
 * 与MySQL InnoDB行记录的对比：
 * - MySQL InnoDB: 复杂的行格式，包含事务ID、回滚指针、列信息等
 * - MYDB Entry: 简化的版本记录，包含XMIN、XMAX和数据
 * 
 * Entry结构布局：
 * ┌─────────────┬─────────────┬─────────────────┐
 * │    XMIN     │    XMAX     │      DATA       │
 * │   (8字节)    │   (8字节)    │     (变长)       │
 * │  创建事务ID   │  删除事务ID   │    实际数据      │
 * └─────────────┴─────────────┴─────────────────┘
 * 
 * 核心概念：
 * 1. XMIN: 创建这个版本的事务ID
 * 2. XMAX: 删除这个版本的事务ID（0表示未删除）
 * 3. 版本可见性: 根据XMIN和XMAX判断版本对事务的可见性
 * 4. 逻辑删除: 通过设置XMAX实现，不物理删除数据
 */
public class Entry {

    /**
     * XMIN字段偏移量 - 创建事务ID的存储位置
     */
    private static final int OF_XMIN = 0;
    
    /**
     * XMAX字段偏移量 - 删除事务ID的存储位置
     */
    private static final int OF_XMAX = OF_XMIN+8;
    
    /**
     * DATA字段偏移量 - 实际数据的存储位置
     */
    private static final int OF_DATA = OF_XMAX+8;

    /**
     * 数据项的唯一标识符
     * 对应MySQL中的行ID概念
     */
    private long uid;
    
    /**
     * 底层数据项对象，负责实际的数据存储和管理
     */
    private DataItem dataItem;
    
    /**
     * 版本管理器引用，用于释放Entry时的回调
     */
    private VersionManager vm;

    /**
     * 创建新的Entry对象
     * 
     * @param vm 版本管理器
     * @param dataItem 底层数据项
     * @param uid 数据项唯一标识
     * @return Entry对象，如果dataItem为null则返回null
     * 
     * 工厂方法，确保Entry对象的正确初始化
     */
    public static Entry newEntry(VersionManager vm, DataItem dataItem, long uid) {
        if (dataItem == null) {
            return null;
        }
        Entry entry = new Entry();
        entry.uid = uid;
        entry.dataItem = dataItem;
        entry.vm = vm;
        return entry;
    }

    /**
     * 从存储中加载Entry
     * 
     * @param vm 版本管理器
     * @param uid 要加载的数据项UID
     * @return 加载的Entry对象
     * @throws Exception 加载异常
     * 
     * 加载流程：
     * 1. 通过DataManager读取底层数据
     * 2. 创建Entry对象包装数据
     * 3. 返回可用的Entry
     */
    public static Entry loadEntry(VersionManager vm, long uid) throws Exception {
        DataItem di = ((VersionManagerImpl)vm).dm.read(uid);
        return newEntry(vm, di, uid);
    }

    /**
     * 包装Entry的原始数据格式
     * 
     * @param xid 创建事务ID
     * @param data 实际数据内容
     * @return 格式化的字节数组
     * 
     * 数据格式：[XMIN(8字节)] + [XMAX(8字节)] + [DATA(变长)]
     * XMIN设置为创建事务ID，XMAX初始化为0（未删除）
     * 
     * 对应MySQL中创建新行记录的过程
     */
    public static byte[] wrapEntryRaw(long xid, byte[] data) {
        byte[] xmin = Parser.long2Byte(xid);          // 创建事务ID
        byte[] xmax = new byte[8];                    // 初始化为0（未删除）
        return Bytes.concat(xmin, xmax, data);        // 拼接完整数据
    }

    /**
     * 释放Entry引用
     * 
     * 将Entry返回给版本管理器的缓存系统，
     * 类似于释放数据库连接回连接池
     */
    public void release() {
        ((VersionManagerImpl)vm).releaseEntry(this);
    }

    /**
     * 移除Entry（彻底释放）
     * 
     * 释放底层DataItem资源，
     * 通常在Entry不再需要时调用
     */
    public void remove() {
        dataItem.release();
    }

    /**
     * 获取Entry的实际数据内容（拷贝形式）
     * 
     * @return 数据内容的副本
     * 
     * 读取流程：
     * 1. 获取读锁保护数据一致性
     * 2. 计算数据部分的长度和位置
     * 3. 拷贝数据到新数组返回
     * 4. 释放读锁
     * 
     * 返回拷贝而非引用，保证数据安全性
     */
    public byte[] data() {
        dataItem.rLock();
        try {
            SubArray sa = dataItem.data();
            // 计算实际数据长度：总长度 - Entry头部长度
            byte[] data = new byte[sa.end - sa.start - OF_DATA];
            // 拷贝数据部分
            System.arraycopy(sa.raw, sa.start+OF_DATA, data, 0, data.length);
            return data;
        } finally {
            dataItem.rUnLock();
        }
    }

    /**
     * 获取创建事务ID (XMIN)
     * 
     * @return 创建这个版本的事务ID
     * 
     * XMIN的含义：
     * - 表示哪个事务创建了这个数据版本
     * - 用于可见性判断：只有XMIN事务提交后，版本才可能对其他事务可见
     * - 对应MySQL InnoDB中的DB_TRX_ID字段
     */
    public long getXmin() {
        dataItem.rLock();
        try {
            SubArray sa = dataItem.data();
            // 从XMIN位置读取8字节的事务ID
            return Parser.parseLong(Arrays.copyOfRange(sa.raw, sa.start+OF_XMIN, sa.start+OF_XMAX));
        } finally {
            dataItem.rUnLock();
        }
    }

    /**
     * 获取删除事务ID (XMAX)
     * 
     * @return 删除这个版本的事务ID，0表示未删除
     * 
     * XMAX的含义：
     * - 0: 版本未被删除，仍然有效
     * - 非0: 版本被对应事务删除，根据删除事务状态判断可见性
     * - 实现逻辑删除：不物理删除数据，而是标记删除
     * - 对应MySQL InnoDB中的删除标记机制
     */
    public long getXmax() {
        dataItem.rLock();
        try {
            SubArray sa = dataItem.data();
            // 从XMAX位置读取8字节的事务ID
            return Parser.parseLong(Arrays.copyOfRange(sa.raw, sa.start+OF_XMAX, sa.start+OF_DATA));
        } finally {
            dataItem.rUnLock();
        }
    }

    /**
     * 设置删除事务ID (XMAX) - 实现逻辑删除
     * 
     * @param xid 删除事务的ID
     * 
     * 逻辑删除原理：
     * 1. 不物理删除数据，而是设置XMAX标记
     * 2. 通过XMAX判断版本对其他事务的可见性
     * 3. 支持MVCC并发控制和事务回滚
     * 
     * 对应MySQL的处理方式：
     * - MySQL InnoDB: 设置删除标记位，放入purge队列
     * - MYDB: 设置XMAX字段，通过可见性判断实现
     * 
     * 操作流程：
     * 1. 获取写锁确保操作原子性
     * 2. 计算XMAX字段在数据中的位置
     * 3. 将事务ID写入XMAX位置
     * 4. 释放写锁
     */
    public void setXmax(long xid) {
        dataItem.before();  // 记录修改前状态，支持事务回滚
        try {
            SubArray sa = dataItem.data();
            // 将事务ID转换为字节数组并写入XMAX位置
            System.arraycopy(Parser.long2Byte(xid), 0, sa.raw, sa.start+OF_XMAX, 8);
        } finally {
            dataItem.after(xid);  // 提交修改，记录修改后状态
        }
    }

    /**
     * 获取数据项的唯一标识符
     * 
     * @return 数据项UID
     * 
     * UID的作用：
     * - 在整个数据库中唯一标识一个数据项
     * - 由页面号和页面内偏移量组成
     * - 用于数据项的定位和引用
     * - 对应MySQL中的行指针(row pointer)概念
     */
    public long getUid() {
        return uid;
    }
}
