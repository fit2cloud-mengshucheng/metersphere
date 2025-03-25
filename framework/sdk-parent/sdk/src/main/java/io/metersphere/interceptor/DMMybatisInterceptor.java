package io.metersphere.interceptor;


import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.util.JdbcConstants;
import com.alibaba.excel.util.StringUtils;
import io.metersphere.utils.LoggerUtil;
import jakarta.annotation.PostConstruct;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @description: mybatis sql拦截器，作用有三种：1.处理非法字符 2.处理boolean参数 3.处理插入主键自增问题
 */
@Intercepts({@Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})})
@Component
public class DMMybatisInterceptor implements Interceptor {

    /**
     * 正则不区分大小写匹配"=true",包括=中间有空白字符
     */
    private static final String SQL_TRUE_PARAM_REG = "(?i)=\\s*true";
    /**
     * 正则不区分大小写匹配"=false",包括=中间有空白字符
     */
    private static final String SQL_FALSE_PARAM_REG = "(?i)=\\s*false";
    /**
     * 正则匹配insert into和merge into语句
     */
    private static final String SQL_INSERT_REG = "(?i)(insert into|merge into)\\s+([^\\s]+)";
    /**
     * 开启insert开关
     */
    private static final String IDENTITY_INSERT_ON = "SET IDENTITY_INSERT MY_DB.%s ON;";
    /**
     * 无主键的关联表
     */
    private Set<String> identityInsertExcludeTableSet;

    //配置，可配置库中无自增键的表，把它过滤掉，因为这些表没有主键自增问题
    @Value("${mybatis.insert.exclude.table:t_no_identity_table_test}")
    private String excludeTable;

    @PostConstruct
    public void initExcludeTableSet() {
        //加载时将excludeTable的表放入HaseSet,提升后续匹配效率
        identityInsertExcludeTableSet = Arrays.stream(excludeTable.split(",")).collect(Collectors.toSet());
    }

    @Override
    public Object intercept(Invocation invocation) throws Exception {
        StatementHandler statementHandler = (StatementHandler) invocation.getTarget();
        MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        MappedStatement mappedStatement = (MappedStatement) metaObject.getValue("delegate.mappedStatement");
        SqlCommandType sqlCommandType = mappedStatement.getSqlCommandType();
        BoundSql boundSql = statementHandler.getBoundSql();

        String sql = null;
        try {
            sql = this.convertMySQLToDM(boundSql.getSql());
        } catch (Exception e) {
            sql = boundSql.getSql();
        }
        if (sqlCommandType == SqlCommandType.SELECT) {
            sql = this.handleBooleanParam(sql);
        }

        if (sqlCommandType == SqlCommandType.INSERT) {
            sql = this.handleIdentityInsertOn(sql);
        }
        metaObject.setValue("delegate.boundSql.sql", sql);

        try {
            return invocation.proceed();
        } catch (InvocationTargetException e) {
            LoggerUtil.error("异常sql：" + sql + " \n异常原因：" + e.getTargetException());
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            LoggerUtil.error("异常sql：" + sql + " \n异常原因：" + e.getMessage());
            throw new RuntimeException(e);
        }
    }

