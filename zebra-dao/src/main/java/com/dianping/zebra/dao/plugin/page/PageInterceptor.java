/*
 * Copyright (c) 2011-2018, Meituan Dianping. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dianping.zebra.dao.plugin.page;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import com.dianping.zebra.group.util.DaoContextHolder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import com.dianping.zebra.dao.dialect.Dialect;
import com.dianping.zebra.dao.util.ReflectionUtils;

/**
 * 
 * @author damonzhu
 *
 */
@Intercepts(@Signature(type = Executor.class, method = "query", args = { MappedStatement.class, Object.class,
		RowBounds.class, ResultHandler.class }))
public class PageInterceptor implements Interceptor {

	private static final List<ResultMapping> EMPTY_RESULTMAPPING = new ArrayList<ResultMapping>(0);

	private static final Map<String, MappedStatement> COUNT_MAPPED_STATS = new ConcurrentHashMap<String, MappedStatement>();

	private static final Map<String, String> MAPPED_ID = new ConcurrentHashMap<String, String>();

	private Dialect dialect;

	@Override
	public Object plugin(Object target) {
		if (target instanceof Executor) {
			return Plugin.wrap(target, this);
		} else {
			return target;
		}
	}

	@Override
	public void setProperties(Properties properties) {
		String dialectClass = properties.get("dialectClass").toString();
		try {
			dialect = (Dialect) Class.forName(dialectClass).newInstance();
		} catch (Exception e) {
			throw new RuntimeException("cannot create dialect instance by dialectClass:" + dialectClass, e);
		}
	}

	@Override
	public Object intercept(Invocation invocation) throws Throwable {
		Object[] args = invocation.getArgs();
		Object rowBound = args[2];

		MappedStatement ms = (MappedStatement) args[0];
		if (rowBound != null) {
			RowBounds rb = (RowBounds) rowBound;

			// without pagination
			if (rb.getOffset() == RowBounds.NO_ROW_OFFSET && rb.getLimit() == RowBounds.NO_ROW_LIMIT) {
				return invocation.proceed();
			} else {
				BoundSql boundSql = ms.getBoundSql(args[1]);

				if (rowBound instanceof PageModel) {
					// physical pagination with PageModel
					PageModel pageModel = (PageModel) rowBound;
					Object count = queryCount(invocation, args, ms, boundSql);
					Object records = queryLimit(invocation, args, ms, boundSql, pageModel);

					pageModel.setRecordCount((Integer) ((List<?>) count).get(0));
					pageModel.setRecords((List<?>) records);

					return null;
				} else {
					// physical pagination with RowBounds
					return queryLimit(invocation, args, ms, boundSql, rb);
				}
			}
		} else {
			// without pagination
			return invocation.proceed();
		}
	}

	private String buildDaoName(String id) {
		String daoName = MAPPED_ID.get(id);

		if (daoName == null) {
			String[] splits = id.split("\\.");
			int len = splits.length;

			if (len < 2) {
				daoName = id;
			} else {
				daoName = splits[len - 2] + "." + splits[len - 1];
			}

			MAPPED_ID.put(id, daoName);
		}

		return daoName;
	}

	private Object queryCount(Invocation invocation, Object[] args, MappedStatement ms, BoundSql boundSql)
			throws InvocationTargetException, IllegalAccessException {
		MappedStatement countRowStatement = COUNT_MAPPED_STATS.get(ms.getId());

		if (countRowStatement == null) {
			String countSql = dialect.getCountSql(boundSql.getSql());
			BoundSql newBoundSql = new BoundSql(ms.getConfiguration(), countSql, boundSql.getParameterMappings(),
					boundSql.getParameterObject());
			MetaObject mo = (MetaObject) ReflectionUtils.getFieldValue(boundSql, "metaParameters");
			ReflectionUtils.setFieldValue(newBoundSql, "metaParameters", mo);
			Object additionalParameters = ReflectionUtils.getFieldValue(boundSql, "additionalParameters");
			ReflectionUtils.setFieldValue(newBoundSql, "additionalParameters", additionalParameters);
			List<ResultMap> resultMaps = new ArrayList<ResultMap>();
			ResultMap resultMap = new ResultMap.Builder(ms.getConfiguration(), ms.getId(), int.class,
					EMPTY_RESULTMAPPING).build();
			resultMaps.add(resultMap);
			countRowStatement = buildMappedStatement(ms, new SqlSourceWrapper(newBoundSql), ms.getId() + "_COUNT",
					resultMaps);
		}

		args[0] = countRowStatement;
		args[2] = new RowBounds();
		args[3] = null;

		try {
			DaoContextHolder.setSqlName(buildDaoName(ms.getId()) + "_COUNT");
			return invocation.proceed();
		} finally {
			DaoContextHolder.clearSqlName();
		}
	}

	private Object queryLimit(Invocation invocation, Object[] args, MappedStatement ms, BoundSql boundSql, RowBounds rb)
			throws InvocationTargetException, IllegalAccessException {
		String limitSql = dialect.getLimitSql(boundSql.getSql(), rb.getOffset(), rb.getLimit());
		BoundSql newBoundSql = new BoundSql(ms.getConfiguration(), limitSql, boundSql.getParameterMappings(),
				boundSql.getParameterObject());
		MetaObject mo = (MetaObject) ReflectionUtils.getFieldValue(boundSql, "metaParameters");
		ReflectionUtils.setFieldValue(newBoundSql, "metaParameters", mo);

		args[0] = buildMappedStatement(ms, new SqlSourceWrapper(newBoundSql), ms.getId() + "_LIMIT",
				ms.getResultMaps());
		args[2] = new RowBounds();
		args[3] = null;

		try {
			DaoContextHolder.setSqlName(buildDaoName(ms.getId()) + "_LIMIT");
			return invocation.proceed();
		} finally {
			DaoContextHolder.clearSqlName();
		}
	}

	public MappedStatement buildMappedStatement(MappedStatement ms, SqlSource newSqlSource, String id,
			List<ResultMap> resultMaps) {
		MappedStatement.Builder builder = new MappedStatement.Builder(ms.getConfiguration(), id, newSqlSource,
				ms.getSqlCommandType());

		builder.resource(ms.getResource());
		builder.fetchSize(ms.getFetchSize());
		builder.statementType(ms.getStatementType());
		builder.keyGenerator(ms.getKeyGenerator());
		if (ms.getKeyProperties() != null && ms.getKeyProperties().length != 0) {
			StringBuilder keyProperties = new StringBuilder();
			for (String keyProperty : ms.getKeyProperties()) {
				keyProperties.append(keyProperty).append(",");
			}
			keyProperties.delete(keyProperties.length() - 1, keyProperties.length());
			builder.keyProperty(keyProperties.toString());
		}
		builder.timeout(ms.getTimeout());
		builder.parameterMap(ms.getParameterMap());
		builder.resultMaps(resultMaps);
		builder.resultSetType(ms.getResultSetType());
		builder.cache(ms.getCache());
		builder.flushCacheRequired(ms.isFlushCacheRequired());
		builder.useCache(ms.isUseCache());

		return builder.build();
	}
}
