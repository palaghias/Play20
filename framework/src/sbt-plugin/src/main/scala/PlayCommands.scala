package sbt

import Keys._
import complete.Parser

import play.console.Colors

import PlayExceptions._
import PlayKeys._
import java.lang.{ ProcessBuilder => JProcessBuilder }

trait PlayCommands extends PlayAssetsCompiler with PlayEclipse with PlayInternalKeys {
  this: PlayReloader =>
  
  //- mainly scala, mainly java or none

  val JAVA = "java"
  val SCALA = "scala"
  val NONE = "none"

  /**
    * Executes the {{packaged-artifacts}} task in the current project (the project to which this setting is applied)
    * and all of its dependencies, yielding a list of all resulting {{jar}} files *except*:
    *
    * * jar files from artifacts with names in [[sbt.PlayKeys.distExcludes]]
    * * the jar file that is returned by {{packageSrc in Compile}}
    * * the jar file that is returned by {{packageDoc in Compile}}
    */
  val playPackageEverythingTask = (state, thisProjectRef, distExcludes).flatMap { (state, project, excludes) =>
      def taskInAllDependencies[T](taskKey: TaskKey[T]): Task[Seq[T]] =
        inAllDependencies(project, taskKey.task, Project structure state).join

      for {
        packaged: Seq[Map[Artifact, File]] <- taskInAllDependencies(packagedArtifacts)
        srcs: Seq[File] <- taskInAllDependencies(packageSrc in Compile)
        docs: Seq[File] <- taskInAllDependencies(packageDoc in Compile)
      } yield {
        val allJars: Seq[Iterable[File]] = for {
          artifacts: Map[Artifact, File] <- packaged
        } yield {
          artifacts
            .filter { case (artifact, _) => artifact.extension == "jar" && !excludes.contains(artifact.name) }
            .map { case (_, path) => path }
        }
        allJars
          .flatten
          .diff(srcs ++ docs) //remove srcs & docs since we do not need them in the dist
          .distinct
      }
    }

  val playCopyAssets = TaskKey[Seq[(File, File)]]("play-copy-assets")
  val playCopyAssetsTask = (baseDirectory, managedResources in Compile, resourceManaged in Compile, playAssetsDirectories, playExternalAssets, classDirectory in Compile, cacheDirectory, streams, state) map { (b, resources, resourcesDirectories, r, externals, t, c, s, state) =>
    val cacheFile = c / "copy-assets"

    val mappings = (r.map(d => (d ***) --- (d ** HiddenFileFilter ***)).foldLeft(PathFinder.empty)(_ +++ _).filter(_.isFile) x relativeTo(b +: r.filterNot(_.getAbsolutePath.startsWith(b.getAbsolutePath))) map {
      case (origin, name) => (origin, new java.io.File(t, name))
    }) ++ (resources x rebase(resourcesDirectories, t))

    val externalMappings = externals.map {
      case (root, paths, common) => {
        paths(root) x relativeTo(root :: Nil) map {
          case (origin, name) => (origin, new java.io.File(t, common + "/" + name))
        }
      }
    }.foldLeft(Seq.empty[(java.io.File, java.io.File)])(_ ++ _)

    val assetsMapping = mappings ++ externalMappings

    s.log.debug("Copy play resource mappings: " + assetsMapping.mkString("\n\t", "\n\t", ""))

    Sync(cacheFile)(assetsMapping)
    assetsMapping
  }

  //- test reporter
  protected lazy val testListener = new PlayTestListener

  val testResultReporter = TaskKey[List[String]]("test-result-reporter")
  val testResultReporterTask = (state, thisProjectRef) map { (s, r) =>
    testListener.result.toList
  }
  val testResultReporterReset = TaskKey[Unit]("test-result-reporter-reset")
  val testResultReporterResetTask = (state, thisProjectRef) map { (s, r) =>
    testListener.result.clear
  }

  val playReloadTask = (playCopyAssets, playCompileEverything) map { (_, analysises) =>
    analysises.reduceLeft(_ ++ _)
  }

