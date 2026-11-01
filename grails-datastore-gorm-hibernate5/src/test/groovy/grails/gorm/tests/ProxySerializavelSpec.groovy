package grails.gorm.tests

import grails.gorm.annotation.Entity
import groovy.transform.CompileStatic
import org.grails.orm.hibernate.HibernateDatastore
import org.hibernate.Session
import org.hibernate.proxy.HibernateProxy
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

/**
 * ZeroGlosa (roteiro NUC-021): proxy lazy do Hibernate precisa serializar (sessão HTTP no Redis guarda entidades com
 * associações lazy). O ProxyFactorySupport montava o serializableProxy() mas não o adicionava à classe gerada: o
 * writeReplace do proxy caía em AbstractMethodError e a serialização falhava com "IOException: unexpected exception type".
 */
class ProxySerializavelSpec extends Specification {

    @Shared
    @AutoCleanup
    HibernateDatastore datastore = new HibernateDatastore(['dataSource.dbCreate': 'create-drop'], EntidadeSerializavel)

    void "proxy lazy não inicializado serializa e volta como proxy da mesma entidade"() {
        given:
        Long id = EntidadeSerializavel.withNewTransaction { new EntidadeSerializavel(nome: 'a').save(flush: true, failOnError: true).id }

        expect:
        EntidadeSerializavel.withNewSession { Session session ->
            Object proxy = session.load(EntidadeSerializavel, id)
            assert proxy instanceof HibernateProxy

            ByteArrayOutputStream bytes = new ByteArrayOutputStream()
            new ObjectOutputStream(bytes).withCloseable { it.writeObject(proxy) }
            Object lido = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())).withCloseable { it.readObject() }

            assert lido instanceof HibernateProxy
            assert idDoProxy(lido) == id
            true
        }
    }

    /** estático: o acesso dinâmico à propriedade passaria pelo handler Groovy do proxy e o inicializaria (sem sessão) */
    @CompileStatic
    private static Serializable idDoProxy(Object proxy) {
        return ((HibernateProxy) proxy).getHibernateLazyInitializer().getIdentifier()
    }
}

@Entity
class EntidadeSerializavel implements Serializable {
    String nome
}
