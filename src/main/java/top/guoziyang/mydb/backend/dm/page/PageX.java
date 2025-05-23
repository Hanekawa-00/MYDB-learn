package top.guoziyang.mydb.backend.dm.page;

import java.util.Arrays;

import top.guoziyang.mydb.backend.dm.pageCache.PageCache;
import top.guoziyang.mydb.backend.utils.Parser;

/**
 * PageX - 普通页面管理器
 * 
 * PageX负责管理数据库中的普通数据页面，是数据存储的核心实现。
 * 
 * 页面结构设计：
 * ┌─────────────────┬─────────────────────────────────────────┐
 * │ FreeSpaceOffset │              Data Area                  │
 * │    (2字节)       │               (变长)                    │
 * └─────────────────┴─────────────────────────────────────────┘
 * 
 * 与MySQL InnoDB页面结构的对比：
 * 
 * | 组件 | MYDB PageX | MySQL InnoDB |
 * |------|------------|--------------|
 * | 页面头 | 2字节FSO | 38字节复杂页头 |
 * | 用户记录 | 简单连续存储 | 复杂记录格式+目录 |
 * | 空闲空间管理 | 单一偏移量 | 空闲空间链表 |
 * | 页面目录 | 无 | 槽目录+记录指针 |
 * | 页面尾 | 无 | 8字节校验信息 |
 * 
 * 设计优势：
 * 1. 简单高效：最小化页面头开销，只用2字节管理空闲空间
 * 2. 连续存储：数据项按顺序紧密排列，提高空间利用率
 * 3. 快速分配：通过FSO直接定位下一个可用位置
 * 4. 易于理解：结构简单，便于学习数据库原理
 * 
 * 空闲空间管理原理：
 * - FreeSpaceOffset指向第一个空闲字节的位置
 * - 新数据总是添加到FSO指向的位置
 * - 插入后FSO向后移动，指向新的空闲起始位置
 * - 这种设计类似于堆栈的顶指针，简单高效
 */
public class PageX {
    
    /**
     * 空闲空间偏移量字段的位置
     * FreeSpaceOffset存储在页面的最开始2字节
     */
    private static final short OF_FREE = 0;
    
    /**
     * 数据区域的起始位置
     * 数据区域从第2字节开始，跳过FSO字段
     */
    private static final short OF_DATA = 2;
    
    /**
     * 页面的最大可用空间
     * 计算方式：页面总大小 - 页面头大小
     * = 8192字节 - 2字节 = 8190字节
     */
    public static final int MAX_FREE_SPACE = PageCache.PAGE_SIZE - OF_DATA;

    /**
     * 初始化空白页面的原始数据
     * 
     * @return 初始化后的页面字节数组
     * 
     * 创建一个全新的空页面：
     * 1. 分配8KB的字节数组
     * 2. 设置FSO为OF_DATA(2)，表示数据从第2字节开始
     * 3. 其余空间全部可用于存储数据
     * 
     * 初始状态：
     * [0-1]: FSO = 2
     * [2-8191]: 全部空闲，可用于存储数据
     */
    public static byte[] initRaw() {
        byte[] raw = new byte[PageCache.PAGE_SIZE];
        setFSO(raw, OF_DATA);  // 设置初始FSO为2
        return raw;
    }

    /**
     * 在字节数组中设置空闲空间偏移量
     * 
     * @param raw 页面的原始字节数组
     * @param ofData 新的空闲空间偏移量
     * 
     * 将FSO值写入页面的前2字节，使用小端序存储。
     * 这个方法是页面空间管理的核心操作。
     */
    private static void setFSO(byte[] raw, short ofData) {
        System.arraycopy(Parser.short2Byte(ofData), 0, raw, OF_FREE, OF_DATA);
    }

    /**
     * 获取页面的空闲空间偏移量
     * 
     * @param pg 页面对象
     * @return 当前的空闲空间偏移量
     * 
     * 通过页面对象获取FSO，用于确定下一个数据应该存储的位置。
     * 这是插入操作的关键步骤。
     */
    public static short getFSO(Page pg) {
        return getFSO(pg.getData());
    }

    /**
     * 从字节数组中解析空闲空间偏移量
     * 
     * @param raw 页面的原始字节数组
     * @return 解析出的FSO值
     * 
     * 从页面的前2字节读取FSO值，使用小端序解析。
     */
    private static short getFSO(byte[] raw) {
        return Parser.parseShort(Arrays.copyOfRange(raw, 0, 2));
    }

