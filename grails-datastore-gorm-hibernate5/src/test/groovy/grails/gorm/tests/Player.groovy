package grails.gorm.tests

import grails.persistence.Entity

/**
 * Created by graemerocher on 21/10/16.
 */
@Entity
class Player {
    String name
    static belongsTo = [team:Team]
    static hasOne = [contract:Contract]
}
