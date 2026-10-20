# Repository Guidelines

## Project Structure & Module Organization

FireflyMC is a NeoForge Minecraft mod. Keep dependency and release versions in `gradle.properties`.

- `src/main/java/firefly520/fireflymc/`: shared code, including `ai/`, `auth/`, `music/`, `story/`, and `network/`; client features belong in `client/`, injections in `mixin/`.
- `src/main/resources/`: assets under `assets/fireflymc/`, story definitions under `data/fireflymc/story/`. Mod metadata templates live in `src/main/templates/`.
- `src/test/java/`: unit tests mirroring source packages; `src/test/resources/` contains story fixtures.
- `docs/`: known issues, architecture, specifications, and implementation plans.

## Build, Test, and Development Commands

Use Java 21 and the repository Gradle Wrapper from the root. Bash examples follow; in PowerShell replace `./gradlew` with `.\gradlew.bat`.

```bash
./gradlew build                    # Compile, test, and package into build/libs/
./gradlew test                     # Run all JUnit tests
./gradlew test --tests "*.MusicQueueManagerTest"  # Focused tests
./gradlew runClient                # Launch the development client
./gradlew runServer                # Launch the dedicated server without GUI
./gradlew runGameTestServer        # Run Minecraft integration tests
./gradlew runData                  # Generate src/generated/resources/
```

## Coding Style & Naming Conventions

Use UTF-8, four-space indentation, and opening braces on the declaration line. Follow existing Java conventions: `PascalCase` classes, `lowerCamelCase` methods/fields, and `UPPER_SNAKE_CASE` constants. No dedicated formatter or lint task is configured.

Keep shared/server classes free of client-only dependencies. Dispatch asynchronous world/player mutations through `server.execute(...)` or the client main-thread executor.

## Testing Guidelines

Use JUnit 5 with `*Test.java` classes and descriptive behavior-based methods. Add regression coverage for changed behavior; no numerical coverage threshold is configured. Run `build` before requesting review.

Run GameTests separately for gameplay changes; their classes reside in the main source set's `story/`, with SNBT structures in `src/main/resources/data/fireflymc/structure/`. Document manual client/server checks for UI or networking changes.

## Commit & Pull Request Guidelines

Follow the history's Conventional Commits, such as `feat(story): ...` or `fix(music): ...`, for commits and PR titles. Keep PRs focused; describe behavior changes, link relevant issues, list validation commands/results, and attach screenshots for UI changes.

CI runs the Gradle build. Merging into `main` automatically creates or updates the release selected by `mod_version`; coordinate version changes accordingly.

## Agent Workflow & Configuration

When `.codegraph/` exists, use CodeGraph before code searches or reads, for example `codegraph explore "ModNetwork"`. Consult `CLAUDE.md` and `docs/known-issues.md`. Commit or push only when explicitly requested.

Keep credentials, `run/` data, `logs/`, and build outputs out of commits. Preserve `NOTICE.md` and `licenses/` when changing JLayer packaging.
