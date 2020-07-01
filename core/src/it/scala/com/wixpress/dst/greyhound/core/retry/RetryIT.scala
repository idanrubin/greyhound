package com.wixpress.dst.greyhound.core.retry

import java.util.regex.Pattern

import com.wixpress.dst.greyhound.core.Serdes._
import com.wixpress.dst.greyhound.core.consumer.ConsumerSubscription.{TopicPattern, Topics}
import com.wixpress.dst.greyhound.core.consumer._
import com.wixpress.dst.greyhound.core.producer.ProducerRecord
import com.wixpress.dst.greyhound.core.testkit.BaseTestWithSharedEnv
import com.wixpress.dst.greyhound.core.zioutils.AcquiredManagedResource
import com.wixpress.dst.greyhound.core.{CleanupPolicy, TopicConfig}
import com.wixpress.dst.greyhound.testkit.{ITEnv, ManagedKafka}
import com.wixpress.dst.greyhound.testkit.ITEnv._
import zio._
import zio.duration._

class RetryIT extends BaseTestWithSharedEnv[Env, TestResources] {
  sequential

  override def env: UManaged[Env] = ITEnv.ManagedEnv

  override def sharedEnv: ZManaged[Env, Throwable, TestResources] = testResources()

  "configure a non-blocking retry policy" in {
      for {
        TestResources(kafka, producer) <- getShared
        topic <- kafka.createRandomTopic()
        group <- randomGroup

        invocations <- Ref.make(0)
        done <- Promise.make[Nothing, Unit]
        retryPolicy = RetryPolicy.nonBlockingOnly(group, 1.second, 1.seconds, 1.seconds)
        retryHandler = failingRecordHandler(invocations, done).withDeserializers(StringSerde, StringSerde)
        success <- RecordConsumer.make(configFor(kafka, topic, group, retryPolicy), retryHandler).use_ {
          producer.produce(ProducerRecord(topic, "bar", Some("foo")), StringSerde, StringSerde) *>
            done.await.timeout(20.seconds)
        }
      } yield success must beSome
    }

  "configure a handler with blocking retry policy" in {
    for {
      TestResources(kafka, producer) <- getShared
      topic <- kafka.createRandomTopic()
      group <- randomGroup

      consumedValuesRef <- Ref.make(List.empty[String])

      retryPolicy = RetryPolicy.blockingOnly(1.second, 1.seconds, 1.seconds)
      retryHandler = failingBlockingRecordHandler(consumedValuesRef, topic).withDeserializers(StringSerde, StringSerde)
      _ <- RecordConsumer.make(configFor(kafka, topic, group, retryPolicy), retryHandler).use_ {
        producer.produce(ProducerRecord(topic, "bar", Some("foo")), StringSerde, StringSerde) *>
          producer.produce(ProducerRecord(topic, "baz", Some("foo")), StringSerde, StringSerde) *>
          UIO.unit.delay(5.seconds)
      }
      consumedValues <- consumedValuesRef.get
    } yield consumedValues must atLeast("bar", "bar", "bar")
  }

  "block only one partition when GreyhoundBlockingException is thrown" in {
     for {
       TestResources(kafka, producer) <- getShared
       topic <- kafka.createRandomTopic(2)
       group <- randomGroup
       firstMessageCallCount <- Ref.make[Int](0)
       secondPartitionCallCount <- Ref.make[Int](0)
       messageIntro = "message number "
       firstMessageContent = messageIntro + "1"
       secondPartitonContent = "second partition"
       numOfMessages = 10
       handler = failOnceOnOnePartitionHandler(firstMessageContent, secondPartitonContent, firstMessageCallCount, secondPartitionCallCount)
         .withDeserializers(StringSerde, StringSerde)
       retryPolicy = RetryPolicy.blockingOnly(3.second, 3.seconds, 3.seconds) // TODO: change to infinite
       resource <- AcquiredManagedResource.acquire(RecordConsumer.make(configFor(kafka, topic, group, retryPolicy), handler))
       consumer = resource.resource
       _ <- ZIO.foreach(1 to numOfMessages) { i =>
           producer.produce(ProducerRecord(topic, messageIntro + i, partition = Some(0)), StringSerde, StringSerde) *>
             producer.produce(ProducerRecord(topic, secondPartitonContent, partition = Some(1)), StringSerde, StringSerde)
         }.fork
     } yield {
       eventually {
         runtime.unsafeRun(firstMessageCallCount.get) === 1
         runtime.unsafeRun(secondPartitionCallCount.get) === numOfMessages
         runtime.unsafeRun(consumer.state).dispatcherState.totalQueuedTasksPerTopic(topic) === (numOfMessages - 1)
       }
       eventually(10, 1.second.asScala) {
         runtime.unsafeRun(firstMessageCallCount.get) === 2
         runtime.unsafeRun(consumer.state).dispatcherState.totalQueuedTasksPerTopic(topic) === 0
       }
       resource.release()
       ok
     }

  }

  "resume specific partition after blocking exception thrown" in {
    for {
    TestResources(kafka, producer) <- getShared
    topic <- kafka.createRandomTopic(1)
    group <- randomGroup
    callCount <- Ref.make[Int](0)
    exceptionMessage = "message that will throw exception"
    numOfMessages = 10

    handler = blockResumeHandler(exceptionMessage, callCount)
      .withDeserializers(StringSerde, StringSerde)
    retryPolicy = RetryPolicy.blockingOnly(30.seconds, 30.seconds) // TODO: add infinite

    resource <- AcquiredManagedResource.acquire(RecordConsumer.make(configFor(kafka, topic, group, retryPolicy), handler))
    consumer = resource.resource
    _ <- producer.produce(ProducerRecord(topic, exceptionMessage), StringSerde, StringSerde)
    _ <- ZIO.foreach(1 to numOfMessages) { _ =>
      producer.produce(ProducerRecord(topic, "irrelevant"), StringSerde, StringSerde)
    }.fork
    _ <- eventuallyZ(callCount.get, (count: Int) => count == 1)
    _ <- UIO(println(">>>> consumer.setBlockingState to IgnoreOnceFor"))
    _ <- consumer.setBlockingState[Any](TopicPartition(topic, 0), IgnoreOnceFor(TopicPartition(topic, 0))) // TODO check successful
    _ <- eventuallyZ(callCount.get, (count: Int) => count == (numOfMessages + 1))
    } yield ok
  }

