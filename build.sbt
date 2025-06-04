ThisBuild / organization  := "com.acme.dsp"
ThisBuild / version       := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion  := "2.13.14"

/* ─────────────────────────  GLOBAL SCALAC FLAGS ───────────────────────── */
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-explaintypes",
  "-Xfatal-warnings",
  "-language:implicitConversions",
  "-Ymacro-annotations"
)

/* ─────────────────────────  DEPENDENCIES  ─────────────────────────────── */
val spinalVersion = "1.12.0"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

libraryDependencies ++= Seq(spinalCore, spinalLib, spinalIdslPlugin)

/* ─────────────────────────  SOURCE LAYOUT EXTENSIONS  ─────────────────── */
/* add `src/formal/scala` to the main compilation pass so that
 * `AudioCoreFormal.scala` can reach package‐private RTL.
 */
Compile / unmanagedSourceDirectories +=
  (Compile / sourceDirectory).value / "formal" / "scala"

/* ─────────────────────────  SPINAL/VERILOG SHORTCUTS  ─────────────────── */
lazy val root = (project in file("."))
  .settings(
    name := "LoudspeakerDSP",

    /* Run `sbt runMain dsp.LoudspeakerDspVerilog` to emit Verilog. */
    Compile / run / fork := true
  )

/* ─────────────────────────  TEST CONFIG  ──────────────────────────────── */
Test / fork := true                       // isolate JVM for SpinalSim
Test / javaOptions += "-Xmx2G"
