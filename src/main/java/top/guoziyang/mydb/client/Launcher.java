package top.guoziyang.mydb.client;

import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

import top.guoziyang.mydb.transport.Encoder;
import top.guoziyang.mydb.transport.Packager;
import top.guoziyang.mydb.transport.Transporter;

/**
 * MYDB客户端启动器 - 客户端应用程序的入口点
 *
 * 功能概述：
 * - 启动MYDB客户端应用程序
 * - 建立与服务器的网络连接
 * - 初始化通信组件栈
 * - 启动交互式Shell界面
 *
 * 设计思想：
 * 实现客户端的引导启动模式：
 * - 类似于MySQL客户端的mysql命令行工具
 * - 硬编码连接参数，简化配置
 * - 自动组装所有必需的组件
 * - 提供即开即用的用户体验
 *
 * 组件初始化顺序：
 * Socket -> Transporter -> Encoder -> Packager -> Client -> Shell
 *
 * 与MySQL客户端对比：
 * MySQL客户端启动支持：
 * - 命令行参数配置（主机、端口、用户名、密码等）
 * - 配置文件读取（my.cnf）
 * - 环境变量支持
 * - SSL配置和认证选项
 * MYDB客户端采用最简配置，专注于核心功能演示
 *
 * @author guoziyang
 * @see Socket Java标准网络连接
 * @see Transporter 网络传输层
 * @see Encoder 数据编码层
 * @see Packager 数据包装层
 * @see Client 客户端核心
 * @see Shell 交互界面
 */
public class Launcher {
    /**
     * 客户端程序主入口
     *
     * 启动流程：
     * 1. 建立到MYDB服务器的TCP连接
     * 2. 创建数据编码器
     * 3. 创建网络传输器
     * 4. 组装数据包装器
     * 5. 创建客户端实例
     * 6. 启动交互式Shell
     *
     * 连接配置：
     * - 服务器地址：127.0.0.1（本地回环地址）
     * - 服务器端口：9999（MYDB默认端口）
     * - 连接类型：TCP套接字连接
     * - 通信模式：同步阻塞
     *
     * 错误处理：
     * - UnknownHostException：主机不可达或DNS解析失败
     * - IOException：网络连接失败或通信异常
     * - 异常直接抛出，程序终止
     *
     * 改进建议：
     * 实际生产环境中可以增加：
     * - 命令行参数解析（主机、端口配置）
     * - 连接超时和重试机制
     * - 更友好的错误提示
     * - 配置文件支持
     *
     * @param args 命令行参数（当前未使用）
     * @throws UnknownHostException 主机不可达异常
     * @throws IOException 网络IO异常
     */
    public static void main(String[] args) throws UnknownHostException, IOException {
        // 建立到MYDB服务器的TCP连接
        // 连接本机9999端口，这是MYDB服务器的默认监听端口
        Socket socket = new Socket("127.0.0.1", 9999);
        
        // 创建数据编码器
        // 负责将Java对象序列化为字节流，以及反向的反序列化
        Encoder e = new Encoder();
        
        // 创建网络传输器
        // 封装Socket，提供字节流的发送和接收功能
        Transporter t = new Transporter(socket);
        
        // 创建数据包装器
        // 组合传输器和编码器，提供数据包级别的通信接口
        Packager packager = new Packager(t, e);

        // 创建客户端实例
        // 封装与服务器的通信逻辑，提供SQL执行接口
        Client client = new Client(packager);
        
        // 创建交互式Shell
        // 提供用户友好的命令行界面
        Shell shell = new Shell(client);
        
        // 启动Shell，进入交互模式
        // 用户可以输入SQL命令与数据库交互
        shell.run();
    }
}
