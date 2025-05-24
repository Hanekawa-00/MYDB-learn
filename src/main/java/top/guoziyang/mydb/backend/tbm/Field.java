package top.guoziyang.mydb.backend.tbm;

import java.util.Arrays;
import java.util.List;

import com.google.common.primitives.Bytes;

import top.guoziyang.mydb.backend.im.BPlusTree;
import top.guoziyang.mydb.backend.parser.statement.SingleExpression;
import top.guoziyang.mydb.backend.tm.TransactionManagerImpl;
import top.guoziyang.mydb.backend.utils.Panic;
import top.guoziyang.mydb.backend.utils.ParseStringRes;
import top.guoziyang.mydb.backend.utils.Parser;
import top.guoziyang.mydb.common.Error;

/**
 * Field类 - 数据库字段的核心表示
 * 
 * Field代表数据库表中的一个字段（列），包含字段的所有元信息和操作方法。
 * 这是MYDB表管理模块的核心组件之一。
 * 
 * 与MySQL的对比：
 * - MySQL中的字段信息存储在information_schema和.frm文件中
 * - MYDB简化了字段信息，但保留了核心功能：类型、索引、存储格式
 * 
 * 持久化格式：
 * [FieldName][TypeName][IndexUid]
 * - FieldName: 字段名称的字符串
 * - TypeName: 字段类型（int32/int64/string）
 * - IndexUid: 索引的UID，如果无索引则为0
 * 
 * 核心功能：
 * 1. 字段元信息管理（名称、类型、索引）
 * 2. 数据类型转换和序列化
 * 3. 索引操作（插入、查询）
 * 4. 表达式计算（WHERE条件）
 */
public class Field {
    
    /**
     * 字段的唯一标识符
     * 在版本管理系统中用于定位字段的元信息
     */
    long uid;
    
    /**
     * 所属的表对象
     * 用于访问表管理器和其他相关资源
     */
    private Table tb;
    
    /**
     * 字段名称
     * 对应SQL中的列名，如 "id", "name", "age" 等
     */
    String fieldName;
    
    /**
     * 字段类型
     * 支持的类型：
     * - "int32": 32位整数
     * - "int64": 64位长整数  
     * - "string": 字符串类型
     */
    String fieldType;
    
    /**
     * 索引UID
     * 如果字段有索引，存储B+树索引的UID
     * 如果为0，表示该字段没有索引
     */
    private long index;
    
    /**
     * B+树索引对象
     * 当字段有索引时，用于快速查找和范围查询
     * 对应MySQL中的二级索引
     */
    private BPlusTree bt;

    /**
     * 从存储中加载已存在的字段
     * 
     * @param tb 所属表对象
     * @param uid 字段的UID
     * @return 加载的Field对象
     * 
     * 加载过程：
     * 1. 从版本管理器读取字段的原始数据
     * 2. 解析字段名称、类型和索引信息
     * 3. 如果有索引，加载对应的B+树
     * 
     * 对应MySQL中从.frm文件或information_schema加载字段定义
     */
    public static Field loadField(Table tb, long uid) {
        byte[] raw = null;
        try {
            // 使用超级事务读取字段元信息
            raw = ((TableManagerImpl)tb.tbm).vm.read(TransactionManagerImpl.SUPER_XID, uid);
        } catch (Exception e) {
            Panic.panic(e);
        }
        assert raw != null;
        return new Field(uid, tb).parseSelf(raw);
    }

    /**
     * 构造函数 - 用于加载已存在的字段
     * 
     * @param uid 字段UID
     * @param tb 所属表
     */
    public Field(long uid, Table tb) {
        this.uid = uid;
        this.tb = tb;
    }

    /**
     * 构造函数 - 用于创建新字段
     * 
     * @param tb 所属表
     * @param fieldName 字段名称
     * @param fieldType 字段类型
     * @param index 索引UID
     */
    public Field(Table tb, String fieldName, String fieldType, long index) {
        this.tb = tb;
        this.fieldName = fieldName;
        this.fieldType = fieldType;
        this.index = index;
    }

    /**
     * 解析字段的原始数据
     * 
     * @param raw 从存储中读取的原始字节数组
     * @return 当前Field对象（链式调用）
     * 
     * 解析格式：[FieldName][TypeName][IndexUid]
     * 解析过程：
     * 1. 解析字段名称字符串
     * 2. 解析字段类型字符串  
     * 3. 解析索引UID（8字节长整数）
     * 4. 如果有索引，加载B+树对象
     */
    private Field parseSelf(byte[] raw) {
        int position = 0;
        
        // 解析字段名称
        ParseStringRes res = Parser.parseString(raw);
        fieldName = res.str;
        position += res.next;
        
        // 解析字段类型
        res = Parser.parseString(Arrays.copyOfRange(raw, position, raw.length));
        fieldType = res.str;
        position += res.next;
        
        // 解析索引UID
        this.index = Parser.parseLong(Arrays.copyOfRange(raw, position, position+8));
        
        // 如果有索引，加载B+树
        if(index != 0) {
            try {
                bt = BPlusTree.load(index, ((TableManagerImpl)tb.tbm).dm);
            } catch(Exception e) {
                Panic.panic(e);
            }
        }
        return this;
    }

