package lila.ws

import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger
import io.lettuce.core._
import io.lettuce.core.pubsub._
import ipc._
import java.util.concurrent.ConcurrentLinkedQueue
import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future, Promise }

final class Lila(config: Config)(implicit ec: ExecutionContext) {

  import Lila._

  object status {
    private var value: Status = Online
    def setOffline() = { value = Offline }
    def setOnline() = {
      value = Online
      buffer.flush()
    }
    def isOnline: Boolean = value == Online
  }

  private object buffer {
    case class Buffered(chan: String, msg: String)
    private val queue       = new ConcurrentLinkedQueue[Buffered]()
    private lazy val connIn = redis.connectPubSub

    def enqueue(chan: String, msg: String) = queue offer Buffered(chan, msg)

    @tailrec def flush(): Unit = {
      val next = queue.poll()
      if (next != null) {
        connIn.async.publish(next.chan, next.msg)
        flush()
      }
    }
  }

  private val logger = Logger(getClass)
  private val redis  = RedisClient create RedisURI.create(config.getString("redis.uri"))

  private val handlersPromise                  = Promise[Handlers]()
  private val futureHandlers: Future[Handlers] = handlersPromise.future
  private var handlers: Handlers               = chan => out => futureHandlers foreach { _(chan)(out) }
  def setHandlers(hs: Handlers) = {
    handlers = hs
    handlersPromise success hs
  }

  val emit: Emits = Await.result(
    util.Chronometer(connectAll).lap.map { lap =>
      logger.info(s"Redis connection took ${lap.showDuration}")
      lap.result
    },
    3.seconds
  )

  private def connectAll: Future[Emits] =
    connect[LilaIn.Site](chans.site) zip
      connect[LilaIn.Tour](chans.tour) zip
      connect[LilaIn.Lobby](chans.lobby) zip
      connect[LilaIn.Simul](chans.simul) zip
      connect[LilaIn.Team](chans.team) zip
      connect[LilaIn.Swiss](chans.swiss) zip
      connect[LilaIn.Study](chans.study) zip
      connect[LilaIn.Round](chans.round) zip
      connect[LilaIn.Challenge](chans.challenge) zip
      connect[LilaIn.Racer](chans.racer) map {
        case site ~ tour ~ lobby ~ simul ~ team ~ swiss ~ study ~ round ~ challenge ~ racer =>
          new Emits(
            site,
            tour,
            lobby,
            simul,
            team,
            swiss,
            study,
            round,
            challenge,
            racer
          )
      }

  private def connect[In <: LilaIn](chan: Chan): Future[Emit[In]] = {

    val connIn = redis.connectPubSub

    val emit: Emit[In] = in => {
      val msg    = in.write
      val path   = msg.takeWhile(' '.!=)
      val chanIn = chan in msg
      if (status.isOnline.pp("1")) {
        connIn.async.publish(chanIn.pp("1 chan"), msg.pp("1 msg")).pp("1a")
        Monitor.redis.in(chanIn, path.pp("1 path"))
      } else if (in.critical.pp("2")) {
        buffer.enqueue(chanIn.pp("2a"), msg.pp("2b"))
        Monitor.redis.queue(chanIn, path)
      } else if (in.isInstanceOf[LilaIn.RoomSetVersions].pp("3")) {
        connIn.async.publish(chanIn.pp("3a"), msg.pp("3b"))
      } else {
        Monitor.redis.drop(chanIn.pp("4a"), path.pp("4b"))
      }
    }

    (chan match {
      case s: SingleLaneChan => connectAndSubscribe(s.out, s.out) map { _ => emit }
      case r: RoundRobinChan =>
        connectAndSubscribe(r.out, r.out) zip Future.sequence {
          (0 to r.parallelism).map { index =>
            connectAndSubscribe(s"${r.out}:$index", r.out)
          }
        }
    }) map { _ =>
      val msg = LilaIn.WsBoot.write
      connIn.async.publish(chan in msg, msg)
      emit
    }
  }

  private def connectAndSubscribe(chanName: String, handlerName: String): Future[Unit] = {
    val connOut = redis.connectPubSub
    connOut.addListener(new RedisPubSubAdapter[String, String] {
      override def message(_chan: String, msg: String): Unit = {
        Monitor.redis.out(chanName, msg.takeWhile(' '.!=))
        LilaOut read msg match {
          case Some(out) => handlers(handlerName)(out)
          case None      => logger.warn(s"Can't parse $msg on $chanName")
        }
      }
    })
    val promise = Promise[Unit]()

    connOut.async.subscribe(chanName) thenRun { () =>
      promise success ()
    }

    promise.future
  }
}

object Lila {

  sealed trait Status
  case object Online  extends Status
  case object Offline extends Status

  type Handlers = String => Emit[LilaOut]

  sealed trait Chan {
    def in(msg: String): String
    val out: String
  }

  sealed abstract class SingleLaneChan(name: String) extends Chan {
    def in(_msg: String) = s"$name-in"
    val out              = s"$name-out"
  }

  sealed abstract class RoundRobinChan(name: String, val parallelism: Int) extends Chan {
    def in(msg: String) = s"$name-in:${msg.hashCode.abs % parallelism}"
    val out             = s"$name-out"
  }

  object chans {
    object site      extends SingleLaneChan("site")
    object tour      extends SingleLaneChan("tour")
    object lobby     extends SingleLaneChan("lobby")
    object simul     extends SingleLaneChan("simul")
    object team      extends SingleLaneChan("team")
    object swiss     extends SingleLaneChan("swiss")
    object study     extends SingleLaneChan("study")
    object round     extends RoundRobinChan("r", 8)
    object challenge extends SingleLaneChan("chal")
    object racer     extends SingleLaneChan("racer")
  }

  final class Emits(
      val site: Emit[LilaIn.Site],
      val tour: Emit[LilaIn.Tour],
      val lobby: Emit[LilaIn.Lobby],
      val simul: Emit[LilaIn.Simul],
      val team: Emit[LilaIn.Team],
      val swiss: Emit[LilaIn.Swiss],
      val study: Emit[LilaIn.Study],
      val round: Emit[LilaIn.Round],
      val challenge: Emit[LilaIn.Challenge],
      val racer: Emit[LilaIn.Racer]
  ) {

    def apply[In](select: Emits => Emit[In], in: In) = select(this)(in)
  }
}
