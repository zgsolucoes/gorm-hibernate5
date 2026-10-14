package grails.gorm.tests

import grails.gorm.annotation.Entity
import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.DefaultGroovyMethods
import org.grails.orm.hibernate.HibernateDatastore
import org.hibernate.Session
import org.hibernate.proxy.HibernateProxy
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * ZeroGlosa (e2e-0097): `proxyDaRaiz as Subclasse` sobre um proxy do Hibernate da raiz abstrata de uma hierarquia.
 * O asType compilado no domínio (o converters do Grails adiciona `asType(Class)` às entidades) chega à instância carregada,
 * que devolve a si mesma; o handler do proxy trocava esse retorno pelo próprio proxy, que não é instância da subclasse, e o
 * checkcast do chamador estourava ClassCastException (`Raiz_$$_jvst... cannot be cast to Subclasse`).
 */
class AsTypeEmProxyDaRaizAbstrataSpec extends Specification {

    @Shared
    @AutoCleanup
    HibernateDatastore datastore = new HibernateDatastore(['dataSource.dbCreate': 'create-drop'], RaizAbstrataAsType, FilhaAsType)

    void "as Subclasse sobre proxy da raiz abstrata devolve a instância carregada"() {
        given:
        Long id = RaizAbstrataAsType.withNewTransaction { new FilhaAsType(nome: 'filha').save(flush: true, failOnError: true).id }

        expect:
        RaizAbstrataAsType.withNewSession { Session session ->
            Object proxy = session.load(RaizAbstrataAsType, id)
            assert proxy instanceof HibernateProxy
            assert !(proxy instanceof FilhaAsType)
            FilhaAsType filha = comoFilha(proxy)
            assert filha.nome == 'filha'
            assert !(filha instanceof HibernateProxy)
            // tipo que o proxy já é: continua devolvendo o proxy (comportamento original)
            assert comoRaiz(proxy).is(proxy)
            true
        }
    }

    @CompileStatic
    private static FilhaAsType comoFilha(Object guia) {
        return guia as FilhaAsType
    }

    @CompileStatic
    private static RaizAbstrataAsType comoRaiz(Object guia) {
        return (RaizAbstrataAsType) ((RaizAbstrataAsType) guia).asType(RaizAbstrataAsType)
    }
}

@Entity
abstract class RaizAbstrataAsType {
    String nome

    /** Como o asType que o converters do Grails compila nas entidades: cai no asType padrão do Groovy. */
    Object asType(Class clazz) {
        return DefaultGroovyMethods.asType((Object) this, clazz)
    }
}

@Entity
class FilhaAsType extends RaizAbstrataAsType {
}
