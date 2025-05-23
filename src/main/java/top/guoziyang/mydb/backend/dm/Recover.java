package top.guoziyang.mydb.backend.dm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.common.primitives.Bytes;

import top.guoziyang.mydb.backend.common.SubArray;
import top.guoziyang.mydb.backend.dm.dataItem.DataItem;
import top.guoziyang.mydb.backend.dm.logger.Logger;
import top.guoziyang.mydb.backend.dm.page.Page;
import top.guoziyang.mydb.backend.dm.page.PageX;
import top.guoziyang.mydb.backend.dm.pageCache.PageCache;
import top.guoziyang.mydb.backend.tm.TransactionManager;
import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.backend.utils.Parser;

/**
 * Recover - 数据库崩溃恢复管理器
 * <p>
 * Recover模块实现数据库的崩溃恢复功能，确保在系统异常关闭后能够恢复到一致状态。
 * 这是所有企业级数据库系统的核心功能之一。
 * <p>
 * 与MySQL InnoDB恢复机制的对比：
 * - MySQL使用复杂的ARIES算法，支持并行恢复和检查点机制
 * - InnoDB有redo log和undo log两套日志系统
 * - MYDB实现了ARIES的简化版本，易于理解核心原理
 * - 都遵循WAL（Write-Ahead Logging）原则
 * <p>
 * 恢复算法原理（ARIES简化版）：
 * 1. 分析阶段(Analysis)：扫描日志，确定数据库状态和需要处理的事务
 * 2. 重做阶段(Redo)：重放所有已提交事务的操作，恢复到故障时的状态
 * 3. 撤销阶段(Undo)：回滚所有未提交事务的操作，确保一致性
 * <p>
 * 支持的操作类型：
 * - INSERT：数据插入操作
 * - UPDATE：数据更新操作
 * <p>
 * 恢复原则：
 * - 已提交事务的所有修改都必须持久化到数据库
 * - 未提交事务的所有修改都必须被撤销
 * - 恢复后数据库必须处于一致状态
 */
public class Recover {

    /**
     * 日志类型常量 - 插入操作
     * 标识这条日志记录是一个数据插入操作
     */
    private static final byte LOG_TYPE_INSERT = 0;

    /**
     * 日志类型常量 - 更新操作
     * 标识这条日志记录是一个数据更新操作
     */
    private static final byte LOG_TYPE_UPDATE = 1;

    /**
     * 操作模式常量 - 重做操作
     * 用于恢复时重新执行已提交事务的操作
     */
    private static final int REDO = 0;

    /**
     * 操作模式常量 - 撤销操作
     * 用于恢复时回滚未提交事务的操作
     */
    private static final int UNDO = 1;

    /**
     * 插入日志信息结构
     * <p>
     * 包含插入操作所需的所有信息：
     * - xid: 执行插入的事务ID
     * - pgno: 目标页面编号
     * - offset: 页面内插入位置的偏移量
     * - raw: 插入的原始数据（完整的DataItem格式）
     */
    static class InsertLogInfo {
        long xid;      // 事务ID
        int pgno;      // 页面编号
        short offset;  // 页面内偏移量
        byte[] raw;    // 原始数据
    }

    /**
     * 更新日志信息结构
     * <p>
     * 包含更新操作所需的所有信息：
     * - xid: 执行更新的事务ID
     * - pgno: 目标页面编号
     * - offset: 页面内更新位置的偏移量
     * - oldRaw: 更新前的原始数据
     * - newRaw: 更新后的新数据
     */
    static class UpdateLogInfo {
        long xid;        // 事务ID
        int pgno;        // 页面编号
        short offset;    // 页面内偏移量
        byte[] oldRaw;   // 旧数据
        byte[] newRaw;   // 新数据
    }

