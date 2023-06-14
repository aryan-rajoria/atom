package io.appthreat.atom.dataflows

import io.appthreat.atom.passes.DataDepsPass
import io.joern.dataflowengineoss.DefaultSemantics
import io.joern.dataflowengineoss.semanticsloader.{FlowSemantic, Semantics}
import io.shiftleft.semanticcpg.layers.{LayerCreator, LayerCreatorContext, LayerCreatorOptions}

object OssDataFlow {
  val overlayName: String = "dataflowOss"
  val description: String = "Layer to support the atom data flow tracker"

  def defaultOpts = new OssDataFlowOptions()
}

class OssDataFlowOptions(
  var maxNumberOfDefinitions: Int = 2000,
  var extraFlows: List[FlowSemantic] = List.empty[FlowSemantic]
) extends LayerCreatorOptions {}

class OssDataFlow(opts: OssDataFlowOptions)(implicit
  s: Semantics = Semantics.fromList(DefaultSemantics().elements ++ opts.extraFlows)
) extends LayerCreator {

  override val overlayName: String = OssDataFlow.overlayName
  override val description: String = OssDataFlow.description

  override def create(context: LayerCreatorContext, storeUndoInfo: Boolean): Unit = {
    val cpg                 = context.cpg
    val enhancementExecList = Iterator(new DataDepsPass(cpg, opts.maxNumberOfDefinitions))
    enhancementExecList.zipWithIndex.foreach { case (pass, index) =>
      runPass(pass, context, storeUndoInfo, index)
    }
  }
}
