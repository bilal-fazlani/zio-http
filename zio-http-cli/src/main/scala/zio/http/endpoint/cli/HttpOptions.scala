package zio.http.endpoint.cli

import scala.language.implicitConversions
import scala.util.Try

import zio.Chunk
import zio.cli._
import zio.json.ast._

import zio.schema._
import zio.schema.annotation.description

import zio.http._
import zio.http.codec._
import zio.http.internal.StringSchemaCodec
import zio.http.internal.StringSchemaCodec.PrimitiveCodec

/*
 * HttpOptions is a wrapper of a transformation Options[CliRequest] => Options[CliRequest].
 * The defined HttpOptions subclasses store an information about a request (body, header and url)
 * and the transformation adds this information to a generic CliRequest wrapped in Options.
 *
 */

private[cli] sealed trait HttpOptions {

  val name: String

  def transform(request: Options[CliRequest]): Options[CliRequest]

  def ??(doc: Doc): HttpOptions

}

private[cli] object HttpOptions {

  sealed trait Constant extends HttpOptions

  /*
   * Subclass for Body
   * It is possible to specify a body writing directly on the terminal, from a file or the body of the response from another Request.
   */
  final case class Body[A](
    override val name: String,
    mediaType: MediaType,
    schema: Schema[A],
    doc: Doc = Doc.empty,
  ) extends HttpOptions {
    self =>

    private def optionName = if (name == "") "" else s"-$name"

    val jsonOptions: Options[Json] = fromSchema(schema)
    val fromFile                   = Options.file("f" + optionName)
    val fromUrl                    = Options.text("u" + optionName)

    def options: Options[Retriever] = {

      def retrieverWithJson = fromFile orElseEither fromUrl orElseEither jsonOptions

      def retrieverWithoutJson = fromFile orElseEither fromUrl

      if (allowJsonInput)
        retrieverWithJson.map {
          case Left(Left(file)) => Retriever.File(name, file, mediaType)
          case Left(Right(url)) => Retriever.URL(name, url, mediaType)
          case Right(json)      => Retriever.Content(FormField.textField(name, json.toString()))
        }
      else
        retrieverWithoutJson.map {
          case Left(file) => Retriever.File(name, file, mediaType)
          case Right(url) => Retriever.URL(name, url, mediaType)
        }
    }

    override def ??(doc: Doc): Body[A] = self.copy(doc = self.doc + doc)

    override def transform(request: Options[CliRequest]): Options[CliRequest] =
      (request ++ options).map { case (cliRequest, retriever) =>
        cliRequest.addBody(retriever)
      }

    private def allowJsonInput: Boolean =
      schema.asInstanceOf[Schema[_]] match {
        case Schema.Primitive(StandardType.BinaryType, _) => false
        case Schema.Map(_, _, _)                          => false
        case Schema.Sequence(_, _, _, _, _)               => false
        case Schema.Set(_, _)                             => false
        case _                                            => true
      }

    /**
     * Allows to specify the body with given schema using Json. It does not
     * support schemas with Binary Primitive, Sequence, Map or Set.
     */
    private def fromSchema(schema: zio.schema.Schema[_]): Options[Json] = {

      implicit def toJson[A0](options: Options[A0]): Options[Json] = options.map(value => Json.Str(value.toString()))

      def emptyJson: Options[Json] = Options.Empty.map(_ => Json.Obj())

      def loop(prefix: List[String], schema: zio.schema.Schema[_]): Options[Json] =
        schema match {
          case record: Schema.Record[_]    =>
            record.fields
              .foldLeft(emptyJson) { (options, field) =>
                val fieldOptions: Options[Json] = field.annotations.headOption match {
                  case Some(description) =>
                    loop(prefix :+ field.name, field.schema) ?? description.asInstanceOf[description].text
                  case None              => loop(prefix :+ field.name, field.schema)
                }
                merge(options, fieldOptions)
              } // TODO review the case of nested sealed trait inside case class
          case enumeration: Schema.Enum[_] =>
            enumeration.cases.foldLeft(emptyJson) { case (options, enumCase) =>
              merge(options, loop(prefix, enumCase.schema))
            }

          case Schema.Primitive(standardType, _) => fromPrimitive(prefix, standardType)

          case Schema.Fail(_, _) => emptyJson

          // Should Map, Sequence and Set have implementations?
          // Options cannot be used to specify an arbitrary number of parameters.
          case Schema.Map(_, _, _)                    => emptyJson
          case Schema.NonEmptyMap(_, _, _)            => emptyJson
          case Schema.Sequence(_, _, _, _, _)         => emptyJson
          case Schema.NonEmptySequence(_, _, _, _, _) => emptyJson
          case Schema.Set(_, _)                       => emptyJson

          case Schema.Lazy(schema0)                 => loop(prefix, schema0())
          case Schema.Dynamic(_)                    => emptyJson
          case Schema.Either(left, right, _)        =>
            (loop(prefix, left) orElseEither loop(prefix, right)).map(_.merge)
          case Schema.Fallback(left, right, _, _)   =>
            (loop(prefix, left) orElseEither loop(prefix, right)).map(_.merge)
          case Schema.Optional(schema, _)           =>
            loop(prefix, schema).optional.map {
              case Some(json) => json
              case None       => Json.Obj()
            }
          case Schema.Tuple2(left, right, _)        =>
            merge(loop(prefix, left), loop(prefix, right))
          case Schema.Transform(schema, _, _, _, _) => loop(prefix, schema)
        }

      def merge(opt1: Options[Json], opt2: Options[Json]): Options[Json] =
        (opt1 ++ opt2).map { case (a, b) => Json.Arr(a, b) }

      def fromPrimitive(prefix: List[String], standardType: StandardType[_]): Options[Json] = standardType match {
        case StandardType.InstantType        => Options.instant(prefix.mkString("."))
        case StandardType.UnitType           => emptyJson
        case StandardType.PeriodType         => Options.period(prefix.mkString("."))
        case StandardType.LongType           =>
          Options.integer(prefix.mkString(".")).map(value => Json.Num(BigDecimal(value)))
        case StandardType.StringType         => Options.text(prefix.mkString("."))
        case StandardType.UUIDType           => Options.text(prefix.mkString("."))
        case StandardType.ByteType           =>
          Options.integer(prefix.mkString(".")).map(value => Json.Num(BigDecimal(value)))
        case StandardType.OffsetDateTimeType => Options.offsetDateTime(prefix.mkString("."))
        case StandardType.LocalDateType      => Options.localDate(prefix.mkString("."))
        case StandardType.OffsetTimeType     => Options.decimal(prefix.mkString("."))
        case StandardType.FloatType          =>
          Options.decimal(prefix.mkString(".")).map(value => Json.Num(value))
        case StandardType.BigDecimalType     =>
          Options.decimal(prefix.mkString(".")).map(value => Json.Num(value))
        case StandardType.BigIntegerType     =>
          Options.integer(prefix.mkString(".")).map(value => Json.Num(BigDecimal(value)))
        case StandardType.DoubleType         =>
          Options.decimal(prefix.mkString(".")).map(value => Json.Num(value))
        case StandardType.BoolType           =>
          Options.boolean(prefix.mkString(".")).map(value => Json.Bool(value))
        case StandardType.CharType           => Options.text(prefix.mkString("."))
        case StandardType.ZoneOffsetType     => Options.zoneOffset(prefix.mkString("."))
        case StandardType.YearMonthType      => Options.yearMonth(prefix.mkString("."))
        case StandardType.BinaryType         => emptyJson
        case StandardType.LocalTimeType      => Options.localTime(prefix.mkString("."))
        case StandardType.ZoneIdType         => Options.zoneId(prefix.mkString("."))
        case StandardType.ZonedDateTimeType  => Options.zonedDateTime(prefix.mkString("."))
        case StandardType.DayOfWeekType      =>
          Options.integer(prefix.mkString(".")).map(value => Json.Num(BigDecimal(value)))
        case StandardType.DurationType       =>
          Options.integer(prefix.mkString(".")).map(value => Json.Num(BigDecimal(value)))
        case StandardType.IntType            =>
          Options.integer(prefix.mkString(".")).map(value => Json.Num(BigDecimal(value)))
        case StandardType.MonthDayType       => Options.monthDay(prefix.mkString("."))
        case StandardType.ShortType          =>
          Options.integer(prefix.mkString(".")).map(value => Json.Num(BigDecimal(value)))
        case StandardType.LocalDateTimeType  => Options.localDateTime(prefix.mkString("."))
        case StandardType.MonthType          => Options.text(prefix.mkString("."))
        case StandardType.YearType           => Options.integer(prefix.mkString("."))
        case StandardType.CurrencyType       => Options.text(prefix.mkString("."))
      }

      loop(List(name), schema)
    }

  }

  /*
   * Subclasses for headers
   */
  sealed trait HeaderOptions extends HttpOptions {
    override def ??(doc: Doc): HeaderOptions
  }
  final case class Header(override val name: String, textCodec: TextCodec[_], doc: Doc = Doc.empty)
      extends HeaderOptions {
    self =>

    def options: Options[_] = optionsFromTextCodec(textCodec)(name)

    override def ??(doc: Doc): Header = self.copy(doc = self.doc + doc)

    override def transform(request: Options[CliRequest]): Options[CliRequest] =
      (request ++ options).map { case (cliRequest, value) =>
        if (true) cliRequest.addHeader(name, value.toString())
        else cliRequest
      }

  }

  final case class HeaderConstant(override val name: String, value: String, doc: Doc = Doc.empty)
      extends HeaderOptions
      with Constant {
    self =>

    override def ??(doc: Doc): HeaderConstant = self.copy(doc = self.doc + doc)

    override def transform(request: Options[CliRequest]): Options[CliRequest] =
      request.map(_.addHeader(name, value))

  }

  /*
   * Subclasses for path
   */
  sealed trait URLOptions extends HttpOptions {
    val tag: String

    override def ??(doc: Doc): URLOptions
  }

  final case class Path(pathCodec: PathCodec[_], doc: Doc = Doc.empty) extends URLOptions {
    self =>

    override val name = pathCodec.segments.map {
      case SegmentCodec.Literal(value) => value
      case _                           => ""
    }
      .filter(_ != "")
      .mkString("-")

    def options: zio.Chunk[Options[String]] = pathCodec.segments.map(optionsFromSegment)

    override def ??(doc: Doc): Path = self.copy(doc = self.doc + doc)

    override val tag = "/" + name

    override def transform(request: Options[CliRequest]): Options[CliRequest] =
      options.foldLeft(request) { case (req, opts) =>
        (req ++ opts).map { case (cliRequest, value) =>
          cliRequest.addPathParam(value)
        }
      }

  }

  final case class Query(codec: PrimitiveCodec[_], name: String, doc: Doc = Doc.empty) extends URLOptions {
    self =>
    override val tag        = "?" + name
    def options: Options[_] = optionsFromSchema(codec.schema)(name)

    override def ??(doc: Doc): Query = self.copy(doc = self.doc + doc)

    override def transform(request: Options[CliRequest]): Options[CliRequest] =
      (request ++ options).map { case (cliRequest, value) =>
        if (true) cliRequest.addQueryParam(name, value.toString())
        else cliRequest
      }

  }

  final case class QueryConstant(override val name: String, value: String, doc: Doc = Doc.empty)
      extends URLOptions
      with Constant {
    self =>

    override val tag                                                          = "?" + name + "=" + value
    override def ??(doc: Doc): QueryConstant                                  = self.copy(doc = self.doc + doc)
    override def transform(request: Options[CliRequest]): Options[CliRequest] =
      request.map(_.addQueryParam(name, value))

  }

  private[cli] def optionsFromSchema[A](schema: Schema[A]): String => Options[A] =
    schema match {
      case Schema.Primitive(standardType, _) =>
        standardType match {
          case StandardType.UnitType           =>
            _ => Options.Empty
          case StandardType.StringType         =>
            Options.text
          case StandardType.BoolType           =>
            Options.boolean(_)
          case StandardType.ByteType           =>
            Options.integer(_).map(_.toByte)
          case StandardType.ShortType          =>
            Options.integer(_).map(_.toShort)
          case StandardType.IntType            =>
            Options.integer(_).map(_.toInt)
          case StandardType.LongType           =>
            Options.integer(_).map(_.toLong)
          case StandardType.FloatType          =>
            Options.decimal(_).map(_.toFloat)
          case StandardType.DoubleType         =>
            Options.decimal(_).map(_.toDouble)
          case StandardType.BinaryType         =>
            Options.text(_).map(_.getBytes).map(Chunk.fromArray)
          case StandardType.CharType           =>
            Options.text(_).map(_.charAt(0))
          case StandardType.UUIDType           =>
            Options.text(_).map(java.util.UUID.fromString)
          case StandardType.CurrencyType       =>
            Options.text(_).map(java.util.Currency.getInstance)
          case StandardType.BigDecimalType     =>
            Options.decimal(_).map(_.bigDecimal)
          case StandardType.BigIntegerType     =>
            Options.integer(_).map(_.bigInteger)
          case StandardType.DayOfWeekType      =>
            Options.integer(_).map(i => java.time.DayOfWeek.of(i.toInt))
          case StandardType.MonthType          =>
            Options.text(_).map(java.time.Month.valueOf)
          case StandardType.MonthDayType       =>
            Options.text(_).map(java.time.MonthDay.parse)
          case StandardType.PeriodType         =>
            Options.text(_).map(java.time.Period.parse)
          case StandardType.YearType           =>
            Options.integer(_).map(i => java.time.Year.of(i.toInt))
          case StandardType.YearMonthType      =>
            Options.text(_).map(java.time.YearMonth.parse)
          case StandardType.ZoneIdType         =>
            Options.text(_).map(java.time.ZoneId.of)
          case StandardType.ZoneOffsetType     =>
            Options.text(_).map(java.time.ZoneOffset.of)
          case StandardType.DurationType       =>
            Options.text(_).map(java.time.Duration.parse)
          case StandardType.InstantType        =>
            Options.instant(_)
          case StandardType.LocalDateType      =>
            Options.localDate(_)
          case StandardType.LocalTimeType      =>
            Options.localTime(_)
          case StandardType.LocalDateTimeType  =>
            Options.localDateTime(_)
          case StandardType.OffsetTimeType     =>
            Options.text(_).map(java.time.OffsetTime.parse)
          case StandardType.OffsetDateTimeType =>
            Options.text(_).map(java.time.OffsetDateTime.parse)
          case StandardType.ZonedDateTimeType  =>
            Options.text(_).map(java.time.ZonedDateTime.parse)
        }
      case schema                            => throw new NotImplementedError(s"Schema $schema not yet supported")
    }

  private[cli] def optionsFromTextCodec[A](textCodec: TextCodec[A]): (String => Options[A]) =
    textCodec match {
      case TextCodec.UUIDCodec    =>
        Options
          .text(_)
          .mapOrFail(str =>
            Try(java.util.UUID.fromString(str)).toEither.left.map { error =>
              ValidationError(
                ValidationErrorType.InvalidValue,
                HelpDoc.p(HelpDoc.Span.code(error.getMessage)),
              )
            },
          )
      case TextCodec.StringCodec  => Options.text(_)
      case TextCodec.IntCodec     => Options.integer(_).map(_.toInt)
      case TextCodec.LongCodec    => Options.integer(_).map(_.toLong)
      case TextCodec.BooleanCodec => Options.boolean(_)
      case TextCodec.Constant(_)  => _ => Options.none
    }

  private[cli] def optionsFromSegment(segment: SegmentCodec[_]): Options[String] = {
    def fromSegment[A](segment: SegmentCodec[A]): Options[String] =
      segment match {
        case SegmentCodec.UUID(name)        =>
          Options
            .text(name)
            .mapOrFail(str =>
              Try(java.util.UUID.fromString(str)).toEither.left.map { error =>
                ValidationError(
                  ValidationErrorType.InvalidValue,
                  HelpDoc.p(HelpDoc.Span.code(error.getMessage)),
                )
              },
            )
            .map(_.toString)
        case SegmentCodec.Text(name)        => Options.text(name)
        case SegmentCodec.IntSeg(name)      => Options.integer(name).map(_.toInt).map(_.toString)
        case SegmentCodec.LongSeg(name)     => Options.integer(name).map(_.toInt).map(_.toString)
        case SegmentCodec.BoolSeg(name)     => Options.boolean(name).map(_.toString)
        case SegmentCodec.Literal(value)    => Options.Empty.map(_ => value)
        case SegmentCodec.Trailing          => Options.none.map(_.toString)
        case SegmentCodec.Empty             => Options.none.map(_.toString)
        case SegmentCodec.Combined(_, _, _) => throw new IllegalArgumentException("Combined segment not supported")
      }

    fromSegment(segment)
  }

}