  private def blockResumeHandler(exceptionMessage: String,
                                 callCount: Ref[Int]) = {
    RecordHandler { r: ConsumerRecord[String, String] =>
      callCount.get.flatMap(count => UIO(println(s">>>> in handler: r: ${r} callCount before: $count"))) *>
      callCount.update(_ + 1) *>
      ZIO.when(r.value == exceptionMessage) {
            ZIO.fail(SomeException())
      }
    }
  }

  private def failOnceOnOnePartitionHandler(firstMessageContent: String, secondPartitonContent: String,
                                            firstMessageCallCount: Ref[Int],
                                            secondPartitionCallCount: Ref[Int]) = {
    RecordHandler { r: ConsumerRecord[String, String] =>
      ZIO.when(r.value == firstMessageContent) {
        firstMessageCallCount.updateAndGet(_ + 1).flatMap(count => {
          if (count == 1)
            ZIO.fail(SomeException())
          else
            ZIO.unit
        })
      } *>
        ZIO.when(r.value == secondPartitonContent) {
          secondPartitionCallCount.update(_ + 1)
        }
    }
  }

  "commit message on failure with NonRetryableException" in {
    for {
      TestResources(kafka, producer) <- getShared
      topic <- kafka.createRandomTopic(partitions = 1) // sequential processing is needed
      group <- randomGroup
      invocations <- Ref.make(0)
      retryPolicy = RetryPolicy.nonBlockingOnly(group, 100.milliseconds, 100.milliseconds, 100.milliseconds)
      retryHandler = failingNonRetryableRecordHandler(invocations).withDeserializers(StringSerde, StringSerde)
      invocations <- RecordConsumer.make(configFor(kafka, topic, group, retryPolicy), retryHandler).use_ {
        producer.produce(ProducerRecord(topic, "bar", Some("foo")), StringSerde, StringSerde) *>
          invocations.get.delay(2.seconds)
      }
    } yield invocations mustEqual 1
  }

  private def configFor(kafka: ManagedKafka, topic: String, group: String, retryPolicy: RetryPolicy) = {
    RecordConsumerConfig(kafka.bootstrapServers, group,
      initialSubscription = Topics(Set(topic)), retryPolicy = Some(retryPolicy),
      extraProperties = fastConsumerMetadataFetching, offsetReset = OffsetReset.Earliest)
  }

  "configure a regex consumer with a retry policy" in {
    for {
      TestResources(kafka, producer) <- getShared
      _ <- kafka.createTopic(TopicConfig("topic-111", 1, 1, CleanupPolicy.Delete(1.hour.toMillis)))
      group <- randomGroup
      invocations <- Ref.make(0)
      done <- Promise.make[Nothing, Unit]
      retryPolicy = RetryPolicy.nonBlockingOnly(group, 1.second, 1.seconds, 1.seconds)
      retryHandler = failingRecordHandler(invocations, done).withDeserializers(StringSerde, StringSerde)
      success <- RecordConsumer.make(RecordConsumerConfig(kafka.bootstrapServers, group,
        initialSubscription = TopicPattern(Pattern.compile("topic-1.*")), retryPolicy = Some(retryPolicy),
        extraProperties = fastConsumerMetadataFetching, offsetReset = OffsetReset.Earliest), retryHandler).use_ {
        producer.produce(ProducerRecord("topic-111", "bar", Some("foo")), StringSerde, StringSerde) *>
          done.await.timeout(20.seconds)
      }
    } yield success must beSome
  }


  private def failingRecordHandler(invocations: Ref[Int], done: Promise[Nothing, Unit]) =
    RecordHandler { _: ConsumerRecord[String, String] =>
      invocations.updateAndGet(_ + 1).flatMap { n =>
        if (n < 4) {
          println(s"failling.. $n")
          ZIO.fail(new RuntimeException("Oops!"))
        } else {
          println(s"success!  $n")
          done.succeed(()) // Succeed on final retry
        }
      }
    }

  private def failingNonRetryableRecordHandler(originalTopicInvocations: Ref[Int]) =
    RecordHandler { _: ConsumerRecord[String, String] =>
      originalTopicInvocations.updateAndGet(_ + 1) *>
        ZIO.fail(NonRetryableException(new RuntimeException("Oops!")))
    }

  private def failingBlockingRecordHandler(consumedValues: Ref[List[String]], originalTopic: String) =
    RecordHandler { r: ConsumerRecord[String, String] =>
      UIO(println(s">>>> failingBlockingRecordHandler: r ${r}")) *>
        ZIO.when(r.topic == originalTopic) {
          consumedValues.updateAndGet(values => r.value :: values)
        } *>
        ZIO.fail(new RuntimeException("Oops!"))
    }

  private def fastConsumerMetadataFetching = Map("metadata.max.age.ms" -> "0")

  def eventuallyZ[T](f: UIO[T], predicate: T => Boolean) = {
    f.repeat(Schedule.spaced(100.milliseconds) && Schedule.doUntil(predicate)).timeoutFail(new RuntimeException)(4.seconds)
  }
}

case class SomeException() extends Exception("expected!", null, true, false)

