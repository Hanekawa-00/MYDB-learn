package top.guoziyang.mydb.backend.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import top.guoziyang.mydb.backend.tbm.TableManager;
import top.guoziyang.mydb.transport.Encoder;
import top.guoziyang.mydb.transport.Package;
import top.guoziyang.mydb.transport.Packager;
import top.guoziyang.mydb.transport.Transporter;

/**
 * MYDB服务器主类 - 负责处理客户端连接和SQL请求
 *
 * 功能概述：
 * - 监听指定端口，接受客户端连接
 * - 使用线程池处理并发连接，提高服务器性能
 * - 为每个客户端连接创建独立的处理线程
 * - 协调传输层、编码层和执行层的工作
 *
 * 设计思想：
 * 类似于MySQL的Connection Manager，但简化了连接管理和认证流程。
 * MySQL使用更复杂的连接池和线程模型，而MYDB采用简单的线程池实现。
 *
 * 架构特点：
 * - 多线程并发处理：每个客户端连接独立处理
 * - 分层设计：将网络传输、数据编码、SQL执行分离
 * - 资源管理：自动清理连接和相关资源
 *
 * @author guoziyang
 * @see HandleSocket 客户端连接处理器
 * @see TableManager 表管理器，负责SQL执行
 * @see Packager 数据包装器，负责网络通信
 */
public class Server {
    /**
     * 服务器监听端口
     * 默认情况下MySQL使用3306端口，MYDB可以使用任意可用端口
     */
    private int port;
    
    /**
     * 表管理器引用
     * 负责处理所有SQL操作，相当于MySQL的SQL Layer
     */
    TableManager tbm;

    /**
     * 构造服务器实例
     *
     * @param port 监听端口号
     * @param tbm 表管理器实例，负责SQL执行
     */
    public Server(int port, TableManager tbm) {
        this.port = port;
        this.tbm = tbm;
    }

