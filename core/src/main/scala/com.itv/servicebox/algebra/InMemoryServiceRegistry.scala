package com.itv.servicebox.algebra

import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

import cats.MonadError
import cats.data.NonEmptyList
import cats.instances.list._
import cats.instances.map._
import cats.instances.set._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.show._
import cats.syntax.traverse._
import com.itv.servicebox.algebra.Container.PortMapping
import com.itv.servicebox.algebra.ServiceRegistry.{ContainerMappings, Location}

import scala.annotation.tailrec
import scala.util.Try

class InMemoryServiceRegistry[F[_]](range: Range, logger: Logger[F])(implicit tag: AppTag,
                                                                     I: ImpureEffect[F],
                                                                     M: MonadError[F, Throwable])
    extends ServiceRegistry[F](logger) {

  private val registry  = new AtomicReference[Map[Service.Ref, ContainerMappings]](Map.empty)
  private val portRange = new AtomicReference[Range](range)

  private def allocatePorts(containerPorts: Set[Int]): F[Set[PortMapping]] = {
    val cannotAllocateErr = new IllegalStateException(
      s"cannot allocate ${containerPorts.size} port/s within range: $range")

    //TODO: make this stack safe?
    def allocateNext(toAllocate: List[Int]): F[List[PortMapping]] = toAllocate match {
      case Nil => M.pure(Nil)
      case guestPort :: rest =>
        for {
          next <- I.lift(portRange.get()).map(_.headOption)
          mapping <- next.fold(M.raiseError[List[PortMapping]](cannotAllocateErr)) { hostPort =>
            checkPort(hostPort).flatMap(
              isAvailable =>
                I.lift(portRange.getAndUpdate(_.drop(1)))
                  .as(if (isAvailable) List(hostPort -> guestPort) else Nil))
          }
          moreMappings <- if (mapping.isEmpty) allocateNext(toAllocate) else allocateNext(rest)
        } yield mapping ++ moreMappings
    }

    allocateNext(containerPorts.toList).map(_.toSet)
  }

  private def checkPort(port: Int): F[Boolean] = I.lift {
    Try {
      new Socket("localhost", port).close()
    }.toOption.isEmpty
  }

  protected def portsBound(ports: Set[Int]): F[Set[Int]] =
    ports.toList.foldMapM(p => checkPort(p).map(isAvailable => if (isAvailable) Set.empty else Set(p)))

  override def register(service: Service.Spec[F]) =
    for {
      registeredContainers <- service.containers.toList.traverse[F, Container.Registered](c =>
        allocatePorts(c.internalPorts).map { portMapping =>
          val id = Container.Ref(s"${service.ref.value}/${c.imageName}")
          Container.Registered(id, c.imageName, c.env, portMapping)
      })

      err = ServiceRegistry.EmptyPortList(registeredContainers.map(_.ref))

      endpoints <- NonEmptyList
        .fromList(
          registeredContainers.flatMap(_.portMappings.map(_._1))
        )
        .fold(M.raiseError[NonEmptyList[Location]](err)) { ports =>
          M.pure(ports.map(Location.localhost))
        }

      rs = Service.Registered(
        service.name,
        NonEmptyList.fromListUnsafe(registeredContainers),
        endpoints,
        service.readyCheck
      )
      summary = registeredContainers
        .map(c => s"[${c.ref.show}] ${c.portMappings.map { case (host, guest) => s"$host -> $guest" }.mkString(", ")}")
        .mkString("\n")

      portMappings = rs.containers.foldMap(c => Map(c.ref -> c.portMappings))

      _ <- logger.debug(s"registering containers with port ranges:\n\t$summary")
      _ = registry.getAndUpdate(_.updated(rs.ref, portMappings))

    } yield rs

  override def deregister(id: Service.Ref) =
    I.lift(registry.getAndUpdate(_ - id)).void

  override def deregister(id: Service.Ref, cRef: Container.Ref) =
    I.lift(registry.getAndUpdate { m =>
        m.get(id)
          .map { mappings =>
            m.updated(id, mappings - cRef)
          }
          .getOrElse(m)
      })
      .void

  override def lookup(id: Service.Ref) =
    I.lift(registry.get).map(_.get(id).filter(_.nonEmpty))

  override def updatePortMappings(id: Service.Ref, cId: Container.Ref, mappings: Set[PortMapping]) =
    I.lift(registry.getAndUpdate { data =>
        val updated = data
          .get(id)
          .map { m =>
            m.updated(cId, mappings)
          }
          .fold(data)(updatedSrv => data.updated(id, updatedSrv))

        logger.debug(s"updated port mappings: ${updated} | mappings set: ${mappings}")

        updated
      })
      .void

}