    /**
     * 数据库崩溃恢复的主入口方法
     *
     * @param tm 事务管理器，用于判断事务状态
     * @param lg 日志管理器，用于读取WAL日志
     * @param pc 页面缓存，用于访问数据页面
     *           <p>
     *           恢复流程（三阶段恢复算法）：
     *           <p>
     *           阶段1 - 分析阶段：
     *           - 扫描整个日志文件
     *           - 确定最大的页面编号
     *           - 截断页面缓存，移除可能损坏的页面
     *           <p>
     *           阶段2 - 重做阶段：
     *           - 重新扫描日志文件
     *           - 对所有已提交事务的操作执行REDO
     *           - 恢复到系统崩溃时的状态
     *           <p>
     *           阶段3 - 撤销阶段：
     *           - 再次扫描日志文件
     *           - 对所有未提交事务的操作执行UNDO
     *           - 确保未提交的修改被完全回滚
     *           <p>
     *           这个算法确保了：
     *           1. 原子性：未提交事务的影响被完全消除
     *           2. 持久性：已提交事务的修改得到保留
     *           3. 一致性：数据库恢复到一致状态
     */
    public static void recover(TransactionManager tm, Logger lg, PageCache pc) {
        System.out.println("Recovering...");

        // === 阶段1：分析阶段 ===
        // 扫描日志确定最大页面号，用于截断可能损坏的页面
        /*
        正常情况：
        WAL日志    ：[Log1][Log2][Log3][Log4]
        数据页面   ：Page1  Page2  Page3  Page4
        状态       ：完整   完整   完整   完整

        崩溃情况：
        WAL日志    ：[Log1][Log2][Log3][损坏]
        数据页面   ：Page1  Page2  Page3  Page4(可能损坏)
        状态       ：完整   完整   完整   不可信

        恢复策略：
        1. 日志扫描确定最大有效页面号 = 3
        2. 截断到Page3，删除Page4及后续页面
        3. 确保数据库边界安全
         * */
        lg.rewind();
        int maxPgno = 0;
        while (true) {
            byte[] log = lg.next();
            if (log == null) break;
            int pgno;
            if (isInsertLog(log)) {
                InsertLogInfo li = parseInsertLog(log);
                pgno = li.pgno;
            } else {
                UpdateLogInfo li = parseUpdateLog(log);
                pgno = li.pgno;
            }
            if (pgno > maxPgno) {
                maxPgno = pgno;
            }
        }
        if (maxPgno == 0) {
            maxPgno = 1;
        }
        pc.truncateByBgno(maxPgno);
        System.out.println("Truncate to " + maxPgno + " pages.");

        // === 阶段2：重做阶段 ===
        redoTranscations(tm, lg, pc);
        System.out.println("Redo Transactions Over.");

        // === 阶段3：撤销阶段 ===
        undoTranscations(tm, lg, pc);
        System.out.println("Undo Transactions Over.");

        System.out.println("Recovery Over.");
    }

    /**
     * 重做阶段 - 重新执行所有已提交事务的操作
     *
     * @param tm 事务管理器
     * @param lg 日志管理器
     * @param pc 页面缓存
     *           <p>
     *           重做原理：
     *           1. 按照日志记录的时间顺序扫描
     *           2. 对每条日志记录，检查对应的事务是否已提交
     *           3. 如果事务已提交(非active状态)，重新执行该操作
     *           4. 这样确保所有已提交事务的修改都被持久化
     *           <p>
     *           重做的必要性：
     *           - 事务可能已经提交，但修改还在缓存中未写入磁盘
     *           - 系统崩溃导致这些修改丢失
     *           - 通过重做确保已提交事务的持久性
     */
    private static void redoTranscations(TransactionManager tm, Logger lg, PageCache pc) {
        lg.rewind();
        while (true) {
            // 按照日志记录的时间顺序扫描
            byte[] log = lg.next();
            if (log == null) break;
            if (isInsertLog(log)) {
                InsertLogInfo li = parseInsertLog(log);
                long xid = li.xid;
                if (!tm.isActive(xid)) {  // 事务已提交或已回滚
                    doInsertLog(pc, log, REDO);
                }
            } else {
                UpdateLogInfo xi = parseUpdateLog(log);
                long xid = xi.xid;
                if (!tm.isActive(xid)) {  // 事务已提交或已回滚
                    doUpdateLog(pc, log, REDO);
                }
            }
        }
    }

