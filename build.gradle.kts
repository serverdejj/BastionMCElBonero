plugins { java }
group = "mc.bastion"
version = "1.4.0"
repositories { maven("https://repo.papermc.io/repository/maven-public/"); maven("https://repo.fancyinnovations.com/releases") }
dependencies { compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT"); compileOnly("de.oliver:FancyNpcs:2.9.2") }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }
tasks.withType<JavaCompile> { options.encoding = "UTF-8" }
