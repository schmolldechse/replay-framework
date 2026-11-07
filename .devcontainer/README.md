# Development container

The container provides the Java 26 toolchain used by both Paper adapters. Its
post-create step compiles production and test sources without starting external
services or accepting the Minecraft EULA.

Run the normal test suite with:

```bash
./gradlew test
```

The adapter tests use their pinned Paper development bundles. The example
plugin is exercised against both MockBukkit versions with:

```bash
./gradlew :replay-example-plugin:test -PtestMinecraftVersion=26.2
./gradlew :replay-example-plugin:test -PtestMinecraftVersion=26.3
```

S3, SFTP, and PostgreSQL integration tests are opt-in and skip when their
documented connection properties are absent. They are independent of adapter
verification and deliberately require no container orchestration in this
repository.
