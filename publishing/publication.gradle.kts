import org.gradle.api.publish.maven.MavenPublication
import org.gradle.plugins.signing.SigningExtension

apply(plugin = "maven-publish")
apply(plugin = "signing")
val repository = extra["parikt.repository"] as File
val metadata = extra["parikt.metadata"] as java.util.Properties
val documentation = tasks.register<Jar>("publicationDocumentation") {
    archiveClassifier.set("javadoc")
    from(File(repository, "README.md"))
    from(File(repository, "docs")) { into("guide") }
    from(File(repository, "LICENSE"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
configure<PublishingExtension> {
    repositories.maven {
        name = "Candidate"
        url = uri(providers.gradleProperty("parikt.candidateRepository")
            .orElse(rootProject.layout.buildDirectory.dir("candidate-repository").map { it.asFile.absolutePath }).get())
    }
    publications.withType<MavenPublication>().configureEach {
        artifact(documentation)
        pom {
            name.set(metadata.getProperty("POM_NAME"))
            description.set(metadata.getProperty("POM_DESCRIPTION"))
            url.set(metadata.getProperty("POM_URL"))
            inceptionYear.set(metadata.getProperty("POM_INCEPTION_YEAR"))
            licenses { license {
                name.set(metadata.getProperty("POM_LICENSE_NAME"))
                url.set(metadata.getProperty("POM_LICENSE_URL"))
                distribution.set(metadata.getProperty("POM_LICENSE_DIST"))
            } }
            developers { developer {
                id.set(metadata.getProperty("POM_DEVELOPER_ID"))
                name.set(metadata.getProperty("POM_DEVELOPER_NAME"))
                url.set(metadata.getProperty("POM_DEVELOPER_URL"))
            } }
            scm {
                url.set(metadata.getProperty("POM_SCM_URL"))
                connection.set(metadata.getProperty("POM_SCM_CONNECTION"))
                developerConnection.set(metadata.getProperty("POM_SCM_DEV_CONNECTION"))
            }
        }
    }
}
configure<SigningExtension> {
    isRequired = providers.gradleProperty("parikt.release.signing").orNull == "true"
    val key = providers.gradleProperty("signingInMemoryKey").orNull
    val password = providers.gradleProperty("signingInMemoryKeyPassword").orNull
    val keyId = providers.gradleProperty("signingInMemoryKeyId").orNull
    if (key != null) useInMemoryPgpKeys(keyId, key, password)
    sign(extensions.getByType<PublishingExtension>().publications)
}
