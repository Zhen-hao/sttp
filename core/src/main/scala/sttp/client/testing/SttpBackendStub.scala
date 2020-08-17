package sttp.client.testing

import java.io.InputStream

import sttp.capabilities.{Effect, WebSockets}
import sttp.client.internal.{SttpFile, _}
import sttp.client.monad.IdMonad
import sttp.client.testing.SttpBackendStub._
import sttp.client.{IgnoreResponse, ResponseAs, ResponseAsByteArray, SttpBackend, _}
import sttp.model.StatusCode
import sttp.monad.{FutureMonad, MonadError}
import sttp.ws.WebSocket
import sttp.ws.testing.WebSocketStub

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/**
  * A stub backend to use in tests.
  *
  * The stub can be configured to respond with a given response if the
  * request matches a predicate (see the [[whenRequestMatches()]] method).
  *
  * Note however, that this is not type-safe with respect to the type of the
  * response body - the stub doesn't have a way to check if the type of the
  * body in the configured response is the same as the one specified by the
  * request. Some conversions will be attempted (e.g. from a `String` to
  * a custom mapped type, as specified in the request, see the documentation
  * for more details).
  *
  * Hence, the predicates can match requests basing on the URI
  * or headers. A [[ClassCastException]] might occur if for a given request,
  * a response is specified with the incorrect or inconvertible body type.
  */
class SttpBackendStub[F[_], +P](
    monad: MonadError[F],
    matchers: PartialFunction[Request[_, _], F[Response[_]]],
    fallback: Option[SttpBackend[F, P]]
) extends SttpBackend[F, P] {

  /**
    * Specify how the stub backend should respond to requests matching the
    * given predicate.
    *
    * Note that the stubs are immutable, and each new
    * specification that is added yields a new stub instance.
    */
  def whenRequestMatches(p: Request[_, _] => Boolean): WhenRequest =
    new WhenRequest(p)

  /**
    * Specify how the stub backend should respond to any request (catch-all).
    *
    * Note that the stubs are immutable, and each new
    * specification that is added yields a new stub instance.
    */
  def whenAnyRequest: WhenRequest = whenRequestMatches(_ => true)

  /**
    * Specify how the stub backend should respond to requests using the
    * given partial function.
    *
    * Note that the stubs are immutable, and each new
    * specification that is added yields a new stub instance.
    */
  def whenRequestMatchesPartial(
      partial: PartialFunction[Request[_, _], Response[_]]
  ): SttpBackendStub[F, P] = {
    val wrappedPartial: PartialFunction[Request[_, _], F[Response[_]]] =
      partial.andThen((r: Response[_]) => monad.unit(r))
    new SttpBackendStub[F, P](monad, matchers.orElse(wrappedPartial), fallback)
  }

  override def send[T, R >: P with Effect[F]](request: Request[T, R]): F[Response[T]] = {
    Try(matchers.lift(request)) match {
      case Success(Some(response)) =>
        tryAdjustResponseType(monad, request.response, response.asInstanceOf[F[Response[T]]])
      case Success(None) =>
        fallback match {
          case None =>
            val response = wrapResponse(
              Response[String](s"Not Found: ${request.uri}", StatusCode.NotFound, "Not Found", Nil, Nil)
            )
            tryAdjustResponseType(monad, request.response, response)
          case Some(fb) => fb.send(request)
        }
      case Failure(e) => monad.error(e)
    }
  }

  private def wrapResponse[T](r: Response[_]): F[Response[T]] =
    monad.unit(r.asInstanceOf[Response[T]])

  override def close(): F[Unit] = monad.unit(())

  override def responseMonad: MonadError[F] = monad

  class WhenRequest(p: Request[_, _] => Boolean) {
    def thenRespondOk(): SttpBackendStub[F, P] =
      thenRespondWithCode(StatusCode.Ok)
    def thenRespondNotFound(): SttpBackendStub[F, P] =
      thenRespondWithCode(StatusCode.NotFound, "Not found")
    def thenRespondServerError(): SttpBackendStub[F, P] =
      thenRespondWithCode(StatusCode.InternalServerError, "Internal server error")
    def thenRespondWithCode(status: StatusCode, msg: String = ""): SttpBackendStub[F, P] = {
      thenRespond(Response(msg, status, msg))
    }
    def thenRespond[T](body: T): SttpBackendStub[F, P] =
      thenRespond(Response[T](body, StatusCode.Ok, "OK"))
    def thenRespond[T](resp: => Response[T]): SttpBackendStub[F, P] = {
      val m: PartialFunction[Request[_, _], F[Response[_]]] = {
        case r if p(r) => monad.eval(resp)
      }
      new SttpBackendStub[F, P](monad, matchers.orElse(m), fallback)
    }

    /**
      * Not thread-safe!
      */
    def thenRespondCyclic[T](bodies: T*): SttpBackendStub[F, P] = {
      thenRespondCyclicResponses(bodies.map(body => Response[T](body, StatusCode.Ok, "OK")): _*)
    }

    /**
      * Not thread-safe!
      */
    def thenRespondCyclicResponses[T](responses: Response[T]*): SttpBackendStub[F, P] = {
      val iterator = Iterator.continually(responses).flatten
      thenRespond(iterator.next)
    }
    def thenRespondWrapped(resp: => F[Response[_]]): SttpBackendStub[F, P] = {
      val m: PartialFunction[Request[_, _], F[Response[_]]] = {
        case r if p(r) => resp
      }
      new SttpBackendStub[F, P](monad, matchers.orElse(m), fallback)
    }
    def thenRespondWrapped(resp: Request[_, _] => F[Response[_]]): SttpBackendStub[F, P] = {
      val m: PartialFunction[Request[_, _], F[Response[_]]] = {
        case r if p(r) => resp(r)
      }
      new SttpBackendStub[F, P](monad, matchers.orElse(m), fallback)
    }
  }
}

