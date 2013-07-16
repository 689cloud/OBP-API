/**
Open Bank Project - API
Copyright (C) 2011, 2013, TESOBE / Music Pictures Ltd

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program.  If not, see <http://www.gnu.org/licenses/>.

Email: contact@tesobe.com
TESOBE / Music Pictures Ltd
Osloerstrasse 16/17
Berlin 13359, Germany

  This product includes software developed at
  TESOBE (http://www.tesobe.com/)
  by
  Simon Redfern : simon AT tesobe DOT com
  Stefan Bethge : stefan AT tesobe DOT com
  Everett Sochowski : everett AT tesobe DOT com
  Ayoub Benali: ayoub AT tesobe DOT com

 */
package code.model.dataAccess

import net.liftweb.mapper._
import net.liftweb.util._
import net.liftweb.common._
import net.liftweb.record.field.StringField
import scala.xml.NodeSeq
import net.liftweb.sitemap.Loc.LocGroup
import net.liftweb.http.{S,SessionVar,Templates}
import com.mongodb.QueryBuilder
import code.model.{View,User, BankAccount}
import net.liftweb.json.JsonDSL._
import net.liftweb.http.SHtml
import net.liftweb.http.S
import net.liftweb.util.Helpers._
import org.bson.types.ObjectId
import com.mongodb.DBObject
import net.liftweb.json.JsonAST.JObject
import net.liftweb.http.js.JsCmds.FocusOnLoad


/**
 * An O-R mapped "User" class that includes first name, last name, password
 */
class OBPUser extends MegaProtoUser[OBPUser] with User with OneToMany[Long, OBPUser]{
  def getSingleton = OBPUser // what's the "meta" server
  def id_ = emailAddress
  def emailAddress = email.get
  def theFirstName : String = firstName.get
  def theLastName : String = lastName.get
  def provider = Props.get("hostname","")
  object privileges extends MappedOneToMany(Privilege, Privilege.user, OrderBy(Privilege.id, Ascending))
}

/**
 * The singleton that has methods for accessing the database
 */
object OBPUser extends OBPUser with MetaMegaProtoUser[OBPUser]{

  override def dbTableName = "users" // define the DB table name

  override def screenWrap = Full(<lift:surround with="default" at="content">
             <lift:bind /></lift:surround>)
  // define the order fields will appear in forms and output
  override def fieldOrder = List(id, firstName, lastName, email,
  locale, timezone, password)

  // comment this line out to require email validations
  override def skipEmailValidation = true

  override def loginXhtml = {
    import net.liftweb.http.TemplateFinder
    import net.liftweb.http.js.JsCmds.Noop
    val loginXml = Templates(List("templates-hidden","_UserLogin")).map({
        "form [action]" #> {S.uri} &
        "#loginText * " #> {S.??("log.in")} &
        "#emailAddressText * " #> {S.??("email.address")} &
        "#passwordText * " #> {S.??("password")} &
        "#recoverPasswordLink * " #> {
          "a [href]" #> {lostPasswordPath.mkString("/", "/", "")} &
          "a *" #> {S.??("recover.password")}
        } &
        "#SignUpLink * " #> {
          "a [href]" #> {OBPUser.signUpPath.foldLeft("")(_ + "/" + _)} &
          "a *" #> {S.??("sign.up")}
        }
      })
      SHtml.span(loginXml getOrElse NodeSeq.Empty,Noop)
  }

  /**
   * Set this to redirect to a certain page after a failed login
   */
  object failedLoginRedirect extends SessionVar[Box[String]](Empty) {
    override lazy val __nameSalt = Helpers.nextFuncName
  }

  //overridden to allow a redirection if login fails
  override def login = {
    if (S.post_?) {
      S.param("username").
      flatMap(username => findUserByUserName(username)) match {
        case Full(user) if user.validated_? &&
          user.testPassword(S.param("password")) => {
            val preLoginState = capturePreLoginState()
            println("login redir: " + loginRedirect.is)
            val redir = loginRedirect.is match {
              case Full(url) =>
                loginRedirect(Empty)
              url
              case _ =>
                homePage
            }

            logUserIn(user, () => {
              S.notice(S.??("logged.in"))

              preLoginState()

              S.redirectTo(redir)
            })
          }

        case _ => {
          println("failed: " + failedLoginRedirect.get)
          failedLoginRedirect.get.foreach(S.redirectTo(_))
        }
      }
    }

    bind("user", loginXhtml,
         "email" -> (FocusOnLoad(<input type="text" name="username"/>)),
         "password" -> (<input type="password" name="password"/>),
         "submit" -> loginSubmitButton(S.??("log.in")))
  }

}

class Privilege extends LongKeyedMapper[Privilege] with CreatedUpdated with ManyToMany{
  def getSingleton = Privilege
  def primaryKeyField = id
  object id extends MappedLongIndex(this)
  object user extends MappedLongForeignKey(this, OBPUser)
  object account extends MappedLongForeignKey(this, HostedAccount)
  object views extends MappedManyToMany(ViewPrivileges, ViewPrivileges.privilege, ViewPrivileges.view, ViewImpl)

}

object Privilege extends Privilege with LongKeyedMetaMapper[Privilege]

class HostedAccount extends LongKeyedMapper[HostedAccount] with OneToMany[Long, HostedAccount]{
  def getSingleton = HostedAccount
  def primaryKeyField = id

  object id extends MappedLongIndex(this)
  object views extends MappedOneToMany(ViewImpl, ViewImpl.account, OrderBy(ViewImpl.id_, Ascending))
  //the object id of the mongoDB Account
  object accountID extends MappedString(this, 255)

  def theAccount = Account.find(("_id", accountID.toString))

  def name : String= theAccount match {
    case Full(account) => account.name.get.toString()
    case _ => ""
  }
  def bank : String = theAccount match {
    case Full(account) => account.bankName
    case _ => ""
  }
  def number : String = theAccount match {
    case Full(account) => account.number.get
    case _ => ""
  }
}
object HostedAccount extends HostedAccount with LongKeyedMetaMapper[HostedAccount]{}