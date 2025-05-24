package top.guoziyang.mydb.transport;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

/**
 * 网络传输器 - 负责客户端与服务器之间的底层数据传输
 *
 * 功能概述：
 * - 封装TCP Socket的输入输出操作
 * - 提供字节数组的发送和接收功能
 * - 使用十六进制编码确保数据传输安全
 * - 采用行式协议简化解析
 *
 * 设计思想：
 * 实现简化的文本协议传输：
 * - 将二进制数据编码为十六进制字符串
 * - 每条消息占用一行，便于解析
 * - 使用缓冲I/O提高传输效率
 * - 自动处理连接状态管理
 *
 * 协议特点：
 * - 基于行的文本协议（Line-based Protocol）
 * - 十六进制编码避免二进制数据传输问题
 * - 简单易调试，可以用telnet等工具测试
 * - 自描述格式，便于协议扩展
 *
 * 与MySQL协议对比：
 * MySQL使用复杂的二进制协议：
 * - 包头包含长度和序列号
 * - 直接传输二进制数据，效率更高
 * - 支持压缩和SSL加密
 * - 有复杂的连接握手和认证流程
 * MYDB采用最简化的文本协议，易于理解和实现
 *
 * @author guoziyang
 * @see Socket Java标准Socket连接
 * @see BufferedReader 缓冲字符输入流
 * @see BufferedWriter 缓冲字符输出流
 * @see Hex Apache Commons编解码工具
 */
public class Transporter {
    /**
     * TCP连接套接字
     */
    private Socket socket;
    
    /**
     * 缓冲字符输入流，用于读取服务器数据
     */
    private BufferedReader reader;
    
    /**
     * 缓冲字符输出流，用于发送数据到服务器
     */
    private BufferedWriter writer;

    /**
     * 构造网络传输器
     *
     * 初始化过程：
     * 1. 保存Socket引用
     * 2. 创建缓冲输入流包装Socket输入流
     * 3. 创建缓冲输出流包装Socket输出流
     *
     * 缓冲流优势：
     * - 减少系统调用次数，提高I/O效率
     * - 提供按行读取功能（readLine）
     * - 自动处理字符编码转换
     * - 内置缓冲区减少网络往返次数
     *
     * @param socket 已建立连接的TCP套接字
     * @throws IOException Socket流获取失败
     */
    public Transporter(Socket socket) throws IOException {
        this.socket = socket;
        // 创建缓冲字符输入流，默认使用系统字符编码
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        // 创建缓冲字符输出流，默认使用系统字符编码
        this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
    }

    /**
     * 发送字节数组数据
     *
     * 发送流程：
     * 1. 将字节数组编码为十六进制字符串
     * 2. 在字符串末尾添加换行符（行分隔符）
     * 3. 写入缓冲输出流
     * 4. 刷新缓冲区确保数据立即发送
     *
     * 协议格式：
     * - 数据编码：十六进制字符串（如：48656C6C6F表示"Hello"）
     * - 行结束：\n字符标记消息结束
     * - 大小写：使用大写十六进制字符
     *
     * 错误处理：
     * - 编码异常：理论上不会发生（字节数组总是可编码的）
     * - 网络异常：IOException向上传播
     * - 缓冲区满：自动flush解决
     *
     * @param data 要发送的字节数组
     * @throws Exception 网络传输异常
     */
    public void send(byte[] data) throws Exception {
        // 将字节数组编码为十六进制字符串并添加换行符
        String raw = hexEncode(data);
        
        // 写入缓冲输出流
        writer.write(raw);
        
        // 立即刷新缓冲区，确保数据发送
        writer.flush();
    }

    /**
     * 接收字节数组数据
     *
     * 接收流程：
     * 1. 从缓冲输入流读取一行文本
     * 2. 检查连接状态（null表示连接已断开）
     * 3. 将十六进制字符串解码为字节数组
     * 4. 返回解码后的数据
     *
     * 连接管理：
     * - 读取到null：表示对端关闭连接
     * - 自动调用close()清理本地资源
     * - 确保连接状态一致性
     *
     * 协议解析：
     * - 按行读取：readLine()自动处理行分隔符
     * - 十六进制解码：将文本还原为原始字节数组
     * - 容错性：格式错误会抛出DecoderException
     *
     * @return 接收到的字节数组，如果连接断开则可能为null
     * @throws Exception 网络异常或解码异常
     */
    public byte[] receive() throws Exception {
        // 读取一行数据（阻塞直到收到完整行或连接断开）
        String line = reader.readLine();
        
        // 检查连接状态
        if(line == null) {
            // 对端关闭连接，清理本地资源
            close();
        }
        
        // 将十六进制字符串解码为字节数组
        return hexDecode(line);
    }

    /**
     * 关闭传输器，释放所有资源
     *
     * 关闭顺序：
     * 1. 关闭输出流（发送FIN到对端）
     * 2. 关闭输入流（停止接收数据）
     * 3. 关闭Socket（释放端口和连接）
     *
     * 资源管理：
     * - 确保所有流正确关闭
     * - 避免资源泄漏
     * - 通知对端连接结束
     *
     * 异常处理：
     * - IOException向上传播
     * - 调用者需要处理关闭异常
     * - 通常在finally块中调用
     *
     * @throws IOException 关闭资源时的IO异常
     */
    public void close() throws IOException {
        // 关闭输出流
        writer.close();
        // 关闭输入流
        reader.close();
        // 关闭Socket连接
        socket.close();
    }

    /**
     * 将字节数组编码为十六进制字符串
     *
     * 编码规则：
     * - 使用大写十六进制字符（A-F）
     * - 每个字节转换为2个十六进制字符
     * - 添加换行符作为行结束标记
     *
     * 示例：
     * - 输入：[72, 101, 108, 108, 111] ("Hello"的字节)
     * - 输出："48656C6C6F\n"
     *
     * @param buf 要编码的字节数组
     * @return 十六进制字符串加换行符
     */
    private String hexEncode(byte[] buf) {
        return Hex.encodeHexString(buf, true)+"\n";
    }

    /**
     * 将十六进制字符串解码为字节数组
     *
     * 解码规则：
     * - 支持大小写十六进制字符
     * - 忽略空白字符（空格、换行等）
     * - 字符数必须为偶数（每2个字符表示1个字节）
     *
     * 异常情况：
     * - 非法十六进制字符：抛出DecoderException
     * - 字符数为奇数：抛出DecoderException
     * - 空字符串：返回空字节数组
     *
     * @param buf 十六进制字符串
     * @return 解码后的字节数组
     * @throws DecoderException 解码失败异常
     */
    private byte[] hexDecode(String buf) throws DecoderException {
        return Hex.decodeHex(buf);
    }
}