  val dist = TaskKey[File]("dist", "Build the standalone application package")
  val distTask = (distDirectory, baseDirectory, playPackageEverything, dependencyClasspath in Runtime, target, normalizedName, version) map { (dist, root, packaged, dependencies, target, id, version) =>

    import sbt.NameFilter._

    val packageName = id + "-" + version
    val zip = dist / (packageName + ".zip")

    IO.delete(dist)
    IO.createDirectory(dist)

    val libs = {
      dependencies.filter(_.data.ext == "jar").map { dependency =>
        val filename = for {
          module <- dependency.metadata.get(AttributeKey[ModuleID]("module-id"))
          artifact <- dependency.metadata.get(AttributeKey[Artifact]("artifact"))
        } yield {
          module.organization + "." + module.name + "-" + Option(artifact.name.replace(module.name, "")).filterNot(_.isEmpty).map(_ + "-").getOrElse("") + module.revision + ".jar"
        }
        val path = ("lib/" + filename.getOrElse(dependency.data.getName))
        dependency.data -> path
      } ++ packaged.map(jar => jar -> ("lib/" + jar.getName))
    }

    val start = target / "start"

    val customConfig = Option(System.getProperty("config.file"))
    val customFileName = customConfig.map(f => Some((new File(f)).getName)).getOrElse(None)

    IO.write(start,
      """#!/usr/bin/env sh
scriptdir=`dirname $0`
classpath=""" + libs.map { case (jar, path) => "$scriptdir/" + path }.mkString("\"", ":", "\"") + """
exec java $* -cp $classpath """ + customFileName.map(fn => "-Dconfig.file=`dirname $0`/" + fn + " ").getOrElse("") + """play.core.server.NettyServer `dirname $0`
""" /* */ )
    val scripts = Seq(start -> (packageName + "/start"))

    val other = Seq((root / "README") -> (packageName + "/README"))

    val productionConfig = customFileName.map(fn => target / fn).getOrElse(target / "application.conf")

    val prodApplicationConf = customConfig.map { location =>
      val customConfigFile = new File(location)
      IO.copyFile(customConfigFile, productionConfig)
      Seq(productionConfig -> (packageName + "/" + customConfigFile.getName))
    }.getOrElse(Nil)

    IO.zip(libs.map { case (jar, path) => jar -> (packageName + "/" + path) } ++ scripts ++ other ++ prodApplicationConf, zip)
    IO.delete(start)
    IO.delete(productionConfig)

    println()
    println("Your application is ready in " + zip.getCanonicalPath)
    println()

    zip
  }

  def intellijCommandSettings(mainLang: String) = {
    import com.typesafe.sbtidea.SbtIdeaPlugin
    SbtIdeaPlugin.ideaSettings ++
      Seq(
        SbtIdeaPlugin.commandName := "idea",
        SbtIdeaPlugin.includeScalaFacet := { mainLang == SCALA },
        SbtIdeaPlugin.defaultClassifierPolicy := false
      )
  }

  val playStage = TaskKey[Unit]("stage")
  val playStageTask = (baseDirectory, playPackageEverything, dependencyClasspath in Runtime, target, streams) map { (root, packaged, dependencies, target, s) =>

    import sbt.NameFilter._

    val staged = target / "staged"

    IO.delete(staged)
    IO.createDirectory(staged)

    val libs = dependencies.filter(_.data.ext == "jar").map(_.data) ++ packaged

    libs.foreach { jar =>
      IO.copyFile(jar, new File(staged, jar.getName))
    }

    val start = target / "start"
    IO.write(start,
      """|#!/usr/bin/env sh
         |
         |exec java $@ -cp "`dirname $0`/staged/*" play.core.server.NettyServer `dirname $0`/..
         |""".stripMargin)

    "chmod a+x %s".format(start.getAbsolutePath) !

    s.log.info("")
    s.log.info("Your application is ready to be run in place: target/start")
    s.log.info("")

    ()
  }

  // ----- Post compile (need to be refactored and fully configurable)