    /**
     * 创建新的字段
     * 
     * @param tb 所属表
     * @param xid 事务ID
     * @param fieldName 字段名称
     * @param fieldType 字段类型
     * @param indexed 是否创建索引
     * @return 新创建的Field对象
     * @throws Exception 类型检查失败或存储失败
     * 
     * 创建过程：
     * 1. 检查字段类型是否有效
     * 2. 如果需要索引，创建B+树索引
     * 3. 持久化字段信息到存储
     * 
     * 对应MySQL中的CREATE TABLE或ALTER TABLE ADD COLUMN
     */
    public static Field createField(Table tb, long xid, String fieldName, String fieldType, boolean indexed) throws Exception {
        typeCheck(fieldType);
        Field f = new Field(tb, fieldName, fieldType, 0);
        
        // 如果需要索引，创建B+树
        if(indexed) {
            long index = BPlusTree.create(((TableManagerImpl)tb.tbm).dm);
            BPlusTree bt = BPlusTree.load(index, ((TableManagerImpl)tb.tbm).dm);
            f.index = index;
            f.bt = bt;
        }
        
        // 持久化字段信息
        f.persistSelf(xid);
        return f;
    }

    /**
     * 持久化字段信息到存储
     * 
     * @param xid 事务ID
     * @throws Exception 存储失败
     * 
     * 持久化过程：
     * 1. 将字段名称、类型、索引UID序列化为字节数组
     * 2. 通过版本管理器插入到存储中
     * 3. 获得字段的UID
     */
    private void persistSelf(long xid) throws Exception {
        byte[] nameRaw = Parser.string2Byte(fieldName);
        byte[] typeRaw = Parser.string2Byte(fieldType);
        byte[] indexRaw = Parser.long2Byte(index);
        
        // 将所有信息连接成一个字节数组并插入
        this.uid = ((TableManagerImpl)tb.tbm).vm.insert(xid, Bytes.concat(nameRaw, typeRaw, indexRaw));
    }

    /**
     * 检查字段类型是否有效
     * 
     * @param fieldType 要检查的字段类型
     * @throws Exception 如果类型无效
     * 
     * MYDB支持的类型：
     * - int32: 32位有符号整数
     * - int64: 64位有符号长整数
     * - string: 变长字符串
     */
    private static void typeCheck(String fieldType) throws Exception {
        if(!"int32".equals(fieldType) && !"int64".equals(fieldType) && !"string".equals(fieldType)) {
            throw Error.InvalidFieldException;
        }
    }

    /**
     * 检查字段是否有索引
     * 
     * @return true表示有索引，false表示无索引
     */
    public boolean isIndexed() {
        return index != 0;
    }

    /**
     * 向字段的索引中插入键值对
     * 
     * @param key 索引键（字段值）
     * @param uid 记录的UID
     * @throws Exception 插入失败
     * 
     * 插入过程：
     * 1. 将字段值转换为UID格式（用于B+树存储）
     * 2. 向B+树索引插入键值对
     * 
     * 对应MySQL中向二级索引插入记录
     */
    public void insert(Object key, long uid) throws Exception {
        long uKey = value2Uid(key);
        bt.insert(uKey, uid);
    }

    /**
     * 在索引中进行范围查询
     * 
     * @param left 左边界（包含）
     * @param right 右边界（包含）
     * @return 满足条件的记录UID列表
     * @throws Exception 查询失败
     * 
     * 对应MySQL中的索引范围扫描
     */
    public List<Long> search(long left, long right) throws Exception {
        return bt.searchRange(left, right);
    }

    /**
     * 将字符串值转换为对应的Java对象
     * 
     * @param str 字符串形式的值
     * @return 转换后的Java对象
     * 
     * 转换规则：
     * - int32 → Integer
     * - int64 → Long  
     * - string → String
     */
    public Object string2Value(String str) {
        switch(fieldType) {
            case "int32":
                return Integer.parseInt(str);
            case "int64":
                return Long.parseLong(str);
            case "string":
                return str;
        }
        return null;
    }

