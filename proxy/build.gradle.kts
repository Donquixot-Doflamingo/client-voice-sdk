plugins { kotlin("jvm"); application }
kotlin { jvmToolchain(17) }
application {
    mainClass.set("dev.indicvoice.proxy.ProxyMain")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Xmx3g")
}
dependencies { implementation(project(":sdk-jvm")) }