  def PostCompile(scope: Configuration) = (sourceDirectory in scope, dependencyClasspath in scope, compile in scope, javaSource in scope, sourceManaged in scope, classDirectory in scope, cacheDirectory in scope) map { (src, deps, analysis, javaSrc, srcManaged, classes, cacheDir) =>

    val classpath = (deps.map(_.data.getAbsolutePath).toArray :+ classes.getAbsolutePath).mkString(java.io.File.pathSeparator)

    val timestampFile = cacheDir / "play_instrumentation"
    val lastEnhanced = if (timestampFile.exists) IO.read(timestampFile).toLong else Long.MinValue
    val javaClasses = (javaSrc ** "*.java").get flatMap { sourceFile =>
      // PropertiesEnhancer is class-local, so no need to check outside the class.
      if (analysis.apis.internal(sourceFile).compilation.startTime > lastEnhanced)
        analysis.relations.products(sourceFile)
      else
        Nil
    }
    val templateClasses = (srcManaged ** "*.template.scala").get flatMap { sourceFile =>
      if (analysis.apis.internal(sourceFile).compilation.startTime > lastEnhanced)
        analysis.relations.products(sourceFile)
      else
        Nil
    }

    javaClasses.foreach(play.core.enhancers.PropertiesEnhancer.generateAccessors(classpath, _))
    javaClasses.foreach(play.core.enhancers.PropertiesEnhancer.rewriteAccess(classpath, _))
    templateClasses.foreach(play.core.enhancers.PropertiesEnhancer.rewriteAccess(classpath, _))

    IO.write(timestampFile, System.currentTimeMillis.toString)

    // EBean
    if (classpath.contains("play-java-ebean")) {

      val originalContextClassLoader = Thread.currentThread.getContextClassLoader

      try {

        val cp = deps.map(_.data.toURI.toURL).toArray :+ classes.toURI.toURL

        Thread.currentThread.setContextClassLoader(new java.net.URLClassLoader(cp, ClassLoader.getSystemClassLoader))

        import com.avaje.ebean.enhance.agent._
        import com.avaje.ebean.enhance.ant._
        import collection.JavaConverters._
        import com.typesafe.config._

        val cl = ClassLoader.getSystemClassLoader

        val t = new Transformer(cp, "debug=-1")

        val ft = new OfflineFileTransform(t, cl, classes.getAbsolutePath, classes.getAbsolutePath)

        val config = ConfigFactory.load(ConfigFactory.parseFileAnySyntax(new File("conf/application.conf")))

        val models = try {
          config.getConfig("ebean").entrySet.asScala.map(_.getValue.unwrapped).toSet.mkString(",")
        } catch { case e: ConfigException.Missing => "models.*" }

        try {
          ft.process(models)
        } catch {
          case _ =>
        }

      } finally {
        Thread.currentThread.setContextClassLoader(originalContextClassLoader)
      }
    }
    // Copy managed classes - only needed in Compile scope
    if (scope.name.toLowerCase == "compile") {
      val managedClassesDirectory = classes.getParentFile / (classes.getName + "_managed")

      val managedClasses = ((srcManaged ** "*.scala").get ++ (srcManaged ** "*.java").get).map { managedSourceFile =>
        analysis.relations.products(managedSourceFile)
      }.flatten x rebase(classes, managedClassesDirectory)

      // Copy modified class files
      val managedSet = IO.copy(managedClasses)

      // Remove deleted class files
      (managedClassesDirectory ** "*.class").get.filterNot(managedSet.contains(_)).foreach(_.delete())
    }
    analysis
  }

  // ----- Source generators

  val RouteFiles = (state: State, confDirectory: File, generatedDir: File, additionalImports: Seq[String]) => {
    import play.router.RoutesCompiler._

    val javaRoutes = (generatedDir ** "routes.java")
    val scalaRoutes = (generatedDir ** "routes_*.scala")
    (javaRoutes.get ++ scalaRoutes.get).map(GeneratedSource(_)).foreach(_.sync())
    try {
      { (confDirectory * "*.routes").get ++ (confDirectory * "routes").get }.map { routesFile =>
        compile(routesFile, generatedDir, additionalImports)
      }
    } catch {
      case RoutesCompilationError(source, message, line, column) => {
        throw reportCompilationError(state, RoutesCompilationException(source, message, line, column.map(_ - 1)))
      }
      case e => throw e
    }

    (scalaRoutes.get ++ javaRoutes.get).map(_.getAbsoluteFile)

  }

  val ScalaTemplates = (state: State, sourceDirectory: File, generatedDir: File, templateTypes: PartialFunction[String, (String, String)], additionalImports: Seq[String]) => {
    import play.templates._

    val templateExt: PartialFunction[File, (File, String, String, String)] = {
      case p if templateTypes.isDefinedAt(p.name.split('.').last) =>
        val extension = p.name.split('.').last
        val exts = templateTypes(extension)
        (p, extension, exts._1, exts._2)
    }
    (generatedDir ** "*.template.scala").get.map(GeneratedSource(_)).foreach(_.sync())
    try {

      (sourceDirectory ** "*.scala.*").get.collect(templateExt).foreach {
        case (template, extension, t, format) =>
          ScalaTemplateCompiler.compile(
            template,
            sourceDirectory,
            generatedDir,
            t,
            format,
            additionalImports.map("import " + _.replace("%format%", extension)).mkString("\n"))
      }
    } catch {
      case TemplateCompilationError(source, message, line, column) => {
        throw reportCompilationError(state, TemplateCompilationException(source, message, line, column - 1))
      }
    }

    (generatedDir ** "*.template.scala").get.map(_.getAbsoluteFile)
  }

