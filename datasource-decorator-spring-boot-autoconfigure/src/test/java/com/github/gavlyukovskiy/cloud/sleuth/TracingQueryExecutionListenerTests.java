/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.github.gavlyukovskiy.cloud.sleuth;

import brave.handler.MutableSpan;
import brave.test.TestSpanHandler;
import com.github.gavlyukovskiy.boot.jdbc.decorator.DataSourceDecoratorAutoConfiguration;
import com.github.gavlyukovskiy.boot.jdbc.decorator.HidePackagesClassLoader;
import com.zaxxer.hikari.HikariDataSource;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.sleuth.autoconfig.brave.BraveAutoConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

class TracingQueryExecutionListenerTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DataSourceDecoratorAutoConfiguration.class,
                    BraveAutoConfiguration.class,
                    SleuthListenerAutoConfiguration.class,
                    TestSpanHandlerConfiguration.class,
                    PropertyPlaceholderAutoConfiguration.class
            ))
            .withPropertyValues("spring.datasource.initialization-mode=never",
                    "spring.datasource.url:jdbc:h2:mem:testdb-" + ThreadLocalRandom.current().nextInt(),
                    "spring.datasource.hikari.pool-name=test")
            .withClassLoader(new HidePackagesClassLoader("com.vladmihalcea.flexypool", "com.p6spy"));

    @Test
    void testShouldAddSpanForConnection() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            connection.commit();
            connection.rollback();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(1);
            MutableSpan connectionSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(connectionSpan.annotations()).extracting("value").contains("commit");
            assertThat(connectionSpan.annotations()).extracting("value").contains("rollback");
        });
    }

    @Test
    void testShouldAddSpanForPreparedStatementExecute() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            connection.prepareStatement("SELECT NOW()").execute();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan connectionSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    void testShouldAddSpanForPreparedStatementExecuteUpdate() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            connection.prepareStatement("UPDATE INFORMATION_SCHEMA.TABLES SET table_Name = '' WHERE 0 = 1").executeUpdate();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan connectionSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME,
                    "UPDATE INFORMATION_SCHEMA.TABLES SET table_Name = '' WHERE 0 = 1");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_ROW_COUNT_TAG_NAME, "0");
        });
    }

    @Test
    void testShouldAddSpanForStatementExecuteUpdate() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            connection.createStatement().executeUpdate("UPDATE INFORMATION_SCHEMA.TABLES SET table_Name = '' WHERE 0 = 1");
            connection.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan connectionSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME,
                    "UPDATE INFORMATION_SCHEMA.TABLES SET table_Name = '' WHERE 0 = 1");
        });
    }

    @Test
    void testShouldAddSpanForPreparedStatementExecuteQueryIncludingTimeToCloseResultSet() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            ResultSet resultSet = connection.prepareStatement("SELECT NOW() UNION ALL SELECT NOW()").executeQuery();
            resultSet.next();
            resultSet.next();
            resultSet.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW() UNION ALL SELECT NOW()");
        });
    }

    @Test
    void testShouldAddSpanForStatementAndResultSet() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            ResultSet resultSet = connection.createStatement().executeQuery("SELECT NOW()");
            resultSet.next();
            Thread.sleep(200L);
            resultSet.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    void testShouldNotFailWhenStatementIsClosedWihoutResultSet() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT NOW()");
            resultSet.next();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    void testShouldNotFailWhenConnectionIsClosedWihoutResultSet() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT NOW()");
            resultSet.next();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    void testShouldNotFailWhenResultSetNextWasNotCalled() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT NOW()");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    void testShouldNotFailWhenResourceIsAlreadyClosed() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT NOW()");
            resultSet.next();
            resultSet.close();
            resultSet.close();
            statement.close();
            statement.close();
            connection.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    void testShouldNotFailWhenResourceIsAlreadyClosed2() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            Connection connection = dataSource.getConnection();
            try {
                connection.close();
                connection.prepareStatement("SELECT NOW()");
                fail("should fail due to closed connection");
            }
            catch (SQLException expected) {
            }
        });
    }

    @Test
    void testShouldNotFailWhenResourceIsAlreadyClosed3() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            try {
                statement.close();
                statement.executeQuery("SELECT NOW()");
                fail("should fail due to closed connection");
            }
            catch (SQLException expected) {
            }
            connection.close();
        });
    }

    @Test
    void testShouldNotFailWhenResourceIsAlreadyClosed4() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT NOW()");
            try {
                resultSet.close();
                resultSet.next();
                fail("should fail due to closed connection");
            }
            catch (SQLException expected) {
            }
            statement.close();
            connection.close();
        });
    }

    @Test
    void testShouldNotFailToCloseSpanForTwoConsecutiveConnections() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection1 = dataSource.getConnection();
            Connection connection2 = dataSource.getConnection();
            connection1.close();
            connection2.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan connection1Span = spanReporter.spans().get(0);
            MutableSpan connection2Span = spanReporter.spans().get(1);
            assertThat(connection1Span.name()).isEqualTo("jdbc:/test/connection");
            assertThat(connection2Span.name()).isEqualTo("jdbc:/test/connection");
        });
    }

    @Test
    void testShouldNotFailWhenClosedInReversedOrder() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT NOW()");
            resultSet.next();
            connection.close();
            statement.close();
            resultSet.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void testShouldNotCauseMemoryLeak() {
        contextRunner.withPropertyValues("spring.datasource.type:org.apache.tomcat.jdbc.pool.DataSource").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TracingQueryExecutionListener tracingQueryExecutionListener = context.getBean(TracingQueryExecutionListener.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT 1 FROM dual");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(tracingQueryExecutionListener)
                    .extracting("strategy")
                    .extracting("openConnections")
                    .isInstanceOfSatisfying(Map.class, map -> assertThat(map).isEmpty());
        });
    }

    @Test
    void testShouldIncludeOnlyConnectionTraces() {
        contextRunner.withPropertyValues("decorator.datasource.sleuth.include: connection").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT 1 FROM dual");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(1);
            MutableSpan connectionSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
        });
    }

    @Test
    void testShouldIncludeOnlyQueryTraces() {
        contextRunner.withPropertyValues("decorator.datasource.sleuth.include: query").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT 1 FROM dual");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
        });
    }

    @Test
    void testShouldIncludeOnlyFetchTraces() {
        contextRunner.withPropertyValues("decorator.datasource.sleuth.include: fetch").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT 1 FROM dual");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(1);
            MutableSpan resultSetSpan = spanReporter.spans().get(0);
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
        });
    }

    @Test
    void testShouldIncludeOnlyConnectionAndQueryTraces() {
        contextRunner.withPropertyValues("decorator.datasource.sleuth.include: connection, query").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT 1 FROM dual");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan connectionSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
        });
    }

    @Test
    void testShouldIncludeOnlyConnectionAndFetchTraces() {
        contextRunner.withPropertyValues("decorator.datasource.sleuth.include: connection, fetch").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT 1 FROM dual");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan connectionSpan = spanReporter.spans().get(1);
            MutableSpan resultSetSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
        });
    }

    @Test
    void testShouldIncludeOnlyQueryAndFetchTraces() {
        contextRunner.withPropertyValues("decorator.datasource.sleuth.include: query, fetch").run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT 1 FROM dual");
            resultSet.next();
            resultSet.close();
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
        });
    }

    @Test
    void testShouldNotOverrideExceptionWhenConnectionWasClosedBeforeExecutingQuery() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT NOW()");
            connection.close();
            try {
                statement.executeQuery();
                fail("should throw SQLException");
            }
            catch (SQLException expected) {
            }

            assertThat(spanReporter.spans()).hasSize(1);
            MutableSpan connectionSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
        });
    }

    @Test
    void testShouldNotOverrideExceptionWhenStatementWasClosedBeforeExecutingQuery() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT NOW()");
            statement.close();
            try {
                statement.executeQuery();
                fail("should throw SQLException");
            }
            catch (SQLException expected) {
            }
            connection.close();

            assertThat(spanReporter.spans()).hasSize(2);
            MutableSpan connectionSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
        });
    }

    @Test
    void testShouldNotOverrideExceptionWhenResultSetWasClosedBeforeNext() {
        contextRunner.run(context -> {
            DataSource dataSource = context.getBean(DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            Connection connection = dataSource.getConnection();
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT NOW()");
            resultSet.close();
            try {
                resultSet.next();
                fail("should throw SQLException");
            }
            catch (SQLException expected) {
            }
            statement.close();
            connection.close();

            assertThat(spanReporter.spans()).hasSize(3);
            MutableSpan connectionSpan = spanReporter.spans().get(2);
            MutableSpan resultSetSpan = spanReporter.spans().get(1);
            MutableSpan statementSpan = spanReporter.spans().get(0);
            assertThat(connectionSpan.name()).isEqualTo("jdbc:/test/connection");
            assertThat(statementSpan.name()).isEqualTo("jdbc:/test/query");
            assertThat(resultSetSpan.name()).isEqualTo("jdbc:/test/fetch");
            assertThat(statementSpan.tags()).containsEntry(SleuthListenerAutoConfiguration.SPAN_SQL_QUERY_TAG_NAME, "SELECT NOW()");
        });
    }

    @Test
    void testShouldNotFailWhenClosingConnectionFromDifferentDataSource() {
        ApplicationContextRunner contextRunner = this.contextRunner.withUserConfiguration(MultiDataSourceConfiguration.class);

        contextRunner.run(context -> {
            DataSource dataSource1 = context.getBean("test1", DataSource.class);
            DataSource dataSource2 = context.getBean("test2", DataSource.class);
            TestSpanHandler spanReporter = context.getBean(TestSpanHandler.class);

            dataSource1.getConnection().close();
            dataSource2.getConnection().close();

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    Connection connection1 = dataSource1.getConnection();
                    PreparedStatement statement = connection1.prepareStatement("SELECT NOW()");
                    statement.executeQuery().close();
                    statement.close();
                    connection1.close();
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            Thread.sleep(100);
            Connection connection2 = dataSource2.getConnection();
            Thread.sleep(300);
            connection2.close();

            future.join();
        });
    }

    private static class MultiDataSourceConfiguration {
        @Bean
        public HikariDataSource test1() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl("jdbc:h2:mem:testdb-1-" + ThreadLocalRandom.current().nextInt());
            dataSource.setPoolName("test1");
            return dataSource;
        }

        @Bean
        public HikariDataSource test2() {
            HikariDataSource dataSource = new HikariDataSource();
            dataSource.setJdbcUrl("jdbc:h2:mem:testdb-2-" + ThreadLocalRandom.current().nextInt());
            dataSource.setPoolName("test2");
            return dataSource;
        }

        @Bean
        public QueryExecutionListener slowListener() {
            return new QueryExecutionListener() {
                @Override
                public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                    // emulating long query
                    if (execInfo.getDataSourceName().equals("test1")) {
                        try {
                            Thread.sleep(200);
                        } catch (InterruptedException e) {
                            throw new IllegalStateException();
                        }
                    }
                }

                @Override
                public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                }
            };
        }
    }
}
