// Any customized settings must be written here, i.e. after 'assemblySettings' above.
// See https://github.com/sbt/sbt-assembly for available parameters.

// Include "provided" dependencies back to run/test tasks' classpath.
run in Compile <<= Defaults.runTask(fullClasspath in Compile, mainClass in (Compile, run), runner in (Compile, run))

logLevel in assembly := Level.Warn
