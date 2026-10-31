plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
java { withSourcesJar() }
// Zero dependencies on purpose: the core must drop into any Android/JVM project without version conflicts.
