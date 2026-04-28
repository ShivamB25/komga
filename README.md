[![Open Collective backers and sponsors](https://img.shields.io/opencollective/all/komga?label=OpenCollective%20Sponsors&color=success)](https://opencollective.com/komga) [![GitHub Sponsors](https://img.shields.io/github/sponsors/gotson?label=Github%20Sponsors&color=success)](https://github.com/sponsors/gotson)
[![Discord](https://img.shields.io/discord/678794935368941569?label=Discord&color=blue)](https://discord.gg/TdRpkDu)

[![GitHub Workflow Status](https://img.shields.io/github/actions/workflow/status/gotson/komga/tests.yml?branch=master)](https://github.com/gotson/komga/actions?query=workflow%3ATests+branch%3Amaster)
[![GitHub release (latest SemVer)](https://img.shields.io/github/v/release/gotson/komga?color=blue&label=download&sort=semver)](https://github.com/gotson/komga/releases) [![GitHub all releases](https://img.shields.io/github/downloads/gotson/komga/total?color=blue&label=github%20downloads)](https://github.com/gotson/komga/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/gotson/komga)](https://hub.docker.com/r/gotson/komga)

[![Translation status](https://hosted.weblate.org/widgets/komga/-/webui/svg-badge.svg)](https://hosted.weblate.org/engage/komga/)

# ![app icon](./.github/readme-images/app-icon.png) Komga

Komga is a media server for your comics, mangas, BDs, magazines and eBooks.

#### Chat on [Discord](https://discord.gg/TdRpkDu)

## Features

- Browse libraries, series and books via a responsive web UI that works on desktop, tablets and phones
- Organize your library with collections and read lists
- Edit metadata for your series and books
- Import embedded metadata automatically
- Webreader with multiple reading modes
- Manage multiple users, with per-library access control, age restrictions, and labels restrictions
- Offers a REST API, many community tools and scripts can interact with Komga
- OPDS v1 and v2 support
- Kobo Sync with your Kobo eReader
- KOReader Sync
- Download book files, whole series, or read lists
- Duplicate files detection
- Duplicate pages detection and removal
- Import books from outside your libraries directly into your series folder
- Import ComicRack `cbl` read lists

## Installation

Refer to the [website](https://komga.org/docs/category/installation) for instructions.

### Advanced experimental configuration

SQLite remains the default database and requires no configuration. PostgreSQL is available as an advanced, opt-in, experimental backend; configure both the main and tasks databases explicitly:

```yaml
komga:
  database:
    backend: POSTGRESQL
    postgresql:
      url: jdbc:postgresql://localhost:5432/komga
      username: komga
      password: change-me
  tasks-db:
    backend: POSTGRESQL
    postgresql:
      url: jdbc:postgresql://localhost:5432/komga
      username: komga
      password: change-me
```

The two PostgreSQL datasources may share one database; Komga keeps their Flyway histories separated. PostgreSQL startup requires all PostgreSQL properties above and rejects unsupported SQLite-only tuning such as custom pragmas or busy timeouts. Migrating an existing SQLite installation to PostgreSQL is never automatic and must be run explicitly while Komga is offline.

#### SQLite to PostgreSQL migration

Before migrating:

- Stop the normal Komga process or container.
- Use the same Komga version as your target installation.
- Run `preflight`, review the generated report, then run `migrate`.

What the commands do:

- `preflight` validates and initializes the target PostgreSQL schema when needed, checks source and target safety, and writes a report without copying data.
- `migrate` repeats those checks, initializes the target PostgreSQL schema when needed, copies data, and validates the copied data.

For most installs, pass only the Komga config folder with `--source-config-dir`; it resolves both `database.sqlite` and `tasks.sqlite`.

For a built jar, invoke the migration command through Spring Boot's `PropertiesLauncher`:

```sh
java -Dloader.main=org.gotson.komga.infrastructure.migration.MigrationCommandKt -cp komga/build/libs/komga-1.24.4.jar org.springframework.boot.loader.launch.PropertiesLauncher preflight --source-config-dir=/path/to/config --target=jdbc:postgresql://localhost:5432/komga --target-user=komga --target-password=change-me --report=/path/to/config/migration-preflight.json
java -Dloader.main=org.gotson.komga.infrastructure.migration.MigrationCommandKt -cp komga/build/libs/komga-1.24.4.jar org.springframework.boot.loader.launch.PropertiesLauncher migrate --source-config-dir=/path/to/config --target=jdbc:postgresql://localhost:5432/komga --target-user=komga --target-password=change-me --report=/path/to/config/migration.json
```

Docker deployments can use the `migration` entrypoint command:

```sh
docker run --rm -v /path/to/config:/config gotson/komga:latest migration preflight --source-config-dir=/config --target=jdbc:postgresql://postgres:5432/komga --target-user=komga --target-password=change-me --report=/config/migration-preflight.json
docker run --rm -v /path/to/config:/config gotson/komga:latest migration migrate --source-config-dir=/config --target=jdbc:postgresql://postgres:5432/komga --target-user=komga --target-password=change-me --report=/config/migration.json
```

For advanced setups with custom SQLite locations, use explicit sources instead: `--source-main=jdbc:sqlite:/path/to/database.sqlite` and `--source-tasks=jdbc:sqlite:/path/to/tasks.sqlite`.

Generated book thumbnail cache storage can also be configured. Database storage is the default. `FILESYSTEM` and `HYBRID` store generated thumbnail cache files under the configured directory, while durable user-uploaded covers and non-book thumbnail tables remain database-backed:

```yaml
komga:
  thumbnails:
    storage:
      mode: FILESYSTEM # DATABASE, FILESYSTEM, or HYBRID
      directory: /path/to/komga-thumbnail-cache
```

Generated thumbnail JPEG quality is a server setting exposed as `thumbnailJpegQuality` through the settings API. Values must be between `1` and `100`; omit or set it to `null` to keep the default encoder behavior.

## Documentation

Head over to our [website](https://komga.org) for more information.

## Develop in Komga

Check the [development guidelines](./DEVELOPING.md).

## Translation

[![Translation status](https://hosted.weblate.org/widgets/komga/-/webui/horizontal-auto.svg)](https://hosted.weblate.org/engage/komga/)

## Powered by

[![Jetbrains_logo](./.github/readme-images/jetbrains.svg)](https://www.jetbrains.com/?from=Komga)

Thanks to [JetBrains](https://www.jetbrains.com/?from=Komga) for providing the development environment that helps us develop Komga.

[![Chromatic logo](https://user-images.githubusercontent.com/321738/84662277-e3db4f80-af1b-11ea-88f5-91d67a5e59f6.png)](https://www.chromatic.com)

Thanks to [Chromatic](https://www.chromatic.com/) for providing the visual testing platform that helps us review UI changes and catch visual regressions.

## Credits

The Komga icon is based on an icon made by [Freepik](https://www.freepik.com/home) from www.flaticon.com
