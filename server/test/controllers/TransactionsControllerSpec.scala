package controllers

import com.alexitc.playsonify.PublicErrorRenderer
import com.alexitc.playsonify.core.FutureApplicationResult
import com.xsn.explorer.errors.TransactionNotFoundError
import com.xsn.explorer.helpers.{DummyXSNService, TransactionLoader}
import com.xsn.explorer.models._
import com.xsn.explorer.models.rpc.Transaction
import com.xsn.explorer.services.XSNService
import controllers.common.MyAPISpec
import org.scalactic.{Bad, Good}
import play.api.inject.bind
import play.api.libs.json.JsValue
import play.api.test.Helpers._

import scala.concurrent.Future

class TransactionsControllerSpec extends MyAPISpec {

  val coinbaseTx = TransactionLoader.get("024aba1d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c")
  val nonCoinbaseTx = TransactionLoader.get("0834641a7d30d8a2d2b451617599670445ee94ed7736e146c13be260c576c641")
  val nonCoinbasePreviousTx = TransactionLoader.get("585cec5009c8ca19e83e33d282a6a8de65eb2ca007b54d6572167703768967d9")

  val customXSNService = new DummyXSNService {
    val map = Map(
      coinbaseTx.id -> coinbaseTx,
      nonCoinbaseTx.id -> nonCoinbaseTx,
      nonCoinbasePreviousTx.id -> nonCoinbasePreviousTx
    )

    override def getTransaction(txid: TransactionId): FutureApplicationResult[Transaction] = {
      val result = map.get(txid)
          .map(Good(_))
          .getOrElse {
            Bad(TransactionNotFoundError).accumulating
          }

      Future.successful(result)
    }
  }

  override val application = guiceApplicationBuilder
      .overrides(bind[XSNService].to(customXSNService))
      .build()

  "GET /transactions/:txid" should {
    def url(txid: String) = s"/transactions/$txid"

    "return coinbase transaction" in {
      val tx = coinbaseTx
      val response = GET(url(tx.id.string))

      status(response) mustEqual OK
      val json = contentAsJson(response)
      (json \ "id").as[String] mustEqual tx.id.string
      (json \ "blockhash").as[String] mustEqual tx.blockhash.string
      (json \ "size").as[Size] mustEqual tx.size
      (json \ "time").as[Long] mustEqual tx.time
      (json \ "blocktime").as[Long] mustEqual tx.blocktime
      (json \ "confirmations").as[Confirmations] mustEqual tx.confirmations

      val outputJsonList = (json \ "output").as[List[JsValue]]
      outputJsonList.size mustEqual 1

      val outputJson = outputJsonList.head
      (outputJson \ "address").as[String] mustEqual tx.vout.head.address.get.string
      (outputJson \ "value").as[BigDecimal] mustEqual tx.vout.head.value
    }

    "return non-coinbase transaction" in {
      val tx = nonCoinbaseTx
      val details = TransactionDetails.from(nonCoinbaseTx, nonCoinbasePreviousTx)
      val response = GET(url(tx.id.string))

      status(response) mustEqual OK
      val json = contentAsJson(response)
      (json \ "id").as[String] mustEqual tx.id.string
      (json \ "blockhash").as[String] mustEqual tx.blockhash.string
      (json \ "size").as[Size] mustEqual tx.size
      (json \ "time").as[Long] mustEqual tx.time
      (json \ "blocktime").as[Long] mustEqual tx.blocktime
      (json \ "confirmations").as[Confirmations] mustEqual tx.confirmations

      val inputJson = (json \ "input").as[JsValue]
      (inputJson \ "address").as[String] mustEqual details.input.get.address.string
      (inputJson \ "value").as[BigDecimal] mustEqual details.input.get.value

      val outputJsonList = (json \ "output").as[List[JsValue]]
      outputJsonList.size mustEqual 2

      val outputJson = outputJsonList.head
      (outputJson \ "address").as[String] mustEqual details.output.head.address.string
      (outputJson \ "value").as[BigDecimal] mustEqual details.output.head.value

      val outputJson2 = outputJsonList.drop(1).head
      (outputJson2 \ "address").as[String] mustEqual details.output.drop(1).head.address.string
      (outputJson2 \ "value").as[BigDecimal] mustEqual details.output.drop(1).head.value
    }

    "fail on wrong transaction format" in {
      // 63 characters
      val txid = "000001d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c"
      val response = GET(url(txid))

      status(response) mustEqual BAD_REQUEST
      val json = contentAsJson(response)
      val errorList = (json \ "errors").as[List[JsValue]]

      errorList.size mustEqual 1
      val error = errorList.head

      (error \ "type").as[String] mustEqual PublicErrorRenderer.FieldValidationErrorType
      (error \ "field").as[String] mustEqual "transactionId"
      (error \ "message").as[String].nonEmpty mustEqual true
    }

    "fail on unknown transaction" in {
      val txid = "0000001d535cfe5dd3ea465d46a828a57b00e1df012d7a2d158e0f7484173f7c"
      val response = GET(url(txid))

      status(response) mustEqual BAD_REQUEST
      val json = contentAsJson(response)
      val errorList = (json \ "errors").as[List[JsValue]]

      errorList.size mustEqual 1
      val error = errorList.head

      (error \ "type").as[String] mustEqual PublicErrorRenderer.FieldValidationErrorType
      (error \ "field").as[String] mustEqual "transactionId"
      (error \ "message").as[String].nonEmpty mustEqual true
    }
  }
}
