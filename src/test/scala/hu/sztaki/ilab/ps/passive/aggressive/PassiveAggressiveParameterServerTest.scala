package hu.sztaki.ilab.ps.passive.aggressive

import breeze.linalg.{SparseVector, VectorBuilder}
import hu.sztaki.ilab.ps.passive.aggressive.algorithm.PassiveAggressiveBinaryAlgorithm
import hu.sztaki.ilab.ps.test.utils.FlinkTestUtils.{SuccessException, executeWithSuccessCheck}
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.streaming.api.scala._
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks

import scala.util.Random


object PassiveAggressiveParameterServerTest {
  val featureCount = 500000
  val spareFeatureCount = 10000
  val numberOfTraining = 80
  val numberOfTest = 20
  val random = new Random(50L)

  private def randomSparseVector = {
    val vectorBuilder = new VectorBuilder[Double](length = featureCount)
    0 to spareFeatureCount foreach { i =>
      vectorBuilder.add(random.nextInt(featureCount), random.nextDouble())
    }
    vectorBuilder.toSparseVector()
  }

  val trainingData: Seq[(SparseVector[Double], Boolean)] =  Seq.fill(numberOfTraining)(
    (randomSparseVector, random.nextBoolean())
  )

  val testData: Seq[(SparseVector[Double], Boolean)] =  Seq.fill(numberOfTest)(
    (randomSparseVector, random.nextBoolean())
  )

}

class PassiveAggressiveParameterServerTest extends FlatSpec with PropertyChecks with Matchers {

  import PassiveAggressiveParameterServerTest._

  "Passive Aggressive with PS" should "give reasonable error on test data" in {
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    val src: DataStream[Either[(SparseVector[Double], Boolean), (Long, SparseVector[Double])]] =
      env.fromCollection(trainingData).map(Left(_))

    type LabeledVector = (SparseVector[Double], Boolean)

    PassiveAggressiveParameterServer.transformBinary(None)(
      src,
      workerParallelism = 3,
      psParallelism = 3,
      passiveAggressiveMethod = PassiveAggressiveBinaryAlgorithm.buildPA(),
      pullLimit = 10000,
      featureCount = PassiveAggressiveParameterServerTest.featureCount,
      rangePartitioning = true,
      iterationWaitTime = 20000
    ).addSink(new RichSinkFunction[Either[LabeledVector, (Int, Double)]] {

      val modelBuilder = new VectorBuilder[Double](length = featureCount)


      override def invoke(value: Either[LabeledVector, (Int, Double)]): Unit = {
        value match {
          case Right((id, modelValue)) =>
            modelBuilder.add(id, modelValue)
          case Left((vector, label)) =>
            // prediction channel is deaf
        }
      }

      override def close(): Unit = {
        import hu.sztaki.ilab.ps.test.utils.PassiveAggressiveBinaryModelEvaluation
        val model = modelBuilder.toDenseVector
        // compute percent
        //        Note: It would be better if the testData was used here but the random data does not fit to evaluation the algorithm
//        The part of the training dataset is used here to test the model
//        val percent = ModelEvaluation.processModel(model, testData, featureCount,
        val percent = PassiveAggressiveBinaryModelEvaluation.accuracy(model,
          trainingData.take(20).map { case (vec, lab) => (vec, Some(lab)) },
          featureCount,
          PassiveAggressiveBinaryAlgorithm.buildPA())
        throw SuccessException(percent)
      }
    }).setParallelism(1)

    val minAllowedPercent = 80

    executeWithSuccessCheck[Double](env) {
      percent =>
        println(percent)
        if (percent < minAllowedPercent) {
          fail(s"Got percent: $percent, expected higher than $minAllowedPercent." +
            s" Note that the result highly depends on environment due to the asynchronous updates.")
        }
    }
  }


}
