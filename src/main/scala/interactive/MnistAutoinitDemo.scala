/*
 * Copyright (c) 2017 by Andrew Charneski.
 *
 * The author licenses this file to you under the
 * Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package interactive

import java.util.concurrent.TimeUnit

import _root_.util.NetworkViz._
import _root_.util._
import com.simiacryptus.mindseye.layers.NNLayer
import com.simiacryptus.mindseye.layers.activation._
import com.simiacryptus.mindseye.layers.cross.CrossProductLayer
import com.simiacryptus.mindseye.layers.loss.EntropyLossLayer
import com.simiacryptus.mindseye.layers.meta.{AvgMetaLayer, SumMetaLayer}
import com.simiacryptus.mindseye.layers.reducers.{AvgReducerLayer, SumInputsLayer, SumReducerLayer}
import com.simiacryptus.mindseye.layers.synapse.{BiasLayer, DenseSynapseLayer}
import com.simiacryptus.mindseye.layers.util.{MonitoringSynapse, MonitoringWrapper}
import com.simiacryptus.mindseye.network.graph._
import com.simiacryptus.mindseye.network.{PipelineNetwork, SimpleLossNetwork, SupervisedNetwork}
import com.simiacryptus.mindseye.opt.trainable.StochasticArrayTrainable
import com.simiacryptus.mindseye.opt.{GradientDescent, IterativeTrainer}
import com.simiacryptus.util.StreamNanoHTTPD
import com.simiacryptus.util.io.{HtmlNotebookOutput, KryoUtil, MarkdownNotebookOutput}
import com.simiacryptus.util.ml.Tensor
import com.simiacryptus.util.test.MNIST
import com.simiacryptus.util.text.TableOutput
import guru.nidi.graphviz.engine.{Format, Graphviz}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random


object MnistAutoinitDemo extends ServiceNotebook {

  def main(args: Array[String]): Unit = {
    report((s,log)⇒new MnistAutoinitDemo(s,log).run)
    System.exit(0)
  }
}

class MnistAutoinitDemo(server: StreamNanoHTTPD, log: HtmlNotebookOutput with ScalaNotebookOutput) extends MindsEyeNotebook(server, log) {

  val inputSize = Array[Int](28, 28, 1)
  val midSize = Array[Int](20)
  val outputSize = Array[Int](10)
  val trainingTime = 5

  lazy val component1 = log.eval {
    var model: PipelineNetwork = new PipelineNetwork
    model.add(new MonitoringWrapper(new BiasLayer(inputSize: _*)).addTo(monitoringRoot, "bias1a"))
    model.add(new MonitoringWrapper(new DenseSynapseLayer(inputSize, midSize)
      .setWeights(Java8Util.cvt(() ⇒ 0.001 * (Random.nextDouble() - 0.5)))).addTo(monitoringRoot, "synapse1"))
    model.add(new MonitoringWrapper(new ReLuActivationLayer).addTo(monitoringRoot, "relu1"))
    model.add(new MonitoringWrapper(new BiasLayer(midSize: _*)).addTo(monitoringRoot, "bias1b"))
    model
  }

  lazy val autoinitializer = log.eval {
    var model: PipelineNetwork = new PipelineNetwork
    model.add(new MonitoringWrapper(component1).addTo(monitoringRoot, "component1init"))
    val componentNode = model.add(new MonitoringSynapse().addTo(monitoringRoot, "component1out"))

    model.add(new SumMetaLayer(), componentNode)
    model.add(new SqActivationLayer())
    model.add(new MonitoringSynapse().addTo(monitoringRoot, "meanNormalizer1"))
    model.add(new SumReducerLayer())
    val meanNormalizer1 = model.add(new LinearActivationLayer().setWeight(1.0).freeze())

    model.add(new SumReducerLayer(), componentNode)
    model.add(new SqActivationLayer())
    model.add(new MonitoringSynapse().addTo(monitoringRoot, "meanNormalizer2"))
    model.add(new SumMetaLayer())
    val meanNormalizer2 = model.add(new LinearActivationLayer().setWeight(1.0).freeze())

    model.add(new CrossProductLayer(), componentNode)
    model.add(new SumMetaLayer())
    model.add(new AbsActivationLayer())
    model.add(new MonitoringSynapse().addTo(monitoringRoot, "dotNormalizer"))
    model.add(new SumReducerLayer())
    val dotNormalizer = model.add(new LinearActivationLayer().setWeight(1.0).freeze())

    model.add(new SqActivationLayer(), componentNode)
    model.add(new SumMetaLayer())
    model.add(new LogActivationLayer())
    model.add(new SqActivationLayer())
    model.add(new MonitoringSynapse().addTo(monitoringRoot, "lengthNormalizer1"))
    model.add(new SumReducerLayer())
    val lengthNormalizer1 = model.add(new LinearActivationLayer().setWeight(1.0).freeze())

    model.add(new SqActivationLayer(), componentNode)
    model.add(new SumReducerLayer())
    model.add(new LogActivationLayer())
    model.add(new SqActivationLayer())
    model.add(new MonitoringSynapse().addTo(monitoringRoot, "lengthNormalizer2"))
    model.add(new SumMetaLayer())
    val lengthNormalizer2 = model.add(new LinearActivationLayer().setWeight(1.0).freeze())

    model.add(new SumInputsLayer(), dotNormalizer, meanNormalizer1, meanNormalizer2, lengthNormalizer1, lengthNormalizer2)
    model
  }

  lazy val model = log.eval {
    var model: PipelineNetwork = new PipelineNetwork
    model.add(new MonitoringWrapper(component1).addTo(monitoringRoot, "component1"))
    model.add(new MonitoringSynapse().addTo(monitoringRoot, "pre-softmax"))
    model.add(new LinearActivationLayer().setWeight(0.1).freeze())
    model.add(new MonitoringWrapper(new DenseSynapseLayer(midSize, outputSize)
      .setWeights(Java8Util.cvt(() ⇒ 0.001 * (Random.nextDouble() - 0.5)))).addTo(monitoringRoot, "synapseEnd"))
    model.add(new MonitoringWrapper(new BiasLayer(outputSize: _*)).addTo(monitoringRoot, "outbias"))

    model.add(new SoftmaxActivationLayer)
    model
  }

  def buildTrainer(data: Seq[Array[Tensor]], model: NNLayer): IterativeTrainer = {
    val trainingNetwork: SupervisedNetwork = new SimpleLossNetwork(model, new EntropyLossLayer)
    val trainable = new StochasticArrayTrainable(data.toArray, trainingNetwork, 1000)
    val trainer = new com.simiacryptus.mindseye.opt.IterativeTrainer(trainable)
    trainer.setMonitor(monitor)
    trainer.setOrientation(new GradientDescent);
    trainer.setTimeout(trainingTime, TimeUnit.MINUTES)
    trainer.setTerminateThreshold(0.0)
    trainer
  }

  def run {

    log.p("In this demo we newTrainer a simple neural network against the MNIST handwritten digit dataset")

    log.h2("Data")
    log.p("First, we cache the training dataset: ")
    val data: Seq[Array[Tensor]] = log.eval {
      MNIST.trainingDataStream().iterator().asScala.toStream.map(labeledObj ⇒ {
        Array(labeledObj.data, toOutNDArray(toOut(labeledObj.label), 10))
      })
    }
    log.p("<a href='/sample.html'>View a preview table here</a>")
    server.addSyncHandler("sample.html", "text/html", Java8Util.cvt(out ⇒ {
      Option(new HtmlNotebookOutput(log.workingDir, out) with ScalaNotebookOutput).foreach(log ⇒ {
        log.eval {
          TableOutput.create(data.take(10).map(testObj ⇒ Map[String, AnyRef](
            "Input1 (as Image)" → log.image(testObj(0).toGrayImage(), testObj(0).toString),
            "Input2 (as String)" → testObj(1).toString,
            "Input1 (as String)" → testObj(0).toString
          ).asJava): _*)
        }
      })
    }), false)

    log.h2("Model")
    log.p("Here we define the logic network that we are about to train: ")
    model
    defineMonitorReports()

    log.p("<a href='/test.html'>Validation Report</a>")
    server.addSyncHandler("test.html", "text/html", Java8Util.cvt(out ⇒ {
      Option(new HtmlNotebookOutput(log.workingDir, out) with ScalaNotebookOutput).foreach(log ⇒ {
        validation(log, KryoUtil.kryo().copy(model))
      })
    }), false)


    log.eval {
      val trainingNetwork: SupervisedNetwork = new SimpleLossNetwork(model, new EntropyLossLayer)
      val trainable = new StochasticArrayTrainable(data.map(x⇒Array(x(0))).toArray, autoinitializer, 1000)
      val trainer = new com.simiacryptus.mindseye.opt.IterativeTrainer(trainable)
      trainer.setMonitor(monitor)
      trainer.setTimeout(2, TimeUnit.MINUTES)
      trainer.setTerminateThreshold(0.0)
      trainer
    }.run()
    Await.result(regenerateReports(), Duration(1, TimeUnit.MINUTES))
    log.p("Pretraining complete")
    Thread.sleep(10000)

    log.p("We train using a the following strategy: ")
    buildTrainer(data, model).run()

    log.p("A summary of the training timeline: ")
    summarizeHistory(log)
    Await.result(regenerateReports(), Duration(1, TimeUnit.MINUTES))

    log.p("Validation Report")
    validation(log, model)

    log.p("Parameter History Data Table")
    log.p(dataTable.toHtmlTable)

    waitForExit()
  }

  def validation(log: HtmlNotebookOutput with ScalaNotebookOutput, model: NNLayer) = {
    log.h2("Validation")
    log.p("Here we examine a sample of validation rows, randomly selected: ")
    log.eval {
      TableOutput.create(MNIST.validationDataStream().iterator().asScala.toStream.take(10).map(testObj ⇒ {
        val result = model.eval(testObj.data).data.head
        Map[String, AnyRef](
          "Input" → log.image(testObj.data.toGrayImage(), testObj.label),
          "Predicted Label" → (0 to 9).maxBy(i ⇒ result.get(i)).asInstanceOf[Integer],
          "Actual Label" → testObj.label,
          "Network Output" → result
        ).asJava
      }): _*)
    }
    log.p("Validation rows that are mispredicted are also sampled: ")
    log.eval {
      TableOutput.create(MNIST.validationDataStream().iterator().asScala.toStream.filterNot(testObj ⇒ {
        val result = model.eval(testObj.data).data.head
        val prediction: Int = (0 to 9).maxBy(i ⇒ result.get(i))
        val actual = toOut(testObj.label)
        prediction == actual
      }).take(10).map(testObj ⇒ {
        val result = model.eval(testObj.data).data.head
        Map[String, AnyRef](
          "Input" → log.image(testObj.data.toGrayImage(), testObj.label),
          "Predicted Label" → (0 to 9).maxBy(i ⇒ result.get(i)).asInstanceOf[Integer],
          "Actual Label" → testObj.label,
          "Network Output" → result
        ).asJava
      }): _*)
    }
    log.p("To summarize the accuracy of the model, we calculate several summaries: ")
    log.p("The (mis)categorization matrix displays a count matrix for every actual/predicted category: ")
    val categorizationMatrix: Map[Int, Map[Int, Int]] = log.eval {
      MNIST.validationDataStream().iterator().asScala.toStream.map(testObj ⇒ {
        val result = model.eval(testObj.data).data.head
        val prediction: Int = (0 to 9).maxBy(i ⇒ result.get(i))
        val actual: Int = toOut(testObj.label)
        actual → prediction
      }).groupBy(_._1).mapValues(_.groupBy(_._2).mapValues(_.size))
    }
    writeMislassificationMatrix(log, categorizationMatrix)
    log.out("")
    log.p("The accuracy, summarized per category: ")
    log.eval {
      (0 to 9).map(actual ⇒ {
        actual → (categorizationMatrix.getOrElse(actual, Map.empty).getOrElse(actual, 0) * 100.0 / categorizationMatrix.getOrElse(actual, Map.empty).values.sum)
      }).toMap
    }
    log.p("The accuracy, summarized over the entire validation set: ")
    log.eval {
      (0 to 9).map(actual ⇒ {
        categorizationMatrix.getOrElse(actual, Map.empty).getOrElse(actual, 0)
      }).sum.toDouble * 100.0 / categorizationMatrix.values.flatMap(_.values).sum
    }
  }

  def writeMislassificationMatrix(log: HtmlNotebookOutput, categorizationMatrix: Map[Int, Map[Int, Int]]) = {
    log.out("<table>")
    log.out("<tr>")
    log.out((List("Actual \\ Predicted | ") ++ (0 to 9)).map("<td>"+_+"</td>").mkString(""))
    log.out("</tr>")
    (0 to 9).foreach(actual ⇒ {
      log.out("<tr>")
      log.out(s"<td>$actual</td>" + (0 to 9).map(prediction ⇒ {
        categorizationMatrix.getOrElse(actual, Map.empty).getOrElse(prediction, 0)
      }).map("<td>"+_+"</td>").mkString(""))
      log.out("</tr>")
    })
    log.out("</table>")
  }

  def writeMislassificationMatrix(log: MarkdownNotebookOutput, categorizationMatrix: Map[Int, Map[Int, Int]]) = {
    log.out("Actual \\ Predicted | " + (0 to 9).mkString(" | "))
    log.out((0 to 10).map(_ ⇒ "---").mkString(" | "))
    (0 to 9).foreach(actual ⇒ {
      log.out(s" **$actual** | " + (0 to 9).map(prediction ⇒ {
        categorizationMatrix.getOrElse(actual, Map.empty).getOrElse(prediction, 0)
      }).mkString(" | "))
    })
  }

  def toOut(label: String): Int = {
    (0 until 10).find(label == "[" + _ + "]").get
  }

  def networkGraph(log: ScalaNotebookOutput, network: DAGNetwork, width: Int = 1200, height: Int = 1000) = {
    try {
      log.eval {
        Graphviz.fromGraph(toGraph(network)).height(height).width(width).render(Format.PNG).toImage
      }
    } catch {
      case e : Throwable ⇒ e.printStackTrace()
    }
  }

  def toOutNDArray(out: Int, max: Int): Tensor = {
    val ndArray = new Tensor(max)
    ndArray.set(out, 1)
    ndArray
  }

}