    /**
     * 撤销阶段 - 回滚所有未提交事务的操作
     *
     * @param tm 事务管理器
     * @param lg 日志管理器
     * @param pc 页面缓存
     *           <p>
     *           撤销原理：
     *           1. 扫描日志，收集所有未提交事务的操作
     *           2. 按照事务分组，每个事务的操作按时间倒序排列
     *           3. 对每个未提交事务，倒序执行UNDO操作
     *           4. 倒序的原因：后执行的操作先撤销，保证正确性
     *           <p>
     *           撤销的必要性：
     *           - 未提交事务的修改可能已经写入数据页
     *           - 必须撤销这些修改以保证原子性
     *           - 撤销后将事务标记为已回滚状态
     */
    private static void undoTranscations(TransactionManager tm, Logger lg, PageCache pc) {
        // 收集所有未提交事务的日志记录
        Map<Long, List<byte[]>> logCache = new HashMap<>();
        lg.rewind();
        while (true) {
            byte[] log = lg.next();
            if (log == null) break;
            if (isInsertLog(log)) {
                InsertLogInfo li = parseInsertLog(log);
                long xid = li.xid;
                if (tm.isActive(xid)) {  // 事务仍然活跃（未提交）
                    if (!logCache.containsKey(xid)) {
                        logCache.put(xid, new ArrayList<>());
                    }
                    logCache.get(xid).add(log);
                }
            } else {
                UpdateLogInfo xi = parseUpdateLog(log);
                long xid = xi.xid;
                if (tm.isActive(xid)) {  // 事务仍然活跃（未提交）
                    if (!logCache.containsKey(xid)) {
                        logCache.put(xid, new ArrayList<>());
                    }
                    logCache.get(xid).add(log);
                }
            }
        }

        // 对所有未提交事务进行倒序undo操作
        for (Entry<Long, List<byte[]>> entry : logCache.entrySet()) {
            List<byte[]> logs = entry.getValue();
            // 倒序执行UNDO：最后的操作最先撤销
            for (int i = logs.size() - 1; i >= 0; i--) {
                byte[] log = logs.get(i);
                if (isInsertLog(log)) {
                    doInsertLog(pc, log, UNDO);
                } else {
                    doUpdateLog(pc, log, UNDO);
                }
            }
            // 将事务标记为已回滚
            tm.abort(entry.getKey());
        }
    }

    /**
     * 判断日志记录是否为插入类型
     *
     * @param log 日志记录的字节数组
     * @return true表示插入日志，false表示更新日志
     */
    private static boolean isInsertLog(byte[] log) {
        return log[0] == LOG_TYPE_INSERT;
    }

    // ================== 更新日志相关方法 ==================

    /**
     * 更新日志格式定义
     * <p>
     * 格式：[LogType] [XID] [UID] [OldRaw] [NewRaw]
     * - LogType: 1字节，日志类型标识
     * - XID: 8字节，事务ID
     * - UID: 8字节，数据项唯一标识符（页面号+偏移量）
     * - OldRaw: 变长，修改前的数据
     * - NewRaw: 变长，修改后的数据
     */
    private static final int OF_TYPE = 0;
    private static final int OF_XID = OF_TYPE + 1;
    private static final int OF_UPDATE_UID = OF_XID + 8;
    private static final int OF_UPDATE_RAW = OF_UPDATE_UID + 8;

