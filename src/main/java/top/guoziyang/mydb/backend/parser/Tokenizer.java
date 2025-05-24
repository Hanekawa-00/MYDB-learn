package top.guoziyang.mydb.backend.parser;

import top.guoziyang.mydb.common.Error;

/**
 * SQL词法分析器 - 负责将SQL语句分解为词法单元(Token)
 *
 * 功能概述：
 * 词法分析器是SQL解析的第一阶段，负责将输入的字节流识别为有意义的词法单元，
 * 如关键字、标识符、操作符、字面量等。类似于MySQL中的sql_lex.cc文件，
 * 但MYDB实现了一个简化版本的词法分析器。
 *
 * 设计特点：
 * 1. 状态机设计：采用有限状态自动机的方式进行词法分析
 * 2. 向前看机制：支持peek()操作，可以预读下一个token而不消费它
 * 3. 错误恢复：提供错误位置标记功能，便于调试
 * 4. 简化的词法规则：只支持基本的SQL词法单元
 *
 * 支持的词法单元类型：
 * 1. 关键字：begin, commit, select, insert, update, delete等
 * 2. 标识符：表名、字段名等
 * 3. 字面量：字符串常量、数字常量
 * 4. 操作符：=, >, <, (, ), ,等
 * 5. 空白字符：空格、制表符、换行符（会被跳过）
 *
 * 与MySQL对比：
 * - MySQL词法分析器：支持完整的SQL词法，包括注释、十六进制数、科学计数法等
 * - MYDB词法分析器：只支持基本词法单元，易于理解和学习
 *
 * 使用方式：
 * 1. 创建Tokenizer实例并传入SQL语句字节数组
 * 2. 使用peek()预读下一个token
 * 3. 使用pop()消费当前token并移动到下一个
 * 4. 重复步骤2-3直到语句结束
 *
 * @author guoziyang
 * @see Parser SQL语法分析器
 */
public class Tokenizer {
    /** SQL语句的字节数组 */
    private byte[] stat;
    /** 当前读取位置 */
    private int pos;
    /** 当前缓存的token */
    private String currentToken;
    /** 是否需要刷新token（获取下一个token） */
    private boolean flushToken;
    /** 词法分析过程中的异常 */
    private Exception err;

    /**
     * 构造词法分析器
     *
     * @param stat SQL语句的字节数组
     */
    public Tokenizer(byte[] stat) {
        this.stat = stat;
        this.pos = 0;
        this.currentToken = "";
        this.flushToken = true;
    }

    /**
     * 预读下一个token，但不消费它
     *
     * 这是一个向前看(lookahead)机制，允许语法分析器
     * 在不改变当前状态的情况下检查下一个token。
     * 这对于LL(1)语法分析非常重要。
     *
     * @return 下一个token字符串，如果到达末尾返回空字符串
     * @throws Exception 词法分析异常
     */
    public String peek() throws Exception {
        if(err != null) {
            throw err;
        }
        if(flushToken) {
            String token = null;
            try {
                token = next();
            } catch(Exception e) {
                err = e;
                throw e;
            }
            currentToken = token;
            flushToken = false;
        }
        return currentToken;
    }

    /**
     * 消费当前token，移动到下一个token
     *
     * 调用此方法后，下次调用peek()将返回下一个token。
     * 这是词法分析器的状态推进机制。
     */
    public void pop() {
        flushToken = true;
    }

    public byte[] errStat() {
        byte[] res = new byte[stat.length+3];
        System.arraycopy(stat, 0, res, 0, pos);
        System.arraycopy("<< ".getBytes(), 0, res, pos, 3);
        System.arraycopy(stat, pos, res, pos+3, stat.length-pos);
        return res;
    }

    private void popByte() {
        pos ++;
        if(pos > stat.length) {
            pos = stat.length;
        }
    }

    private Byte peekByte() {
        if(pos == stat.length) {
            return null;
        }
        return stat[pos];
    }

    private String next() throws Exception {
        if(err != null) {
            throw err;
        }
        return nextMetaState();
    }

    private String nextMetaState() throws Exception {
        while(true) {
            Byte b = peekByte();
            if(b == null) {
                return "";
            }
            if(!isBlank(b)) {
                break;
            }
            popByte();
        }
        byte b = peekByte();
        if(isSymbol(b)) {
            popByte();
            return new String(new byte[]{b});
        } else if(b == '"' || b == '\'') {
            return nextQuoteState();
        } else if(isAlphaBeta(b) || isDigit(b)) {
            return nextTokenState();
        } else {
            err = Error.InvalidCommandException;
            throw err;
        }
    }

    private String nextTokenState() throws Exception {
        StringBuilder sb = new StringBuilder();
        while(true) {
            Byte b = peekByte();
            if(b == null || !(isAlphaBeta(b) || isDigit(b) || b == '_')) {
                if(b != null && isBlank(b)) {
                    popByte();
                }
                return sb.toString();
            }
            sb.append(new String(new byte[]{b}));
            popByte();
        }
    }

    static boolean isDigit(byte b) {
        return (b >= '0' && b <= '9');
    }

    static boolean isAlphaBeta(byte b) {
        return ((b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z'));
    }

    private String nextQuoteState() throws Exception {
        byte quote = peekByte();
        popByte();
        StringBuilder sb = new StringBuilder();
        while(true) {
            Byte b = peekByte();
            if(b == null) {
                err = Error.InvalidCommandException;
                throw err;
            }
            if(b == quote) {
                popByte();
                break;
            }
            sb.append(new String(new byte[]{b}));
            popByte();
        }
        return sb.toString();
    }

    static boolean isSymbol(byte b) {
        return (b == '>' || b == '<' || b == '=' || b == '*' ||
		b == ',' || b == '(' || b == ')');
    }

    static boolean isBlank(byte b) {
        return (b == '\n' || b == ' ' || b == '\t');
    }
}
