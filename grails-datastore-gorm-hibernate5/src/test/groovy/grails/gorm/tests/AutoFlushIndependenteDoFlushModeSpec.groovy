package grails.gorm.tests

import grails.gorm.annotation.Entity
import org.grails.orm.hibernate.HibernateDatastore
import org.hibernate.FlushMode
import org.hibernate.Session
import org.hibernate.engine.spi.SessionImplementor
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import spock.lang.Specification

import java.sql.ResultSet

/**
 * Grails 2 semantics (ZeroGlosa): `grails.gorm.autoFlush` (default false) decides whether save()/delete() flush, independently
 * of the Hibernate session FlushMode. With `hibernate.flush.mode: AUTO` and `grails.gorm.autoFlush: false` the session keeps
 * flushing before queries inside a transaction, but save() without `flush: true` does not hit the database by itself.
 */
class AutoFlushIndependenteDoFlushModeSpec extends Specification {

    HibernateDatastore datastore

    void cleanup() {
        datastore?.close()
    }

    private HibernateDatastore criarDatastore(Map config) {
        datastore = new HibernateDatastore(config + ['dataSource.dbCreate': 'create-drop'], LivroAutoFlush)
        return datastore
    }

    private static int contarNoBanco(Session session) {
        ResultSet rs = ((SessionImplementor) session).connection().prepareStatement('select count(*) from livro_auto_flush').executeQuery()
        rs.next()
        return rs.getInt(1)
    }

    private <T> T emTransacao(Closure<T> c) {
        new TransactionTemplate(datastore.transactionManager).execute { TransactionStatus status ->
            try {
                return c.call(datastore.sessionFactory.currentSession)
            } finally {
                status.setRollbackOnly()
            }
        } as T
    }

    void "flush.mode AUTO com grails.gorm.autoFlush false: save sem flush nao vai ao banco ate a query ou o commit"() {
        given:
        criarDatastore(['hibernate.flush.mode': 'AUTO', 'grails.gorm.autoFlush': false])

        expect: 'o datastore nao faz autoFlush, mas o modo padrao da sessao continua AUTO'
        !datastore.autoFlush
        datastore.defaultFlushModeName == 'AUTO'

        when:
        Map r = emTransacao { Session session ->
            new LivroAutoFlush(titulo: 'A').save(failOnError: true)
            int depoisDoSave = contarNoBanco(session)
            FlushMode modoDaSessao = session.flushMode
            int pelaQuery = LivroAutoFlush.count()
            int depoisDaQuery = contarNoBanco(session)
            [depoisDoSave: depoisDoSave, modoDaSessao: modoDaSessao, pelaQuery: pelaQuery, depoisDaQuery: depoisDaQuery]
        }

        then: 'o save ficou pendente na sessao'
        r.depoisDoSave == 0
        and: 'a query HQL dentro da transacao deu flush (FlushMode AUTO) e viu o save'
        r.modoDaSessao == FlushMode.AUTO
        r.pelaQuery == 1
        r.depoisDaQuery == 1
    }

    void "flush.mode AUTO com grails.gorm.autoFlush false: save(flush: true) continua indo ao banco na hora"() {
        given:
        criarDatastore(['hibernate.flush.mode': 'AUTO', 'grails.gorm.autoFlush': false])

        when:
        int depoisDoSave = emTransacao { Session session ->
            new LivroAutoFlush(titulo: 'A').save(flush: true, failOnError: true)
            contarNoBanco(session)
        }

        then:
        depoisDoSave == 1
    }

    void "flush.mode AUTO com grails.gorm.autoFlush false: delete sem flush nao vai ao banco ate a query"() {
        given:
        criarDatastore(['hibernate.flush.mode': 'AUTO', 'grails.gorm.autoFlush': false])

        when:
        Map r = emTransacao { Session session ->
            LivroAutoFlush livro = new LivroAutoFlush(titulo: 'A').save(flush: true, failOnError: true)
            livro.delete()
            int depoisDoDelete = contarNoBanco(session)
            int pelaQuery = LivroAutoFlush.count()
            [depoisDoDelete: depoisDoDelete, pelaQuery: pelaQuery]
        }

        then:
        r.depoisDoDelete == 1
        r.pelaQuery == 0
    }

    void "grails.gorm.autoFlush true: save sem flush vai ao banco na hora"() {
        given:
        criarDatastore(['hibernate.flush.mode': 'AUTO', 'grails.gorm.autoFlush': true])

        when:
        int depoisDoSave = emTransacao { Session session ->
            new LivroAutoFlush(titulo: 'A').save(failOnError: true)
            contarNoBanco(session)
        }

        then:
        datastore.autoFlush
        depoisDoSave == 1
    }

    void "sem grails.gorm.autoFlush explicito o comportamento do GORM 6.1.7 se mantem (autoFlush = flush.mode AUTO)"() {
        when:
        criarDatastore(['hibernate.flush.mode': flushMode])

        then:
        datastore.autoFlush == esperado

        where:
        flushMode || esperado
        'AUTO'    || true
        'COMMIT'  || false
    }
}

@Entity
class LivroAutoFlush {
    String titulo

    static mapping = {
        version false
        id generator: 'sequence', params: [sequence: 'livro_auto_flush_seq']
    }
}
