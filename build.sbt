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

/* ─────────────────────────  DEPENDENCIES ─────────────────────────────── */
val spinalVersion = "1.12.0"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)

libraryDependencies ++= Seq(
  spinalCore,
  spinalLib,
  spinalIdslPlugin,
  "org.scalatest" %% "scalatest" % "3.2.18" % Test // Add ScalaTest for PinkNoiseEnd2End
)

/* ─────────────────────────  SOURCE LAYOUT EXTENSIONS ─────────────────── */
/* Include src/formal/scala for AudioCoreFormal.scala */
Compile / unmanagedSourceDirectories +=
  (Compile / sourceDirectory).value / "formal" / "scala"

/* ─────────────────────────  SPINAL/VERILOG SHORTCUTS ─────────────────── */
lazy val root = (project in file("."))
  .settings(
    name := "LoudspeakerDSP",
    Compile / run / fork := true // Fork for Verilog generation
  )

/* ─────────────────────────  TEST CONFIG ──────────────────────────────── */
Test / fork := true // Isolate JVM for SpinalSim
Test / javaOptions += "-Xmx4G" // Increase memory for Verilator simulation