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

import code.model._
import net.liftweb.common.{ Box, Empty, Full, Failure }
import net.liftweb.util.Helpers.tryo
import net.liftweb.json.JsonDSL._
import net.liftweb.common.Loggable
import code.model.dataAccess.OBPEnvelope.OBPQueryParam
import net.liftweb.mapper.{By,IHaveValidatedThisSQL}
import net.liftweb.mongodb.MongoDB
import com.mongodb.BasicDBList
import java.util.ArrayList
import org.bson.types.ObjectId
import net.liftweb.mapper.BySql
import net.liftweb.db.DB
import net.liftweb.mongodb.JsonObject
import com.mongodb.QueryBuilder


object LocalStorage extends MongoDBLocalStorage

trait LocalStorage extends Loggable {

  def getBank(name: String): Box[Bank]
  def allBanks : List[Bank]

  def getBankAccount(bankId : String, bankAccountId : String) : Box[BankAccount]
  def getAllPublicAccounts() : List[BankAccount]
  def getPublicBankAccounts(bank : Bank) : Box[List[BankAccount]]
  def getNonPublicBankAccounts(user : User) : Box[List[BankAccount]]
  def getNonPublicBankAccounts(user : User, bankID : String) : Box[List[BankAccount]]
  def getModeratedOtherBankAccount(accountID : String, otherAccountID : String)
  	(moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]) : Box[ModeratedOtherBankAccount]
  def getModeratedOtherBankAccounts(accountID : String)
  	(moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]): Box[List[ModeratedOtherBankAccount]]
  def getModeratedTransactions(permalink: String, bankPermalink: String, queryParams: OBPQueryParam*)
    (moderate: Transaction => ModeratedTransaction): Box[List[ModeratedTransaction]]

  def getUser(id : String) : Box[User]
  def getCurrentUser : Box[User]

  def permissions(account : BankAccount) : Box[List[Permission]]
  def permission(account : BankAccount, user: User) : Box[Permission]
  def addPermission(bankAccountId : String, view : View, user : User) : Box[Boolean]
  def addPermissions(bankAccountId : String, views : List[View], user : User) : Box[Boolean]
  def revokePermission(bankAccountId : String, view : View, user : User) : Box[Boolean]
  def revokeAllPermission(bankAccountId : String, user : User) : Box[Boolean]

  def view(viewPermalink : String) : Box[View]
  def createView(bankAccount : BankAccount, view: ViewCreationJSON) : Box[View]
  def removeView(viewId: String, bankAccount: BankAccount): Box[Unit]
  def views(bankAccountID : String) : Box[List[View]]
  def permittedViews(user: User, bankAccount: BankAccount): List[View]
  def permittedView(user: User, v: View, bankAccount: BankAccount): Boolean
  def publicViews(bankAccountID : String) : Box[List[View]]
  def ownerAccess(user: User, bankAccount: BankAccount) : Boolean

}

class MongoDBLocalStorage extends LocalStorage {

