package libs.xml

import cats.data.Validated
import libs.xml.synthax.XmlResult
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormatter
import utils.DateUtils
import utils.Result.AppErrors

import scala.util.Try
import scala.xml.{Elem, NodeSeq}

object synthax {

  type XmlResult[T] = Validated[AppErrors, T]

  implicit class XmlSyntax(val nodeSeq: NodeSeq) extends AnyVal {
    def validate[T](implicit read: XMLRead[T]): XmlResult[T] =
      read.read(nodeSeq)

    def validateNullable[T](
        implicit read: XMLRead[Option[T]]): XmlResult[Option[T]] = {
      read.read(nodeSeq)
    }

    def validateNullable[T](default: T)(
        implicit read: XMLRead[Option[T]]): XmlResult[T] = {
      read.read(nodeSeq).map(_.getOrElse(default))
    }
  }

}

trait XMLRead[T] {
  def read(xml: NodeSeq): XmlResult[T]
}

object implicits {

  import cats.implicits._

  implicit def readString: XMLRead[String] =
    (xml: NodeSeq) =>
      Try(xml.head.text)
        .map(_.valid)
        .getOrElse(AppErrors.error("invalid.path").invalid)

  implicit def readInt: XMLRead[Int] =
    (xml: NodeSeq) =>
      Try(xml.head.text.toInt)
        .map(_.valid)
        .getOrElse(AppErrors.error("invalid.path").invalid)

  implicit def defaultReadDateTime: XMLRead[DateTime] =
    readDateTime(DateUtils.utcDateFormatter)

  def readDateTime(dateTimeFormatter: DateTimeFormatter): XMLRead[DateTime] =
    (xml: NodeSeq) =>
      Try(xml.head.text)
        .map(_.valid)
        .getOrElse(AppErrors.error("invalid.path").invalid)
        .andThen { t =>
          Try(dateTimeFormatter.parseDateTime(t))
            .map(_.valid)
            .getOrElse(AppErrors.error("parse.error").invalid)
      }

  implicit def readOption[T](implicit read: XMLRead[T]): XMLRead[Option[T]] =
    (xml: NodeSeq) => {
      val option: Option[XmlResult[T]] = xml.headOption.map(read.read)
      val res: XmlResult[Option[T]] = option.sequence
      res
    }

}

object XmlUtil {

  implicit class XmlCleaner(val elem: Elem) extends AnyVal {

    def clean(): Elem =
      scala.xml.Utility.trim(elem) match {
        case res if res.isInstanceOf[Elem] => res.asInstanceOf[Elem]
      }
  }
}