  // ----- Play prompt

  val playPrompt = { state: State =>

    val extracted = Project.extract(state)
    import extracted._

    (name in currentRef get structure.data).map { name =>
      "[" + Colors.cyan(name) + "] $ "
    }.getOrElse("> ")

  }

  // ----- Play commands

  private def fork(args: Seq[String]) = {
    val builder = new JProcessBuilder(args: _*)
    Process(builder).run(JvmIO(new JvmLogger(), false))
  }

  val shCommand = Command.args("sh", "<shell command>") { (state: State, args: Seq[String]) =>
    if (args.isEmpty)
      println("sh <command to run>")
    else
      fork(args)
    state
  }

  // -- Utility methods for 0.10-> 0.11 migration
  def inAllDeps[T](base: ProjectRef, deps: ProjectRef => Seq[ProjectRef], key: SettingKey[T], data: Settings[Scope]): Seq[T] =
    inAllProjects(Dag.topologicalSort(base)(deps), key, data)
  def inAllProjects[T](allProjects: Seq[Reference], key: SettingKey[T], data: Settings[Scope]): Seq[T] =
    allProjects.flatMap { p => key in p get data }

  def inAllDependencies[T](base: ProjectRef, key: SettingKey[T], structure: Load.BuildStructure): Seq[T] = {
    def deps(ref: ProjectRef): Seq[ProjectRef] =
      Project.getProject(ref, structure).toList.flatMap { p =>
        p.dependencies.map(_.project) ++ p.aggregate
      }
    inAllDeps(base, deps, key, structure.data)
  }

  private[this] var commonClassLoader: ClassLoader = _

  val playCommonClassloaderTask = (dependencyClasspath in Compile) map { classpath =>
    lazy val commonJars: PartialFunction[java.io.File, java.net.URL] = {
      case jar if jar.getName.startsWith("h2-") || jar.getName == "h2.jar" => jar.toURI.toURL
    }

    if (commonClassLoader == null) {
      commonClassLoader = new java.net.URLClassLoader(classpath.map(_.data).collect(commonJars).toArray, null /* important here, don't depend of the sbt classLoader! */) {
        override def toString = "Common ClassLoader: " + getURLs.map(_.toString).mkString(",")
      }
    }

    commonClassLoader
  }

  val playCompileEverythingTask = (state, thisProjectRef) flatMap { (s, r) =>
    inAllDependencies(r, (compile in Compile).task, Project structure s).join
  }

  val buildRequireTask = (copyResources in Compile, crossTarget, requireJs, requireJsFolder, requireJsShim, requireNativePath, streams) map { (cr, crossTarget, requireJs, requireJsFolder, requireJsShim, requireNativePath,  s) =>
    val buildDescName = "app.build.js"
    val jsFolder = if(!requireJsFolder.isEmpty) {requireJsFolder} else "javascripts"
    val rjoldDir = crossTarget / "classes" / "public" / jsFolder
    val buildDesc = crossTarget / "classes" / "public" / buildDescName
    if (requireJs.isEmpty == false) {
      val rjnewDir = new File(rjoldDir.getAbsolutePath + "-min")
      //cleanup previous version
      IO.delete(rjnewDir)
      val relativeModulePath = (str: String) => str.replace(".js", "")
      val shim = if (!requireJsShim.isEmpty) {"""mainConfigFile: """" + jsFolder + """/""" + requireJsShim + """", """} else {""};
      val content =  """({appDir: """" + jsFolder + """",
          baseUrl: ".",
          dir:"""" + rjnewDir.getName + """", """ +
        shim +
        """modules: [""" + requireJs.map(f => "{name: \"" + relativeModulePath(f) + "\"}").mkString(",") + """]})""".stripMargin

      IO.write(buildDesc,content)
      //run requireJS
      s.log.info("RequireJS optimization has begun...")
      s.log.info(buildDescName+":")
      s.log.info(content)
      try {
        requireNativePath.map(nativePath =>
          println(play.core.jscompile.JavascriptCompiler.executeNativeCompiler(nativePath + " -o " + buildDesc.getAbsolutePath, buildDesc))
        ).getOrElse {
          play.core.jscompile.JavascriptCompiler.require(buildDesc)
        }
        s.log.info("RequireJS optimization finished.")
      } catch {case ex: Exception =>
        s.log.error("RequireJS optimization has failed...")
        throw ex
      }
      //clean-up
      IO.delete(buildDesc)
    }
    cr
  }

