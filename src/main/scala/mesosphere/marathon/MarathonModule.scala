package mesosphere.marathon

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Named

import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ ActorRef, ActorRefFactory, ActorSystem, OneForOneStrategy, Props }
import akka.event.EventStream
import akka.routing.RoundRobinPool
import com.codahale.metrics.MetricRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.inject._
import com.google.inject.name.Names
import com.twitter.common.base.Supplier
import com.twitter.common.zookeeper.{ Candidate, CandidateImpl, Group => ZGroup, ZooKeeperClient }
import com.twitter.zk.{ NativeConnector, ZkClient }
import mesosphere.chaos.http.HttpConf
import mesosphere.marathon.event.EventModule
import mesosphere.marathon.event.http.HttpEventStreamActor
import mesosphere.marathon.health.{ HealthCheckManager, MarathonHealthCheckManager }
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.state._
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker, _ }
import mesosphere.marathon.upgrade.DeploymentPlan
import mesosphere.util.SerializeExecution
import mesosphere.util.state.mesos.MesosStateStore
import mesosphere.util.state.zk.ZKStore
import mesosphere.util.state.{ FrameworkIdUtil, PersistentStore }
import org.apache.log4j.Logger
import org.apache.mesos.state.ZooKeeperState
import org.apache.zookeeper.ZooDefs
import org.apache.zookeeper.ZooDefs.Ids

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object ModuleNames {
  final val NAMED_CANDIDATE = "CANDIDATE"
  final val NAMED_LEADER_ATOMIC_BOOLEAN = "LEADER_ATOMIC_BOOLEAN"
  final val NAMED_SERVER_SET_PATH = "SERVER_SET_PATH"
  final val NAMED_SERIALIZE_GROUP_UPDATES = "SERIALIZE_GROUP_UPDATES"
  final val NAMED_HTTP_EVENT_STREAM = "HTTP_EVENT_STREAM"
}

