package zhttp.logging

import zhttp.logging.frontend.ConsoleLogger
import zhttp.logging.macros.LoggerMacro._
import zhttp.logging.macros.LoggerMacro

final class Logger(configuration: Configuration) {

  import LogLevel._

  private[zhttp] val logger = new ConsoleLogger(configuration)

  @inline def name = configuration.loggerName

  @inline def isTraceEnabled: Boolean = configuration.logLevel >= TRACE
  @inline def isDebugEnabled: Boolean = configuration.logLevel >= DEBUG
  @inline def isInfoEnabled: Boolean  = configuration.logLevel >= INFO
  @inline def isWarnEnabled: Boolean  = configuration.logLevel >= WARN
  @inline def isErrorEnabled: Boolean = configuration.logLevel >= ERROR

  import scala.language.experimental.macros

  inline def trace(inline t: Throwable)(inline msg: String): Unit =
  ${traceTM('this)('t)('msg)}
  inline def trace(inline msg: String): Unit =
  ${traceM('this)('msg)}

  inline def debug(inline t: Throwable)(inline msg: String): Unit =
  ${debugTM('this)('t)('msg)}
  inline def debug(inline msg: String): Unit =
  ${debugM('this)('msg)}

  inline def info(inline t: Throwable)(inline msg: String): Unit =
  ${infoTM('this)('t)('msg)}
  inline def info(inline msg: String): Unit =
  ${infoM('this)('msg)}

  inline def warn(inline t: Throwable)(inline msg: String): Unit =
  ${warnTM('this)('t)('msg)}
  inline def warn(inline msg: String): Unit =
  ${warnM('this)('msg)}

  inline def error(inline t: Throwable)(inline msg: String): Unit =
  ${errorTM('this)('t)('msg)}
  inline def error(inline msg: String): Unit =
  ${errorM('this)('msg)}

}

object Logger {
  import scala.language.experimental.macros
  inline def getLogger: Logger = ${LoggerMacro.getLoggerImpl}
  final def getLogger(logLevel: LogLevel)                     =
    new Logger(configuration = Configuration(getClass.getSimpleName, logLevel, LogFormat.default))
  final def getLogger(loggerName: String)                     =
    new Logger(configuration = Configuration(loggerName, logLevel = LogLevel.INFO, LogFormat.default))
  final def getLogger(loggerName: String, logLevel: LogLevel) =
    new Logger(configuration = Configuration(loggerName, logLevel, LogFormat.default))
  final def getLogger(
    loggerName: String,
    logLevel: LogLevel,
    logFormat: LogFormat,
  ) = new Logger(configuration = Configuration(loggerName, logLevel, logFormat))
}