  val playCommand = Command.command("play", Help("play", ("play", "Enter the play console"), "Welcome to Play " + play.core.PlayVersion.current + """!
        |
        |These commands are available:
        |-----------------------------
        |classpath                  Display the project classpath.
        |clean                      Clean all generated files.
        |compile                    Compile the current application.
        |console                    Launch the interactive Scala console (use :quit to exit).
        |dependencies               Display the dependencies summary.
        |dist                       Construct standalone application package.
        |exit                       Exit the console.
        |h2-browser                 Launch the H2 Web browser.
        |license                    Display licensing informations.
        |package                    Package your application as a JAR.
        |play-version               Display the Play version.
        |publish                    Publish your application in a remote repository.
        |publish-local              Publish your application in the local repository.
        |reload                     Reload the current application build file.
        |run <port>                 Run the current application in DEV mode.
        |test                       Run Junit tests and/or Specs from the command line
        |eclipse                    generate eclipse project file
        |idea                       generate Intellij IDEA project file
        |sh <command to run>        execute a shell command 
        |start <port>               Start the current application in another JVM in PROD mode.
        |update                     Update application dependencies.
        |
        |Type `help` to get the standard sbt help.
        |""".stripMargin)) { state: State =>

    val extracted = Project.extract(state)
    import extracted._

    // Display logo
    println(play.console.Console.logo)
    println("""
            |> Type "help play" or "license" for more information.
            |> Type "exit" or use Ctrl+D to leave this console.
            |""".stripMargin)

    state.copy(
      remainingCommands = state.remainingCommands :+ "shell")

  }

  val h2Command = Command.command("h2-browser") { state: State =>
    try {
      val commonLoader = Project.runTask(playCommonClassloader, state).get._2.toEither.right.get
      val h2ServerClass = commonLoader.loadClass(classOf[org.h2.tools.Server].getName)
      h2ServerClass.getMethod("main", classOf[Array[String]]).invoke(null, Array.empty[String])
    } catch {
      case e: Exception => e.printStackTrace
    }
    state
  }

  val licenseCommand = Command.command("license") { state: State =>
    println(
      """
      |This software is licensed under the Apache 2 license, quoted below.
      |
      |Copyright 2013 Typesafe <http://www.typesafe.com>
      |
      |Licensed under the Apache License, Version 2.0 (the "License"); you may not
      |use this file except in compliance with the License. You may obtain a copy of
      |the License at
      |
      |    http://www.apache.org/licenses/LICENSE-2.0
      |
      |Unless required by applicable law or agreed to in writing, software
      |distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
      |WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
      |License for the specific language governing permissions and limitations under
      |the License.
      """.stripMargin)
    state
  }

  val classpathCommand = Command.command("classpath") { state: State =>

    val extracted = Project.extract(state)

    Project.runTask(dependencyClasspath in Runtime, state).get._2.toEither match {
      case Left(_) => {
        println()
        println("Cannot compute the classpath")
        println()
        state.fail
      }
      case Right(classpath) => {
        println()
        println("Here is the computed classpath of your application:")
        println()
        classpath.foreach { item =>
          println("\t- " + item.data.getAbsolutePath)
        }
        println()
        state
      }
    }

  }

  val playMonitoredFiles = TaskKey[Seq[String]]("play-monitored-files")
  val playMonitoredFilesTask = (thisProjectRef, state) map { (ref, state) =>
    val src = inAllDependencies(ref, sourceDirectories in Compile, Project structure state).foldLeft(Seq.empty[File])(_ ++ _)
    val resources = inAllDependencies(ref, resourceDirectories in Compile, Project structure state).foldLeft(Seq.empty[File])(_ ++ _)
    val assets = inAllDependencies(ref, playAssetsDirectories, Project structure state).foldLeft(Seq.empty[File])(_ ++ _)
    (src ++ resources ++ assets).map { f =>
      if (!f.exists) f.mkdirs(); f
    }.map(_.getCanonicalPath).distinct
  }

