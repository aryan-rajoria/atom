package io.appthreat.atom.slicing

import io.appthreat.dataflowengineoss.DefaultSemantics
import io.appthreat.dataflowengineoss.language.*
import io.appthreat.dataflowengineoss.queryengine.{EngineConfig, EngineContext}
import io.appthreat.dataflowengineoss.semanticsloader.Semantics
import io.shiftleft.codepropertygraph.Cpg
import io.shiftleft.codepropertygraph.generated.Languages
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object ReachableSlicing {

  implicit val semantics: Semantics                = DefaultSemantics()
  val engineConfig                                 = EngineConfig()
  implicit val context: EngineContext              = EngineContext(semantics, engineConfig)
  private implicit val finder: NodeExtensionFinder = DefaultNodeExtensionFinder
  private def API_TAG                              = "api"
  private def FRAMEWORK_TAG                        = "framework"

  def calculateReachableSlice(atom: Cpg, config: ReachablesConfig): ReachableSlice = {
    val language  = atom.metaData.language.head
    def source    = atom.tag.name(config.sourceTag).parameter
    def sink      = atom.ret.where(_.tag.name(config.sinkTag))
    var flowsList = sink.reachableByFlows(source).map(toSlice).toList
    flowsList ++=
      atom.tag
        .name(FRAMEWORK_TAG)
        .method
        .parameter
        .reachableByFlows(atom.tag.name(config.sourceTag).parameter)
        .map(toSlice)
        .toList
    flowsList ++=
      atom.tag.name(API_TAG).parameter.reachableByFlows(atom.tag.name(API_TAG).parameter).map(toSlice).toList
    // For JavaScript, we need flows between arguments of call nodes to track callbacks and middlewares
    if (language == Languages.JSSRC || language == Languages.JAVASCRIPT) {
      def jsCallSource          = atom.tag.name(config.sourceTag).call.argument.isIdentifier
      def jsFrameworkIdentifier = atom.tag.name(FRAMEWORK_TAG).identifier
      def jsFrameworkParameter  = atom.tag.name(FRAMEWORK_TAG).parameter
      def jsSink                = atom.tag.name(config.sinkTag).call.argument.isIdentifier
      flowsList ++= jsSink
        .reachableByFlows(jsCallSource, jsFrameworkIdentifier, jsFrameworkParameter)
        .map(toSlice)
        .toList
      flowsList ++= atom.tag
        .name(FRAMEWORK_TAG)
        .call
        .argument
        .reachableByFlows(jsFrameworkParameter)
        .map(toSlice)
        .toList
    }
    ReachableSlice(flowsList)
  }

  private def tagAsString(tag: Iterator[Tag]): String = if (tag.nonEmpty) tag.name.mkString(", ") else ""
  private def purlsFromTag(tag: Iterator[Tag]) =
    if (tag.nonEmpty) tag.name.filter(_.startsWith("pkg:")).toSet else Set.empty

  private def toSlice(path: Path) = {
    val tableRows  = ArrayBuffer[SliceNode]()
    val addedPaths = mutable.Set[String]()
    val purls      = mutable.Set[String]()
    path.elements.foreach { astNode =>
      val lineNumber   = astNode.lineNumber.getOrElse("").toString
      val fileName     = astNode.file.name.headOption.getOrElse("").replace("<unknown>", "")
      var fileLocation = s"${fileName}#${lineNumber}"
      var tags: String = tagAsString(astNode.tag)
      purls ++= purlsFromTag(astNode.tag)
      if (fileLocation == "#") fileLocation = "N/A"
      var sliceNode = SliceNode(
        astNode.id(),
        astNode.label,
        code = astNode.code,
        parentFileName = astNode.file.name.headOption.getOrElse(""),
        lineNumber = astNode.lineNumber,
        columnNumber = astNode.columnNumber,
        tags = tags
      )
      astNode match {
        case _: MethodReturn =>
        case _: Block        =>
        case methodParameterIn: MethodParameterIn =>
          val methodName = methodParameterIn.method.name
          if (tags.isEmpty && methodParameterIn.method.tag.nonEmpty) {
            tags = tagAsString(methodParameterIn.method.tag)
            purls ++= purlsFromTag(methodParameterIn.method.tag)
          }
          if (tags.isEmpty && methodParameterIn.tag.nonEmpty) {
            tags = tagAsString(methodParameterIn.tag)
            purls ++= purlsFromTag(methodParameterIn.tag)
          }
          sliceNode = sliceNode.copy(
            name = methodParameterIn.name,
            code = methodParameterIn.code,
            typeFullName = methodParameterIn.typeFullName,
            parentMethodName = methodName,
            parentMethodSignature = methodParameterIn.method.signature,
            parentPackageName = methodParameterIn.method.location.packageName,
            parentClassName = methodParameterIn.method.location.className,
            isExternal = methodParameterIn.method.isExternal,
            lineNumber = methodParameterIn.lineNumber,
            columnNumber = methodParameterIn.columnNumber,
            tags = tags
          )
          tableRows += sliceNode
        case ret: Return =>
          val methodName = ret.method.name
          sliceNode = sliceNode.copy(
            name = ret.argumentName.getOrElse(""),
            code = ret.code,
            parentMethodName = methodName,
            parentMethodSignature = ret.method.signature,
            parentPackageName = ret.method.location.packageName,
            parentClassName = ret.method.location.className,
            lineNumber = ret.lineNumber,
            columnNumber = ret.columnNumber
          )
          tableRows += sliceNode
        case identifier: Identifier =>
          val methodName = identifier.method.name
          if (tags.isEmpty && identifier.inCall.nonEmpty && identifier.inCall.head.tag.nonEmpty) {
            tags = tagAsString(identifier.inCall.head.tag)
            purls ++= purlsFromTag(identifier.inCall.head.tag)
          }
          if (!addedPaths.contains(s"${fileName}#${lineNumber}") && identifier.inCall.nonEmpty) {
            sliceNode = sliceNode.copy(
              name = identifier.name,
              code =
                if (identifier.inCall.nonEmpty) identifier.inCall.head.code
                else identifier.code,
              parentMethodName = methodName,
              parentMethodSignature = identifier.method.signature,
              parentPackageName = identifier.method.location.packageName,
              parentClassName = identifier.method.location.className,
              lineNumber = identifier.lineNumber,
              columnNumber = identifier.columnNumber,
              tags = tags
            )
            tableRows += sliceNode
          }
        case member: Member =>
          val methodName = "<not-in-method>"
          sliceNode = sliceNode.copy(name = member.name, code = member.code, parentMethodName = methodName)
          tableRows += sliceNode
        case call: Call =>
          if (!call.code.startsWith("<operator") || !call.methodFullName.startsWith("<operator")) {
            if (
              tags.isEmpty && call.callee(NoResolve).nonEmpty && call
                .callee(NoResolve)
                .head
                .isExternal && !call.methodFullName.startsWith("<operator") && !call.name
                .startsWith("<operator") && !call.methodFullName.startsWith("new ")
            ) {
              tags = tagAsString(call.callee(NoResolve).head.tag)
              purls ++= purlsFromTag(call.callee(NoResolve).head.tag)
            }
            var isExternal =
              if (
                call.callee(NoResolve).nonEmpty && call.callee(NoResolve).head.isExternal && !call.name
                  .startsWith("<operator") && !call.methodFullName.startsWith("new ")
              ) true
              else false
            if (call.methodFullName.startsWith("<operator")) isExternal = false
            sliceNode = sliceNode.copy(
              name = call.name,
              fullName = if (call.callee(NoResolve).nonEmpty) call.callee(NoResolve).head.fullName else "",
              code = call.code,
              isExternal = isExternal,
              parentMethodName = call.method.name,
              parentMethodSignature = call.method.signature,
              parentPackageName = call.method.location.packageName,
              parentClassName = call.method.location.className,
              lineNumber = call.lineNumber,
              columnNumber = call.columnNumber,
              tags = tags
            )
            tableRows += sliceNode
          }
        case cfgNode: CfgNode =>
          val method = cfgNode.method
          if (tags.isEmpty && method.tag.nonEmpty) {
            tags = tagAsString(method.tag)
            purls ++= purlsFromTag(method.tag)
          }
          val methodName = method.name
          val statement = cfgNode match {
            case _: MethodParameterIn =>
              if (tags.isEmpty && method.parameter.tag.nonEmpty) {
                tags = tagAsString(method.parameter.tag)
                purls ++= purlsFromTag(method.parameter.tag)
              }
              val paramsPretty = method.parameter.toList.sortBy(_.index).map(_.code).mkString(", ")
              s"$methodName($paramsPretty)"
            case _ =>
              if (tags.isEmpty && cfgNode.statement.tag.nonEmpty) {
                tags = tagAsString(cfgNode.statement.tag)
                purls ++= purlsFromTag(cfgNode.statement.tag)
              }
              cfgNode.statement.repr
          }
          sliceNode = sliceNode.copy(parentMethodName = methodName, code = statement, tags = tags)
          tableRows += sliceNode
      }
      addedPaths += s"${fileName}#${lineNumber}"
    }
    ReachableFlows(flows = tableRows.toList, purls = purls.toSet)
  }
}