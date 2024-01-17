package io.appthreat.atom

import better.files.File
import io.appthreat.atom.dataflows.{DataFlowGraph, OssDataFlow, OssDataFlowOptions}
import io.appthreat.atom.frontends.clike.C2Atom
import io.appthreat.atom.parsedeps.parseDependencies
import io.appthreat.atom.passes.TypeHintPass
import io.appthreat.atom.slicing.*
import io.appthreat.c2cpg.{C2Cpg, Config as CConfig}
import io.appthreat.javasrc2cpg.{JavaSrc2Cpg, Config as JavaConfig}
import io.appthreat.jimple2cpg.{Jimple2Cpg, Config as JimpleConfig}
import io.appthreat.jssrc2cpg.passes.{
    ConstClosurePass,
    ImportResolverPass,
    JavaScriptInheritanceNamePass,
    JavaScriptTypeRecoveryPass
}
import io.appthreat.jssrc2cpg.{JsSrc2Cpg, Config as JSConfig}
import io.appthreat.php2atom.passes.PhpSetKnownTypesPass
import io.appthreat.php2atom.{Php2Atom, Config as PhpConfig}
import io.appthreat.pysrc2cpg.{
    DynamicTypeHintFullNamePass,
    Py2CpgOnFileSystem,
    PythonInheritanceNamePass,
    PythonTypeHintCallLinker,
    PythonTypeRecoveryPass,
    ImportResolverPass as PyImportResolverPass,
    ImportsPass as PythonImportsPass,
    Py2CpgOnFileSystemConfig as PyConfig
}
import io.appthreat.x2cpg.passes.base.AstLinkerPass
import io.appthreat.x2cpg.passes.frontend.XTypeRecoveryConfig
import io.appthreat.x2cpg.passes.taggers.{CdxPass, ChennaiTagsPass, EasyTagsPass}
import io.shiftleft.codepropertygraph.cpgloading.CpgLoaderConfig
import io.shiftleft.codepropertygraph.generated.{Cpg, Languages}
import io.shiftleft.semanticcpg.language.*
import io.shiftleft.semanticcpg.layers.LayerCreatorContext
import scopt.OptionParser

import java.nio.charset.Charset
import java.util.Locale
import scala.language.postfixOps
import scala.util.{Failure, Properties, Success, Try}