  private def locatationTag(loc: OBPGeoTag): Option[GeoTag]={
    if(loc.longitude==0 && loc.latitude==0 && loc.userId.get.isEmpty)
      None
    else
      Some(loc)
  }
  private def createTransaction(env: OBPEnvelope, theAccount: Account): Transaction = {
    import net.liftweb.json.JsonDSL._
    val transaction: OBPTransaction = env.obp_transaction.get
    val thisAccount = transaction.this_account
    val otherAccount_ = transaction.other_account.get
    val otherUnmediatedHolder = otherAccount_.holder.get

    val thisBankAccount = Account.toBankAccount(theAccount)

    val oAcc = theAccount.otherAccounts.objs.find(o => {
      otherUnmediatedHolder.equals(o.holder.get)
    }).getOrElse {
      OtherAccount.createRecord
    }

    val id = env.id.is.toString()
    val uuid = id
    val otherAccountMetadata =
      new OtherBankAccountMetadata(
        publicAlias = oAcc.publicAlias.get,
        privateAlias = oAcc.privateAlias.get,
        moreInfo = oAcc.moreInfo.get,
        url = oAcc.url.get,
        imageURL = oAcc.imageUrl.get,
        openCorporatesURL = oAcc.openCorporatesUrl.get,
        corporateLocation = locatationTag(oAcc.corporateLocation.get),
        physicalLocation = locatationTag(oAcc.physicalLocation.get),
        addMoreInfo = (text => {
          oAcc.moreInfo(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addURL = (text => {
          oAcc.url(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addImageURL = (text => {
          oAcc.imageUrl(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addOpenCorporatesURL = (text => {
          oAcc.openCorporatesUrl(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addCorporateLocation = oAcc.addCorporateLocation,
        addPhysicalLocation = oAcc.addPhysicalLocation,
        addPublicAlias = (alias => {
          oAcc.publicAlias(alias).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addPrivateAlias = (alias => {
          oAcc.privateAlias(alias).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        deleteCorporateLocation = oAcc.deleteCorporateLocation _,
        deletePhysicalLocation = oAcc.deletePhysicalLocation _
      )
    val otherAccount = new OtherBankAccount(
        id = oAcc.id.is.toString,
        label = otherAccount_.holder.get,
        nationalIdentifier = otherAccount_.bank.get.national_identifier.get,
        swift_bic = None, //TODO: need to add this to the json/model
        iban = Some(otherAccount_.bank.get.IBAN.get),
        number = otherAccount_.number.get,
        bankName = otherAccount_.bank.get.name.get,
        metadata = otherAccountMetadata,
        kind = ""
      )
    val metadata = new TransactionMetadata(
      env.narrative.get,
      (text => env.narrative(text).save),
      env.obp_comments.objs,
      env.addComment,
      env.deleteComment,
      env.tags.objs,
      env.addTag,
      env.deleteTag,
      env.images.objs,
      env.addImage,
      env.deleteImage,
      env.whereTags.get,
      env.addWhereTag,
      env.deleteWhereTag
    )
    val transactionType = transaction.details.get.type_en.get
    val amount = transaction.details.get.value.get.amount.get
    val currency = transaction.details.get.value.get.currency.get
    val label = Some(transaction.details.get.label.get)
    val startDate = transaction.details.get.posted.get
    val finishDate = transaction.details.get.completed.get
    val balance = transaction.details.get.new_balance.get.amount.get
    new Transaction(
      uuid,
      id,
      thisBankAccount,
      otherAccount,
      metadata,
      transactionType,
      amount,
      currency,
      label,
      startDate,
      finishDate,
      balance
    )
  }

  private def createOtherBankAccount(otherAccount : OtherAccount, otherAccountFromTransaction : OBPAccount) : OtherBankAccount = {
    val metadata =
      new OtherBankAccountMetadata(
        publicAlias = otherAccount.publicAlias.get,
        privateAlias = otherAccount.privateAlias.get,
        moreInfo = otherAccount.moreInfo.get,
        url = otherAccount.url.get,
        imageURL = otherAccount.imageUrl.get,
        openCorporatesURL = otherAccount.openCorporatesUrl.get,
        corporateLocation = locatationTag(otherAccount.corporateLocation.get),
        physicalLocation = locatationTag(otherAccount.physicalLocation.get),
        addMoreInfo = (text => {
          otherAccount.moreInfo(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addURL = (text => {
          otherAccount.url(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addImageURL = (text => {
          otherAccount.imageUrl(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addOpenCorporatesURL = (text => {
          otherAccount.openCorporatesUrl(text).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addCorporateLocation = otherAccount.addCorporateLocation,
        addPhysicalLocation = otherAccount.addPhysicalLocation,
        addPublicAlias = (alias => {
          otherAccount.publicAlias(alias).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        addPrivateAlias = (alias => {
          otherAccount.privateAlias(alias).save
          //the save method does not return a Boolean to inform about the saving state,
          //so we a true
          true
        }),
        deleteCorporateLocation = otherAccount.deleteCorporateLocation _,
        deletePhysicalLocation = otherAccount.deletePhysicalLocation _
      )

    new OtherBankAccount(
      id = otherAccount.id.is.toString,
      label = otherAccount.holder.get,
      nationalIdentifier = otherAccountFromTransaction.bank.get.national_identifier.get,
      swift_bic = None, //TODO: need to add this to the json/model
      iban = Some(otherAccountFromTransaction.bank.get.IBAN.get),
      number = otherAccountFromTransaction.number.get,
      bankName = otherAccountFromTransaction.bank.get.name.get,
      metadata = metadata,
      kind = ""
    )
  }

  private def createBank(bank : HostedBank) : Bank = {
    new Bank(
      bank.id.is.toString,
      bank.alias.is,
      bank.name.is,
      bank.permalink.is,
      bank.logoURL.is,
      bank.website.is
    )
  }

  private def getHostedBank(permalink : String) : Box[HostedBank] = {
    for{
      bank <- HostedBank.find("permalink", permalink) ?~ {"bank " + permalink + " not found"}
    } yield bank
  }

  private def getTransaction(id : String, bankPermalink : String, accountPermalink : String) : Box[Transaction] = {
    for{
      bank <- getHostedBank(bankPermalink)
      account  <- bank.getAccount(accountPermalink)
      objectId <- tryo{new ObjectId(id)} ?~ {"Transaction "+id+" not found"}
      envelope <- OBPEnvelope.find(account.transactionsForAccount.put("_id").is(objectId).get)
    } yield createTransaction(envelope,account)
  }

  private def getTransactions(permalink: String, bankPermalink: String, queryParams: OBPQueryParam*): Box[List[Transaction]] = {
      logger.debug("getTransactions for " + bankPermalink + "/" + permalink)
      for{
        bank <- getHostedBank(bankPermalink)
        account <- bank.getAccount(permalink)
      } yield account.envelopes(queryParams: _*).map(createTransaction(_, account))
  }

  def getBank(permalink: String): Box[Bank] =
    for{
      bank <- getHostedBank(permalink)
    } yield createBank(bank)

  def allBanks : List[Bank] =
    HostedBank.findAll.map(createBank)

  def getBankAccount(bankId : String, bankAccountId : String) : Box[BankAccount] = {
    for{
      bank <- getHostedBank(bankId)
      account <- bank.getAccount(bankAccountId)
    } yield Account toBankAccount account
  }

  def getAllPublicAccounts() : List[BankAccount] = {
    ViewImpl.findAll(By(ViewImpl.isPublic_, true)).
      map{_.account.obj}.
      collect{case Full(a) => a.theAccount}.
      collect{case Full(a) => Account.toBankAccount(a)}
  }

  def getPublicBankAccounts(bank : Bank) : Box[List[BankAccount]] = {
    val bankAccounts = ViewImpl.findAll(By(ViewImpl.isPublic_, true)).
      map{_.account.obj}.
      collect{case Full(a) if a.bank==bank.fullName => a.theAccount}.
      collect{case Full(a) => Account.toBankAccount(a)}
    Full(bankAccounts)
  }

  private def moreThanAnonHostedAccounts(user : User) : List[HostedAccount] = {
    user match {
      case u : OBPUser => {
        Privilege.findAll(By(Privilege.user, u.id)).
          filter(_.views.exists(_.isPublic==false)).
          map(_.account.obj.get)
      }
      case _ => {
        logger.error("OBPUser instance not found, could not find the accounts")
        Nil
      }
    }
  }

  /**
  * @return the bank accounts where the user has at least access to a non public view (is_public==false)
  */
  def getNonPublicBankAccounts(user : User) :  Box[List[BankAccount]] = {

    val accountsList =
      user match {
        case u : OBPUser => {
          val moreThanAnon = moreThanAnonHostedAccounts(u)
          val mongoIds = moreThanAnon.map(hAcc => new ObjectId(hAcc.accountID.get))
          Account.findAll(mongoIds).map(Account.toBankAccount)
        }
        case u: User => {
          logger.error("OBPUser instance not found, could not find the non public accounts")
          Nil
        }
      }
    Full(accountsList)
  }

  /**
  * @return the bank accounts where the user has at least access to a non public view (is_public==false) for a specific bank
  */
  def getNonPublicBankAccounts(user : User, bankID : String) :  Box[List[BankAccount]] = {
    user match {
      case u : OBPUser => {
        for {
          bankObjectId <- tryo{new ObjectId(bankID)}
        } yield {
          def sameBank(account : Account) : Boolean =
            account.bankID.get == bankObjectId

          val moreThanAnon = moreThanAnonHostedAccounts(u)
          val mongoIds = moreThanAnon.map(hAcc => new ObjectId(hAcc.accountID.get))
          Account.findAll(mongoIds).filter(sameBank).map(Account.toBankAccount)
        }
      }
      case u : User => {
        logger.error("OBPUser instance not found, could not find the non public account ")
        Full(Nil)
      }
    }
  }

  def getModeratedOtherBankAccount(accountID : String, otherAccountID : String)
  (moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]): Box[ModeratedOtherBankAccount] = {
      for{
        id <- tryo{new ObjectId(accountID)} ?~ {"account " + accountID + " not found"}
        account <- Account.find("_id",id)
        otherAccount <- account.otherAccounts.objs.find(_.id.get.equals(otherAccountID))
      } yield{
          val otherAccountFromTransaction : OBPAccount = OBPEnvelope.find("obp_transaction.other_account.holder",otherAccount.holder.get) match {
            case Full(envelope) => envelope.obp_transaction.get.other_account.get
            case _ => OBPAccount.createRecord
          }
          moderate(createOtherBankAccount(otherAccount, otherAccountFromTransaction)).get
        }
  }

  def getModeratedOtherBankAccounts(accountID : String)
  (moderate: OtherBankAccount => Option[ModeratedOtherBankAccount]): Box[List[ModeratedOtherBankAccount]] = {
    for{
      id <- tryo{new ObjectId(accountID)} ?~ {"account " + accountID + " not found"}
      account <- Account.find("_id",id)
    } yield{
        val otherBankAccounts = account.otherAccounts.objs.map(otherAccount => {
          //for legacy reasons some of the data about the "other account" are stored only on the transactions
          //so we need first to get a transaction that match to have the rest of the data
          val otherAccountFromTransaction : OBPAccount = OBPEnvelope.find("obp_transaction.other_account.holder",otherAccount.holder.get) match {
              case Full(envelope) =>
                envelope.obp_transaction.get.other_account.get
              case _ => OBPAccount.createRecord
            }
          createOtherBankAccount(otherAccount, otherAccountFromTransaction)
        })

        (otherBankAccounts.map(moderate)).collect{case Some(t) => t}
      }
  }

  def getModeratedTransactions(permalink: String, bankPermalink: String, queryParams: OBPQueryParam*)
  (moderate: Transaction => ModeratedTransaction): Box[List[ModeratedTransaction]] = {
    for{
      rawTransactions <- getTransactions(permalink, bankPermalink, queryParams: _*)
    } yield rawTransactions.map(moderate)
  }

  def getUser(id : String) : Box[User] =
    OBPUser.find(By(OBPUser.email,id)) match {
      case Full(u) => Full(u)
      case _ => Failure("user " + id + " not found")
    }

  def getModeratedTransaction(id : String, bankPermalink : String, accountPermalink : String)
  (moderate: Transaction => ModeratedTransaction) : Box[ModeratedTransaction] = {
    for{
      transaction <- getTransaction(id,bankPermalink,accountPermalink)
    } yield moderate(transaction)
  }

  def getCurrentUser : Box[User] = OBPUser.currentUser

  def permissions(account : BankAccount) : Box[List[Permission]] = {
    for{
      acc <- HostedAccount.find(By(HostedAccount.accountID,account.id))
    } yield {
        val privileges = Privilege.findAll(By(Privilege.account, acc.id.get)).sortWith((p1,p2) => p1.updatedAt.get after p2.updatedAt.get)
        val permissions : List[Box[Permission]] =
          privileges.map( p => {
            p.user.obj.map(u => {
              new Permission(
                u,
                p.views.toList
              )
            })
          })
        permissions.flatten
      }
  }

  def permission(account : BankAccount, user: User) : Box[Permission] = {
    user match {
      case u: OBPUser =>{
        for{
          acc <- HostedAccount.find(By(HostedAccount.accountID,account.id))
          p <- Privilege.find(By(Privilege.account, acc.id.get), By(Privilege.user, u)) ?~ {"No permission found for user: " + u.id}
        } yield Permission(u, p.views.toList)
      }
      case u: User =>{
        logger.error("OBPUser instance not found, could not get privilege ")
        Failure("could not get user: " + user.id_ + " permission" )
      }
    }
  }

  def addPermission(bankAccountId : String, view: View, user : User) : Box[Boolean] = {
    user match {
      case u: OBPUser =>
        for{
          bankAccount <- HostedAccount.find(By(HostedAccount.accountID, bankAccountId))
        } yield {
            val privilege =
              Privilege.find(By(Privilege.user, u.id), By(Privilege.account, bankAccount)) match {
                //update the existing privilege
                case Full(p) => p
                //there is no privilege to this user, so we create one
                case _ =>
                  Privilege.create.
                    user(u.id).
                    account(bankAccount).
                    saveMe
              }
            ViewPrivileges.create.
              privilege(privilege).
              view(view.id).
              save
          }
      case u: User => {
          logger.error("OBPUser instance not found, could not grant access ")
          Empty
      }
    }
  }

  def addPermissions(bankAccountId : String, views : List[View], user : User) : Box[Boolean] ={
    user match {
      case u : OBPUser => {
        for{
          bankAccount <- HostedAccount.find(By(HostedAccount.accountID, bankAccountId))
        } yield {
            val privilege =
              Privilege.find(By(Privilege.user, u.id), By(Privilege.account, bankAccount)) match {
                //update the existing privilege
                case Full(p) => p
                //there is no privilege to this user, so we create one
                case _ =>
                  Privilege.create.
                    user(u.id).
                    account(bankAccount).
                    saveMe
              }
            views.foreach(v => {
              ViewPrivileges.create.
                privilege(privilege).
                view(v.id).
                save
            })
            true
          }
      }
      case u: User => {
        logger.error("OBPUser instance not found, could not grant access ")
        Empty
      }
    }

  }
  def revokePermission(bankAccountId : String, view : View, user : User) : Box[Boolean] = {
    user match {
      case u:OBPUser =>
        for{
          bankAccount <- HostedAccount.find(By(HostedAccount.accountID, bankAccountId))
          p <- Privilege.find(By(Privilege.user, u), By(Privilege.account, bankAccount))
          vp <- ViewPrivileges.find(By(ViewPrivileges.privilege, p), By(ViewPrivileges.view, view.id))
        } yield {
              vp.delete_!
          }
      case u: User => {
        logger.error("OBPUser instance not found, could not revoke access ")
        Empty
      }
    }
  }

  def revokeAllPermission(bankAccountId : String, user : User) : Box[Boolean] = {
    user match {
      case u:OBPUser =>{
        for{
          bankAccount <- HostedAccount.find(By(HostedAccount.accountID, bankAccountId))
          p <- Privilege.find(By(Privilege.user, u), By(Privilege.account, bankAccount))
        } yield {
          ViewPrivileges.findAll(By(ViewPrivileges.privilege, p)).map(_.delete_!)
          true
        }
      }
      case u: User => {
        logger.error("OBPUser instance not found, could not revoke access ")
        Empty
      }
    }
  }

  def view(viewPermalink : String) : Box[View] = {
    ViewImpl.find(By(ViewImpl.permalink_, viewPermalink))
  }

  def createView(bankAccount: BankAccount, view: ViewCreationJSON): Box[View] = {
    def generatePermalink(name: String): String = {
      name.replaceAllLiterally(" ","").toLowerCase
    }

    if(view.name=="Owner")
      Failure("There is already an Owner view on this bank account")
    else
      for{
          account <- HostedAccount.find(By(HostedAccount.accountID,bankAccount.id))
        } yield{
            val createdView = ViewImpl.create.
              name_(view.name).
              description_(view.description).
              permalink_(generatePermalink(view.name)).
              isPublic_(view.is_public).
              account(account)

            if(view.which_alias_to_use == "public"){
              createdView.usePrivateAliasIfOneExists_(true)
              createdView.hideOtherAccountMetadataIfAlias_(view.hide_metadata_if_alias_used)
            }
            else if(view.which_alias_to_use == "private"){
              createdView.usePublicAliasIfOneExists_(true)
              createdView.hideOtherAccountMetadataIfAlias_(view.hide_metadata_if_alias_used)
            }

            if(view.allowed_actions.exists(a => a=="can_see_transaction_this_bank_account"))
              createdView.canSeeTransactionThisBankAccount_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_other_bank_account"))
              createdView.canSeeTransactionOtherBankAccount_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_metadata"))
              createdView.canSeeTransactionMetadata_(true)
            if(view.allowed_actions.exists(a => (a=="can_see_transaction_label" || a=="can_see_transaction_description")))
              createdView.canSeeTransactionDescription_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_amount"))
              createdView.canSeeTransactionAmount_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_type"))
              createdView.canSeeTransactionType_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_currency"))
              createdView.canSeeTransactionCurrency_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_start_date"))
              createdView.canSeeTransactionStartDate_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_finish_date"))
              createdView.canSeeTransactionFinishDate_(true)
            if(view.allowed_actions.exists(a => a=="can_see_transaction_balance"))
              createdView.canSeeTransactionBalance_(true)
            if(view.allowed_actions.exists(a => a=="can_see_comments"))
              createdView.canSeeComments_(true)
            if(view.allowed_actions.exists(a => a=="can_see_narrative"))
              createdView.canSeeOwnerComment_(true)
            if(view.allowed_actions.exists(a => a=="can_see_tags"))
              createdView.canSeeTags_(true)
            if(view.allowed_actions.exists(a => a=="can_see_images"))
              createdView.canSeeImages_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_owners"))
              createdView.canSeeBankAccountOwners_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_type"))
              createdView.canSeeBankAccountType_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_balance"))
              createdView.canSeeBankAccountBalance_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_currency"))
              createdView.canSeeBankAccountCurrency_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_label"))
              createdView.canSeeBankAccountLabel_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_national_identifier"))
              createdView.canSeeBankAccountNationalIdentifier_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_swift_bic"))
              createdView.canSeeBankAccountSwift_bic_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_iban"))
              createdView.canSeeBankAccountIban_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_number"))
              createdView.canSeeBankAccountNumber_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_bank_name"))
              createdView.canSeeBankAccountBankName_(true)
            if(view.allowed_actions.exists(a => a=="can_see_bank_account_bank_permalink"))
              createdView.canSeeBankAccountBankPermalink_(true)
            if(view.allowed_actions.exists(a => a=="can_see_other_account_national_identifier"))
              createdView.canSeeOtherAccountNationalIdentifier_(true)
            if(view.allowed_actions.exists(a => a=="can_see_other_account_swift_bic"))
              createdView.canSeeOtherAccountSWIFT_BIC_(true)
            if(view.allowed_actions.exists(a => a=="can_see_other_account_iban"))
              createdView.canSeeOtherAccountIBAN_(true)
            if(view.allowed_actions.exists(a => a=="can_see_other_account_bank_name"))
              createdView.canSeeOtherAccountBankName_(true)
            if(view.allowed_actions.exists(a => a=="can_see_other_account_number"))
              createdView.canSeeOtherAccountNumber_(true)
            if(view.allowed_actions.exists(a => a=="can_see_other_account_metadata"))
              createdView.canSeeOtherAccountMetadata_(true)
            if(view.allowed_actions.exists(a => a=="can_see_other_account_kind"))
              createdView.canSeeOtherAccountKind_(true)
            if(view.allowed_actions.exists(a => a=="can_see_more_info"))
              createdView.canSeeMoreInfo_(true)
            if(view.allowed_actions.exists(a => a=="can_see_url"))
              createdView.canSeeUrl_(true)
            if(view.allowed_actions.exists(a => a=="can_see_image_url"))
              createdView.canSeeImageUrl_(true)
            if(view.allowed_actions.exists(a => a=="can_see_open_corporates_url"))
              createdView.canSeeOpenCorporatesUrl_(true)
            if(view.allowed_actions.exists(a => a=="can_see_corporate_location"))
              createdView.canSeeCorporateLocation_(true)
            if(view.allowed_actions.exists(a => a=="can_see_physical_location"))
              createdView.canSeePhysicalLocation_(true)
            if(view.allowed_actions.exists(a => a=="can_see_public_alias"))
              createdView.canSeePublicAlias_(true)
            if(view.allowed_actions.exists(a => a=="can_see_private_alias"))
              createdView.canSeePrivateAlias_(true)
            if(view.allowed_actions.exists(a => a=="can_add_more_info"))
              createdView.canAddMoreInfo_(true)
            if(view.allowed_actions.exists(a => a=="can_add_url"))
              createdView.canAddURL_(true)
            if(view.allowed_actions.exists(a => a=="can_add_image_url"))
              createdView.canAddImageURL_(true)
            if(view.allowed_actions.exists(a => a=="can_add_open_corporates_url"))
              createdView.canAddOpenCorporatesUrl_(true)
            if(view.allowed_actions.exists(a => a=="can_add_corporate_location"))
              createdView.canAddCorporateLocation_(true)
            if(view.allowed_actions.exists(a => a=="can_add_physical_location"))
              createdView.canAddPhysicalLocation_(true)
            if(view.allowed_actions.exists(a => a=="can_add_public_alias"))
              createdView.canAddPublicAlias_(true)
            if(view.allowed_actions.exists(a => a=="can_add_private_alias"))
              createdView.canAddPrivateAlias_(true)
            if(view.allowed_actions.exists(a => a=="can_delete_corporate_location"))
              createdView.canDeleteCorporateLocation_(true)
            if(view.allowed_actions.exists(a => a=="can_delete_physical_location"))
              createdView.canDeletePhysicalLocation_(true)
            if(view.allowed_actions.exists(a => a=="can_edit_narrative"))
              createdView.canEditOwnerComment_(true)
            if(view.allowed_actions.exists(a => a=="can_add_comment"))
              createdView.canAddComment_(true)
            if(view.allowed_actions.exists(a => a=="can_delete_comment"))
              createdView.canDeleteComment_(true)
            if(view.allowed_actions.exists(a => a=="can_add_tag"))
              createdView.canAddTag_(true)
            if(view.allowed_actions.exists(a => a=="can_delete_tag"))
              createdView.canDeleteTag_(true)
            if(view.allowed_actions.exists(a => a=="can_add_image"))
              createdView.canAddImage_(true)
            if(view.allowed_actions.exists(a => a=="can_delete_image"))
              createdView.canDeleteImage_(true)
            if(view.allowed_actions.exists(a => a=="can_add_where_tag"))
              createdView.canAddWhereTag_(true)
            if(view.allowed_actions.exists(a => a=="can_see_where_tag"))
              createdView.canSeeWhereTag_(true)
            if(view.allowed_actions.exists(a => a=="can_delete_where_tag"))
              createdView.canDeleteWhereTag_(true)
            createdView.saveMe
        }
  }

  def removeView(viewId: String, bankAccount: BankAccount): Box[Unit] = {
    if(viewId=="Owner")
      Failure("you cannot delete the Owner view")
    else
      for{
        v <- ViewImpl.find(By(ViewImpl.permalink_,viewId)) ?~ "view not found"
        if(v.delete_!)
      } yield {}
  }

  def views(bankAccountID : String) : Box[List[View]] = {
    for(account <- HostedAccount.find(By(HostedAccount.accountID,bankAccountID)))
      yield account.views.toList
  }

  def permittedViews(user: User, bankAccount: BankAccount): List[View] = {
    user match {
      case u: OBPUser=> {
        HostedAccount.find(By(HostedAccount.accountID, bankAccount.id)) match {
          case Full(account) =>
            Privilege.find(By(Privilege.user, u.id), By(Privilege.account,account)) match {
              case Full(p) => p.views.toList
              case _ => Nil
            }
          case _ => Nil
        }
      }
      case _ => {
        logger.error("OBPUser instance not found, could not get Permitted views")
        Nil
      }
    }
  }

  def permittedView(user: User, v: View, bankAccount: BankAccount): Boolean = {
    user match {
      case u: OBPUser=> {
        HostedAccount.find(By(HostedAccount.accountID, bankAccount.id)) match {
          case Full(account) =>
            Privilege.find(By(Privilege.user, u.id), By(Privilege.account, account)) match {
              case Full(p) => ViewPrivileges.count(By(ViewPrivileges.privilege, p), By(ViewPrivileges.view, v.id)) == 1
              case _ => false
            }
          case _ => false
        }
      }
      case _ => {
        logger.error("OBPUser instance not found, could not get the privilege")
        false
      }
    }
  }

  def publicViews(bankAccountID: String) : Box[List[View]] = {
    for{account <- HostedAccount.find(By(HostedAccount.accountID,bankAccountID))}
      yield{
        account.views.toList.filter(v => v.isPublic==true)
      }
  }

  def ownerAccess(user: User, bankAccount: BankAccount) : Boolean = {
    user match {
      case u: OBPUser=> {
        val ownerView = for{
          account <- HostedAccount.find(By(HostedAccount.accountID,bankAccount.id))
          v <- ViewImpl.find(By(ViewImpl.account, account.id), By(ViewImpl.name_, "Owner"))
          p <- Privilege.find(By(Privilege.user, u.id), By(Privilege.account, account))
        } yield {
          p.views.contains(v)
        }
        ownerView.getOrElse(false)
      }
      case _ => {
        logger.error("OBPUser instance not found, could not get the privilege")
        false
      }
    }
  }
}