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

package interactive.classify

import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.{Graphics2D, RenderingHints}
import java.io._
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import java.util.stream.Collectors
import javax.imageio.ImageIO

import _root_.util.Java8Util.cvt
import _root_.util._
import com.simiacryptus.mindseye.layers.NNLayer.NNExecutionContext
import com.simiacryptus.mindseye.layers.activation.{AbsActivationLayer, LinearActivationLayer, NthPowerActivationLayer, SoftmaxActivationLayer}
import com.simiacryptus.mindseye.layers.cudnn.CuDNN
import com.simiacryptus.mindseye.layers.cudnn.f32.PoolingLayer.PoolingMode
import com.simiacryptus.mindseye.layers.cudnn.f32._
import com.simiacryptus.mindseye.layers.loss.{EntropyLossLayer, MeanSqLossLayer}
import com.simiacryptus.mindseye.layers.media.{AvgImageBandLayer, ImgCropLayer, ImgReshapeLayer}
import com.simiacryptus.mindseye.layers.meta.StdDevMetaLayer
import com.simiacryptus.mindseye.layers.reducers.{AvgReducerLayer, ProductInputsLayer}
import com.simiacryptus.mindseye.layers.synapse.BiasLayer
import com.simiacryptus.mindseye.layers.{NNLayer, NNResult, SchemaComponent}
import com.simiacryptus.mindseye.network.graph.{DAGNetwork, DAGNode, EvaluationContext}
import com.simiacryptus.mindseye.network.{PipelineNetwork, SimpleLossNetwork}
import com.simiacryptus.mindseye.opt._
import com.simiacryptus.mindseye.opt.line._
import com.simiacryptus.mindseye.opt.orient._
import com.simiacryptus.mindseye.opt.trainable._
import com.simiacryptus.util.StreamNanoHTTPD
import com.simiacryptus.util.io.{HtmlNotebookOutput, KryoUtil}
import com.simiacryptus.util.ml.{SoftCachedSupplier, Tensor, WeakCachedSupplier}
import com.simiacryptus.util.text.TableOutput
import interactive.classify.GoogLeNetModeler.tileSize
import interactive.classify.IncrementalClassifierModeler.{modelName, numberOfCategories}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag
import scala.util.Random

object IncGoogLeNetModeler extends Report {
  val modelName = System.getProperty("modelName", "googlenet_1")
  val tileSize = 224
  val categoryWhitelist = Set[String]()
  //("greyhound", "soccer-ball", "telephone-box", "windmill")
  val numberOfCategories = 256 // categoryWhitelist.size
  val imagesPerCategory = 500
  val fuzz = 1e-4
  val artificialVariants = 10

  def main(args: Array[String]): Unit = {

    report((server, out) ⇒ args match {
      case Array(source) ⇒ new IncGoogLeNetModeler(source, server, out).run()
      case _ ⇒ new IncGoogLeNetModeler("D:\\testImages\\256_ObjectCategories", server, out).run()
    })

  }

}

import interactive.classify.GoogLeNetModeler._

class IncGoogLeNetModeler(source: String, server: StreamNanoHTTPD, out: HtmlNotebookOutput with ScalaNotebookOutput) extends MindsEyeNotebook(server, out) {

  def run(awaitExit: Boolean = true): Unit = {
    recordMetrics = false
    defineHeader()
    declareTestHandler()
    out.out("<hr/>")
    val timeBlockMinutes = 15
    step_Generate()
    step_AddLayer1(trainingMin = timeBlockMinutes, sampleSize = 100)
    step_Train(trainingMin = timeBlockMinutes, numberOfCategories=2, sampleSize = 200, iterationsPerSample = 5)
    step_AddLayer2(trainingMin = timeBlockMinutes, sampleSize = 100)
    step_Train(trainingMin = timeBlockMinutes, numberOfCategories=2, sampleSize = 200, iterationsPerSample = 5)
    step_GAN()
    out.out("<hr/>")
    if (awaitExit) waitForExit()
  }

