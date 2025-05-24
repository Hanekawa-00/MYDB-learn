package top.guoziyang.mydb.transport;

import java.util.Arrays;

import com.google.common.primitives.Bytes;

import top.guoziyang.mydb.common.Error;

/**
 * 数据包编码器 - 负责Package对象与字节数组之间的序列化和反序列化
 *
 * 功能概述：
 * - 将Package对象编码为可传输的字节数组
 * - 将接收到的字节数组解码为Package对象
 * - 区分正常数据和错误信息的传输
 * - 实现简单而高效的二进制协议
 *
 * 设计思想：
 * 实现自定义的简单二进制协议：
 * - 使用状态标志位区分数据类型
 * - 最小化协议开销（仅1字节头部）
 * - 支持数据和异常的统一传输
 * - 保持协议的前向兼容性
 *
 * 协议格式：
 * - 字节0：状态标志（0=正常数据，1=错误信息）
 * - 字节1+：实际载荷数据
 *
 * 与标准协议对比：
 * 相比HTTP、MySQL等复杂协议，MYDB协议极其简化：
 * - 无需复杂的头部字段
 * - 无需长度字段（由底层传输层处理）
 * - 无需版本协商和能力交换
 * - 专注于基本的成功/失败状态传递
 *
 * @author guoziyang
 * @see Package 数据包封装类
 * @see Bytes Google Guava字节操作工具
 */
public class Encoder {

    /**
     * 将Package对象编码为字节数组
     *
     * 编码逻辑：
     * 1. 检查Package是否包含错误信息
     * 2. 如果有错误，编码为错误包格式
     * 3. 如果正常，编码为数据包格式
     *
     * 错误包格式：
     * - 第1字节：0x01（错误标志）
     * - 后续字节：错误消息的UTF-8编码
     *
     * 数据包格式：
     * - 第1字节：0x00（成功标志）
     * - 后续字节：实际数据载荷
     *
     * 错误处理策略：
     * - 优先使用异常的原始消息
     * - 异常消息为空时使用默认错误提示
     * - 确保错误信息能正确传递给客户端
     *
     * @param pkg 要编码的Package对象
     * @return 编码后的字节数组，格式为[标志位][载荷数据]
     */
    public byte[] encode(Package pkg) {
        // 检查Package是否包含错误信息
        if(pkg.getErr() != null) {
            Exception err = pkg.getErr();
            
            // 准备错误消息
            String msg = "Intern server error!";  // 默认错误消息
            if(err.getMessage() != null) {
                msg = err.getMessage();  // 使用异常的具体消息
            }
            
            // 编码错误包：[1][错误消息字节]
            return Bytes.concat(new byte[]{1}, msg.getBytes());
        } else {
            // 编码数据包：[0][数据字节]
            return Bytes.concat(new byte[]{0}, pkg.getData());
        }
    }

    /**
     * 将字节数组解码为Package对象
     *
     * 解码逻辑：
     * 1. 验证数据包最小长度
     * 2. 读取状态标志位
     * 3. 根据标志位解析载荷数据
     * 4. 构造相应的Package对象
     *
     * 状态标志含义：
     * - 0x00：正常数据包，载荷为业务数据
     * - 0x01：错误数据包，载荷为错误消息
     * - 其他：非法数据包，抛出异常
     *
     * 数据提取：
     * - 使用Arrays.copyOfRange提取载荷数据
     * - 避免直接操作原始数组，确保数据隔离
     * - 自动处理空载荷情况
     *
     * 异常重构：
     * - 将错误消息重新包装为RuntimeException
     * - 保持异常信息的完整性
     * - 确保异常能在客户端正确抛出
     *
     * @param data 要解码的字节数组
     * @return 解码后的Package对象
     * @throws Exception 数据格式无效时抛出异常
     */
    public Package decode(byte[] data) throws Exception {
        // 验证数据包最小长度（至少包含1字节标志位）
        if(data.length < 1) {
            throw Error.InvalidPkgDataException;
        }
        
        // 根据状态标志位进行解码
        if(data[0] == 0) {
            // 正常数据包：提取载荷数据，无错误信息
            return new Package(Arrays.copyOfRange(data, 1, data.length), null);
        } else if(data[0] == 1) {
            // 错误数据包：提取错误消息，无业务数据
            String errorMsg = new String(Arrays.copyOfRange(data, 1, data.length));
            return new Package(null, new RuntimeException(errorMsg));
        } else {
            // 非法数据包：未知的状态标志位
            throw Error.InvalidPkgDataException;
        }
    }
}
