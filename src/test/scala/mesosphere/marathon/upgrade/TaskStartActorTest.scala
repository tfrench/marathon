package mesosphere.marathon.upgrade

import akka.actor.{ ActorSystem, Props }
import akka.testkit.{ TestActorRef, TestKit }
import com.codahale.metrics.MetricRegistry
import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.event.{ HealthStatusChanged, MesosStatusUpdateEvent }
import mesosphere.marathon.health.HealthCheck
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state.{ AppDefinition, Timestamp }
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskQueue, TaskTracker }
import mesosphere.marathon.{ MarathonConf, SchedulerActions, TaskUpgradeCanceledException }
import mesosphere.util.state.memory.InMemoryStore
import org.apache.mesos.SchedulerDriver
import org.mockito.Mockito.{ spy, times, verify, when }
import org.scalatest.mock.MockitoSugar
import org.scalatest.{ BeforeAndAfter, BeforeAndAfterAll, FunSuiteLike, Matchers }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }

class TaskStartActorTest
    extends TestKit(ActorSystem("System"))
    with FunSuiteLike
    with Matchers
    with MockitoSugar
    with BeforeAndAfter
    with BeforeAndAfterAll {

  var driver: SchedulerDriver = _
  var scheduler: SchedulerActions = _
  var taskQueue: TaskQueue = _
  var taskTracker: TaskTracker = _
  var registry: MetricRegistry = _

  before {
    driver = mock[SchedulerDriver]
    scheduler = mock[SchedulerActions]
    taskTracker = new TaskTracker(new InMemoryStore, mock[MarathonConf], new MetricRegistry)
    taskQueue = spy(new TaskQueue)
    registry = new MetricRegistry
    taskTracker = spy(new TaskTracker(new InMemoryStore, mock[MarathonConf], registry))
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    system.shutdown()
  }

  test("Start success") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    val ref = TestActorRef(Props(
      new TaskStartActor(
        driver,
        scheduler,
        taskQueue,
        taskTracker,
        system.eventStream,
        app,
        app.instances,
        promise)))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with tasks in taskQueue") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    taskQueue.add(app)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with existing task") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    val task = MarathonTask.newBuilder
      .setId(TaskIdUtil.newTaskId(app.id).getValue)
      .setVersion(Timestamp(1024).toString)
      .build
    taskTracker.created(app.id, task)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 4, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with no instances to start") {
    val promise = Promise[Boolean]()
    val app = AppDefinition("/myApp".toPath, instances = 0)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks") {
    val promise = Promise[Boolean]()
    val app = AppDefinition(
      "/myApp".toPath,
      instances = 5,
      healthChecks = Set(HealthCheck())
    )

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(HealthStatusChanged(app.id, s"task_$i", app.version.toString, alive = true))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start with health checks with no instances to start") {
    val promise = Promise[Boolean]()
    val app = AppDefinition(
      "/myApp".toPath,
      instances = 0,
      healthChecks = Set(HealthCheck())
    )

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Cancelled") {
    val promise = Promise[Boolean]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    val ref = system.actorOf(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    system.stop(ref)

    intercept[TaskUpgradeCanceledException] {
      Await.result(promise.future, 5.seconds)
    }.getMessage should equal("The task upgrade has been cancelled")

    expectTerminated(ref)
  }

  test("Task fails to start") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 1)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    awaitCond(taskQueue.count(app.id) == 1, 3.seconds)

    taskQueue.purge(app.id)

    system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_FAILED", "", app.id, "", Nil, app.version.toString))

    awaitCond(taskQueue.count(app.id) == 1, 3.seconds)

    verify(taskQueue, times(2)).add(app, 1)

    for (i <- 0 until taskQueue.count(app.id))
      system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))

    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }

  test("Start success with dying existing task, reschedules, but finishes early") {
    val promise = Promise[Unit]()
    val app = AppDefinition("/myApp".toPath, instances = 5)

    val taskId = TaskIdUtil.newTaskId(app.id)
    val task = MarathonTask.newBuilder
      .setId(taskId.getValue)
      .setVersion(Timestamp(1024).toString)
      .build
    taskTracker.created(app.id, task)

    val ref = TestActorRef(Props(
      classOf[TaskStartActor],
      driver,
      scheduler,
      taskQueue,
      taskTracker,
      system.eventStream,
      app,
      app.instances,
      promise))

    watch(ref)

    // wait for initial sync
    awaitCond(taskQueue.count(app.id) == 4, 3.seconds)

    // let existing task die
    // doesn't work because it needs Zookeeper: taskTracker.terminated(app.id, taskStatus)
    // we mock instead
    when(taskTracker.count(app.id)).thenReturn(0)
    system.eventStream.publish(MesosStatusUpdateEvent("", "", "TASK_ERROR", "", app.id, "", Nil, task.getVersion))

    // sync will reschedule task
    ref ! StartingBehavior.Sync
    awaitCond(taskQueue.count(app.id) == 5, 3.seconds)

    // launch 4 of the tasks
    when(taskTracker.count(app.id)).thenReturn(4)
    List(0, 1, 2, 3) foreach { i =>
      taskQueue.poll()
      system.eventStream.publish(MesosStatusUpdateEvent("", s"task-$i", "TASK_RUNNING", "", app.id, "", Nil, app.version.toString))
    }
    assert(taskQueue.count(app.id) == 1)

    // it finished early
    Await.result(promise.future, 3.seconds) should be(())

    expectTerminated(ref)
  }
}