    /**
     * 处理非法字符
     *
     * @param sql
     * @return: java.lang.String
     */
    private String convertMySQLToDM(String sql) {

        SQLUtils.FormatOption options = new SQLUtils.FormatOption();
        options.setPrettyFormat(true);    // 启用缩进
        options.setUppCase(true);          // 关键字大写
        // 1. 解析 MySQL SQL
        String standardSQL = SQLUtils.format(sql, JdbcConstants.MYSQL, options);

        // 3. 自定义规则替换（覆盖常见差异）
        String dmSQL = standardSQL;

        // --------------------------
        // 规则 1: 分页语法 (LIMIT -> ROWNUM)
        // --------------------------
        // 处理 LIMIT N
        dmSQL = dmSQL.replaceAll("(?i)LIMIT\\s+(\\d+)", "WHERE ROWNUM <= $1");
        // 处理 LIMIT M, N -> 需要转换为达梦的嵌套查询（此处简单示例）
        dmSQL = Pattern.compile("(?i)LIMIT\\s+(\\d+)\\s*,\\s*(\\d+)").matcher(dmSQL).replaceAll("WHERE ROWNUM BETWEEN $1 AND $2");

        // --------------------------
        // 规则 2: 自增列 (AUTO_INCREMENT -> IDENTITY)
        // --------------------------
        dmSQL = dmSQL.replaceAll("(?i)AUTO_INCREMENT", "IDENTITY(1,1)");

        // --------------------------
        // 规则 3: 数据类型转换
        // --------------------------
        dmSQL = dmSQL.replaceAll("(?i)TINYINT\\(\\d+\\)", "SMALLINT"); // TINYINT -> SMALLINT
        dmSQL = dmSQL.replaceAll("(?i)DATETIME", "TIMESTAMP");        // DATETIME -> TIMESTAMP
        dmSQL = dmSQL.replaceAll("(?i)INT\\(\\d+\\)", "INT");         // INT(11) -> INT

        // --------------------------
        // 规则 4: 函数替换
        // --------------------------
        dmSQL = dmSQL.replaceAll("(?i)NOW\\(\\)", "SYSDATE");        // NOW() -> SYSDATE
        dmSQL = dmSQL.replaceAll("(?i)DATABASE\\(\\)", "CURRENT_SCHEMA()"); // DATABASE() -> CURRENT_SCHEMA()
        dmSQL = dmSQL.replaceAll("(?i)CONCAT\\((.+?)\\)", "$1");      // CONCAT(a,b) -> a || b

        // --------------------------
        // 规则 5: INSERT IGNORE -> MERGE INTO
        // --------------------------
        if (dmSQL.toUpperCase().startsWith("INSERT IGNORE")) {
            dmSQL = dmSQL.replaceAll("(?i)INSERT IGNORE INTO (\\w+).*?VALUES\\s*\\((.*?)\\)", "MERGE INTO $1 USING DUAL ON (1=0) WHEN NOT MATCHED THEN INSERT VALUES ($2)");
        }

        dmSQL = dmSQL.replace("unix_timestamp()", "CAST((SYSDATE - DATE '1970-01-01') * 86400000 AS BIGINT)");
        dmSQL = dmSQL.replace("user", "\"user\"");
        dmSQL = dmSQL.replace("\"user\"_group", "user_group");
        dmSQL = dmSQL.replace("_\"user\"_", "_user_");
        dmSQL = dmSQL.replace("_\"user\"", "_user");
        dmSQL = dmSQL.replace("\"user\"_", "user_");
        dmSQL = dmSQL.replace("`group`", "'group'");
        dmSQL = dmSQL.replace("FROM 'group'", "FROM \"group\"");
        dmSQL = dmSQL.replace("order", "\"order\"");
        dmSQL = dmSQL.replace("domain", "\"domain\"");
        dmSQL = dmSQL.replace("`", "");

        return dmSQL;

    }

    /**
     * 处理插入时自增id开关问题
     *
     * @param sql
     */
    private String handleIdentityInsertOn(String sql) {
        String tableName = null;
        Pattern pattern = Pattern.compile(SQL_INSERT_REG, Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(sql);
        if (matcher.find()) {
            tableName = matcher.group(2);
        }

        if (StringUtils.isNotBlank(tableName) && !identityInsertExcludeTableSet.contains(tableName)) {
            String identityInsertOn = String.format(IDENTITY_INSERT_ON, tableName);
            sql = identityInsertOn + sql;
        }
        return sql;
    }

    /**
     * 处理sql中的布尔值
     *
     * @param sql
     * @return: java.lang.String
     */
    private String handleBooleanParam(String sql) {
        return sql.replaceAll(SQL_TRUE_PARAM_REG, "= 1").replaceAll(SQL_FALSE_PARAM_REG, "= 0");
    }

}