    /**
     * 将字段值转换为UID格式（用于索引存储）
     * 
     * @param key 字段值对象
     * @return UID格式的长整数
     * 
     * 转换规则：
     * - string: 通过哈希函数转换为long
     * - int32: 直接转换为long
     * - int64: 保持不变
     * 
     * 这个转换保证了不同类型的值都能在B+树中统一存储
     */
    public long value2Uid(Object key) {
        long uid = 0;
        switch(fieldType) {
            case "string":
                uid = Parser.str2Uid((String)key);
                break;
            case "int32":
                int uint = (int)key;
                return (long)uint;
            case "int64":
                uid = (long)key;
                break;
        }
        return uid;
    }

    /**
     * 将字段值序列化为字节数组
     * 
     * @param v 字段值对象
     * @return 序列化后的字节数组
     * 
     * 用于将字段值存储到记录中，不同类型有不同的序列化格式
     */
    public byte[] value2Raw(Object v) {
        byte[] raw = null;
        switch(fieldType) {
            case "int32":
                raw = Parser.int2Byte((int)v);
                break;
            case "int64":
                raw = Parser.long2Byte((long)v);
                break;
            case "string":
                raw = Parser.string2Byte((String)v);
                break;
        }
        return raw;
    }

    /**
     * 解析值结果的内部类
     * 
     * 用于从字节数组中解析字段值时返回解析结果和位移量
     */
    class ParseValueRes {
        /** 解析出的值对象 */
        Object v;
        /** 解析消耗的字节数（用于解析下一个字段） */
        int shift;
    }

    /**
     * 从字节数组中解析字段值
     * 
     * @param raw 原始字节数组
     * @return 解析结果，包含值和消耗的字节数
     * 
     * 根据字段类型从字节数组的开头解析对应的值，
     * 并返回解析消耗的字节数，用于解析记录中的下一个字段
     */
    public ParseValueRes parserValue(byte[] raw) {
        ParseValueRes res = new ParseValueRes();
        switch(fieldType) {
            case "int32":
                res.v = Parser.parseInt(Arrays.copyOf(raw, 4));
                res.shift = 4;
                break;
            case "int64":
                res.v = Parser.parseLong(Arrays.copyOf(raw, 8));
                res.shift = 8;
                break;
            case "string":
                ParseStringRes r = Parser.parseString(raw);
                res.v = r.str;
                res.shift = r.next;
                break;
        }
        return res;
    }

    /**
     * 将字段值转换为可打印的字符串
     * 
     * @param v 字段值对象
     * @return 字符串表示
     * 
     * 用于SELECT查询结果的显示
     */
    public String printValue(Object v) {
        String str = null;
        switch(fieldType) {
            case "int32":
                str = String.valueOf((int)v);
                break;
            case "int64":
                str = String.valueOf((long)v);
                break;
            case "string":
                str = (String)v;
                break;
        }
        return str;
    }

    /**
     * 字段信息的字符串表示
     * 
     * @return 格式化的字段信息字符串
     * 
     * 格式：(字段名, 字段类型, 索引状态)
     * 例如：(id, int64, Index) 或 (name, string, NoIndex)
     */
    @Override
    public String toString() {
        return new StringBuilder("(")
            .append(fieldName)
            .append(", ")
            .append(fieldType)
            .append(index!=0?", Index":", NoIndex")
            .append(")")
            .toString();
    }

    /**
     * 计算单个表达式的结果范围
     * 
     * @param exp 单个比较表达式（如 age > 18）
     * @return 计算结果，包含满足条件的UID范围
     * @throws Exception 表达式无效或计算失败
     * 
     * 支持的操作符：
     * - "<": 小于，范围是 [0, value-1]
     * - "=": 等于，范围是 [value, value]  
     * - ">": 大于，范围是 [value+1, MAX_VALUE]
     * 
     * 这个方法是WHERE子句处理的核心，将SQL条件转换为索引扫描范围
     * 对应MySQL查询优化器中的范围分析
     */
    public FieldCalRes calExp(SingleExpression exp) throws Exception {
        Object v = null;
        FieldCalRes res = new FieldCalRes();
        
        switch(exp.compareOp) {
            case "<":
                res.left = 0;
                v = string2Value(exp.value);
                res.right = value2Uid(v);
                if(res.right > 0) {
                    res.right --; // 不包含边界值
                }
                break;
            case "=":
                v = string2Value(exp.value);
                res.left = value2Uid(v);
                res.right = res.left; // 精确匹配
                break;
            case ">":
                res.right = Long.MAX_VALUE;
                v = string2Value(exp.value);
                res.left = value2Uid(v) + 1; // 不包含边界值
                break;
        }
        return res;
    }
}
