package top.guoziyang.mydb.backend.dm.pageIndex;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import top.guoziyang.mydb.backend.dm.pageCache.PageCache;

/**
 * PageIndex - 页面空间索引管理器
 * 
 * PageIndex管理所有页面的空闲空间信息，帮助快速找到有足够空间的页面来插入新数据。
 * 这是数据库存储引擎的重要组件，直接影响空间利用率和插入性能。
 * 
 * 与MySQL InnoDB的对比：
 * - MySQL InnoDB使用复杂的段（Segment）和区（Extent）管理
 * - InnoDB有专门的FSEG（File Segment）头来管理空间
 * - MYDB简化了这一设计，使用空间分组的方式管理
 * 
 * 设计思想：
 * 1. 将页面按照空闲空间大小分成40个区间
 * 2. 每个区间维护一个页面列表
 * 3. 插入时从合适的区间开始查找可用页面
 * 4. 这样避免了全表扫描，提高了分配效率
 * 
 * 空间分组策略：
 * - 页面大小：8KB = 8192字节
 * - 分组阈值：8192 / 40 ≈ 204字节
 * - 分组0：0-203字节（几乎满的页面）
 * - 分组1：204-407字节（少量空闲）
 * - ...
 * - 分组39：7980-8191字节（大量空闲）
 * - 分组40：完全空闲的页面
 */
public class PageIndex {
    /**
     * 空间区间数量
     * 将页面空闲空间分为40个区间进行管理
     */
    private static final int INTERVALS_NO = 40;
    
    /**
     * 每个区间的空间阈值
     * 计算方式：页面大小 / 区间数 = 8192 / 40 ≈ 204字节
     */
    private static final int THRESHOLD = PageCache.PAGE_SIZE / INTERVALS_NO;

    /**
     * 并发控制锁
     * 保护页面索引数据结构的线程安全
     */
    private Lock lock;
    
    /**
     * 页面索引数据结构
     * lists[i] 存储空闲空间在第i个区间的所有页面信息
     * 
     * 索引结构：
     * lists[0]  : 0-203字节空闲空间的页面列表
     * lists[1]  : 204-407字节空闲空间的页面列表
     * ...
     * lists[40] : 完全空闲的页面列表
     */
    private List<PageInfo>[] lists;

    /**
     * 构造函数 - 初始化页面索引
     * 
     * 创建41个列表（0-40），每个列表对应一个空间区间。
     * 使用ReentrantLock提供线程安全的并发访问。
     */
    @SuppressWarnings("unchecked")
    public PageIndex() {
        lock = new ReentrantLock();
        lists = new List[INTERVALS_NO+1];  // 0-40共41个区间
        for (int i = 0; i < INTERVALS_NO+1; i ++) {
            lists[i] = new ArrayList<>();
        }
    }

    /**
     * 添加页面到索引中
     * 
     * @param pgno 页面编号
     * @param freeSpace 页面的空闲空间大小（字节）
     * 
     * 添加流程：
     * 1. 根据空闲空间大小计算所属区间
     * 2. 获取锁保证线程安全
     * 3. 将页面信息添加到对应区间的列表中
     * 
     * 区间计算：number = freeSpace / THRESHOLD
     * 例如：500字节空闲 -> 500/204 = 2 -> 放入lists[2]
     */
    public void add(int pgno, int freeSpace) {
        lock.lock();
        try {
            int number = freeSpace / THRESHOLD;
            lists[number].add(new PageInfo(pgno, freeSpace));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 选择合适的页面进行数据插入
     * 
     * @param spaceSize 需要的空间大小（字节）
     * @return 合适的页面信息，如果没有找到返回null
     * 
     * 选择策略：
     * 1. 根据需要的空间计算起始区间号
     * 2. 从起始区间开始向上查找（更大空间的区间）
     * 3. 找到第一个非空的区间，取出第一个页面
     * 4. 移除该页面信息（因为即将被使用，空闲空间会变化）
     * 
     * 算法优化：
     * - 优先选择空间刚好够用的页面，避免空间浪费
     * - 使用first-fit策略，快速找到可用页面
     * - 自动从索引中移除选中的页面，等待下次更新
     * 
     * 与MySQL的对比：
     * - MySQL InnoDB使用更复杂的best-fit算法
     * - MYDB使用简化的first-fit，更适合学习理解
     */
    public PageInfo select(int spaceSize) {
        lock.lock();
        try {
            // 计算需要的最小区间号
            int number = spaceSize / THRESHOLD;
            if(number < INTERVALS_NO) number ++;  // 向上取整，确保空间足够
            
            // 从计算出的区间开始向上查找
            while(number <= INTERVALS_NO) {
                if(lists[number].size() == 0) {
                    number ++;  // 当前区间没有可用页面，查找下一个区间
                    continue;
                }
                // 找到可用页面，取出并返回
                return lists[number].remove(0);  // 移除第一个页面
            }
            return null;  // 没有找到合适的页面
        } finally {
            lock.unlock();
        }
    }
}
