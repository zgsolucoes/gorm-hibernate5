/*
 * Copyright 2004-2010 the original author or authors.
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
package org.grails.orm.hibernate

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.grails.orm.hibernate.support.HibernateVersionSupport
import org.hibernate.FlushMode
import org.hibernate.Session
import org.hibernate.SessionFactory
import org.hibernate.engine.jdbc.spi.JdbcCoordinator
import org.hibernate.engine.spi.SessionImplementor
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.orm.hibernate5.HibernateTransactionManager
import org.springframework.orm.hibernate5.SessionHolder
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.util.Assert

import javax.sql.DataSource

/**
 * Extends the standard class to always set the flush mode to manual when in a read-only transaction.
 *
 * @author Burt Beckwith
 */
@CompileStatic
@Slf4j
class GrailsHibernateTransactionManager extends HibernateTransactionManager {

    final FlushMode defaultFlushMode
    boolean isJdbcBatchVersionedData

    GrailsHibernateTransactionManager(FlushMode defaultFlushMode = FlushMode.AUTO) {
        this.defaultFlushMode = defaultFlushMode
    }

    GrailsHibernateTransactionManager(SessionFactory sessionFactory, FlushMode defaultFlushMode = FlushMode.AUTO) {
        super(sessionFactory)
        this.defaultFlushMode = defaultFlushMode
        this.isJdbcBatchVersionedData = sessionFactory.getSessionFactoryOptions().isJdbcBatchVersionedData()
    }

    GrailsHibernateTransactionManager(SessionFactory sessionFactory, DataSource dataSource, FlushMode defaultFlushMode = FlushMode.AUTO) {
        super(sessionFactory)
        setDataSource(dataSource)
        this.defaultFlushMode = defaultFlushMode
        this.isJdbcBatchVersionedData = sessionFactory.getSessionFactoryOptions().isJdbcBatchVersionedData()
    }

    /**
     * Fork ZeroGlosa (ZG-2497, backlog e2e-0081): sessão/conexão deste datastore presas a OUTRA transação, desligadas
     * durante uma transação nova e religadas no fim (ver {@link #desligueRecursosDeOutraTransacao}).
     */
    private final Map<Object, RecursosDeOutraTransacao> recursosDeOutraTransacao =
            Collections.synchronizedMap(new IdentityHashMap<Object, RecursosDeOutraTransacao>())

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        RecursosDeOutraTransacao deOutraTransacao = desligueRecursosDeOutraTransacao()
        try {
            doBeginDoGrails(transaction, definition)
        } catch (Throwable e) {
            deOutraTransacao?.religue(sessionFactory, dataSource)
            throw e
        }
        if (deOutraTransacao != null) {
            recursosDeOutraTransacao.put(transaction, deOutraTransacao)
        }
    }

    @Override
    protected void doCleanupAfterCompletion(Object transaction) {
        try {
            super.doCleanupAfterCompletion(transaction)
        } finally {
            recursosDeOutraTransacao.remove(transaction)?.religue(sessionFactory, dataSource)
        }
    }

    /**
     * Fork ZeroGlosa (ZG-2497, backlog e2e-0081). O doBegin só roda para transação nova deste datastore (não há uma
     * transação dele em curso). Se ainda assim há, ligados à thread, uma sessão deste datastore marcada
     * synchronizedWithTransaction (sem transação do Hibernate) e/ou um ConnectionHolder do datasource dele
     * synchronizedWithTransaction, eles pertencem a OUTRA transação (ex.: a sessão do portal alistada pelo
     * GrailsSessionContext na transação do datastore padrão, e a conexão dela ligada pelo DataSourceUtils), cujas
     * sincronizações o suspend do Spring não alcançou — o {@link GrailsHibernateTemplate#executeWithNewSession} as esconde
     * (clear + init) em vez de suspendê-las. Sem isto o doBegin abre outra sessão e falha ao ligar a conexão nova:
     * "Already value [ConnectionHolder] for key [...DataSource] bound to thread". Aqui eles são desligados como o suspend
     * dessas sincronizações faria, e religados em {@link #doCleanupAfterCompletion} (ou se o doBegin falhar).
     * Nada muda quando não há recurso alheio ligado.
     */
    private RecursosDeOutraTransacao desligueRecursosDeOutraTransacao() {
        Object sessao = TransactionSynchronizationManager.getResource(sessionFactory)
        Object conexao = dataSource != null ? TransactionSynchronizationManager.getResource(dataSource) : null
        boolean sessaoAlheia = sessao instanceof SessionHolder &&
                ((SessionHolder) sessao).isSynchronizedWithTransaction() && ((SessionHolder) sessao).getTransaction() == null
        boolean conexaoAlheia = conexao instanceof ConnectionHolder && ((ConnectionHolder) conexao).isSynchronizedWithTransaction()
        if (!sessaoAlheia && !conexaoAlheia) {
            return null
        }
        RecursosDeOutraTransacao recursos = new RecursosDeOutraTransacao()
        if (sessaoAlheia) {
            recursos.sessao = (SessionHolder) TransactionSynchronizationManager.unbindResource(sessionFactory)
        }
        if (conexaoAlheia) {
            recursos.conexao = (ConnectionHolder) TransactionSynchronizationManager.unbindResource(dataSource)
        }
        return recursos
    }

    @CompileStatic
    private static class RecursosDeOutraTransacao {
        SessionHolder sessao
        ConnectionHolder conexao

        void religue(SessionFactory sessionFactory, DataSource dataSource) {
            if (sessao != null) {
                TransactionSynchronizationManager.bindResource(sessionFactory, sessao)
            }
            if (conexao != null) {
                TransactionSynchronizationManager.bindResource(dataSource, conexao)
            }
        }
    }

    private void doBeginDoGrails(Object transaction, TransactionDefinition definition) {
        super.doBegin transaction, definition

        if (definition.isReadOnly()) {
            // transaction is HibernateTransactionManager.HibernateTransactionObject private class instance
            // always set to manual; the base class doesn't because the OSIV has already registered a session

            SessionHolder holder = (SessionHolder)TransactionSynchronizationManager.getResource(sessionFactory)
            HibernateVersionSupport.setFlushMode(holder.getSession(), FlushMode.MANUAL)
        }
        else if(defaultFlushMode != FlushMode.AUTO) {
            SessionHolder holder = (SessionHolder)TransactionSynchronizationManager.getResource(sessionFactory)
            HibernateVersionSupport.setFlushMode(holder.getSession(), defaultFlushMode)
        }
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        super.doRollback(status)
        if(isJdbcBatchVersionedData) {
            try {
                SessionHolder holder = (SessionHolder)TransactionSynchronizationManager.getResource(sessionFactory)
                if(holder != null) {
                    Session session = holder.getSession()
                    JdbcCoordinator jdbcCoordinator = ((SessionImplementor) session).getJdbcCoordinator()
                    jdbcCoordinator.abortBatch()
                }
            } catch (Throwable e) {
                log.warn("Error aborting batch during Transaction rollback: ${e.message}", e)
            }
        }
    }

    @Override
    void setSessionFactory(SessionFactory sessionFactory) {
        Assert.notNull(sessionFactory, "SessionFactory cannot be null")
        super.setSessionFactory(sessionFactory)
        this.isJdbcBatchVersionedData = sessionFactory.getSessionFactoryOptions().isJdbcBatchVersionedData()
    }
}
