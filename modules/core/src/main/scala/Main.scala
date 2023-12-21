// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package demo

import cats.effect._
import cats.implicits._
import demo.sangria.SangriaGraphQL
import demo.schema._
import doobie._
import doobie.hikari._
import doobie.util.ExecutionContexts
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import repo._
import sangria._
import _root_.sangria.schema._
import cats.effect.std.Dispatcher
import org.http4s._
import org.http4s.dsl._
import org.http4s.headers.Location
import org.http4s.implicits._
import org.http4s.server.Server
import org.http4s.server.blaze._

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContext.global

object Main extends IOApp {

  // Construct a transactor for connecting to the database.
  def transactor[F[_]: Async](): Resource[F, HikariTransactor[F]] =
    ExecutionContexts.fixedThreadPool[F](10).flatMap { ce =>
      HikariTransactor.newHikariTransactor(
        "org.postgresql.Driver",
        "jdbc:postgresql:world",
        "user",
        "password",
        ce
      )
    }

  // Construct a GraphQL implementation based on our Sangria definitions.
  def graphQL[F[_]: Async: Dispatcher: Logger](
    transactor:      Transactor[F]
  ): GraphQL[F] =
    SangriaGraphQL[F](
      Schema(
        query    = QueryType[F],
        mutation = Some(MutationType[F])
      ),
      WorldDeferredResolver[F],
      MasterRepo.fromTransactor(transactor).pure[F]
    )

  // Playground or else redirect to playground
  def playgroundOrElse[F[_]: Async](): HttpRoutes[F] = {
    object dsl extends Http4sDsl[F]; import dsl._
    HttpRoutes.of[F] {

      case GET -> Root / "playground.html" =>
        StaticFile
          .fromResource[F]("/assets/playground.html")
          .getOrElseF(NotFound())

      case _ =>
        PermanentRedirect(Location(Uri.unsafeFromString("/playground.html")))

    }
  }

  // Resource that mounts the given `routes` and starts a server.
  def server[F[_]: Async: Clock](
    routes: HttpRoutes[F]
  ) = {
    org.http4s.blaze.server.BlazeServerBuilder
      .apply[F]
      .bindHttp(8080, "localhost")
      .withHttpApp(routes.orNotFound)
      .resource
  }

  // Resource that constructs our final server.
  def resource[F[_]: Async: Dispatcher: Clock](
    implicit L: Logger[F]
  ): Resource[F, Server] =
    for {
      xa  <- transactor[F]()
      gql  = graphQL[F](xa)
      rts  = GraphQLRoutes[F](gql) <+> playgroundOrElse()
      svr <- server[F](rts)
    } yield svr

  // Our entry point starts the server and blocks forever.
  def run(args: List[String]): IO[ExitCode] = {
    implicit val log = Slf4jLogger.getLogger[IO]
    val r = for {
      d <- Dispatcher.sequential[IO]
      e <- resource[IO](implicitly[Async[IO]], d, implicitly[Clock[IO]], log)
    } yield e
    r.use(_ => IO.never.as(ExitCode.Success))
  }

}