object Atom:

    val DEFAULT_ATOM_OUT_FILE: String =
        if Properties.isWin || Charset.defaultCharset() != Charset.forName("UTF-8") then "app.atom"
        else "app.⚛"
    val DEFAULT_SLICE_OUT_FILE       = "slices.json"
    val DEFAULT_SLICE_DEPTH          = 7
    val DEFAULT_MAX_DEFS: Int        = 2000
    val FRAMEWORK_INPUT_TAG: String  = "framework-input"
    val FRAMEWORK_OUTPUT_TAG: String = "framework-output"
    val DEFAULT_EXPORT_DIR: String   = "atom-exports"
    // Possible values: graphml, dot
    val DEFAULT_EXPORT_FORMAT: String = "graphml"
    // Possible values: no-delombok, default, types-only, run-delombok
    private val DEFAULT_DELOMBOK_MODE: String =
        sys.env.getOrElse("CHEN_DELOMBOK_MODE", "types-only")
    private val TYPE_PROPAGATION_ITERATIONS = 1
    private val MAVEN_JAR_PATH: File        = File.home / ".m2" / "repository"
    private val GRADLE_JAR_PATH: File = File.home / ".gradle" / "caches" / "modules-2" / "files-2.1"
    private val SBT_JAR_PATH: File    = File.home / ".ivy2" / "cache"
    private val JAR_INFERENCE_PATHS: Set[String] =
        Set(MAVEN_JAR_PATH.pathAsString, GRADLE_JAR_PATH.pathAsString, SBT_JAR_PATH.pathAsString)
    private val ANDROID_JAR_PATH: Option[String] =
        Option(System.getenv("ANDROID_HOME")).flatMap { androidHome =>
            if File(androidHome).isDirectory then
                File(androidHome).glob("**/android.jar").map(_.pathAsString).toSeq.headOption
            else None
        }

    private val COMMON_IGNORE_REGEX = ".*(test|docs|example|samples|mocks|Documentation|demos).*"

    private val CHEN_INCLUDE_PATH = sys.env.getOrElse("CHEN_INCLUDE_PATH", "")
    // Custom include paths for c/c++
    private val C2ATOM_INCLUDE_PATH =
        if CHEN_INCLUDE_PATH.nonEmpty && File(
              CHEN_INCLUDE_PATH
            ).isDirectory
        then CHEN_INCLUDE_PATH.split(java.io.File.pathSeparator).toSet
        else
            Set.empty

    private val optionParser: OptionParser[BaseConfig] = new scopt.OptionParser[BaseConfig]("atom"):
        arg[String]("input")
            .optional()
            .text("source file or directory")
            .action((x, c) => c.withInputPath(File(x)))
            .validate { x =>
                if x == "" then failure("Input path required")
                else if !File(x).exists then
                    failure(s"Input path does not exist at `\"$x\"`, exiting.")
                else success
            }
        opt[String]('o', "output")
            .text("output filename. Default app.⚛ or app.atom in windows")
            .action((x, c) =>
                c match
                    case config: AtomConfig => config.withOutputAtomFile(File(x))
                    case _                  => c
            )
        opt[String]('s', "slice-outfile")
            .text("export intra-procedural slices as json")
            .action((x, c) => c.withOutputSliceFile(File(x)))
        opt[String]('l', "language")
            .text("source language")
            .required()
            .action((x, c) =>
                c match
                    case config: AtomConfig => config.withLanguage(x)
                    case _                  => c
            )
            .validate(x =>
                if x.isBlank then failure(s"Please specify a language using the --language option.")
                else success
            )
        opt[Unit]("with-data-deps")
            .text("generate the atom with data-dependencies - defaults to `false`")
            .action((_, c) =>
                c match
                    case config: AtomConfig => config.withDataDependencies(true)
                    case _                  => c
            )
        opt[Unit]("remove-atom")
            .text("do not persist the atom file - defaults to `false`")
            .action((_, c) =>
                c match
                    case config: AtomConfig => config.withRemoveAtom(true)
                    case _                  => c
            )
        opt[Unit]('x', "export-atom")
            .text("export the atom file with data-dependencies to graphml - defaults to `false`")
            .action((_, c) =>
                c match
                    case config: AtomConfig =>
                        config.withExportAtom(true)
                    case _ => c
            )
        opt[String]("export-dir")
            .text(s"export directory. Default: $DEFAULT_EXPORT_DIR")
            .action((x, c) =>
                c match
                    case config: AtomConfig => config.withExportDir(x)
                    case _                  => c
            )
        opt[String]("export-format")
            .text(s"export format graphml or dot. Default: $DEFAULT_EXPORT_FORMAT")
            .action((x, c) =>
                c match
                    case config: AtomConfig => config.withExportFormat(x)
                    case _                  => c
            )
        opt[String]("file-filter")
            .text(s"the name of the source file to generate slices from. Uses regex.")
            .action((x, c) => c.withFileFilter(Option(x)))
        opt[String]("method-name-filter")
            .text(s"filters in slices that go through specific methods by names. Uses regex.")
            .action((x, c) => c.withMethodNameFilter(Option(x)))
        opt[String]("method-parameter-filter")
            .text(
              s"filters in slices that go through methods with specific types on the method parameters. Uses regex."
            )
            .action((x, c) => c.withMethodParamTypeFilter(Option(x)))
        opt[String]("method-annotation-filter")
            .text(
              s"filters in slices that go through methods with specific annotations on the methods. Uses regex."
            )
            .action((x, c) => c.withMethodAnnotationFilter(Option(x)))
        opt[Int]("max-num-def")
            .text(
              s"maximum number of definitions in per-method data flow calculation - defaults to $DEFAULT_MAX_DEFS"
            )
            .action((x, c) =>
                c match
                    case config: AtomConfig => config.withMaxNumDef(x)
                    case _                  => c
            )
            .validate(x =>
                if x <= 0 then failure("`max-num-def` must be an integer larger than 0")
                else success
            )
        cmd("parsedeps")
            .text("Extract dependencies from the build file and imports")
            .action((_, c) => AtomParseDepsConfig().withRemoveAtom(true))
        cmd("data-flow")
            .text("Extract backward data-flow slices")
            .action((_, _) => AtomDataFlowConfig().withDataDependencies(true))
            .children(
              opt[Int]("slice-depth")
                  .text(
                    s"the max depth to traverse the DDG for the data-flow slice - defaults to $DEFAULT_SLICE_DEPTH."
                  )
                  .action((x, c) =>
                      c match
                          case c: AtomDataFlowConfig => c.copy(sliceDepth = x)
                          case _                     => c
                  ),
              opt[String]("sink-filter")
                  .text(s"filters on the sink's `code` property. Uses regex.")
                  .action((x, c) =>
                      c match
                          case c: AtomDataFlowConfig => c.copy(sinkPatternFilter = Option(x))
                          case _                     => c
                  )
            )
        cmd("usages")
            .text("Extract local variable and parameter usages")
            .action((_, c) => AtomUsagesConfig().withRemoveAtom(true))
            .children(
              opt[Int]("min-num-calls")
                  .text(s"the minimum number of calls required for a usage slice - defaults to 1.")
                  .action((x, c) =>
                      c match
                          case c: AtomUsagesConfig => c.copy(minNumCalls = x)
                          case _                   => c
                  ),
              opt[Unit]("include-source")
                  .text(s"includes method source code in the slices - defaults to false.")
                  .action((_, c) =>
                      c match
                          case c: AtomUsagesConfig => c.copy(includeMethodSource = true)
                          case _                   => c
                  )
            )
        cmd("reachables")
            .text("Extract reachable data-flow slices based on automated framework tags")
            .action((_, c) => AtomReachablesConfig().withDataDependencies(true))
            .children(
              opt[String]("source-tag")
                  .text(s"source tag - defaults to framework-input.")
                  .action((x, c) =>
                      c match
                          case c: AtomReachablesConfig => c.copy(sourceTag = x)
                          case _                       => c
                  ),
              opt[String]("sink-tag")
                  .text(s"sink tag - defaults to framework-output.")
                  .action((x, c) =>
                      c match
                          case c: AtomReachablesConfig => c.copy(sinkTag = x)
                          case _                       => c
                  ),
              opt[Int]("slice-depth")
                  .text(
                    s"the max depth to traverse the DDG during reverse reachability - defaults to $DEFAULT_SLICE_DEPTH."
                  )
                  .action((x, c) =>
                      c match
                          case c: AtomReachablesConfig => c.copy(sliceDepth = x)
                          case _                       => c
                  )
            )
        help("help").text("display this help message")

    def main(args: Array[String]): Unit =
        run(args) match
            case Right(msg) =>
            case Left(errMsg) =>
                if errMsg == null then
                    println("Unexpected error")
                else if errMsg.nonEmpty && errMsg.contains(
                      "storage metadata does not contain version number"
                    )
                then
                    println(
                      "Existing app.atom appears to be corrupted. Please remove and re-run this command."
                    )
                else
                    println(s"Failure: $errMsg")
                System.exit(1)

    private def run(args: Array[String]): Either[String, String] =
        val parserArgs = args.toList
        parseConfig(parserArgs) match
            case Right(config: AtomConfig) => run(config, config.language)
            case Right(_)                  => Left("Invalid configuration generated")
            case Left(err)                 => Left(err)

    private def run(config: AtomConfig, language: String): Either[String, String] =
        for
            _ <- generateAtom(config, language)
        yield newAtomCreatedString(config)

    private def newAtomCreatedString(config: AtomConfig): String =
        val absolutePath = config.outputAtomFile.path.toAbsolutePath
        if config.removeAtom then
            config.outputAtomFile.delete(true)
            ""
        else
            s"Atom created successfully at $absolutePath\n"

    private def generateSlice(config: AtomConfig, ag: Cpg): Either[String, String] =
        def sliceCpg(cpg: Cpg): Option[ProgramSlice] =
            config match
                case x: AtomDataFlowConfig =>
                    println("Slicing the atom for data-flow. This might take a while ...")
                    val dataFlowConfig = migrateAtomConfigToSliceConfig(x)
                    new DataFlowSlicing().calculateDataFlowSlice(
                      cpg,
                      dataFlowConfig.asInstanceOf[DataFlowConfig]
                    )
                case x: AtomUsagesConfig =>
                    println("Slicing the atom for usages. This might take a few minutes ...")
                    new ChennaiTagsPass(cpg).createAndApply()
                    val usagesConfig = migrateAtomConfigToSliceConfig(x)
                    Option(UsageSlicing.calculateUsageSlice(
                      cpg,
                      usagesConfig.asInstanceOf[UsagesConfig]
                    ))
                case x: AtomReachablesConfig =>
                    println("Slicing the atom for reachables. This might take a few minutes ...")
                    val reachablesConfig = migrateAtomConfigToSliceConfig(x)
                    Some(ReachableSlicing.calculateReachableSlice(
                      cpg,
                      reachablesConfig.asInstanceOf[ReachablesConfig]
                    ))
                case _ =>
                    None

        try
            migrateAtomConfigToSliceConfig(config) match
                case x: AtomConfig if config.exportAtom =>
                    println(s"Exporting the atom to the directory ${x.exportDir}")
                    config.exportFormat match
                        case "graphml" =>
                            ag.method.internal.filterNot(_.name.startsWith("<")).filterNot(
                              _.name.startsWith("lambda")
                            ).gml(x.exportDir)
                        case _ =>
                            // Export all representations
                            ag.method.internal.filterNot(_.name.startsWith("<")).filterNot(
                              _.name.startsWith("lambda")
                            ).dot(x.exportDir)
                            // Export individual representations
                            ag.method.internal.filterNot(_.name.startsWith("<")).filterNot(
                              _.name.startsWith("lambda")
                            ).exportAllRepr(x.exportDir)
                case _: DataFlowConfig =>
                    val dataFlowSlice = sliceCpg(ag).collect { case x: DataFlowSlice => x }
                    val atomDataFlowSliceJson =
                        dataFlowSlice.map(x =>
                            AtomDataFlowSlice(x, DataFlowGraph.buildFromSlice(x).paths).toJson
                        )
                    saveSlice(config.outputSliceFile, atomDataFlowSliceJson)
                case _: UsagesConfig =>
                    saveSlice(config.outputSliceFile, sliceCpg(ag).map(_.toJson))
                case _: ReachablesConfig =>
                    saveSlice(config.outputSliceFile, sliceCpg(ag).map(_.toJsonPretty))
                case x: AtomParseDepsConfig =>
                    parseDependencies(ag).map(_.toJson) match
                        case Left(err)    => return Left(err)
                        case Right(slice) => saveSlice(x.outputSliceFile, Option(slice))
                case _ =>
            end match
            Right("Atom sliced successfully")
        catch
            case err: Throwable if err.getMessage == null =>
                Left(err.getStackTrace.take(7).mkString("\n"))
            case err: Throwable => Left(err.getMessage)
        end try
    end generateSlice

    private def saveSlice(outFile: File, programSlice: Option[String]): Unit =
        programSlice.foreach { slice =>
            val finalOutputPath =
                File(outFile.pathAsString)
                    .createFileIfNotExists()
                    .write(slice)
                    .pathAsString
            println(s"Slices have been successfully written to $finalOutputPath")
        }

    private def migrateAtomConfigToSliceConfig(x: BaseConfig): BaseConfig =
        (x match
            case config: AtomDataFlowConfig =>
                DataFlowConfig(
                  config.sinkPatternFilter,
                  config.excludeOperatorCalls,
                  config.mustEndAtExternalMethod,
                  config.sliceDepth
                )
            case config: AtomUsagesConfig =>
                UsagesConfig(
                  config.minNumCalls,
                  config.excludeOperatorCalls,
                  !config.includeMethodSource
                )
            case config: AtomReachablesConfig =>
                ReachablesConfig(config.sourceTag, config.sinkTag, config.sliceDepth)
            case _ => x
        ).withInputPath(x.inputPath)
            .withOutputSliceFile(x.outputSliceFile)
            .withFileFilter(x.fileFilter)
            .withMethodNameFilter(x.methodNameFilter)
            .withMethodParamTypeFilter(x.methodParamTypeFilter)
            .withMethodAnnotationFilter(x.methodAnnotationFilter)

    private def loadFromOdb(filename: String): Try[Cpg] =
        val odbConfig = overflowdb.Config.withDefaults().withStorageLocation(filename)
        val config    = CpgLoaderConfig().withOverflowConfig(odbConfig).doNotCreateIndexesOnLoad
        try
            Success(io.shiftleft.codepropertygraph.cpgloading.CpgLoader.loadFromOverflowDb(config))
        catch
            case err: Throwable =>
                Failure(err)

    private def generateAtom(config: AtomConfig, language: String): Either[String, String] =
        generateForLanguage(language.toUpperCase(Locale.ROOT), config)

    private def generateForLanguage(language: String, config: AtomConfig): Either[String, String] =
        val outputAtomFile = config.outputAtomFile.pathAsString
        // Create a new atom
        def createAtom =
            (
              language match
                  case "H" | "HPP" =>
                      new C2Atom()
                          .createCpg(
                            CConfig(
                              includeComments = false,
                              logProblems = false,
                              includePathsAutoDiscovery = false
                            )
                                .withLogPreprocessor(false)
                                .withInputPath(config.inputPath.pathAsString)
                                .withOutputPath(outputAtomFile)
                                .withFunctionBodies(false)
                                .withIgnoredFilesRegex(
                                  COMMON_IGNORE_REGEX
                                )
                          )
                  case Languages.C | Languages.NEWC | "CPP" | "C++" =>
                      new C2Cpg()
                          .createCpgWithOverlays(
                            CConfig(
                              includeComments = false,
                              logProblems = false,
                              includePathsAutoDiscovery = true
                            )
                                .withLogPreprocessor(false)
                                .withInputPath(config.inputPath.pathAsString)
                                .withOutputPath(outputAtomFile)
                                .withFunctionBodies(true)
                                .withIgnoredFilesRegex(
                                  COMMON_IGNORE_REGEX
                                )
                                .withIncludePaths(C2ATOM_INCLUDE_PATH)
                          )
                  case "JAR" | "JIMPLE" | "ANDROID" | "APK" | "DEX" =>
                      new Jimple2Cpg()
                          .createCpgWithOverlays(
                            JimpleConfig(android = ANDROID_JAR_PATH)
                                .withInputPath(config.inputPath.pathAsString)
                                .withOutputPath(outputAtomFile)
                                .withFullResolver(true)
                          )
                  case Languages.JAVA | Languages.JAVASRC =>
                      new JavaSrc2Cpg()
                          .createCpgWithOverlays(
                            JavaConfig(
                              fetchDependencies = true,
                              inferenceJarPaths = JAR_INFERENCE_PATHS,
                              enableTypeRecovery = true,
                              delombokMode = Some(DEFAULT_DELOMBOK_MODE)
                            )
                                .withInputPath(config.inputPath.pathAsString)
                                .withDefaultIgnoredFilesRegex(
                                  List(
                                    "\\..*".r,
                                    ".*build/(generated|intermediates|outputs|tmp).*" r,
                                    ".*src/test.*" r
                                  )
                                )
                                .withOutputPath(outputAtomFile)
                          )
                  case Languages.JSSRC | Languages.JAVASCRIPT | "JS" | "TS" | "TYPESCRIPT" =>
                      new JsSrc2Cpg()
                          .createCpgWithOverlays(
                            JSConfig()
                                .withDisableDummyTypes(true)
                                .withTypePropagationIterations(TYPE_PROPAGATION_ITERATIONS)
                                .withInputPath(config.inputPath.pathAsString)
                                .withOutputPath(outputAtomFile)
                          )
                          .map { ag =>
                              new JavaScriptInheritanceNamePass(ag).createAndApply()
                              new ConstClosurePass(ag).createAndApply()
                              new ImportResolverPass(ag).createAndApply()
                              new JavaScriptTypeRecoveryPass(ag).createAndApply()
                              new TypeHintPass(ag).createAndApply()
                              ag
                          }
                  case Languages.PYTHONSRC | Languages.PYTHON | "PY" =>
                      new Py2CpgOnFileSystem()
                          .createCpgWithOverlays(
                            PyConfig()
                                .withDisableDummyTypes(true)
                                .withTypePropagationIterations(TYPE_PROPAGATION_ITERATIONS)
                                .withInputPath(config.inputPath.pathAsString)
                                .withOutputPath(outputAtomFile)
                                .withDefaultIgnoredFilesRegex(List("\\..*".r))
                                .withIgnoredFilesRegex(
                                  ".*(samples|examples|test|tests|unittests|docs|virtualenvs|venv|benchmarks|tutorials|noxfile).*"
                                )
                          )
                          .map { ag =>
                              new PythonImportsPass(ag).createAndApply()
                              new PyImportResolverPass(ag).createAndApply()
                              new DynamicTypeHintFullNamePass(ag).createAndApply()
                              new PythonInheritanceNamePass(ag).createAndApply()
                              new PythonTypeRecoveryPass(
                                ag,
                                XTypeRecoveryConfig(enabledDummyTypes = false)
                              )
                                  .createAndApply()
                              new PythonTypeHintCallLinker(ag).createAndApply()
                              new AstLinkerPass(ag).createAndApply()
                              ag
                          }
                  case Languages.PHP =>
                      new Php2Atom().createCpgWithOverlays(
                        PhpConfig()
                            .withDisableDummyTypes(true)
                            .withInputPath(config.inputPath.pathAsString)
                            .withOutputPath(outputAtomFile)
                            .withDefaultIgnoredFilesRegex(List("\\..*".r))
                            .withIgnoredFilesRegex(".*(samples|examples|docs|tests).*")
                      ).map { ag =>
                          new PhpSetKnownTypesPass(ag).createAndApply()
                          ag
                      }
                  case _ => Failure(
                        new RuntimeException(
                          s"No language frontend supported for language '$language'"
                        )
                      )
            )
        // Should we reuse or create the atom
        def getOrCreateAtom =
            config match
                case x: AtomConfig
                    if (x.isInstanceOf[
                      AtomUsagesConfig
                    ] || config.exportAtom) && config.outputAtomFile.exists() =>
                    config.withRemoveAtom(false)
                    try
                        loadFromOdb(outputAtomFile)
                    catch
                        case _: Throwable =>
                            println("Removing the existing atom file since it is corrupted.")
                            config.outputAtomFile.delete(true)
                            createAtom
                case _ =>
                    config.outputAtomFile.delete(true)
                    createAtom

        getOrCreateAtom match
            case Failure(exception) =>
                Left(exception.getMessage)
            case Success(ag) =>
                config match
                    case x: AtomConfig if x.dataDeps || x.isInstanceOf[AtomDataFlowConfig] =>
                        println("Generating data-flow dependencies from atom. Please wait ...")
                        // Enhance with simple and easy tags
                        new EasyTagsPass(ag).createAndApply()
                        // Enhance with the BOM from cdxgen
                        new CdxPass(ag).createAndApply()
                        new ChennaiTagsPass(ag).createAndApply()
                        new OssDataFlow(new OssDataFlowOptions(maxNumberOfDefinitions =
                            x.maxNumDef
                        ))
                            .run(new LayerCreatorContext(ag))
                    case _ =>
                generateSlice(config, ag)
                try
                    ag.close()
                catch
                    case err: Throwable if err.getMessage == null =>
                        Left(err.getStackTrace.take(7).mkString("\n"))
                    case err: Throwable => Left(err.getMessage)
                Right("Atom generation successful")
        end match
    end generateForLanguage

    private def parseConfig(parserArgs: List[String]): Either[String, BaseConfig] =
        optionParser.parse(
          parserArgs,
          DefaultAtomConfig()
              .withOutputAtomFile(File(DEFAULT_ATOM_OUT_FILE))
              .withOutputSliceFile(File(DEFAULT_SLICE_OUT_FILE))
        ) match
            case Some(config) =>
                Right(config)
            case None =>
                Left("Could not parse command line options")
end Atom
