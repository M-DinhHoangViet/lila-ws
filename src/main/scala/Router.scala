package lila.ws

import akka.actor.typed.{ ActorSystem, Behavior }
import io.netty.channel.Channel
import io.netty.handler.codec.http._
import javax.inject._
import scala.concurrent.{ Future, ExecutionContext }

import util.RequestHeader

@Singleton
final class Router @Inject() (
    controller: Controller,
    system: ActorSystem[Clients.Control]
)(implicit ec: ExecutionContext) {

  // implicit def ec = system.executionContext

  def apply(
    uri: String,
    headers: HttpHeaders,
    emit: ClientEmit,
    ip: IpAddress
  ): Controller.Response = {
    val req = new RequestHeader(uri, headers, ip)
    req.path drop 1 split '/' match {
      case Array("socket") | Array("socket", _) => controller.site(req, emit)
      case Array("analysis", "socket") | Array("analysis", "socket", _) => controller.site(req, emit)
      case Array("api", "socket") => controller.api(req, emit)
      case Array("lobby", "socket") | Array("lobby", "socket", _) => controller.lobby(req, emit)
      case Array("simul", id, "socket", _) => controller.simul(id, req, emit)
      case Array("tournament", id, "socket", _) => controller.tournament(id, req, emit)
      case Array("study", id, "socket", _) => controller.study(id, req, emit)
      case Array("watch", id, color, _) => controller.roundWatch(Game.Id(id), req, emit)
      case Array("play", id, _) => controller.roundPlay(Game.FullId(id), req, emit)
      case Array("challenge", id, "socket", _) => controller.challenge(Challenge.Id(id), req, emit)
      case _ => Future successful Left(HttpResponseStatus.NOT_FOUND)
    }
  }
}