class MarathonModule(conf: MarathonConf, http: HttpConf, zk: ZooKeeperClient)
    extends AbstractModule {

  val log = Logger.getLogger(getClass.getName)

  def configure() {

    bind(classOf[MarathonConf]).toInstance(conf)
    bind(classOf[HttpConf]).toInstance(http)
    bind(classOf[ZooKeeperClient]).toInstance(zk)

    // needs to be eager to break circular dependencies
    bind(classOf[SchedulerCallbacks]).to(classOf[SchedulerCallbacksServiceAdapter]).asEagerSingleton()

    bind(classOf[MarathonSchedulerDriverHolder]).in(Scopes.SINGLETON)
    bind(classOf[SchedulerDriverFactory]).to(classOf[MesosSchedulerDriverFactory]).in(Scopes.SINGLETON)
    bind(classOf[MarathonScheduler]).in(Scopes.SINGLETON)
    bind(classOf[MarathonSchedulerService]).in(Scopes.SINGLETON)
    bind(classOf[TaskTracker]).in(Scopes.SINGLETON)
    bind(classOf[TaskQueue]).in(Scopes.SINGLETON)
    bind(classOf[TaskFactory]).to(classOf[DefaultTaskFactory]).in(Scopes.SINGLETON)
    bind(classOf[IterativeOfferMatcherMetrics]).in(Scopes.SINGLETON)
    bind(classOf[OfferMatcher]).to(classOf[IterativeOfferMatcher]).in(Scopes.SINGLETON)
    bind(classOf[GroupManager]).in(Scopes.SINGLETON)

    bind(classOf[HealthCheckManager]).to(classOf[MarathonHealthCheckManager]).asEagerSingleton()

    bind(classOf[String])
      .annotatedWith(Names.named(ModuleNames.NAMED_SERVER_SET_PATH))
      .toInstance(conf.zooKeeperServerSetPath())

    // If running in single scheduler mode, this node is the leader.
    val leader = new AtomicBoolean(!conf.highlyAvailable())
    bind(classOf[AtomicBoolean])
      .annotatedWith(Names.named(ModuleNames.NAMED_LEADER_ATOMIC_BOOLEAN))
      .toInstance(leader)

  }

  @Named(ModuleNames.NAMED_HTTP_EVENT_STREAM)
  @Provides
  @Singleton
  def provideHttpEventStreamActor(system: ActorSystem,
                                  @Named(EventModule.busName) eventBus: EventStream): ActorRef = {
    val outstanding = conf.eventStreamMaxOutstandingMessages.get.getOrElse(50)
    system.actorOf(Props(classOf[HttpEventStreamActor], eventBus, outstanding), "HttpEventStream")
  }

  @Provides
  @Singleton
  def provideIterativeOfferMatcherConfig(): IterativeOfferMatcherConfig = conf

  @Provides
  @Singleton
  def provideStore(): PersistentStore = {
    def directZK(): PersistentStore = {
      implicit val timer = com.twitter.util.Timer.Nil
      import com.twitter.util.TimeConversions._
      val sessionTimeout = conf.zooKeeperSessionTimeout.get.map(_.millis).getOrElse(30.minutes)
      val connector = NativeConnector(conf.zkHosts, None, sessionTimeout, timer)
      val client = ZkClient(connector)
        .withAcl(Ids.OPEN_ACL_UNSAFE.asScala)
        .withRetries(3)
      new ZKStore(client, client(conf.zooKeeperStatePath()))
    }
    def mesosZK(): PersistentStore = {
      val state = new ZooKeeperState(
        conf.zkHosts,
        conf.zkTimeoutDuration.toMillis,
        TimeUnit.MILLISECONDS,
        conf.zooKeeperStatePath()
      )
      new MesosStateStore(state, conf.zkTimeoutDuration)
    }
    conf.internalStoreBackend.get match {
      case Some("zk")       => directZK()
      case Some("mesos_zk") => mesosZK()
      case backend          => throw new IllegalArgumentException(s"Storage backend $backend not known!")
    }
  }

  @Named("schedulerActor")
  @Provides
  @Singleton
  @Inject
  def provideSchedulerActor(
    @Named("restMapper") mapper: ObjectMapper,
    system: ActorSystem,
    appRepository: AppRepository,
    deploymentRepository: DeploymentRepository,
    healthCheckManager: HealthCheckManager,
    taskTracker: TaskTracker,
    taskQueue: TaskQueue,
    frameworkIdUtil: FrameworkIdUtil,
    driverHolder: MarathonSchedulerDriverHolder,
    taskIdUtil: TaskIdUtil,
    storage: StorageProvider,
    @Named(EventModule.busName) eventBus: EventStream,
    taskFailureRepository: TaskFailureRepository,
    config: MarathonConf): ActorRef = {
    val supervision = OneForOneStrategy() {
      case NonFatal(_) => Restart
    }

    system.actorOf(
      Props(
        classOf[MarathonSchedulerActor],
        mapper,
        appRepository,
        deploymentRepository,
        healthCheckManager,
        taskTracker,
        taskQueue,
        frameworkIdUtil,
        driverHolder,
        taskIdUtil,
        storage,
        eventBus,
        taskFailureRepository,
        config).withRouter(RoundRobinPool(nrOfInstances = 1, supervisorStrategy = supervision)),
      "MarathonScheduler")
  }

  @Named(ModuleNames.NAMED_CANDIDATE)
  @Provides
  @Singleton
  def provideCandidate(zk: ZooKeeperClient): Option[Candidate] = {
    if (conf.highlyAvailable()) {
      log.info("Registering in Zookeeper with hostname:"
        + conf.hostname())
      val candidate = new CandidateImpl(new ZGroup(zk, ZooDefs.Ids.OPEN_ACL_UNSAFE,
        conf.zooKeeperLeaderPath()),
        new Supplier[Array[Byte]] {
          def get(): Array[Byte] = {
            //host:port
            "%s:%d".format(conf.hostname(),
              http.httpPort()).getBytes
          }
        })
      return Some(candidate)
    }
    None
  }

  @Provides
  @Singleton
  def provideTaskFailureRepository(
    store: PersistentStore,
    conf: MarathonConf,
    registry: MetricRegistry): TaskFailureRepository = {
    import mesosphere.marathon.state.PathId
    import org.apache.mesos.{ Protos => mesos }
    new TaskFailureRepository(
      new MarathonStore[TaskFailure](
        store,
        registry,
        () => TaskFailure(
          PathId.empty,
          mesos.TaskID.newBuilder().setValue("").build,
          mesos.TaskState.TASK_STAGING
        )
      ),
      conf.zooKeeperMaxVersions.get
    )
  }

  @Provides
  @Singleton
  def provideAppRepository(
    store: PersistentStore,
    conf: MarathonConf,
    registry: MetricRegistry): AppRepository = {
    new AppRepository(
      new MarathonStore[AppDefinition](store, registry, () => AppDefinition.apply()),
      maxVersions = conf.zooKeeperMaxVersions.get,
      registry
    )
  }

  @Provides
  @Singleton
  def provideGroupRepository(
    store: PersistentStore,
    appRepository: AppRepository,
    conf: MarathonConf,
    registry: MetricRegistry): GroupRepository = {
    new GroupRepository(
      new MarathonStore[Group](store, registry, () => Group.empty, "group:"),
      appRepository, conf.zooKeeperMaxVersions.get,
      registry
    )
  }

  @Provides
  @Singleton
  def provideDeploymentRepository(
    store: PersistentStore,
    conf: MarathonConf,
    registry: MetricRegistry): DeploymentRepository = {
    new DeploymentRepository(
      new MarathonStore[DeploymentPlan](store, registry, () => DeploymentPlan.empty, "deployment:"),
      conf.zooKeeperMaxVersions.get,
      registry
    )
  }

  @Provides
  @Singleton
  def provideActorSystem(): ActorSystem = ActorSystem("marathon")

  /* Reexports the `akka.actor.ActorSystem` as `akka.actor.ActorRefFactory`. It doesn't work automatically. */
  @Provides
  @Singleton
  def provideActorRefFactory(system: ActorSystem): ActorRefFactory = system

  @Provides
  @Singleton
  def provideFrameworkIdUtil(store: PersistentStore, registry: MetricRegistry): FrameworkIdUtil = {
    new FrameworkIdUtil(store)
  }

  @Provides
  @Singleton
  def provideMigration(
    store: PersistentStore,
    appRepo: AppRepository,
    groupRepo: GroupRepository,
    registry: MetricRegistry,
    config: MarathonConf): Migration = {
    new Migration(store, appRepo, groupRepo, config, registry)
  }

  @Provides
  @Singleton
  def provideTaskIdUtil(): TaskIdUtil = new TaskIdUtil

  @Provides
  @Singleton
  def provideStorageProvider(config: MarathonConf, http: HttpConf): StorageProvider =
    StorageProvider.provider(config, http)

  @Named(ModuleNames.NAMED_SERIALIZE_GROUP_UPDATES)
  @Provides
  @Singleton
  def provideSerializeGroupUpdates(actorRefFactory: ActorRefFactory): SerializeExecution = {
    SerializeExecution(actorRefFactory, "serializeGroupUpdates")
  }
}
