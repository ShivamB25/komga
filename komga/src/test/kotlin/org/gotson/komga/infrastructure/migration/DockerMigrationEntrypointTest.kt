package org.gotson.komga.infrastructure.migration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

class DockerMigrationEntrypointTest {
  private val dockerDirectory = Path.of(System.getProperty("user.dir"), "docker")

  @Test
  fun `Docker image entrypoint exposes explicit migration command and preserves app startup path`() {
    val dockerfile = dockerDirectory.resolve("Dockerfile.tpl").readText()
    val entrypoint = dockerDirectory.resolve("komga-entrypoint.sh").readText()

    assertThat(dockerfile)
      .contains("COPY --from=builder /builder/application.jar ./application.jar")
      .contains("COPY komga-entrypoint.sh /usr/local/bin/komga-entrypoint")
      .contains("ENTRYPOINT [\"/usr/local/bin/komga-entrypoint\"]")

    assertThat(entrypoint)
      .contains("if [ \"\${1:-}\" = \"migration\" ]; then")
      .contains("shift")
      .contains("-cp \"application.jar:lib/*\"")
      .contains("org.gotson.komga.infrastructure.migration.MigrationCommandKt")
      .doesNotContain("-Dloader.main=org.gotson.komga.infrastructure.migration.MigrationCommandKt")
      .doesNotContain("org.springframework.boot.loader.launch.PropertiesLauncher")

    assertThat(entrypoint.indexOf("-cp \"application.jar:lib/*\""))
      .isLessThan(entrypoint.indexOf("org.gotson.komga.infrastructure.migration.MigrationCommandKt"))

    assertThat(entrypoint)
      .contains("-jar application.jar")
      .contains("--spring.config.additional-location=file:/config/")

    assertThat(entrypoint.indexOf("-jar application.jar"))
      .isGreaterThan(entrypoint.indexOf("fi"))
  }
}
