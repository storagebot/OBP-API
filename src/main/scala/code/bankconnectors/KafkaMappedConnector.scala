package code.bankconnectors

import java.util.{Calendar, Date, UUID, TimeZone, Locale, Properties}
import java.text.{SimpleDateFormat, DateFormat}

import code.metadata.comments.MappedComment
import code.metadata.counterparties.Counterparties
import code.metadata.narrative.MappedNarrative
import code.metadata.tags.MappedTag
import code.metadata.transactionimages.MappedTransactionImage
import code.metadata.wheretags.MappedWhereTag
import code.model.dataAccess.ViewImpl
import code.model.dataAccess.ViewPrivileges

import code.model._
import code.model.dataAccess.{UpdatesRequestSender, MappedBankAccount, MappedAccountHolder, MappedBank}
import code.tesobe.CashTransaction
import code.management.ImporterAPI.ImporterTransaction
import code.transactionrequests.{TransactionRequests, MappedTransactionRequest}
import code.transactionrequests.TransactionRequests.{TransactionRequestChallenge, TransactionRequest, TransactionRequestBody}
import code.util.Helper
import com.tesobe.model.UpdateBankAccount
import net.liftweb.common.{Loggable, Full, Box, Failure}
import net.liftweb.mapper._
import net.liftweb.util.Helpers._
import net.liftweb.util.{False, Props}

import scala.concurrent.ops._

object KafkaMappedConnector extends Connector with Loggable {

  type AccountType = MappedBankAccount

  //gets a particular bank handled by this connector
  override def getBank(bankId: BankId): Box[Bank] =
    getMappedBank(bankId)

  private def getMappedBank(bankId: BankId): Box[MappedBank] =
    MappedBank.find(By(MappedBank.permalink, bankId.value))

  //gets banks handled by this connector
  override def getBanks: List[Bank] =
    MappedBank.findAll

/*
  //gets banks handled by this connector
  override def getBanks: List[Bank] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create Kafka producer
    val producer: KafkaProducer = new KafkaProducer()
    // Create empty argument list
    val argList: Map[String, String] = Map()
    // Send request to Kafka, marked with reqId 
    // so we can fetch the corresponding response 
    producer.send(reqId, "getBanks", argList, "1")
    // Request sent, now we wait for response with the same reqId
    val consumer = new KafkaConsumer()
    val rList = consumer.getResponse(reqId)
    // Loop through list of responses and create entry for each
    val res = { for ( r <- rList ) yield {
        MappedBank.create
        .permalink(r.getOrElse("BankId", ""))
        .shortBankName(r.getOrElse("shortBankName", ""))
        .fullBankName(r.getOrElse("fullBankName", ""))
        .logoURL(r.getOrElse("logoURL", ""))
        .websiteURL(r.getOrElse("websiteURL", ""))
      }
    }
    // Return list of results
    res
  }

  // Gets bank identified by bankId
  override def getBank(bankId: code.model.BankId): Box[Bank] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create Kafka producer
    val producer: KafkaProducer = new KafkaProducer()
    // Create argument list
    val argList = Map( "bankId" -> bankId.toString )
    // Send request to Kafka, marked with reqId 
    // so we can fetch the corresponding response 
    producer.send(reqId, "getBank", argList, "1")
    // Request sent, now we wait for response with the same reqId
    val consumer = new KafkaConsumer()
    // Create entry only for the first item on returned list 
    val r = consumer.getResponse(reqId).head
    val res = MappedBank.create
             .permalink(r.getOrElse("bankId", ""))
             .shortBankName(r.getOrElse("shortBankName", ""))
             .fullBankName(r.getOrElse("fullBankName", ""))
             .logoURL(r.getOrElse("logoURL", ""))
             .websiteURL(r.getOrElse("websiteURL", ""))
    // Return result
    Full(res)
  }
*/

