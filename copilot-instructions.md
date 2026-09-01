# Project instructions

- Keep GitHub Actions startup minimal.
- Use runner-provided JVM and Gradle on `ubuntu-latest`, plus Gradle's bundled Groovy runtime.
- Do not add Java, Groovy, or Gradle setup actions unless requirements change.
- Evaluate the Groovy script as a Gradle init script because standalone `groovy` is unavailable.
- Keep `README.md` aligned with workflow behavior.
