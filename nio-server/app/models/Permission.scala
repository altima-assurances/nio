package models

import cats.data.Validated._
import cats.implicits._
import libs.xml.XMLRead
import libs.xml.XmlUtil.XmlCleaner
import libs.xml.implicits._
import libs.xml.syntax._
import play.api.libs.json.Json

import scala.xml.NodeSeq

case class Permission(key: String, label: String) {
  def asXml = <permission>
      <key>{key}</key>
      <label>{label}</label>
    </permission>.clean()
}

object Permission {
  implicit val formats = Json.format[Permission]

  implicit val readXml: XMLRead[Permission] =
    (node: NodeSeq, path: Option[String]) =>
      (
        (node \ "key").validate[String](Some(s"${path.convert()}key")),
        (node \ "label").validate[String](Some(s"${path.convert()}label"))
      ).mapN(Permission.apply)

}