  def resize(source: BufferedImage, size: Int) = {
    val image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.getGraphics.asInstanceOf[Graphics2D]
    graphics.asInstanceOf[Graphics2D].setRenderingHints(new RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC))
    graphics.drawImage(source, 0, 0, size, size, null)
    image
  }

  def resize(source: BufferedImage, width: Int, height: Int) = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.getGraphics.asInstanceOf[Graphics2D]
    graphics.asInstanceOf[Graphics2D].setRenderingHints(new RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC))
    graphics.drawImage(source, 0, 0, width, height, null)
    image
  }

  def step_Generate() = phase({
    new PipelineNetwork(2)
  }, (model: NNLayer) ⇒ {
    // Do Nothing
  }: Unit, modelName)


  def newInceptionLayer(layerName : String, inputBands: Int, bands1x1: Int, bands3x1: Int, bands1x3: Int, bands5x1: Int, bands1x5: Int, bandsPooling: Int): NNLayer = {
    val network = new PipelineNetwork()
    newInceptionLayer(network, inputBands = inputBands, layerName = layerName, head = network.getHead,
      bands1x1 = bands1x1, bands1x3 = bands1x3, bands1x5 = bands1x5, bands3x1 = bands3x1,
      bands5x1 = bands5x1, bandsPooling = bandsPooling)
    network
  }
  def newInceptionLayer(network : PipelineNetwork, layerName : String, head: DAGNode, inputBands: Int, bands1x1: Int, bands3x1: Int, bands1x3: Int, bands5x1: Int, bands1x5: Int, bandsPooling: Int): DAGNode = {
    var conv1a: Double = 0.01
    var conv1b: Double = 0.01
    var conv3a: Double = 0.01
    var conv3b: Double = 0.01
    var conv5a: Double = 0.01
    var conv5b: Double = 0.01
    network.add(new ImgConcatLayer(),
      network.addAll(head,
        new ConvolutionLayer(1, 1, inputBands, bands1x1).setWeightsLog(conv1a).setName("conv_1x1_" + layerName),
        new ImgBandBiasLayer(bands1x1).setName("bias_1x1_" + layerName),
        new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_1x1_" + layerName)),
      network.addAll(head,
        new ConvolutionLayer(1, 1, inputBands, bands3x1).setWeightsLog(conv3a).setName("conv_3x1_" + layerName),
        new ImgBandBiasLayer(bands3x1).setName("bias_3x1_" + layerName),
        new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_3x1_" + layerName),
        new ConvolutionLayer(3, 3, bands3x1, bands1x3).setWeightsLog(conv3b).setName("conv_1x3_" + layerName),
        new ImgBandBiasLayer(bands1x3).setName("bias_1x3_" + layerName),
        new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_1x3_" + layerName)),
      network.addAll(head,
        new ConvolutionLayer(1, 1, inputBands, bands5x1).setWeightsLog(conv5a).setName("conv_5x1_" + layerName),
        new ImgBandBiasLayer(bands5x1).setName("bias_5x1_" + layerName),
        new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_5x1_" + layerName),
        new ConvolutionLayer(5, 5, bands5x1, bands1x5).setWeightsLog(conv5b).setName("conv_1x5_" + layerName),
        new ImgBandBiasLayer(bands1x5).setName("bias_1x5_" + layerName),
        new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_1x5_" + layerName)),
      network.addAll(head,
        new PoolingLayer().setWindowXY(3, 3).setStrideXY(1, 1).setPaddingXY(1, 1).setName("pool_" + layerName),
        new ConvolutionLayer(1, 1, inputBands, bandsPooling).setWeightsLog(conv1b).setName("conv_pool_" + layerName),
        new ImgBandBiasLayer(bandsPooling).setName("bias_pool_" + layerName),
        new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_pool_" + layerName)))
  }

  def step_AddLayer1(trainingMin: Int = 15, sampleSize: Int = 100): Any = phase(modelName, (model: NNLayer) ⇒
    {
      val sourceNetwork = model.asInstanceOf[PipelineNetwork]
      val priorFeaturesNode = Option(sourceNetwork.getByLabel("features")).getOrElse(sourceNetwork.getHead)
      addLayer(
        trainingArray = preprocessFeatures(sourceNetwork, priorFeaturesNode),
        sourceNetwork = sourceNetwork,
        priorFeaturesNode = priorFeaturesNode,
        additionalLayer = new PipelineNetwork(
          new ConvolutionLayer(7, 7, 3, 64).setWeightsLog(-4).setStrideXY(2, 2).setName("conv_1"),
          new ImgBandBiasLayer(64).setName("bias_1"),
          new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_1"),
          new PoolingLayer().setWindowXY(3, 3).setStrideXY(2, 2).setPaddingXY(1, 1).setName("pool_1")
        ), reconstructionLayer = new PipelineNetwork(
          new ConvolutionLayer(7, 7, 64, 48).setWeightsLog(-4),
          new ImgReshapeLayer(4, 4, true)
        ), trainingMin = trainingMin, sampleSize = sampleSize)
    }: Unit, modelName)

  def step_AddLayer2(trainingMin: Int = 15, sampleSize: Int = 100): Any = phase(modelName, (model: NNLayer) ⇒
    {
      val sourceNetwork = model.asInstanceOf[PipelineNetwork]
      val priorFeaturesNode = Option(sourceNetwork.getByLabel("features")).getOrElse(sourceNetwork.getHead)
      addLayer(
        trainingArray = preprocessFeatures(sourceNetwork, priorFeaturesNode),
        sourceNetwork = sourceNetwork,
        priorFeaturesNode = priorFeaturesNode,
        additionalLayer = new PipelineNetwork(
          new ConvolutionLayer(1, 1, 64, 64).setWeightsLog(-4).setName("conv_2"),
          new ImgBandBiasLayer(64).setName("bias_2"),
          new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_2"),
          new ConvolutionLayer(3, 3, 64, 192).setWeightsLog(-4).setName("conv_3"),
          new ImgBandBiasLayer(192).setName("bias_3"),
          new ActivationLayer(ActivationLayer.Mode.RELU).setName("relu_3"),
          new PoolingLayer().setWindowXY(3, 3).setStrideXY(2, 2).setPaddingXY(1, 1).setName("pool_3")
      ), reconstructionLayer = new PipelineNetwork(
          new ConvolutionLayer(3, 3, 192, 12).setWeightsLog(-4),
          new ImgReshapeLayer(2, 2, true)
        ), trainingMin = trainingMin, sampleSize = sampleSize)
    }: Unit, modelName)

  def step_AddLayer(trainingMin: Int = 15, sampleSize: Int = 100, inputBands: Int, featureBands: Int, radius: Int = 3, mode: PoolingMode = PoolingMode.Max)
                   (additionalLayer: NNLayer): Any = phase(modelName, (model: NNLayer) ⇒
  {
    val weight = -6
    val sourceNetwork = model.asInstanceOf[PipelineNetwork]
    val priorFeaturesNode = Option(sourceNetwork.getByLabel("features")).getOrElse(sourceNetwork.getHead)
    val trainingArray: Array[Array[Tensor]] = preprocessFeatures(sourceNetwork, priorFeaturesNode)

    val prevFeatureDimensions = trainingArray.head.head.getDimensions()
    val newFeatureDimensions = additionalLayer.eval(new NNExecutionContext() {}, trainingArray.head.head).getData.get(0).getDimensions
    val inputBands: Int = prevFeatureDimensions(2)
    val featureBands: Int = newFeatureDimensions(2)
    val reconstructionCrop = prevFeatureDimensions(0) - newFeatureDimensions(0)*2

    val reconstructionLayer = new PipelineNetwork(
      new ConvolutionLayer(1, 1, featureBands, 4 * inputBands, false).setWeights(() => (Random.nextDouble() - 0.5) * Math.pow(10, weight)),
      new ImgReshapeLayer(2, 2, true)
    )
    val cropLayer = new ImgCropLayer(reconstructionCrop, reconstructionCrop)
    addLayer(trainingArray, sourceNetwork, priorFeaturesNode, additionalLayer, reconstructionLayer, cropLayer, trainingMin, sampleSize)
  }: Unit, modelName)

  private def preprocessFeatures(sourceNetwork: PipelineNetwork, priorFeaturesNode: DAGNode): Array[Array[Tensor]] = {
    assert(null != data)
    val rawTrainingData: Array[Array[Tensor]] = takeData(5, 10).map(_.get()).toArray
    val featureTrainingData: Array[Tensor] = priorFeaturesNode.get(new NNExecutionContext() {}, sourceNetwork.buildExeCtx(
      NNResult.batchResultArray(rawTrainingData.map(_.take(2))): _*)).getData
      .stream().collect(Collectors.toList()).asScala.toArray
    (0 until featureTrainingData.length).map(i => Array(featureTrainingData(i), rawTrainingData(i)(1))).toArray
  }


  private def addLayer(trainingArray: Array[Array[Tensor]], sourceNetwork: PipelineNetwork, priorFeaturesNode: DAGNode, additionalLayer: NNLayer,
                       reconstructionLayer: PipelineNetwork, cropLayer: ImgCropLayer = null,
                       trainingMin: Int, sampleSize: Int,
                       featuresLabel:String = "features") =
  {

    val numberOfCategories:Int = trainingArray.head(1).dim()
    val prevFeatureDimensions = trainingArray.head.head.getDimensions()
    val newFeatureDimensions = additionalLayer.eval(new NNExecutionContext() {}, trainingArray.head.head).getData.get(0).getDimensions


    val stdDevTarget: Int = 1
    val rmsSmoothing: Int = 1
    val stdDevSmoothing: Double = 0.2
    val weight: Double = 0.001

    val trainingNetwork = new PipelineNetwork(2)
    val features = trainingNetwork.add(featuresLabel, additionalLayer, trainingNetwork.getInput(0))
    val fitness = trainingNetwork.add(new ProductInputsLayer(),
      // Features should be relevant - predict the class given a final linear/softmax transform
      trainingNetwork.add(new EntropyLossLayer(),
        trainingNetwork.add(new SoftmaxActivationLayer(),
          trainingNetwork.add(new AvgImageBandLayer(),
            trainingNetwork.add(new ConvolutionLayer(1, 1, newFeatureDimensions(2), numberOfCategories, true).setWeights(() => (Random.nextDouble() - 0.5) * Math.pow(10, -4)),
              features))
        ),
        trainingNetwork.getInput(1)
      ),
      // Features should be able to reconstruct input - Preserve information
      trainingNetwork.add(new LinearActivationLayer().setScale(1.0 / 255).setBias(rmsSmoothing).freeze(),
        trainingNetwork.add(new NthPowerActivationLayer().setPower(0.5).freeze(),
          trainingNetwork.add(new MeanSqLossLayer(),
            trainingNetwork.add(reconstructionLayer, features),
            trainingNetwork.add(cropLayer, trainingNetwork.getInput(0))
          )
        )
      ),
      // Features signal should target a uniform magnitude to balance the network
      trainingNetwork.add(new LinearActivationLayer().setBias(stdDevSmoothing).freeze(),
        trainingNetwork.add(new AbsActivationLayer(),
          trainingNetwork.add(new LinearActivationLayer().setBias(-stdDevTarget).freeze(),
            trainingNetwork.add(new AvgReducerLayer(),
              trainingNetwork.add(new StdDevMetaLayer(), features))
          )
        )
      )
    )

    out.h1("Training New Layer")
    val trainer1 = out.eval {
      var inner: Trainable = new StochasticArrayTrainable(trainingArray, trainingNetwork, sampleSize, 20)
      val trainer = new IterativeTrainer(inner)
      trainer.setMonitor(monitor)
      trainer.setTimeout(trainingMin, TimeUnit.MINUTES)
      trainer.setIterationsPerSample(50)
      trainer.setOrientation(new LBFGS)
      trainer.setLineSearchFactory(Java8Util.cvt((s: String) ⇒ (s match {
        case s if s.contains("LBFGS") ⇒ new StaticLearningRate().setRate(1.0)
        case _ ⇒ new QuadraticSearch
      })))
      trainer.setTerminateThreshold(0.0)
      trainer
    }
    trainer1.run()


    sourceNetwork.add(new EntropyLossLayer(),
      sourceNetwork.add(new SoftmaxActivationLayer(),
        sourceNetwork.add(new SchemaBiasLayer(),
          sourceNetwork.add(new AvgImageBandLayer(),
            sourceNetwork.add(new SchemaOutputLayer(newFeatureDimensions(2), -4).setSchema((1 to numberOfCategories).map(_.toString):_*), features)))
      ),
      sourceNetwork.getInput(1)
    )
  }

  def step_Train(trainingMin: Int = 15, numberOfCategories: Int = 2, sampleSize: Int = 250, iterationsPerSample: Int = 5) = {
    var selectedCategories = selectCategories(numberOfCategories)
    val categoryArray = selectedCategories.keys.toArray
    val categoryIndices = categoryArray.zipWithIndex.toMap
    selectedCategories = selectedCategories.map(e=>{
      e._1 -> e._2.map(f=>new WeakCachedSupplier[Array[Tensor]](()=>{
        f.get().take(1) ++ Array(toOutNDArray(categoryIndices.size, categoryIndices(e._1)))
      }))
    })
    phase(modelName, (model: NNLayer) ⇒ {
      out.h1("Integration Training")
      model.asInstanceOf[DAGNetwork].visit((layer:NNLayer)=>if(layer.isInstanceOf[SchemaComponent]) {
        System.out.println(String.format("Setting schema to %s for layer %s", categoryArray.mkString(";"), layer))
        layer.asInstanceOf[SchemaComponent].setSchema(categoryArray:_*)
      } : Unit)
      val trainer2 = out.eval {
        assert(null != data)
        var inner: Trainable = new StochasticArrayTrainable(selectedCategories.values.flatten.toList.asJava, model, sampleSize)
        val trainer = new IterativeTrainer(inner)
        trainer.setMonitor(monitor)
        trainer.setTimeout(trainingMin, TimeUnit.MINUTES)
        trainer.setIterationsPerSample(iterationsPerSample)
        trainer.setOrientation(new LBFGS() {
          override def reset(): Unit = {
            model.asInstanceOf[DAGNetwork].visit(Java8Util.cvt(layer => layer match {
              case layer: DropoutNoiseLayer => layer.shuffle()
              case _ =>
            }))
            super.reset()
          }
        }.setMinHistory(4).setMaxHistory(20))
        trainer.setLineSearchFactory(Java8Util.cvt((s: String) ⇒ (s match {
          case s if s.contains("LBFGS") ⇒ new StaticLearningRate().setRate(1.0)
          case _ ⇒ new ArmijoWolfeSearch().setAlpha(1e-5)
        })))
        trainer.setTerminateThreshold(0.0)
        trainer
      }
      trainer2.run()
    }: Unit, modelName)
    (for (i <- 1 to 3) yield Random.shuffle(selectedCategories.keys).take(2).toList).distinct.foreach {
      case Seq(from: String, to: String) => gan(out, model)(imageCount = 1, sourceCategory = from, targetCategory = to)
    }
  }

  def step_GAN(imageCount: Int = 10, sourceCategory: String = "fire-hydrant", targetCategory: String = "bear") = phase(modelName, (model: NNLayer) ⇒ {
    gan(out, model)(imageCount = imageCount, sourceCategory = sourceCategory, targetCategory = targetCategory)
  }: Unit, null)

  def gan(out: HtmlNotebookOutput with ScalaNotebookOutput, model: NNLayer)
         (imageCount: Int = 1, sourceCategory: String = "fire-hydrant", targetCategory: String = "bear") = {
    assert(null != model)
    val categoryArray = Array(sourceCategory, targetCategory)
    val categoryIndices = categoryArray.zipWithIndex.toMap
    val sourceClassId = categoryIndices(sourceCategory)
    val targetClassId = categoryIndices(targetCategory)
    out.h1(s"GAN Images Generation: $sourceCategory to $targetCategory")
    val sourceClass = toOutNDArray(categoryArray.length, sourceClassId)
    val targetClass = toOutNDArray(categoryArray.length, targetClassId)
    val adversarialOutput = new ArrayBuffer[Array[Tensor]]()
    val rows = data(sourceCategory)
      .filter(_!=null)
      .take(imageCount)
      .grouped(1).map(group => {
      val adversarialData: Array[Array[Tensor]] = group.map(_.get().take(1) ++ Array(targetClass)).toArray
      val biasLayer = new BiasLayer(data.values.flatten.head.get().head.getDimensions(): _*)
      val trainingNetwork = new PipelineNetwork()
      trainingNetwork.add(biasLayer)
      val pipelineNetwork = KryoUtil.kryo().copy(model).freeze().asInstanceOf[PipelineNetwork]
      pipelineNetwork.setHead(pipelineNetwork.getByLabel("classify")).removeLastInput()
      trainingNetwork.add(pipelineNetwork)
      CuDNN.devicePool.`with`(Java8Util.cvt((device: CuDNN) => {
        System.out.print(s"Starting to process ${adversarialData.length} images")
        val trainer1 = out.eval {
          var inner: Trainable = new ArrayTrainable(adversarialData,
            new SimpleLossNetwork(trainingNetwork, new EntropyLossLayer()))
          val trainer = new IterativeTrainer(inner)
          trainer.setMonitor(monitor)
          trainer.setTimeout(1, TimeUnit.MINUTES)
          trainer.setOrientation(new GradientDescent)
          trainer.setLineSearchFactory(Java8Util.cvt((s) ⇒ new ArmijoWolfeSearch().setMaxAlpha(1e8)))
          //trainer.setLineSearchFactory(Java8Util.cvt((s) ⇒ new QuadraticSearch))
          trainer.setTerminateThreshold(0.01)
          trainer
        }
        trainer1.run()
        System.out.print(s"Finished processing ${adversarialData.length} images")
      }))
      val evalNetwork = new PipelineNetwork()
      evalNetwork.add(biasLayer)
      val adversarialImage = evalNetwork.eval(new NNExecutionContext {}, adversarialData.head.head).getData.get(0)
      adversarialOutput += Array(adversarialImage, sourceClass)
      Map[String, AnyRef](
        "Original Image" → out.image(adversarialData.head.head.toRgbImage, ""),
        "Adversarial" → out.image(adversarialImage.toRgbImage, "")
      ).asJava
    }).toArray
    out.eval {
      TableOutput.create(rows: _*)
    }

  }

  def declareTestHandler() = {
    out.p("<a href='testCat.html'>Test Categorization</a><br/>")
    server.addSyncHandler("testCat.html", "text/html", cvt(o ⇒ {
      Option(new HtmlNotebookOutput(out.workingDir, o) with ScalaNotebookOutput).foreach(out ⇒ {
        testCategorization(out, getModelCheckpoint)
      })
    }), false)
    out.p("<a href='gan.html'>Generate Adversarial Images</a><br/>")
    server.addSyncHandler("gan.html", "text/html", cvt(o ⇒ {
      Option(new HtmlNotebookOutput(out.workingDir, o) with ScalaNotebookOutput).foreach(out ⇒ {
        gan(out, getModelCheckpoint)()
      })
    }), false)
  }

  def testCategorization(out: HtmlNotebookOutput with ScalaNotebookOutput, model: NNLayer) = {
    try {
      out.eval {
        TableOutput.create(takeData(5, 10).map(_.get()).map(testObj ⇒ Map[String, AnyRef](
          "Image" → out.image(testObj(0).toRgbImage(), ""),
          "Categorization" → categories.toList.sortBy(_._2).map(_._1)
            .zip(model.eval(new NNLayer.NNExecutionContext() {}, testObj(0)).getData.get(0).getData.map(_ * 100.0))
        ).asJava): _*)
      }
    } catch {
      case e: Throwable ⇒ e.printStackTrace()
    }
  }

  lazy val categories: Map[String, Int] = categoryList.zipWithIndex.toMap
  lazy val (categoryList, data) = {
    out.p("Loading data from " + source)
    val (categoryList, data) = {
      val categoryDirs = Random.shuffle(new File(source).listFiles().toStream)
        .filter(dir => categoryWhitelist.isEmpty || categoryWhitelist.find(str => dir.getName.contains(str)).isDefined)
        .take(numberOfCategories)
      val categoryList = categoryDirs.map((categoryDirectory: File) ⇒ {
        categoryDirectory.getName.split('.').last
      }).sorted.toArray
      val categoryMap: Map[String, Int] = categoryList.zipWithIndex.toMap
      (categoryList, categoryDirs
        .map((categoryDirectory: File) ⇒ {
          val categoryName = categoryDirectory.getName.split('.').last
          categoryName -> categoryDirectory.listFiles()
            .filterNot(_ == null)
            .filter(_.exists())
            .filter(_.length() > 0)
            .par.map(readImage(_))
            .flatMap(variants(_, artificialVariants))
            .map(resize(_, tileSize))
            .map(toTenors(_, toOutNDArray(categoryMap.size, categoryMap(categoryName))))
            .toList
        }).toMap)
    }
    val categories: Map[String, Int] = categoryList.zipWithIndex.toMap
    out.p("<ol>" + categories.toList.sortBy(_._2).map(x ⇒ "<li>" + x + "</li>").mkString("\n") + "</ol>")
    out.eval {
      TableOutput.create(Random.shuffle(data.toList).take(10).flatMap(x => Random.shuffle(x._2).take(10)).par.filter(_.get() != null).map(x ⇒ {
        val e = x.get()
        Map[String, AnyRef](
          "Image" → out.image(e(0).toRgbImage(), e(1).toString),
          "Classification" → e(1)
        ).asJava
      }).toArray: _*)
    }
    out.p("Loading data complete")
    (categoryList, data)
  }

  private def readImage(file: File): Supplier[BufferedImage] = {
    new WeakCachedSupplier[BufferedImage](Java8Util.cvt(() => {
      try {
        val image = ImageIO.read(file)
        if (null == image) {
          System.err.println(s"Error reading ${file.getAbsolutePath}: No image found")
        }
        image
      } catch {
        case e: Throwable =>
          System.err.println(s"Error reading ${file.getAbsolutePath}: $e")
          file.delete()
          null
      }
    }))
  }

  private def toTenors(originalRef:Supplier[BufferedImage], expectedOutput: Tensor): Supplier[Array[Tensor]] = {
    new SoftCachedSupplier[Array[Tensor]](Java8Util.cvt(() => {
      try {
        val resized = originalRef.get()
        if (null == resized) null
        else {
          Array(Tensor.fromRGB(resized), expectedOutput)
        }
      } catch {
        case e: Throwable =>
          e.printStackTrace(System.err)
          null
      }
    }
    ))
  }

  private def resize(originalRef:Supplier[BufferedImage], tileSize:Int): Supplier[BufferedImage] = {
    new SoftCachedSupplier[BufferedImage](Java8Util.cvt(() => {
      try {
        val original = originalRef.get()
        if (null == original) null
        else {
          val fromWidth = original.getWidth()
          val fromHeight = original.getHeight()
          val scale = tileSize.toDouble / Math.min(fromWidth, fromHeight)
          val toWidth = ((fromWidth * scale).toInt)
          val toHeight = ((fromHeight * scale).toInt)
          val resized = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB)
          val graphics = resized.getGraphics.asInstanceOf[Graphics2D]
          graphics.asInstanceOf[Graphics2D].setRenderingHints(new RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC))
          if (toWidth < toHeight) {
            graphics.drawImage(original, 0, (toWidth - toHeight) / 2, toWidth, toHeight, null)
          } else {
            graphics.drawImage(original, (toHeight - toWidth) / 2, 0, toWidth, toHeight, null)
          }
          resized
        }
      } catch {
        case e: Throwable =>
          e.printStackTrace(System.err)
          null
      }
    }
    ))
  }

  def variants(imageFn: Supplier[BufferedImage], items: Int): Stream[Supplier[BufferedImage]] = {
    Stream.continually({
      val sy = 1.05 + Random.nextDouble() * 0.05
      val sx = 1.05 + Random.nextDouble() * 0.05
      val theta = (Random.nextDouble() - 0.5) * 0.2
      new SoftCachedSupplier[BufferedImage](()=>{
        val image = imageFn.get()
        if(null == image) return null
        val resized = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = resized.getGraphics.asInstanceOf[Graphics2D]
        val transform = AffineTransform.getScaleInstance(sx,sy)
        transform.concatenate(AffineTransform.getRotateInstance(theta))
        graphics.asInstanceOf[Graphics2D].setRenderingHints(new RenderingHints(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC))
        graphics.drawImage(image, transform, null)
        resized
      })
    }).take(items)
  }

  def toOutNDArray(max: Int, out: Int*): Tensor = {
    val ndArray = new Tensor(max)
    for (i <- 0 until max) ndArray.set(i, fuzz)
    out.foreach(out=>ndArray.set(out, 1 - (fuzz * (max - 1))))
    ndArray
  }

  def takeData(numCategories: Int = 2, numImages: Int = 5000): List[_ <: Supplier[Array[Tensor]]] = {
    val selectedCategories = selectCategories(numCategories)
    takeNonNull(numImages, selectedCategories)
  }

  def takeNonNull[X <: Supplier[Array[Tensor]]](numImages: Int, selectedCategories: Map[String, List[X]])(implicit classTag: ClassTag[X]): List[X] = {
    monitor.log(s"Selecting $numImages images from categories ${selectedCategories.keySet}")
    Random.shuffle(selectedCategories.values.flatten.toList).par.take(2*numImages).filter(_.get() != null).take(numImages).toArray.toList
  }

  def selectCategories(numCategories: Int) = {
    Random.shuffle(data.toList).take(numCategories).toMap
  }

}
