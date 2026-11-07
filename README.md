# jenkins-atom-coverage-upload

Coverage report — port of **coverage** (17,966) as an atom.

JaCoCo counters and Cobertura rates in one reader, plus the part that matters: a threshold that fails the build.

## Build and test

```bash
mvn verify
```

`src/test/java/atom/CoverageUploadIT.java` covers JaCoCo, Cobertura, lcov, Sonar generic and Clover reports, the
threshold gate, and the refusal of a truncated report.
