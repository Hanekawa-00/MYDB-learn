package top.guoziyang.mydb.backend.common;

/**
 * SubArray - 数据分片类
 * 
 * 这个类用于表示一个字节数组的子集合，避免频繁的数组拷贝操作，提高内存使用效率。
 * 
 * 与MySQL的对比：
 * - MySQL中类似的概念是数据页中的记录片段，通过偏移量来定位具体的数据
 * - InnoDB存储引擎中，页面内的数据通过偏移量进行定位，避免不必要的数据拷贝
 * - MYDB的SubArray简化了这一概念，提供了一个轻量级的数据分片表示
 * 
 * 设计优势：
 * 1. 内存效率：不需要创建新的字节数组，只是引用原始数组的一部分
 * 2. 性能优化：避免了大量的数组拷贝操作
 * 3. 灵活性：可以方便地表示任意范围的数据片段
 */
public class SubArray {
    /**
     * 原始字节数组的引用
     * 存储实际的数据内容
     */
    public byte[] raw;
    
    /**
     * 子数组的起始位置（包含）
     * 表示在原始数组中的起始索引
     */
    public int start;
    
    /**
     * 子数组的结束位置（不包含）
     * 表示在原始数组中的结束索引
     */
    public int end;

    /**
     * 构造函数 - 创建数据分片
     * 
     * @param raw   原始字节数组
     * @param start 起始位置（包含）
     * @param end   结束位置（不包含）
     * 
     * 注意：这种设计类似于Java的String.substring()方法，
     * 采用左闭右开区间 [start, end)
     */
    public SubArray(byte[] raw, int start, int end) {
        this.raw = raw;
        this.start = start;
        this.end = end;
    }
}