    /**
     * 启动服务器，开始监听客户端连接
     *
     * 工作流程：
     * 1. 创建ServerSocket监听指定端口
     * 2. 创建线程池处理并发连接
     * 3. 循环接受客户端连接
     * 4. 为每个连接分配处理线程
     *
     * 线程池配置说明：
     * - 核心线程数：10（类似MySQL的thread_cache_size）
     * - 最大线程数：20（类似MySQL的max_connections的简化版）
     * - 空闲超时：1秒（MySQL通常更长）
     * - 队列大小：100（缓冲等待处理的连接）
     * - 拒绝策略：CallerRunsPolicy（主线程处理，避免丢失连接）
     *
     * 与MySQL对比：
     * MySQL使用更复杂的连接管理，包括连接认证、SSL、字符集协商等。
     * MYDB简化了这些流程，直接进入SQL处理阶段。
     */
    public void start() {
        ServerSocket ss = null;
        try {
            // 创建服务器套接字，绑定到指定端口
            ss = new ServerSocket(port);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        
        System.out.println("Server listen to port: " + port);
        
        // 创建线程池处理客户端连接
        // 核心线程10个，最大20个，超时1秒，队列100个，使用CallerRunsPolicy策略
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(
            10, 20, 1L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        try {
            // 主循环：持续接受客户端连接
            while(true) {
                // 阻塞等待客户端连接
                Socket socket = ss.accept();
                
                // 创建连接处理器
                Runnable worker = new HandleSocket(socket, tbm);
                
                // 提交到线程池执行
                tpe.execute(worker);
            }
        } catch(IOException e) {
            e.printStackTrace();
        } finally {
            // 确保服务器套接字正确关闭
            try {
                ss.close();
            } catch (IOException ignored) {}
        }
    }
}

/**
 * 客户端连接处理器 - 处理单个客户端的所有请求
 *
 * 功能概述：
 * - 管理单个客户端连接的生命周期
 * - 接收、解析、执行SQL请求
 * - 返回执行结果给客户端
 * - 处理连接异常和资源清理
 *
 * 工作原理：
 * 1. 建立传输通道（Transporter）
 * 2. 设置编码器（Encoder）
 * 3. 创建包装器（Packager）处理数据包
 * 4. 创建执行器（Executor）处理SQL
 * 5. 循环处理客户端请求直到连接断开
 *
 * 与MySQL对比：
 * MySQL的每个连接都有更复杂的状态管理，包括：
 * - 连接认证和权限检查
 * - 字符集和时区设置
 * - 会话变量管理
 * - 事务状态跟踪
 * MYDB简化了这些特性，专注于SQL执行本身。
 *
 * @author guoziyang
 * @see Transporter 网络传输层
 * @see Encoder 数据编码层
 * @see Packager 数据包装层
 * @see Executor SQL执行器
 */
class HandleSocket implements Runnable {
    /**
     * 客户端连接套接字
     */
    private Socket socket;
    
    /**
     * 表管理器引用，用于SQL执行
     */
    private TableManager tbm;

    /**
     * 构造连接处理器
     *
     * @param socket 客户端连接套接字
     * @param tbm 表管理器实例
     */
    public HandleSocket(Socket socket, TableManager tbm) {
        this.socket = socket;
        this.tbm = tbm;
    }

    /**
     * 处理客户端连接的主要逻辑
     *
     * 执行流程：
     * 1. 获取客户端地址信息并记录
     * 2. 初始化通信组件（传输器、编码器、包装器）
     * 3. 创建SQL执行器
     * 4. 进入请求处理循环
     * 5. 清理资源并关闭连接
     *
     * 请求处理循环：
     * - 接收客户端数据包
     * - 提取SQL语句
     * - 执行SQL并获取结果
     * - 打包结果并发送给客户端
     * - 处理异常情况
     *
     * 错误处理：
     * - 网络异常：断开连接并清理资源
     * - SQL执行异常：返回错误信息给客户端
     * - 包装异常：记录日志并断开连接
     */
    @Override
    public void run() {
        // 获取客户端地址信息用于日志记录
        InetSocketAddress address = (InetSocketAddress)socket.getRemoteSocketAddress();
        System.out.println("Establish connection: " + address.getAddress().getHostAddress()+":"+address.getPort());
        
        Packager packager = null;
        try {
            // 初始化通信组件栈
            // 传输器：负责底层socket通信
            Transporter t = new Transporter(socket);
            
            // 编码器：负责数据序列化/反序列化
            Encoder e = new Encoder();
            
            // 包装器：组合传输器和编码器，提供高级数据包操作
            packager = new Packager(t, e);
        } catch(IOException e) {
            // 初始化失败，清理资源并退出
            e.printStackTrace();
            try {
                socket.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            return;
        }
        
        // 创建SQL执行器
        Executor exe = new Executor(tbm);
        
        // 主要的请求处理循环
        while(true) {
            Package pkg = null;
            try {
                // 接收客户端数据包
                // 这里会阻塞等待客户端发送数据
                pkg = packager.receive();
            } catch(Exception e) {
                // 接收异常通常表示连接断开，退出循环
                break;
            }
            
            // 提取SQL语句数据
            byte[] sql = pkg.getData();
            byte[] result = null;
            Exception e = null;
            
            try {
                // 执行SQL语句
                // 这里调用Executor处理具体的SQL逻辑
                result = exe.execute(sql);
            } catch (Exception e1) {
                // 捕获SQL执行异常，稍后会发送给客户端
                e = e1;
                e.printStackTrace();
            }
            
            // 创建响应数据包
            // 包含执行结果或异常信息
            pkg = new Package(result, e);
            
            try {
                // 发送响应给客户端
                packager.send(pkg);
            } catch (Exception e1) {
                // 发送失败，记录错误并断开连接
                e1.printStackTrace();
                break;
            }
        }
        
        // 清理资源
        // 关闭SQL执行器
        exe.close();
        
        try {
            // 关闭数据包装器（同时会关闭传输器和socket）
            packager.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}