  val computeDependencies = TaskKey[Seq[Map[Symbol, Any]]]("ivy-dependencies")
  val computeDependenciesTask = (deliverLocal, ivySbt, streams, organizationName, moduleName, version, scalaBinaryVersion) map { (_, ivySbt, s, org, id, version, scalaVersion) =>

    import scala.xml._

    ivySbt.withIvy(s.log) { ivy =>
      val report = XML.loadFile(
        ivy.getResolutionCacheManager.getConfigurationResolveReportInCache(org + "-" + id + "_" + scalaVersion, "runtime"))

      val deps: Seq[Map[Symbol, Any]] = (report \ "dependencies" \ "module").flatMap { module =>

        (module \ "revision").map { rev =>
          Map(
            'module -> (module \ "@organisation" text, module \ "@name" text, rev \ "@name"),
            'evictedBy -> (rev \ "evicted-by").headOption.map(_ \ "@rev" text),
            'requiredBy -> (rev \ "caller").map { caller =>
              (caller \ "@organisation" text, caller \ "@name" text, caller \ "@callerrev" text)
            },
            'artifacts -> (rev \ "artifacts" \ "artifact").flatMap { artifact =>
              (artifact \ "@location").headOption.map(node => new java.io.File(node.text).getName)
            })
        }

      }

      deps.filterNot(_('artifacts).asInstanceOf[Seq[_]].isEmpty)

    }

  }

  val computeDependenciesCommand = Command.command("dependencies") { state: State =>

    val extracted = Project.extract(state)

    Project.runTask(computeDependencies, state).get._2.toEither match {
      case Left(_) => {
        println()
        println("Cannot compute dependencies")
        println()
        state.fail
      }

      case Right(dependencies) => {
        println()
        println("Here are the resolved dependencies of your application:")
        println()

        import scala.Console._

        def asTableRow(module: Map[Symbol, Any]): Seq[(String, String, String, Boolean)] = {
          val formatted = (Seq(module.get('module).map {
            case (org, name, rev) => org + ":" + name + ":" + rev
          }).flatten,

            module.get('requiredBy).map {
              case callers: Seq[_] => callers.map {
                case (org, name, rev) => org.toString + ":" + name.toString + ":" + rev.toString
              }
            }.flatten.toSeq,

            module.get('evictedBy).map {
              case Some(rev) => Seq("Evicted by " + rev)
              case None => module.get('artifacts).map {
                case artifacts: Seq[_] => artifacts.map("As " + _.toString)
              }.flatten
            }.flatten.toSeq)
          val maxLines = Seq(formatted._1.size, formatted._2.size, formatted._3.size).max

          formatted._1.padTo(maxLines, "").zip(
            formatted._2.padTo(maxLines, "")).zip(
              formatted._3.padTo(maxLines, "")).map {
                case ((name, callers), notes) => (name, callers, notes, module.get('evictedBy).map { case Some(_) => true; case _ => false }.get)
              }
        }

        def display(modules: Seq[Seq[(String, String, String, Boolean)]]) {
          val c1Size = modules.flatten.map(_._1.size).max
          val c2Size = modules.flatten.map(_._2.size).max
          val c3Size = modules.flatten.map(_._3.size).max

          def bar(length: Int) = (1 to length).map(_ => "-").mkString

          val lineFormat = "| %-" + (c1Size + 9) + "s | %-" + (c2Size + 9) + "s | %-" + (c3Size + 9) + "s |"
          val separator = "+-%s-+-%s-+-%s-+".format(
            bar(c1Size), bar(c2Size), bar(c3Size))

          println(separator)
          println(lineFormat.format(CYAN + "Module" + RESET, CYAN + "Required by" + RESET, CYAN + "Note" + RESET))
          println(separator)

          modules.foreach { lines =>
            lines.foreach {
              case (module, caller, note, evicted) => {
                println(lineFormat.format(
                  if (evicted) (RED + module + RESET) else (GREEN + module + RESET),
                  (WHITE + caller + RESET),
                  if (evicted) (RED + note + RESET) else (WHITE + note + RESET)))
              }
            }
            println(separator)
          }
        }

        display(dependencies.map(asTableRow))

        println()
        state
      }
    }

  }

}