    /**
     * 在页面中插入新数据
     * 
     * @param pg 目标页面
     * @param raw 要插入的数据
     * @return 数据在页面中的偏移量
     * 
     * 插入流程：
     * 1. 标记页面为脏页（需要写回磁盘）
     * 2. 获取当前FSO作为插入位置
     * 3. 将数据复制到FSO指向的位置
     * 4. 更新FSO，向后移动插入数据的长度
     * 5. 返回插入位置，用于构造数据项UID
     * 
     * 这种设计类似于：
     * - 内存分配器的bump pointer算法
     * - 日志文件的append-only模式
     * - 堆栈的push操作
     * 
     * 优势：
     * - O(1)时间复杂度，无需搜索空闲空间
     * - 数据连续存储，提高访问局部性
     * - 简单可靠，不会产生内部碎片
     */
    public static short insert(Page pg, byte[] raw) {
        pg.setDirty(true);  // 标记为脏页
        short offset = getFSO(pg.getData());  // 获取插入位置
        // 复制数据到指定位置
        System.arraycopy(raw, 0, pg.getData(), offset, raw.length);
        // 更新FSO，指向新的空闲位置
        setFSO(pg.getData(), (short)(offset + raw.length));
        return offset;  // 返回插入位置
    }

    /**
     * 获取页面的可用空闲空间大小
     * 
     * @param pg 页面对象
     * @return 剩余的可用空间字节数
     * 
     * 计算公式：剩余空间 = 页面总大小 - 当前FSO位置
     * 
     * 例如：
     * - 页面大小：8192字节
     * - 当前FSO：100
     * - 剩余空间：8192 - 100 = 8092字节
     * 
     * 这个值用于：
     * 1. PageIndex的空间分组管理
     * 2. 插入前的空间检查
     * 3. 页面使用率统计
     */
    public static int getFreeSpace(Page pg) {
        return PageCache.PAGE_SIZE - (int)getFSO(pg.getData());
    }

    /**
     * 恢复时的插入操作
     * 
     * @param pg 目标页面
     * @param raw 要插入的数据
     * @param offset 指定的插入位置
     * 
     * 与普通插入的区别：
     * 1. 使用指定的offset位置，而不是当前FSO
     * 2. 智能更新FSO：只有当插入位置超出当前FSO时才更新
     * 3. 用于崩溃恢复时重放日志操作
     * 
     * 恢复逻辑：
     * - 如果offset + raw.length > 当前FSO，说明这是新增数据
     * - 更新FSO到新位置，确保页面状态正确
     * - 如果offset + raw.length <= 当前FSO，说明是重复恢复
     * - 不更新FSO，避免破坏页面结构
     * 
     * 这种设计支持：
     * 1. 幂等的恢复操作（可以重复执行）
     * 2. 乱序的日志重放
     * 3. 部分失败的恢复重试
     */
    public static void recoverInsert(Page pg, byte[] raw, short offset) {
        pg.setDirty(true);  // 标记为脏页
        // 在指定位置插入数据
        System.arraycopy(raw, 0, pg.getData(), offset, raw.length);

        // 智能更新FSO：只有当插入位置超出当前边界时才更新
        short rawFSO = getFSO(pg.getData());
        if(rawFSO < offset + raw.length) {
            setFSO(pg.getData(), (short)(offset+raw.length));
        }
    }

    /**
     * 恢复时的更新操作
     * 
     * @param pg 目标页面
     * @param raw 新数据
     * @param offset 更新位置
     * 
     * 更新操作的特点：
     * 1. 在原位置覆盖数据，不改变数据项的大小
     * 2. 不需要更新FSO，因为页面的空间边界不变
     * 3. 只需标记脏页，确保修改会被写回磁盘
     * 
     * 使用场景：
     * - 崩溃恢复时重放UPDATE日志
     * - 事务回滚时恢复旧值
     * - 数据项内容的就地修改
     * 
     * 与recoverInsert的区别：
     * - recoverInsert可能扩展页面边界
     * - recoverUpdate只在现有边界内修改
     * - recoverUpdate不涉及FSO变化
     */
    public static void recoverUpdate(Page pg, byte[] raw, short offset) {
        pg.setDirty(true);  // 标记为脏页
        // 在指定位置覆盖数据，不更新FSO
        System.arraycopy(raw, 0, pg.getData(), offset, raw.length);
    }
}
