import sbt.*
import Keys.*
import sbt.nio.Keys.*

import scala.sys.process.Process

/** Runs a module's npm frontend build and folds its output into that module's
  * MANAGED resources, so the bundle ships in the jar and nothing built is
  * committed.
  *
  * Ported from the sbt 1 plugin in perok/workshop-programs-as-values. What
  * changed on the way, all of it forced by sbt 2:
  *
  *   - No Scala.js. That plugin bundled a `fastLinkJS` output; the input here
  *     is plain TypeScript/JavaScript under `src/js`, so the `ScalaJSPlugin`
  *     requirement and the `fastLinkJS` dependency are gone.
  *   - The tasks are [[Def.uncached]]. sbt 2 caches task results by default,
  *     keyed on the task graph — which cannot see that `npm` wrote a tree into
  *     `node_modules`, or that somebody deleted the bundle out of `target`.
  *   - Change detection is therefore explicit: a fingerprint of the input
  *     files' CONTENT, against a stamp next to the output.
  *     `FileFunction.cached` is the sbt 1 answer and `inputFileChanges` the sbt
  *     1.4+ one, but the latter's macro cannot expand in a task body that also
  *     mentions `Def.uncached` ("a reference to value ts was used outside the
  *     scope where it was defined"), and this build needs the uncached marker
  *     more than it needs the sugar.
  *
  * `fileInputs` is still declared even though nothing reads it here: it is what
  * `~` consults, so a watched build re-bundles on a source edit.
  *
  * Content hashes rather than timestamps because something in this build does
  * touch these files (the sbt 1 plugin carried the same note), and a rebundle
  * of CodeMirror is slow enough to be worth not doing twice.
  */
object NpmPlugin extends AutoPlugin {
  override def trigger = noTrigger

  object autoImport {
    lazy val frontendDirectory =
      settingKey[File]("Directory holding package.json (the npm root)")
    lazy val frontendSources =
      settingKey[File]("Directory holding the frontend sources")
    lazy val frontendTarget =
      settingKey[File]("Directory the bundler writes to")
    lazy val frontendInstall =
      taskKey[Unit]("npm ci — install the frontend dependencies")
    lazy val frontendBundle =
      taskKey[Seq[File]]("npm run build — bundle into managed resources")
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    frontendDirectory := baseDirectory.value,
    frontendSources := frontendDirectory.value / "src" / "js",
    frontendTarget := frontendDirectory.value / "target" / "frontend",
    frontendInstall / fileInputs ++= Seq(
      frontendDirectory.value.toGlob / "package.json",
      frontendDirectory.value.toGlob / "package-lock.json"
    ),
    frontendInstall := {
      val dir = frontendDirectory.value
      val log = streams.value.log
      Def.uncached {
        val installed = dir / "node_modules"
        // `npm ci`, not `npm install`: it installs exactly the lockfile and
        // fails when package.json has drifted from it, which is what is wanted
        // here and in CI alike. It also clears node_modules first, so there is
        // never a half-updated tree to reason about.
        //
        // `--prefer-offline` is the one that matters: without it npm
        // revalidates every package against the registry even with a warm
        // cache, so a slow registry costs minutes for a download it already
        // has. One CI run spent 7m here ("added 39 packages in 7m" against a
        // usual 2s) and took the whole job past its timeout. The lockfile
        // pins the versions, so serving them from cache changes nothing about
        // what gets installed.
        stamped(
          dir / "target" / "npm-install.stamp",
          Seq(dir / "package.json", dir / "package-lock.json"),
          force = !installed.exists
        ) {
          log.info(s"npm ci ($dir)")
          npm(
            "ci --prefer-offline --no-audit --fund=false",
            dir,
            log,
            "npm ci failed"
          )
        }
      }
    },
    frontendBundle / fileInputs ++= Seq(
      frontendSources.value.toGlob / ** / *,
      frontendDirectory.value.toGlob / "vite.config.ts",
      frontendDirectory.value.toGlob / "tsconfig.json",
      frontendDirectory.value.toGlob / "package.json"
    ),
    frontendBundle := {
      frontendInstall.value
      val dir = frontendDirectory.value
      val sources = frontendSources.value
      val dist = frontendTarget.value
      val into = (Compile / resourceManaged).value
      val log = streams.value.log
      Def.uncached {
        val inputs = (sources.allPaths.get() ++ Seq(
          dir / "vite.config.ts",
          dir / "tsconfig.json",
          dir / "package.json"
        )).filter(_.isFile)
        stamped(
          dir / "target" / "npm-bundle.stamp",
          inputs,
          force = !dist.exists
        ) {
          log.info(s"npm run build ($dir)")
          // Wiped first, because the bundler appends: three `vite build` runs
          // share two output directories, so none of them may empty its own.
          // Without this a source file that is deleted leaves its bundle
          // behind, and the copy below would keep shipping it.
          IO.delete(dist)
          npm("run build", dir, log, "frontend bundle failed")
        }
        // Copy rather than point the bundler straight at `resourceManaged`:
        // that directory belongs to every resource generator at once, and a
        // bundler that cleans its own output dir would take the others with it.
        val copies = Path
          .allSubpaths(dist)
          .collect { case (from, rel) if from.isFile => from -> into / rel }
          .toSeq
        if (copies.isEmpty)
          sys.error(s"the frontend bundle produced nothing in $dist")
        IO.copy(copies, CopyOptions().withOverwrite(true))
        copies.map(_._2)
      }
    },
    Compile / resourceGenerators += (Compile / frontendBundle)
  )

  /** Run `work` when `inputs`' content no longer matches `stamp` (or when
    * `force` says the output is missing), then record what was built from.
    */
  private def stamped(stamp: File, inputs: Seq[File], force: Boolean)(
      work: => Unit
  ): Unit = {
    val fingerprint = inputs
      .filter(_.isFile)
      .sortBy(_.getAbsolutePath)
      .map(f => s"${f.getAbsolutePath}:${Hash.toHex(Hash(f))}")
      .mkString("\n")
    val current =
      if (stamp.isFile) IO.read(stamp) else ""
    if (force || current != fingerprint) {
      work
      IO.write(stamp, fingerprint)
    }
  }

  private def npm(
      args: String,
      cwd: File,
      log: Logger,
      failed: String
  ): Unit = {
    val code = Process(s"npm $args", cwd) ! log
    if (code != 0) sys.error(failed)
  }
}
