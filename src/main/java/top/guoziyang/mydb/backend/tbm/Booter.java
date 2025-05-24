package top.guoziyang.mydb.backend.tbm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.common.Error;

/**
 * Booter类 - 表管理启动器
 * 
 * Booter负责管理表管理模块的启动信息，主要功能是记录和维护第一个表的UID。
 * 它通过一个独立的文件来持久化这个关键信息，确保数据库重启后能正确恢复表链表结构。
 * 
 * 与MySQL的对比：
 * - MySQL使用多个系统表（如mysql.tables）来管理表的元信息
 * - MYDB简化了这个设计，使用一个简单的booter文件记录表链表的起始点
 * 
 * 文件结构：
 * - 主文件：path.bt，存储第一个表的UID
 * - 临时文件：path.bt_tmp，用于原子性更新操作
 * 
 * 核心功能：
 * 1. 创建和打开booter文件
 * 2. 读取和更新第一个表的UID
 * 3. 通过临时文件保证更新操作的原子性
 * 4. 处理异常情况和文件恢复
 */
public class Booter {
    
    /** booter文件后缀 */
    public static final String BOOTER_SUFFIX = ".bt";
    
    /** 临时文件后缀，用于原子性更新 */
    public static final String BOOTER_TMP_SUFFIX = ".bt_tmp";

    /** 文件路径（不含后缀） */
    String path;
    
    /** booter文件对象 */
    File file;

    /**
     * 创建新的booter文件
     * 
     * @param path 文件路径（不含后缀）
     * @return 新创建的Booter对象
     * 
     * 创建过程：
     * 1. 清理可能存在的坏临时文件
     * 2. 创建新的.bt文件
     * 3. 检查文件权限
     * 4. 返回Booter对象
     * 
     * 对应MySQL中初始化数据字典的过程
     */
    public static Booter create(String path) {
        removeBadTmp(path);
        File f = new File(path+BOOTER_SUFFIX);
        try {
            if(!f.createNewFile()) {
                Panic.panic(Error.FileExistsException);
            }
        } catch (Exception e) {
            Panic.panic(e);
        }
        if(!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }
        return new Booter(path, f);
    }

    /**
     * 打开已存在的booter文件
     * 
     * @param path 文件路径（不含后缀）
     * @return 打开的Booter对象
     * 
     * 打开过程：
     * 1. 清理可能存在的坏临时文件
     * 2. 检查.bt文件是否存在
     * 3. 检查文件权限
     * 4. 返回Booter对象
     * 
     * 对应MySQL中加载现有数据字典的过程
     */
    public static Booter open(String path) {
        removeBadTmp(path);
        File f = new File(path+BOOTER_SUFFIX);
        if(!f.exists()) {
            Panic.panic(Error.FileNotExistsException);
        }
        if(!f.canRead() || !f.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }
        return new Booter(path, f);
    }

    /**
     * 清理坏的临时文件
     * 
     * @param path 文件路径
     * 
     * 清理原因：
     * 1. 如果上次更新过程中发生崩溃，可能留下临时文件
     * 2. 临时文件的存在表明更新可能没有完成
     * 3. 删除临时文件，使用原文件作为正确的数据源
     * 
     * 这是一种简单的崩溃恢复机制
     */
    private static void removeBadTmp(String path) {
        new File(path+BOOTER_TMP_SUFFIX).delete();
    }

    /**
     * 私有构造函数
     * 
     * @param path 文件路径
     * @param file 文件对象
     */
    private Booter(String path, File file) {
        this.path = path;
        this.file = file;
    }

    /**
     * 加载booter文件中的数据
     * 
     * @return 文件内容的字节数组
     * 
     * 加载的数据通常包含：
     * - 第一个表的UID（8字节long值）
     * - 可能还包含其他表管理的元信息
     * 
     * 这个方法在TableManager启动时被调用，
     * 用于重建表的链表结构
     */
    public byte[] load() {
        byte[] buf = null;
        try {
            buf = Files.readAllBytes(file.toPath());
        } catch (IOException e) {
            Panic.panic(e);
        }
        return buf;
    }

    /**
     * 原子性更新booter文件
     * 
     * @param data 要写入的新数据
     * 
     * 更新过程（原子性保证）：
     * 1. 创建临时文件（.bt_tmp）
     * 2. 将新数据写入临时文件
     * 3. 刷新到磁盘（确保数据真正写入）
     * 4. 原子性地将临时文件重命名为正式文件
     * 5. 更新文件引用
     * 
     * 原子性保证：
     * - 文件系统的rename操作通常是原子的
     * - 即使在更新过程中崩溃，也不会损坏原有数据
     * - 重启后通过removeBadTmp清理临时文件
     * 
     * 对应MySQL中更新系统表的原子性机制
     */
    public void update(byte[] data) {
        File tmp = new File(path + BOOTER_TMP_SUFFIX);
        try {
            tmp.createNewFile();
        } catch (Exception e) {
            Panic.panic(e);
        }
        if(!tmp.canRead() || !tmp.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }
        
        // 写入数据到临时文件
        try(FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.flush(); // 确保数据写入磁盘
        } catch(IOException e) {
            Panic.panic(e);
        }
        
        // 原子性重命名
        try {
            Files.move(tmp.toPath(), new File(path+BOOTER_SUFFIX).toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException e) {
            Panic.panic(e);
        }
        
        // 更新文件引用
        file = new File(path+BOOTER_SUFFIX);
        if(!file.canRead() || !file.canWrite()) {
            Panic.panic(Error.FileCannotRWException);
        }
    }
}