    /**
     * 创建更新操作的日志记录
     *
     * @param xid 事务ID
     * @param di  被更新的数据项
     * @return 格式化的日志字节数组
     * <p>
     * 日志内容包括：
     * - 操作类型（UPDATE）
     * - 事务ID
     * - 数据项的UID
     * - 修改前的完整数据
     * - 修改后的完整数据
     * <p>
     * 这些信息足以支持REDO和UNDO操作
     */
    public static byte[] updateLog(long xid, DataItem di) {
        byte[] logType = {LOG_TYPE_UPDATE};
        byte[] xidRaw = Parser.long2Byte(xid);
        byte[] uidRaw = Parser.long2Byte(di.getUid());
        byte[] oldRaw = di.getOldRaw();  // 修改前数据
        SubArray raw = di.getRaw();
        byte[] newRaw = Arrays.copyOfRange(raw.raw, raw.start, raw.end);  // 修改后数据
        // 拼接日志，包括操作类型、事务ID、UID、修改前数据、修改后数据
        return Bytes.concat(logType, xidRaw, uidRaw, oldRaw, newRaw);
    }

    /**
     * 解析更新日志记录
     *
     * @param log 日志字节数组
     * @return 解析出的更新日志信息
     * <p>
     * 解析步骤：
     * 1. 提取事务ID
     * 2. 提取并解析UID（分离页面号和偏移量）
     * 3. 根据数据长度分离旧数据和新数据
     */
    private static UpdateLogInfo parseUpdateLog(byte[] log) {
        UpdateLogInfo li = new UpdateLogInfo();
        li.xid = Parser.parseLong(Arrays.copyOfRange(log, OF_XID, OF_UPDATE_UID));
        long uid = Parser.parseLong(Arrays.copyOfRange(log, OF_UPDATE_UID, OF_UPDATE_RAW));

        // 从UID中提取页面内偏移量（低16位）
        li.offset = (short) (uid & ((1L << 16) - 1));
        uid >>>= 32;
        // 从UID中提取页面号（高32位的低32位）
        li.pgno = (int) (uid & ((1L << 32) - 1));

        // 剩余数据平均分为旧数据和新数据
        int length = (log.length - OF_UPDATE_RAW) / 2;
        li.oldRaw = Arrays.copyOfRange(log, OF_UPDATE_RAW, OF_UPDATE_RAW + length);
        li.newRaw = Arrays.copyOfRange(log, OF_UPDATE_RAW + length, OF_UPDATE_RAW + length * 2);
        return li;
    }

    /**
     * 执行更新日志的恢复操作
     *
     * @param pc   页面缓存
     * @param log  日志记录
     * @param flag 操作模式（REDO或UNDO）
     *             <p>
     *             恢复逻辑：
     *             - REDO模式：使用新数据覆盖页面中的对应位置
     *             - UNDO模式：使用旧数据恢复页面中的对应位置
     *             <p>
     *             操作步骤：
     *             1. 解析日志获取页面号、偏移量和数据
     *             2. 从页面缓存获取目标页面
     *             3. 在指定偏移量处写入相应的数据
     *             4. 释放页面引用
     */
    private static void doUpdateLog(PageCache pc, byte[] log, int flag) {
        int pgno;
        short offset;
        byte[] raw;
        if (flag == REDO) {
            UpdateLogInfo xi = parseUpdateLog(log);
            pgno = xi.pgno;
            offset = xi.offset;
            raw = xi.newRaw;  // REDO使用新数据
        } else {
            UpdateLogInfo xi = parseUpdateLog(log);
            pgno = xi.pgno;
            offset = xi.offset;
            raw = xi.oldRaw;  // UNDO使用旧数据
        }
        Page pg = null;
        try {
            pg = pc.getPage(pgno);
        } catch (Exception e) {
            Panic.panic(e);
        }
        try {
            PageX.recoverUpdate(pg, raw, offset);
        } finally {
            pg.release();
        }
    }

    // ================== 插入日志相关方法 ==================