  // Gets transaction identified by bankid, accountid and transactionId 
  def getTransaction(bankId: BankId, accountID: AccountId, transactionId: TransactionId): Box[Transaction] = {

    updateAccountTransactions(bankId, accountID)

    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString

    // Create Kafka producer, using list of brokers from Zookeeper
    val producer: KafkaProducer = new KafkaProducer()
    // Send request to Kafka, marked with reqId 
    // so we can fetch the corresponding response 
    val argList = Map( "bankId" -> bankId.toString,
                       "accountId" -> accountID.toString,
                       "transactionId" -> transactionId.toString )
    producer.send(reqId, "getTransaction", argList, "1")

    // Request sent, now we wait for response with the same reqId
    val consumer = new KafkaConsumer()
    // Create entry only for the first item on returned list 
    val r = consumer.getResponse(reqId).head

    // If empty result from Kafka return empty data 
    if (r.getOrElse("accountId", "") == "") {
      val res = MappedTransaction.find(
                  By(MappedTransaction.transactionId, "EMPTY")).flatMap(_.toTransaction)
      return res
    }

    // helper for creating otherbankaccount
    def createOtherBankAccount(alreadyFoundMetadata : Option[OtherBankAccountMetadata]) = {
      new OtherBankAccount(
        id = alreadyFoundMetadata.map(_.metadataId).getOrElse(""),
        label = r.getOrElse("label", ""),
        nationalIdentifier = r.getOrElse("nationalIdentifier ", ""),
        swift_bic = Some(r.getOrElse("swift_bic", "")), //TODO: need to add this to the json/model?
        iban = Some(r.getOrElse("iban", "")),
        number = r.getOrElse("number", ""),
        bankName = r.getOrElse("bankName", ""),
        kind = r.getOrElse("accountType", ""),
        originalPartyBankId = new BankId(r.getOrElse("bankId", "")),
        originalPartyAccountId = new AccountId(r.getOrElse("accountId", "")),
        alreadyFoundMetadata = alreadyFoundMetadata
      )
    }
    //creates a dummy OtherBankAccount without an OtherBankAccountMetadata, which results in one being generated (in OtherBankAccount init)
    val dummyOtherBankAccount = createOtherBankAccount(None)
    //and create the proper OtherBankAccount with the correct "id" attribute set to the metadataId of the OtherBankAccountMetadata object
    //note: as we are passing in the OtherBankAccountMetadata we don't incur another db call to get it in OtherBankAccount init
    val otherAccount = createOtherBankAccount(Some(dummyOtherBankAccount.metadata))

    Full(
      new Transaction(
        TransactionId(r.getOrElse("accountId", "")).value,                                                       // uuid:String
        TransactionId(r.getOrElse("accountId", "")),                                                             // id:TransactionId
        getBankAccount(BankId(r.getOrElse("bankId", "")), AccountId(r.getOrElse("accountId", ""))).openOr(null), // thisAccount:BankAccount
        otherAccount,                                                                                            // otherAccount:OtherBankAccount
        r.getOrElse("transactionType", ""),                                                                      // transactionType:String
        BigDecimal(r.getOrElse("amount", "0.0")),                                                                // val amount:BigDecimal
        r.getOrElse("currency", ""),                                                                             // currency:String
        Some(r.getOrElse("description", "")),                                                                    // description:Option[String]
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.getOrElse("startDate", "1970-01-01T00:00:00.000Z")),  // startDate:Date
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.getOrElse("finishDate", "1970-01-01T00:00:00.000Z")), // finishDate:Date
        BigDecimal(r.getOrElse("balance", "0.0"))                                                                // balance:BigDecimal
    ))
  }


  override def getTransactions(bankId: BankId, accountID: AccountId, queryParams: OBPQueryParam*): Box[List[Transaction]] = {
    val limit = queryParams.collect { case OBPLimit(value) => MaxRows[MappedTransaction](value) }.headOption
    val offset = queryParams.collect { case OBPOffset(value) => StartAt[MappedTransaction](value) }.headOption
    val fromDate = queryParams.collect { case OBPFromDate(date) => By_>=(MappedTransaction.tFinishDate, date) }.headOption
    val toDate = queryParams.collect { case OBPToDate(date) => By_<=(MappedTransaction.tFinishDate, date) }.headOption
    val ordering = queryParams.collect {
      //we don't care about the intended sort field and only sort on finish date for now
      case OBPOrdering(_, direction) =>
        direction match {
          case OBPAscending => OrderBy(MappedTransaction.tFinishDate, Ascending)
          case OBPDescending => OrderBy(MappedTransaction.tFinishDate, Descending)
        }
    }

    val optionalParams : Seq[QueryParam[MappedTransaction]] = Seq(limit.toSeq, offset.toSeq, fromDate.toSeq, toDate.toSeq, ordering.toSeq).flatten
    val mapperParams = Seq(By(MappedTransaction.bank, bankId.value), By(MappedTransaction.account, accountID.value)) ++ optionalParams

    //val mappedTransactions = MappedTransaction.findAll(mapperParams: _*)

    ////////////////////////////////////////////////////////////
    //// Populate Transactions with data from from Kafka sandbox
    //
    // Generate random uuid to be used as request-response match id
    val reqId: String = UUID.randomUUID().toString
    // Create Kafka producer, using list of brokers from Zookeeper
    val producer: KafkaProducer = new KafkaProducer()
    // Send request to Kafka, marked with reqId 
    // so we can fetch the corresponding response 
    val argList = Map( "bankId" -> bankId.toString,
                       "accountId" -> accountID.toString,
                       "queryParams" -> queryParams.toString )
    producer.send(reqId, "getTransactions", argList, "1")
    // Request sent, now we wait for response with the same reqId
    val consumer = new KafkaConsumer()
    // Create entry only for the first item on returned list 
    val rList = consumer.getResponse(reqId)
    // Return blank if empty 
    if (rList(0).getOrElse("accountId", "") == "") {
      return Full(List())
    }
    // Populate fields and generate result
    val res = { for ( r <- rList ) yield {
      // helper for creating otherbankaccount
      def createOtherBankAccount(alreadyFoundMetadata : Option[OtherBankAccountMetadata]) = {
        new OtherBankAccount(
          id = alreadyFoundMetadata.map(_.metadataId).getOrElse(""),
          label = r.getOrElse("label", ""),
          nationalIdentifier = r.getOrElse("nationalIdentifier ", ""),
          swift_bic = Some(r.getOrElse("swift_bic", "")), //TODO: need to add this to the json/model
          iban = Some(r.getOrElse("iban", "")),
          number = r.getOrElse("number", ""),
          bankName = r.getOrElse("bankName", ""),
          kind = r.getOrElse("accountType", ""),
          originalPartyBankId = new BankId(r.getOrElse("bankId", "")),
          originalPartyAccountId = new AccountId(r.getOrElse("accountId", "")),
          alreadyFoundMetadata = alreadyFoundMetadata
        )
      }
      //creates a dummy OtherBankAccount without an OtherBankAccountMetadata, which results in one being generated (in OtherBankAccount init)
      val dummyOtherBankAccount = createOtherBankAccount(None)
      //and create the proper OtherBankAccount with the correct "id" attribute set to the metadataId of the OtherBankAccountMetadata object
      //note: as we are passing in the OtherBankAccountMetadata we don't incur another db call to get it in OtherBankAccount init
      val otherAccount = createOtherBankAccount(Some(dummyOtherBankAccount.metadata))
      new Transaction(
        TransactionId(r.getOrElse("transactionId", "")).value,                                                   // uuid:String
        TransactionId(r.getOrElse("transactionId", "")),                                                         // id:TransactionId
        getBankAccount(BankId(r.getOrElse("bankId", "")), AccountId(r.getOrElse("accountId", ""))).openOr(null), // thisAccount:BankAccount
        otherAccount,                                                                                            // otherAccount:OtherBankAccount
        r.getOrElse("transactionType", ""),                                                                      // transactionType:String
        BigDecimal(r.getOrElse("amount", "0.0")),                                                                // val amount:BigDecimal
        r.getOrElse("currency", ""),                                                                             // currency:String
        Some(r.getOrElse("description", "")),                                                                    // description:Option[String]
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.getOrElse("startDate", "1970-01-01T00:00:00.000Z")),  // startDate:Date
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.getOrElse("finishDate", "1970-01-01T00:00:00.000Z")), // finishDate:Date
        BigDecimal(r.getOrElse("balance", "0.0"))                                                                // balance:BigDecimal
        )
      }
    }

    updateAccountTransactions(bankId, accountID)

    return Full(res)
  }


  /**
   *
   * refreshes transactions via hbci if the transaction info is sourced from hbci
   *
   *  Checks if the last update of the account was made more than one hour ago.
   *  if it is the case we put a message in the message queue to ask for
   *  transactions updates
   *
   *  It will be used each time we fetch transactions from the DB. But the test
   *  is performed in a different thread.
   */
  private def updateAccountTransactions(bankId : BankId, accountID : AccountId) = {

    for {
      bank <- getMappedBank(bankId)
      account <- getBankAccountType(bankId, accountID)
    } {
      spawn{
        val useMessageQueue = Props.getBool("messageQueue.updateBankAccountsTransaction", false)
        val outDatedTransactions = Box!!account.accountLastUpdate.get match {
          case Full(l) => now after time(l.getTime + hours(Props.getInt("messageQueue.updateTransactionsInterval", 1)))
          case _ => true
        }
        if(outDatedTransactions && useMessageQueue) {
          UpdatesRequestSender.sendMsg(UpdateBankAccount(account.accountNumber.get, bank.national_identifier.get))
        }
      }
    }
  }

  override def getBankAccountType(bankId: BankId, accountID: AccountId): Box[MappedBankAccount] = {
    MappedBankAccount.find(
      By(MappedBankAccount.bank, bankId.value),
      By(MappedBankAccount.theAccountId, accountID.value))
  }

  //gets the users who are the legal owners/holders of the account
  override def getAccountHolders(bankId: BankId, accountID: AccountId): Set[User] =
    MappedAccountHolder.findAll(
      By(MappedAccountHolder.accountBankPermalink, bankId.value),
      By(MappedAccountHolder.accountPermalink, accountID.value)).map(accHolder => accHolder.user.obj).flatten.toSet


  def getOtherBankAccount(thisAccountBankId : BankId, thisAccountId : AccountId, metadata : OtherBankAccountMetadata) : Box[OtherBankAccount] = {
    //because we don't have a db backed model for OtherBankAccounts, we need to construct it from an
    //OtherBankAccountMetadata and a transaction
    for { //find a transaction with this counterparty
      t <- MappedTransaction.find(
        By(MappedTransaction.bank, thisAccountBankId.value),
        By(MappedTransaction.account, thisAccountId.value),
        By(MappedTransaction.counterpartyAccountHolder, metadata.getHolder),
        By(MappedTransaction.counterpartyAccountNumber, metadata.getAccountNumber))
    } yield {
      new OtherBankAccount(
        //counterparty id is defined to be the id of its metadata as we don't actually have an id for the counterparty itself
        id = metadata.metadataId,
        label = metadata.getHolder,
        nationalIdentifier = t.counterpartyNationalId.get,
        swift_bic = None,
        iban = t.getCounterpartyIban(),
        number = metadata.getAccountNumber,
        bankName = t.counterpartyBankName.get,
        kind = t.counterpartyAccountKind.get,
        originalPartyBankId = thisAccountBankId,
        originalPartyAccountId = thisAccountId,
        alreadyFoundMetadata = Some(metadata)
      )
    }
  }

  // Get all counterparties related to an account
  override def getOtherBankAccounts(bankId: BankId, accountID: AccountId): List[OtherBankAccount] =
    Counterparties.counterparties.vend.getMetadatas(bankId, accountID).flatMap(getOtherBankAccount(bankId, accountID, _))

  // Get one counterparty related to a bank account
  override def getOtherBankAccount(bankId: BankId, accountID: AccountId, otherAccountID: String): Box[OtherBankAccount] =
    // Get the metadata and pass it to getOtherBankAccount to construct the other account.
    Counterparties.counterparties.vend.getMetadata(bankId, accountID, otherAccountID).flatMap(getOtherBankAccount(bankId, accountID, _))

  override def getPhysicalCards(user: User): Set[PhysicalCard] =
    Set.empty

  override def getPhysicalCardsForBank(bankId: BankId, user: User): Set[PhysicalCard] =
    Set.empty


  override def makePaymentImpl(fromAccount: MappedBankAccount, toAccount: MappedBankAccount, amt: BigDecimal, description : String): Box[TransactionId] = {
    val fromTransAmt = -amt //from account balance should decrease
    val toTransAmt = amt //to account balance should increase

    //we need to save a copy of this payment as a transaction in each of the accounts involved, with opposite amounts
    val sentTransactionId = saveTransaction(fromAccount, toAccount, fromTransAmt, description)
    saveTransaction(toAccount, fromAccount, toTransAmt, description)

    sentTransactionId
  }

  /**
   * Saves a transaction with amount @amt and counterparty @counterparty for account @account. Returns the id
   * of the saved transaction.
   */
  private def saveTransaction(account : MappedBankAccount, counterparty : BankAccount, amt : BigDecimal, description : String) : Box[TransactionId] = {

    val transactionTime = now
    val currency = account.currency


    //update the balance of the account for which a transaction is being created
    val newAccountBalance : Long = account.accountBalance.get + Helper.convertToSmallestCurrencyUnits(amt, account.currency)
    account.accountBalance(newAccountBalance).save()

    val mappedTransaction = MappedTransaction.create
      .bank(account.bankId.value)
      .account(account.accountId.value)
      .transactionType("sandbox-payment")
      .amount(Helper.convertToSmallestCurrencyUnits(amt, currency))
      .newAccountBalance(newAccountBalance)
      .currency(currency)
      .tStartDate(transactionTime)
      .tFinishDate(transactionTime)
      .description(description)
      .counterpartyAccountHolder(counterparty.accountHolder)
      .counterpartyAccountNumber(counterparty.number)
      .counterpartyAccountKind(counterparty.accountType)
      .counterpartyBankName(counterparty.bankName)
      .counterpartyIban(counterparty.iban.getOrElse(""))
      .counterpartyNationalId(counterparty.nationalIdentifier).saveMe

    Full(mappedTransaction.theTransactionId)
  }

  /*
    Transaction Requests
  */

  override def createTransactionRequestImpl(transactionRequestId: TransactionRequestId, transactionRequestType: TransactionRequestType,
                                            account : BankAccount, counterparty : BankAccount, body: TransactionRequestBody,
                                            status: String) : Box[TransactionRequest] = {
    val mappedTransactionRequest = MappedTransactionRequest.create
      .mTransactionRequestId(transactionRequestId.value)
      .mType(transactionRequestType.value)
      .mFrom_BankId(account.bankId.value)
      .mFrom_AccountId(account.accountId.value)
      .mBody_To_BankId(counterparty.bankId.value)
      .mBody_To_AccountId(counterparty.accountId.value)
      .mBody_Value_Currency(body.value.currency)
      .mBody_Value_Amount(body.value.amount)
      .mBody_Description(body.description)
      .mStatus(status)
      .mStartDate(now)
      .mEndDate(now).saveMe
    Full(mappedTransactionRequest).flatMap(_.toTransactionRequest)
  }

  override def saveTransactionRequestTransactionImpl(transactionRequestId: TransactionRequestId, transactionId: TransactionId): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
        case Full(tr: MappedTransactionRequest) => Full(tr.mTransactionIDs(transactionId.value).save)
        case _ => Failure("Couldn't find transaction request ${transactionRequestId}")
      }
  }

  override def saveTransactionRequestChallengeImpl(transactionRequestId: TransactionRequestId, challenge: TransactionRequestChallenge): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
      case Full(tr: MappedTransactionRequest) => Full{
        tr.mChallenge_Id(challenge.id)
        tr.mChallenge_AllowedAttempts(challenge.allowed_attempts)
        tr.mChallenge_ChallengeType(challenge.challenge_type).save
      }
      case _ => Failure(s"Couldn't find transaction request ${transactionRequestId} to set transactionId")
    }
  }

  override def saveTransactionRequestStatusImpl(transactionRequestId: TransactionRequestId, status: String): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
      case Full(tr: MappedTransactionRequest) => Full(tr.mStatus(status).save)
      case _ => Failure(s"Couldn't find transaction request ${transactionRequestId} to set status")
    }
  }


  override def getTransactionRequestsImpl(fromAccount : BankAccount) : Box[List[TransactionRequest]] = {
    val transactionRequests = MappedTransactionRequest.findAll(By(MappedTransactionRequest.mFrom_AccountId, fromAccount.accountId.value),
                                                               By(MappedTransactionRequest.mFrom_BankId, fromAccount.bankId.value))

    Full(transactionRequests.flatMap(_.toTransactionRequest))
  }

  override def getTransactionRequestImpl(transactionRequestId: TransactionRequestId) : Box[TransactionRequest] = {
    val transactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    transactionRequest.flatMap(_.toTransactionRequest)
  }


  override def getTransactionRequestTypesImpl(fromAccount : BankAccount) : Box[List[TransactionRequestType]] = {
    //TODO: write logic / data access
    Full(List(TransactionRequestType("SANDBOX_TAN")))
  }

  /*
    Bank account creation
   */

  //creates a bank account (if it doesn't exist) and creates a bank (if it doesn't exist)
  //again assume national identifier is unique
  override def createBankAndAccount(bankName: String, bankNationalIdentifier: String, accountNumber: String, accountHolderName: String): (Bank, BankAccount) = {
    //don't require and exact match on the name, just the identifier
    val bank = MappedBank.find(By(MappedBank.national_identifier, bankNationalIdentifier)) match {
      case Full(b) =>
        logger.info(s"bank with id ${b.bankId} and national identifier ${b.nationalIdentifier} found")
        b
      case _ =>
        logger.info(s"creating bank with national identifier $bankNationalIdentifier")
        //TODO: need to handle the case where generatePermalink returns a permalink that is already used for another bank
        MappedBank.create
          .permalink(Helper.generatePermalink(bankName))
          .fullBankName(bankName)
          .shortBankName(bankName)
          .national_identifier(bankNationalIdentifier)
          .saveMe()
    }

    //TODO: pass in currency as a parameter?
    val account = createAccountIfNotExisting(bank.bankId, AccountId(UUID.randomUUID().toString), accountNumber, "EUR", 0L, accountHolderName)

    (bank, account)
  }

  //for sandbox use -> allows us to check if we can generate a new test account with the given number
  override def accountExists(bankId: BankId, accountNumber: String): Boolean = {
    MappedBankAccount.count(
      By(MappedBankAccount.bank, bankId.value),
      By(MappedBankAccount.accountNumber, accountNumber)) > 0
  }

  //remove an account and associated transactions
  override def removeAccount(bankId: BankId, accountID: AccountId) : Boolean = {
    //delete comments on transactions of this account
    val commentsDeleted = MappedComment.bulkDelete_!!(
      By(MappedComment.bank, bankId.value),
      By(MappedComment.account, accountID.value)
    )

    //delete narratives on transactions of this account
    val narrativesDeleted = MappedNarrative.bulkDelete_!!(
      By(MappedNarrative.bank, bankId.value),
      By(MappedNarrative.account, accountID.value)
    )

    //delete narratives on transactions of this account
    val tagsDeleted = MappedTag.bulkDelete_!!(
      By(MappedTag.bank, bankId.value),
      By(MappedTag.account, accountID.value)
    )

    //delete WhereTags on transactions of this account
    val whereTagsDeleted = MappedWhereTag.bulkDelete_!!(
      By(MappedWhereTag.bank, bankId.value),
      By(MappedWhereTag.account, accountID.value)
    )

    //delete transaction images on transactions of this account
    val transactionImagesDeleted = MappedTransactionImage.bulkDelete_!!(
      By(MappedTransactionImage.bank, bankId.value),
      By(MappedTransactionImage.account, accountID.value)
    )

    //delete transactions of account
    val transactionsDeleted = MappedTransaction.bulkDelete_!!(
      By(MappedTransaction.bank, bankId.value),
      By(MappedTransaction.account, accountID.value)
    )

    //remove view privileges (get views first)
    val views = ViewImpl.findAll(
      By(ViewImpl.bankPermalink, bankId.value),
      By(ViewImpl.accountPermalink, accountID.value)
    )

    //loop over them and delete
    var privilegesDeleted = true
    views.map (x => {
      privilegesDeleted &&= ViewPrivileges.bulkDelete_!!(By(ViewPrivileges.view, x.id_))
    })

    //delete views of account
    val viewsDeleted = ViewImpl.bulkDelete_!!(
      By(ViewImpl.bankPermalink, bankId.value),
      By(ViewImpl.accountPermalink, accountID.value)
    )

    //delete account
    val account = MappedBankAccount.find(
      By(MappedBankAccount.bank, bankId.value),
      By(MappedBankAccount.theAccountId, accountID.value)
    )

    val accountDeleted = account match {
      case Full(acc) => acc.delete_!
      case _ => false
    }

    commentsDeleted && narrativesDeleted && tagsDeleted && whereTagsDeleted && transactionImagesDeleted &&
      transactionsDeleted && privilegesDeleted && viewsDeleted && accountDeleted
}

  //creates a bank account for an existing bank, with the appropriate values set. Can fail if the bank doesn't exist
  override def createSandboxBankAccount(bankId: BankId, accountID: AccountId, accountNumber: String,
                                        currency: String, initialBalance: BigDecimal, accountHolderName: String): Box[BankAccount] = {

    for {
      bank <- getBank(bankId) //bank is not really used, but doing this will ensure account creations fails if the bank doesn't
    } yield {

      val balanceInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(initialBalance, currency)
      createAccountIfNotExisting(bankId, accountID, accountNumber, currency, balanceInSmallestCurrencyUnits, accountHolderName)
    }

  }

  //sets a user as an account owner/holder
  override def setAccountHolder(bankAccountUID: BankAccountUID, user: User): Unit = {
    MappedAccountHolder.create
      .accountBankPermalink(bankAccountUID.bankId.value)
      .accountPermalink(bankAccountUID.accountId.value)
      .user(user.apiId.value)
      .save
  }

  private def createAccountIfNotExisting(bankId: BankId, accountID: AccountId, accountNumber: String,
                            currency: String, balanceInSmallestCurrencyUnits: Long, accountHolderName: String) : BankAccount = {
    getBankAccountType(bankId, accountID) match {
      case Full(a) =>
        logger.info(s"account with id $accountID at bank with id $bankId already exists. No need to create a new one.")
        a
      case _ =>
        MappedBankAccount.create
          .bank(bankId.value)
          .theAccountId(accountID.value)
          .accountNumber(accountNumber)
          .accountCurrency(currency)
          .accountBalance(balanceInSmallestCurrencyUnits)
          .holder(accountHolderName)
          .saveMe()
    }
  }

  /*
    End of bank account creation
   */

  /*
      Cash api
     */

  //cash api requires getting an account via a uuid: for legacy reasons it does not use bankId + accountID
  override def getAccountByUUID(uuid: String): Box[AccountType] = {
    MappedBankAccount.find(By(MappedBankAccount.accUUID, uuid))
  }

  //cash api requires a call to add a new transaction and update the account balance
  override def addCashTransactionAndUpdateBalance(account: AccountType, cashTransaction: CashTransaction): Unit = {

    val currency = account.currency
    val currencyDecimalPlaces = Helper.currencyDecimalPlaces(currency)

    //not ideal to have to convert it this way
    def doubleToSmallestCurrencyUnits(x : Double) : Long = {
      (x * math.pow(10, currencyDecimalPlaces)).toLong
    }

    //can't forget to set the sign of the amount cashed on kind being "in" or "out"
    //we just assume if it's not "in", then it's "out"
    val amountInSmallestCurrencyUnits = {
      if(cashTransaction.kind == "in") doubleToSmallestCurrencyUnits(cashTransaction.amount)
      else doubleToSmallestCurrencyUnits(-1 * cashTransaction.amount)
    }

    val currentBalanceInSmallestCurrencyUnits = account.accountBalance.get
    val newBalanceInSmallestCurrencyUnits = currentBalanceInSmallestCurrencyUnits + amountInSmallestCurrencyUnits

    //create transaction
    val transactionCreated = MappedTransaction.create
      .bank(account.bankId.value)
      .account(account.accountId.value)
      .transactionType("cash")
      .amount(amountInSmallestCurrencyUnits)
      .newAccountBalance(newBalanceInSmallestCurrencyUnits)
      .currency(account.currency)
      .tStartDate(cashTransaction.date)
      .tFinishDate(cashTransaction.date)
      .description(cashTransaction.label)
      .counterpartyAccountHolder(cashTransaction.otherParty)
      .counterpartyAccountKind("cash")
      .save

    if(!transactionCreated) {
      logger.warn("Failed to save cash transaction")
    } else {
      //update account
      val accountUpdated = account.accountBalance(newBalanceInSmallestCurrencyUnits).save()

      if(!accountUpdated)
        logger.warn("Failed to update account balance after new cash transaction")
    }
  }

  /*
    End of cash api
   */

  /*
    Transaction importer api
   */

  //used by the transaction import api
  override def updateAccountBalance(bankId: BankId, accountID: AccountId, newBalance: BigDecimal): Boolean = {

    //this will be Full(true) if everything went well
    val result = for {
      acc <- getBankAccountType(bankId, accountID)
      bank <- getMappedBank(bankId)
    } yield {
      acc.accountBalance(Helper.convertToSmallestCurrencyUnits(newBalance, acc.currency)).save
      setBankAccountLastUpdated(bank.nationalIdentifier, acc.number, now)
    }

    result.getOrElse(false)
  }

  //transaction import api uses bank national identifiers to uniquely indentify banks,
  //which is unfortunate as theoretically the national identifier is unique to a bank within
  //one country
  private def getBankByNationalIdentifier(nationalIdentifier : String) : Box[Bank] = {
    MappedBank.find(By(MappedBank.national_identifier, nationalIdentifier))
  }

  private def getAccountByNumber(bankId : BankId, number : String) : Box[AccountType] = {
    MappedBankAccount.find(
      By(MappedBankAccount.bank, bankId.value),
      By(MappedBankAccount.accountNumber, number))
  }

  private val bigDecimalFailureHandler : PartialFunction[Throwable, Unit] = {
    case ex : NumberFormatException => {
      logger.warn(s"could not convert amount to a BigDecimal: $ex")
    }
  }

  //used by transaction import api call to check for duplicates
  override def getMatchingTransactionCount(bankNationalIdentifier : String, accountNumber : String, amount: String, completed: Date, otherAccountHolder: String): Int = {
    //we need to convert from the legacy bankNationalIdentifier to BankId, and from the legacy accountNumber to AccountId
    val count = for {
      bankId <- getBankByNationalIdentifier(bankNationalIdentifier).map(_.bankId)
      account <- getAccountByNumber(bankId, accountNumber)
      amountAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(amount))
    } yield {

      val amountInSmallestCurrencyUnits =
        Helper.convertToSmallestCurrencyUnits(amountAsBigDecimal, account.currency)

      MappedTransaction.count(
        By(MappedTransaction.bank, bankId.value),
        By(MappedTransaction.account, account.accountId.value),
        By(MappedTransaction.amount, amountInSmallestCurrencyUnits),
        By(MappedTransaction.tFinishDate, completed),
        By(MappedTransaction.counterpartyAccountHolder, otherAccountHolder))
    }

    //icky
    count.map(_.toInt) getOrElse 0
  }

  //used by transaction import api
  override def createImportedTransaction(transaction: ImporterTransaction): Box[Transaction] = {
    //we need to convert from the legacy bankNationalIdentifier to BankId, and from the legacy accountNumber to AccountId
    val obpTransaction = transaction.obp_transaction
    val thisAccount = obpTransaction.this_account
    val nationalIdentifier = thisAccount.bank.national_identifier
    val accountNumber = thisAccount.number
    for {
      bank <- getBankByNationalIdentifier(transaction.obp_transaction.this_account.bank.national_identifier) ?~!
        s"No bank found with national identifier $nationalIdentifier"
      bankId = bank.bankId
      account <- getAccountByNumber(bankId, accountNumber)
      details = obpTransaction.details
      amountAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(details.value.amount))
      newBalanceAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(details.new_balance.amount))
      amountInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(amountAsBigDecimal, account.currency)
      newBalanceInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(newBalanceAsBigDecimal, account.currency)
      otherAccount = obpTransaction.other_account
      mappedTransaction = MappedTransaction.create
        .bank(bankId.value)
        .account(account.accountId.value)
        .transactionType(details.kind)
        .amount(amountInSmallestCurrencyUnits)
        .newAccountBalance(newBalanceInSmallestCurrencyUnits)
        .currency(account.currency)
        .tStartDate(details.posted.`$dt`)
        .tFinishDate(details.completed.`$dt`)
        .description(details.label)
        .counterpartyAccountNumber(otherAccount.number)
        .counterpartyAccountHolder(otherAccount.holder)
        .counterpartyAccountKind(otherAccount.kind)
        .counterpartyNationalId(otherAccount.bank.national_identifier)
        .counterpartyBankName(otherAccount.bank.name)
        .counterpartyIban(otherAccount.bank.IBAN)
        .saveMe()
      transaction <- mappedTransaction.toTransaction(account)
    } yield transaction
  }

  override def setBankAccountLastUpdated(bankNationalIdentifier: String, accountNumber : String, updateDate: Date) : Boolean = {
    val result = for {
      bankId <- getBankByNationalIdentifier(bankNationalIdentifier).map(_.bankId)
      account <- getAccountByNumber(bankId, accountNumber)
    } yield {
        val acc = MappedBankAccount.find(
          By(MappedBankAccount.bank, bankId.value),
          By(MappedBankAccount.theAccountId, account.accountId.value)
        )
        acc match {
          case Full(a) => a.accountLastUpdate(updateDate).save
          case _ => logger.warn("can't set bank account.lastUpdated because the account was not found"); false
        }
    }
    result.getOrElse(false)
  }

  /*
    End of transaction importer api
   */


  override def updateAccountLabel(bankId: BankId, accountID: AccountId, label: String): Boolean = {
    //this will be Full(true) if everything went well
    val result = for {
      acc <- getBankAccountType(bankId, accountID)
      bank <- getMappedBank(bankId)
    } yield {
        acc.accountLabel(label).save
      }

    result.getOrElse(false)
  }

}

