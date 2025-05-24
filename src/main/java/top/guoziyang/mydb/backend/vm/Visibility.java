package top.guoziyang.mydb.backend.vm;

import top.guoziyang.mydb.backend.tm.TransactionManager;

/**
 * Visibility类 - 版本可见性判断核心
 * 
 * Visibility实现了MVCC的核心逻辑：判断一个数据版本对于特定事务是否可见。
 * 这是实现事务隔离级别的关键组件。
 * 
 * 与MySQL InnoDB可见性判断的对比：
 * - MySQL InnoDB: 复杂的Read View机制，包含多个事务ID阈值
 * - MYDB Visibility: 简化的可见性算法，但保留了核心逻辑
 * 
 * 核心概念：
 * 1. XMIN: 创建版本的事务ID
 * 2. XMAX: 删除版本的事务ID（0表示未删除）
 * 3. 快照: 事务开始时的活跃事务列表
 * 4. 隔离级别: 决定可见性判断的策略
 */
public class Visibility {
    
    /**
     * 判断版本是否应该被跳过（用于删除版本的特殊处理）
     * 
     * @param tm 事务管理器
     * @param t 当前事务
     * @param e 要检查的数据版本
     * @return true表示应该跳过这个版本
     * 
     * 跳过条件：
     * 1. 只有REPEATABLE_READ级别才检查
     * 2. XMAX事务已提交且满足以下条件之一：
     *    - XMAX > 当前事务ID（删除事务比当前事务晚开始）
     *    - XMAX在当前事务的快照中（删除事务在当前事务开始时还活跃）
     * 
     * 作用：防止REPEATABLE_READ级别看到"幻读"现象
     */
    public static boolean isVersionSkip(TransactionManager tm, Transaction t, Entry e) {
        long xmax = e.getXmax();
        
        // READ_COMMITTED级别不跳过版本
        if(t.level == 0) {
            return false;
        } else {
            // REPEATABLE_READ级别的跳过逻辑
            return tm.isCommitted(xmax) && (xmax > t.xid || t.isInSnapshot(xmax));
        }
    }

    /**
     * 判断版本对当前事务是否可见
     * 
     * @param tm 事务管理器
     * @param t 当前事务
     * @param e 要检查的数据版本
     * @return true表示版本可见
     * 
     * 根据事务隔离级别选择不同的可见性策略：
     * - READ_COMMITTED: 读已提交策略
     * - REPEATABLE_READ: 可重复读策略
     */
    public static boolean isVisible(TransactionManager tm, Transaction t, Entry e) {
        if(t.level == 0) {
            return readCommitted(tm, t, e);
        } else {
            return repeatableRead(tm, t, e);
        }
    }

    /**
     * READ_COMMITTED隔离级别的可见性判断
     * 
     * @param tm 事务管理器
     * @param t 当前事务
     * @param e 要检查的数据版本
     * @return true表示版本可见
     * 
     * READ_COMMITTED规则：
     * 1. 版本由当前事务创建 → 可见
     * 2. 版本由其他已提交事务创建 且 未被删除 → 可见
     * 3. 版本由其他已提交事务创建 且 被当前事务删除 → 可见
     * 4. 版本由其他已提交事务创建 且 被其他已提交事务删除 → 不可见
     * 5. 其他情况 → 不可见
     * 
     * 特点：每次读取都能看到最新的已提交数据
     */
    private static boolean readCommitted(TransactionManager tm, Transaction t, Entry e) {
        long xid = t.xid;
        long xmin = e.getXmin();
        long xmax = e.getXmax();
        
        // 情况1：当前事务创建的版本，总是可见
        if(xmin == xid && xmax == 0) return true;
        
        // 情况2：其他已提交事务创建，未被删除
        if(tm.isCommitted(xmin) && xmax == 0) return true;
        
        // 情况3：其他已提交事务创建，被当前事务删除
        if(tm.isCommitted(xmin) && xmax != 0 && !tm.isCommitted(xmax) && xmax != xid) {
            return true;
        }
        
        // 情况4：其他已提交事务创建，被当前事务删除
        if(tm.isCommitted(xmin) && xmax == xid) return true;
        
        // 其他情况不可见
        return false;
    }

    /**
     * REPEATABLE_READ隔离级别的可见性判断
     * 
     * @param tm 事务管理器
     * @param t 当前事务
     * @param e 要检查的数据版本
     * @return true表示版本可见
     * 
     * REPEATABLE_READ规则：
     * 1. 版本由当前事务创建 → 可见
     * 2. 版本的创建事务在快照中（事务开始时还活跃） → 不可见
     * 3. 版本的创建事务已提交 且 未被删除 → 可见
     * 4. 版本的创建事务已提交 且 被当前事务删除 → 可见
     * 5. 版本的创建事务已提交 且 被其他事务删除，但删除事务在快照中 → 可见
     * 6. 其他情况 → 不可见
     * 
     * 特点：整个事务期间看到的数据保持一致，避免不可重复读
     */
    private static boolean repeatableRead(TransactionManager tm, Transaction t, Entry e) {
        long xid = t.xid;
        long xmin = e.getXmin();
        long xmax = e.getXmax();
        
        // 情况1：当前事务创建的版本，总是可见
        if(xmin == xid && xmax == 0) return true;
        
        // 情况2：创建事务在快照中（开始时还活跃），不可见
        if(t.isInSnapshot(xmin)) return false;
        
        // 情况3：创建事务已提交，未被删除
        if(tm.isCommitted(xmin) && xmax == 0) return true;
        
        // 情况4：创建事务已提交，被当前事务删除
        if(tm.isCommitted(xmin) && xmax == xid) return true;
        
        // 情况5：创建事务已提交，被其他事务删除
        if(tm.isCommitted(xmin) && xmax != 0 && xmax != xid) {
            // 如果删除事务在快照中，则版本可见
            if(t.isInSnapshot(xmax)) return true;
            // 如果删除事务未提交，则版本可见
            if(!tm.isCommitted(xmax)) return true;
        }
        
        // 其他情况不可见
        return false;
    }

}