    /**
     * 插入日志格式定义
     * <p>
     * 格式：[LogType] [XID] [Pgno] [Offset] [Raw]
     * - LogType: 1字节，日志类型标识
     * - XID: 8字节，事务ID
     * - Pgno: 4字节，目标页面编号
     * - Offset: 2字节，页面内插入位置偏移量
     * - Raw: 变长，插入的完整数据项
     */
    private static final int OF_INSERT_PGNO = OF_XID + 8;
    private static final int OF_INSERT_OFFSET = OF_INSERT_PGNO + 4;
    private static final int OF_INSERT_RAW = OF_INSERT_OFFSET + 2;

    /**
     * 创建插入操作的日志记录
     *
     * @param xid 事务ID
     * @param pg  目标页面
     * @param raw 插入的原始数据
     * @return 格式化的日志字节数组
     * <p>
     * 插入日志包含：
     * - 操作类型（INSERT）
     * - 事务ID
     * - 目标页面编号
     * - 页面内的插入位置（当前空闲空间偏移量）
     * - 完整的数据项内容
     * <p>
     * 这些信息足以支持插入操作的REDO和UNDO
     */
    public static byte[] insertLog(long xid, Page pg, byte[] raw) {
        byte[] logTypeRaw = {LOG_TYPE_INSERT};
        byte[] xidRaw = Parser.long2Byte(xid);
        byte[] pgnoRaw = Parser.int2Byte(pg.getPageNumber());
        byte[] offsetRaw = Parser.short2Byte(PageX.getFSO(pg));  // 获取当前空闲空间偏移量
        return Bytes.concat(logTypeRaw, xidRaw, pgnoRaw, offsetRaw, raw);
    }

    /**
     * 解析插入日志记录
     *
     * @param log 日志字节数组
     * @return 解析出的插入日志信息
     * <p>
     * 解析出插入操作所需的所有信息：
     * - 事务ID
     * - 目标页面编号
     * - 插入位置偏移量
     * - 插入的数据内容
     */
    private static InsertLogInfo parseInsertLog(byte[] log) {
        InsertLogInfo li = new InsertLogInfo();
        li.xid = Parser.parseLong(Arrays.copyOfRange(log, OF_XID, OF_INSERT_PGNO));
        li.pgno = Parser.parseInt(Arrays.copyOfRange(log, OF_INSERT_PGNO, OF_INSERT_OFFSET));
        li.offset = Parser.parseShort(Arrays.copyOfRange(log, OF_INSERT_OFFSET, OF_INSERT_RAW));
        li.raw = Arrays.copyOfRange(log, OF_INSERT_RAW, log.length);
        return li;
    }

    /**
     * 执行插入日志的恢复操作
     *
     * @param pc   页面缓存
     * @param log  日志记录
     * @param flag 操作模式（REDO或UNDO）
     *             <p>
     *             恢复逻辑：
     *             - REDO模式：重新执行插入操作，在指定位置插入数据
     *             - UNDO模式：撤销插入操作，将数据项标记为无效（逻辑删除）
     *             <p>
     *             插入UNDO的特殊处理：
     *             - 不能物理删除数据（可能影响其他数据的位置）
     *             - 通过设置ValidFlag为1来标记数据项无效
     *             - 这样既撤销了插入效果，又保持了页面结构稳定
     */
    private static void doInsertLog(PageCache pc, byte[] log, int flag) {
        InsertLogInfo li = parseInsertLog(log);
        // 从页面缓存获取目标页面对象
        Page pg = null;
        try {
            pg = pc.getPage(li.pgno);
        } catch (Exception e) {
            Panic.panic(e);
        }
        try {
            if (flag == UNDO) {
                // UNDO插入：将数据标记为无效（逻辑删除）
                DataItem.setDataItemRawInvalid(li.raw);
            }
            // 在指定位置恢复数据项
            PageX.recoverInsert(pg, li.raw, li.offset);
        } finally {
            pg.release();
        }
    }
}
