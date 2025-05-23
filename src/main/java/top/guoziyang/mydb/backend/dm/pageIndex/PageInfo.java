package top.guoziyang.mydb.backend.dm.pageIndex;

/**
 * PageInfo - 页面信息类
 * 
 * PageInfo用于存储页面的基本信息，主要包括页面编号和可用空闲空间大小。
 * 这是PageIndex索引系统中的基本数据单元。
 * 
 * 与MySQL InnoDB的对比：
 * - MySQL InnoDB在段头和区描述符中维护类似信息
 * - InnoDB使用位图来表示页面的使用状态
 * - MYDB简化了这一设计，直接存储空闲空间大小
 * 
 * 设计思想：
 * 1. 轻量级的信息存储，只保留必要字段
 * 2. 配合PageIndex实现快速的空间查找
 * 3. 支持动态的空间管理和更新
 */
public class PageInfo {
    /**
     * 页面编号
     * 
     * 页面在数据库文件中的唯一标识符，从1开始递增。
     * 页面0通常保留给特殊用途（如MYDB的PageOne）。
     * 
     * 通过页面编号可以：
     * - 计算页面在文件中的物理偏移量
     * - 在PageCache中定位和缓存页面
     * - 构建数据项的UID（页面号+页面内偏移）
     */
    public int pgno;
    
    /**
     * 页面的空闲空间大小（字节）
     * 
     * 表示当前页面还有多少字节可以用于存储新数据。
     * 这个值会随着数据的插入和删除而动态变化。
     * 
     * 空闲空间的计算：
     * - 初始空闲空间 = 页面大小 - 页面头大小
     * - 插入数据后：空闲空间 -= 数据项大小
     * - 删除数据后：空闲空间 += 被删除数据项大小
     * 
     * 注意：
     * - 空闲空间可能不连续（存在碎片）
     * - MYDB简化处理，假设空闲空间是连续的
     * - 实际的MySQL InnoDB会处理页面碎片整理
     */
    public int freeSpace;

    /**
     * 构造函数 - 创建页面信息对象
     * 
     * @param pgno 页面编号
     * @param freeSpace 空闲空间大小（字节）
     * 
     * 创建一个新的页面信息对象，用于在PageIndex中跟踪页面状态。
     * 通常在以下情况下创建：
     * 1. 新分配页面时
     * 2. 页面空闲空间发生变化后重新索引时
     * 3. 数据库启动时重建页面索引
     */
    public PageInfo(int pgno, int freeSpace) {
        this.pgno = pgno;
        this.freeSpace = freeSpace;
    }
}