object SttpBackendStub {

  /**
    * Create a stub of a synchronous backend (which doesn't wrap results in any
    * container), without streaming.
    */
  def synchronous: SttpBackendStub[Identity, WebSockets] =
    new SttpBackendStub(
      IdMonad,
      PartialFunction.empty,
      None
    )

  /**
    * Create a stub of an asynchronous backend (which wraps results in Scala's
    * built-in [[Future]]), without streaming.
    */
  def asynchronousFuture: SttpBackendStub[Future, WebSockets] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    new SttpBackendStub(
      new FutureMonad(),
      PartialFunction.empty,
      None
    )
  }

  /**
    * Create a stub backend using the given response monad (which determines
    * how requests are wrapped), any stream type and any websocket handler.
    */
  def apply[F[_], P](responseMonad: MonadError[F]): SttpBackendStub[F, P] =
    new SttpBackendStub[F, P](
      responseMonad,
      PartialFunction.empty,
      None
    )

  /**
    * Create a stub backend which delegates send requests to the given fallback
    * backend, if the request doesn't match any of the specified predicates.
    */
  def withFallback[F[_], P0, P1 >: P0](
      fallback: SttpBackend[F, P0]
  ): SttpBackendStub[F, P1] =
    new SttpBackendStub[F, P1](
      fallback.responseMonad,
      PartialFunction.empty,
      Some(fallback)
    )

  private[client] def tryAdjustResponseType[DesiredRType, RType, F[_]](
      monad: MonadError[F],
      ra: ResponseAs[DesiredRType, _],
      m: F[Response[RType]]
  ): F[Response[DesiredRType]] = {
    monad.map[Response[RType], Response[DesiredRType]](m) { r =>
      val newBody: Any = tryAdjustResponseBody(ra, r.body, r, monad).getOrElse(r.body)
      r.copy(body = newBody.asInstanceOf[DesiredRType])
    }
  }

  private[client] def tryAdjustResponseBody[F[_], T, U](
      ra: ResponseAs[T, _],
      b: U,
      meta: ResponseMetadata,
      monad: MonadError[F]
  ): Option[T] = {
    ra match {
      case IgnoreResponse => Some(())
      case ResponseAsByteArray =>
        b match {
          case s: String       => Some(s.getBytes(Utf8))
          case a: Array[Byte]  => Some(a)
          case is: InputStream => Some(toByteArray(is))
          case _               => None
        }
      case ResponseAsStream(_, _)    => None
      case ResponseAsStreamUnsafe(_) => None
      case ResponseAsFile(_) =>
        b match {
          case f: SttpFile => Some(f)
          case _           => None
        }
      case ResponseAsWebSocket(f) =>
        b match {
          case wss: WebSocketStub[_] => Some(f(wss.build(monad.asInstanceOf[MonadError[Any]])))
          case ws: WebSocket[_]      => Some(f(ws))
          case _                     => None
        }
      case ResponseAsWebSocketUnsafe() =>
        b match {
          case wss: WebSocketStub[_] => Some(wss.build(monad.asInstanceOf[MonadError[Any]]))
          case _                     => None
        }
      case ResponseAsWebSocketStream(_, _)   => None
      case MappedResponseAs(raw, g)          => tryAdjustResponseBody(raw, b, meta, monad).map(g(_, meta))
      case rfm: ResponseAsFromMetadata[_, _] => tryAdjustResponseBody(rfm(meta), b, meta, monad)
    }
  }
}
