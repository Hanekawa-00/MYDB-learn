package top.guoziyang.mydb.backend.utils;

/**
 * ParseStringRes - 字符串解析结果类
 * 
 * 这个类用于封装字符串解析操作的结果，包含解析出的字符串内容和
 * 在原始字节数组中消耗的字节数。这种设计模式在数据库系统中非常常见，
 * 因为需要精确控制数据的读取位置。
 * 
 * 与MySQL的对比：
 * - MySQL在解析行数据时也需要跟踪字段的位置和长度
 * - InnoDB的行格式解析中，每个字段解析完成后都要更新读取位置
 * - VARCHAR字段解析时，MySQL也返回内容和消耗的字节数
 * - MYDB的ParseStringRes简化了这一概念，专门用于字符串解析
 * 
 * 使用场景：
 * 1. 从数据页中逐个解析字符串字段
 * 2. 解析日志记录中的变长字段
 * 3. 网络协议中的字符串数据解析
 * 4. 任何需要顺序解析多个字符串的场景
 * 
 * 设计优势：
 * - 一次解析操作返回两个重要信息：内容和位置
 * - 避免了调用方需要自己计算字节消耗的复杂性
 * - 支持连续解析多个字符串而不出现位置错误
 * 
 * 示例用法：
 * ```java
 * byte[] data = ...; // 包含多个字符串的字节数组
 * int offset = 0;
 * 
 * ParseStringRes result1 = Parser.parseString(Arrays.copyOfRange(data, offset, data.length));
 * String str1 = result1.str;
 * offset += result1.next;
 * 
 * ParseStringRes result2 = Parser.parseString(Arrays.copyOfRange(data, offset, data.length));
 * String str2 = result2.str;
 * offset += result2.next;
 * ```
 */
public class ParseStringRes {
    
    /**
     * 解析出的字符串内容
     * 
     * 存储从字节数组中解析出的实际字符串数据。
     * 这是解析操作的主要结果。
     */
    public String str;
    
    /**
     * 消耗的字节数
     * 
     * 表示在解析这个字符串时总共消耗了多少字节。
     * 包括：
     * - 长度前缀的字节数（通常是4字节）
     * - 字符串内容的实际字节数
     * 
     * 这个值对于连续解析非常重要，调用方可以使用这个值
     * 来更新读取位置，以便解析下一个字段。
     * 
     * 计算公式：next = 长度前缀字节数 + 字符串实际字节数
     * 在MYDB中：next = 4 + str.getBytes().length
     */
    public int next;

    /**
     * 构造函数 - 创建字符串解析结果
     * 
     * @param str  解析出的字符串内容
     * @param next 消耗的总字节数，用于更新下次读取的起始位置
     * 
     * 这个构造函数通常由Parser.parseString()方法调用，
     * 不建议在其他地方直接使用。
     */
    public ParseStringRes(String str, int next) {
        this.str = str;
        this.next = next;
    }
}
