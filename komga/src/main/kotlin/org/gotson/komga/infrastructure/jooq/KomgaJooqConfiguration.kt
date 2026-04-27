package org.gotson.komga.infrastructure.jooq

import org.gotson.komga.infrastructure.configuration.KomgaProperties
import org.jooq.DSLContext
import org.jooq.ExecuteListenerProvider
import org.jooq.TransactionProvider
import org.jooq.impl.DataSourceConnectionProvider
import org.jooq.impl.DefaultConfiguration
import org.jooq.impl.DefaultDSLContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import javax.sql.DataSource

// taken from https://github.com/spring-projects/spring-boot/blob/v3.1.4/spring-boot-project/spring-boot-autoconfigure/src/main/java/org/springframework/boot/autoconfigure/jooq/JooqAutoConfiguration.java
// as advised in https://docs.spring.io/spring-boot/docs/3.1.4/reference/htmlsingle/#howto.data-access.configure-jooq-with-multiple-datasources
@Configuration
class KomgaJooqConfiguration(
  private val komgaProperties: KomgaProperties,
) {
  @Bean("dslContextRW")
  @Primary
  fun mainDslContextRW(
    dataSource: DataSource,
    transactionProvider: ObjectProvider<TransactionProvider?>,
    executeListenerProviders: ObjectProvider<ExecuteListenerProvider?>,
  ): DSLContext = createDslContext(dataSource, komgaProperties.database, transactionProvider, executeListenerProviders)

  @Bean("dslContextRO")
  fun mainDslContextRO(
    @Qualifier("dataSourceRO") dataSource: DataSource,
    transactionProvider: ObjectProvider<TransactionProvider?>,
    executeListenerProviders: ObjectProvider<ExecuteListenerProvider?>,
  ): DSLContext = createDslContext(dataSource, komgaProperties.database, transactionProvider, executeListenerProviders)

  @Bean("tasksDslContextRW")
  fun tasksDslContextRW(
    @Qualifier("tasksDataSourceRW") dataSource: DataSource,
    transactionProvider: ObjectProvider<TransactionProvider?>,
    executeListenerProviders: ObjectProvider<ExecuteListenerProvider?>,
  ): DSLContext = createDslContext(dataSource, komgaProperties.tasksDb, transactionProvider, executeListenerProviders)

  @Bean("tasksDslContextRO")
  fun tasksDslContextRO(
    @Qualifier("tasksDataSourceRO") dataSource: DataSource,
    transactionProvider: ObjectProvider<TransactionProvider?>,
    executeListenerProviders: ObjectProvider<ExecuteListenerProvider?>,
  ): DSLContext = createDslContext(dataSource, komgaProperties.tasksDb, transactionProvider, executeListenerProviders)

  private fun createDslContext(
    dataSource: DataSource,
    databaseProps: KomgaProperties.Database,
    transactionProvider: ObjectProvider<TransactionProvider?>,
    executeListenerProviders: ObjectProvider<ExecuteListenerProvider?>,
  ) = DefaultDSLContext(
    DefaultConfiguration().also { configuration ->
      configuration.set(databaseProps.backend.jooqDialect)
      configuration.set(DataSourceConnectionProvider(TransactionAwareDataSourceProxy(dataSource)))
      transactionProvider.ifAvailable { newTransactionProvider: TransactionProvider? -> configuration.set(newTransactionProvider) }
      configuration.set(*executeListenerProviders.orderedStream().toList().toTypedArray())
    },
  )
}
