package zhttp.service
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel.{ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http.HttpContent
import zhttp.service.server.content.handlers.UnsafeRequestHandler.{UnsafeChannel, UnsafeContent}

@Sharable
final case class RequestBodyHandler[R](
  msgCallback: (UnsafeChannel, UnsafeContent) => Unit,
  config: Server.Config[R, Throwable],
) extends SimpleChannelInboundHandler[Any](true) {

  override def channelRead0(ctx: ChannelHandlerContext, msg: Any): Unit = {
    if (msg.isInstanceOf[HttpContent])
      msgCallback(UnsafeChannel(ctx), UnsafeContent(msg.asInstanceOf[HttpContent], config.maxRequestSize))
    else
      ctx.fireChannelRead(msg): Unit
  }

  override def handlerAdded(ctx: ChannelHandlerContext): Unit = {
    ctx.channel().config().setAutoRead(false)
    ctx.read(): Unit
  }
}