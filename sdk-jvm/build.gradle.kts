plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
application {
    mainClass.set("dev.indicvoice.jvm.Main")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Xmx2g")
}
dependencies { implementation(project(":sdk-sherpa")) }
