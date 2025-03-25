package io.metersphere.gateway.config;


import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.util.JdbcConstants;
import jakarta.annotation.PostConstruct;
import org.apache.commons.lang3.StringUtils;
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

        String sql = this.handleIllegalChar(boundSql.getSql());
        if (sqlCommandType == SqlCommandType.SELECT) {
            sql = this.handleBooleanParam(sql);
        }

        if (sqlCommandType == SqlCommandType.INSERT) {
            sql = this.handleIdentityInsertOn(sql);
        }
        metaObject.setValue("delegate.boundSql.sql", sql);

        return invocation.proceed();
    }

    /**
     * 处理非法字符
     *
     * @param sql
     * @return: java.lang.String
     */
    private String handleIllegalChar(String sql) {
        SQLUtils.FormatOption options = new SQLUtils.FormatOption();
        options.setPrettyFormat(true);    // 启用缩进
        options.setUppCase(true);          // 关键字大写
        // 1. 解析 MySQL SQL
        sql = SQLUtils.format(sql, JdbcConstants.MYSQL, options);
        sql = sql.replace("FROM user", "FROM \"user\"");
        sql = sql.replace("\"user\"_group", "user_group");
        sql = sql.replace("`group`", "'group'");
        sql = sql.replace("FROM 'group'", "FROM \"group\"");
        return sql.replace("`", "");
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
