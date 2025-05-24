package top.guoziyang.mydb.client;

import java.util.Scanner;

/**
 * MYDB客户端交互式Shell - 提供命令行界面与数据库交互
 *
 * 功能概述：
 * - 提供交互式SQL执行环境
 * - 接收用户输入的SQL命令
 * - 显示SQL执行结果或错误信息
 * - 支持退出命令和资源清理
 *
 * 设计思想：
 * 类似于MySQL命令行客户端（mysql命令），但功能简化：
 * - MySQL客户端支持命令历史、自动补全、格式化输出等
 * - MYDB Shell专注于基本的SQL执行和结果显示
 * - MySQL客户端有复杂的连接参数和配置选项
 * - MYDB Shell假设连接已经建立，只处理SQL交互
 *
 * 用户体验特点：
 * - 简洁的提示符":>"
 * - 支持exit/quit命令退出
 * - 友好的错误信息显示
 * - 自动资源清理
 *
 * 架构位置：
 * 在客户端架构中作为用户界面层：
 * User -> Shell -> Client -> RoundTripper -> Network
 *
 * @author guoziyang
 * @see Client 客户端核心类
 */
public class Shell {
    /**
     * 客户端引用，负责与服务器通信
     */
    private Client client;

    /**
     * 构造交互式Shell
     *
     * @param client 已连接的客户端实例
     */
    public Shell(Client client) {
        this.client = client;
    }

    /**
     * 启动交互式Shell主循环
     *
     * 运行逻辑：
     * 1. 显示提示符等待用户输入
     * 2. 读取用户输入的SQL命令
     * 3. 检查是否为退出命令
     * 4. 执行SQL并显示结果
     * 5. 处理执行异常并显示错误信息
     * 6. 循环直到用户退出
     * 7. 清理资源
     *
     * 交互特性：
     * - 提示符":>"模仿数据库客户端的常见格式
     * - 支持"exit"和"quit"两种退出命令
     * - 每行输入作为一个完整的SQL语句处理
     * - 异常信息友好显示，不中断会话
     *
     * 与MySQL客户端对比：
     * MySQL客户端支持：
     * - 多行SQL语句（以分号结束）
     * - 命令历史记录（上下箭头）
 * - 自动补全（Tab键）
     * - 格式化的结果表格显示
     * - 各种客户端命令（\G, \s等）
     * MYDB Shell简化为最基本的交互模式
     *
     * 资源管理：
     * - 使用try-finally确保资源正确清理
     * - Scanner和Client都会在程序结束时关闭
     * - 即使发生异常也能正确清理资源
     */
    public void run() {
        // 创建标准输入扫描器
        Scanner sc = new Scanner(System.in);
        
        try {
            // 主交互循环
            while(true) {
                // 显示提示符，等待用户输入
                System.out.print(":> ");
                
                // 读取用户输入的一行命令
                String statStr = sc.nextLine();
                
                // 检查退出命令
                if("exit".equals(statStr) || "quit".equals(statStr)) {
                    break;
                }
                
                try {
                    // 执行SQL语句
                    // 将字符串转换为字节数组发送给服务器
                    byte[] res = client.execute(statStr.getBytes());
                    
                    // 显示执行结果
                    // 将服务器返回的字节数组转换为字符串显示
                    System.out.println(new String(res));
                } catch(Exception e) {
                    // 捕获并显示SQL执行异常
                    // 这包括网络异常、SQL语法错误、执行错误等
                    System.out.println(e.getMessage());
                }
            }
        } finally {
            // 确保资源正确清理
            // 关闭输入扫描器
            sc.close();
            
            // 关闭客户端连接
            client.close();
        }
    }
}
