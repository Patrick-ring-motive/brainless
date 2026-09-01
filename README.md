# Painless-style Groovy runtime

Minimal Gradle init script that augments Groovy runtime with receiver-style APIs from Elasticsearch Painless [`Augmentation.java`](https://github.com/elastic/elasticsearch/blob/main/modules/lang-painless/src/main/java/org/elasticsearch/painless/api/Augmentation.java).

## Implemented augmentations

`scripts/main.groovy` installs Painless behavior for:

- `Iterable`, `Collection`, `List`, `ArrayList`, object arrays, and every primitive array type
- traversal, search, collection, grouping, joining, splitting, and numeric sums
- `List.length` and nested `Map`/`List` `getByPath`
- `CharSequence`, `String`, `GString`, and `GStringImpl` Base64, hashing, and literal `splitOnToken`
- regex `Matcher.namedGroup`
- `TemporalAccessor` epoch milliseconds and Painless `ZonedDateTime` compatibility getters

Implementations preserve Painless result shapes and notable behavior where Groovy methods with the same names differ. Map callbacks accept either `(entry)` or `(key, value)` closures.

Painless cancellation overloads are runtime/compiler infrastructure, not script-visible receiver APIs, so they are not installed. JDK methods already exposed by Painless remain native. Regex limit-factor enforcement depends on Elasticsearch's `LimitedCharSequence` and cannot be reproduced with only bundled Groovy and JDK classes.

## Context globals

`PainlessContexts.execute(name, globals) { ... }` runs a closure with context-specific globals and validates its return type. Supported contexts:

- `runtime`, `field`, `ingest`, `filter`, `score`, and `sort`
- `update`, `update_by_query`, and `reindex`
- `similarity`, `weight`, and `minimum_should_match`
- `metric_agg_init`, `metric_agg_map`, `metric_agg_combine`, and `metric_agg_reduce`
- `bucket_script`, `bucket_selector`, `watcher_condition`, and `watcher_transform`
- `analysis_predicate`

Globals include each context's applicable `params`, `doc`, `ctx`, `_score`, `state`, `states`, `weight`, `query`, `field`, `term`, and `token`. Runtime fields provide `emit`, `grok`, and `dissect`. `doc` values support list operations and `.value` first-value access.

This is a synthetic context harness, not Elasticsearch execution. It cannot reproduce Painless compilation, allowlists, `needs_score` analysis, Lucene-backed doc values/scoring, `CtxMap` metadata enforcement, or regex complexity limits.

## Lenient whitelist

`painless-whitelist-lenient.json` is a deterministic union of every generated whitelist under Elasticsearch `main/modules/lang-painless/src/main/generated/whitelist-json`. It includes every class, constructor, method, and field allowed by any generated context. Duplicate members are removed by full signature; a class is imported when any source imports it.

During `PainlessContexts.execute`, guarded concrete metaclasses allow methods and getter/setter properties present in this union. Unapproved direct methods throw `MissingMethodException`; unapproved properties throw `MissingPropertyException`. Enforcement is thread-scoped and inactive outside context execution, so it does not interfere with Gradle. Current metaclass enforcement covers `String`, `StringBuilder`, `GStringImpl`, `ArrayList`, `HashMap`, and `LinkedHashMap`. Groovy extension methods that bypass receiver metaclass dispatch require AST-level enforcement and remain outside this guard.

## Validation

Tests live in `scripts/main_test.groovy`. They adapt portable behavior from Elasticsearch's `FactoryTests`, `EmitTests`, `ScriptedMetricAggContextsTests`, `SimilarityScriptTests`, and `PainlessExecuteApiTests`. They also verify approved access, rejected direct methods and properties, and unrestricted behavior outside context execution. Elasticsearch/Lucene integration tests cannot run in this dependency-free pipeline.

Run tests locally with concise output:

`./test.sh`

CI runs the underlying command directly:

`gradle --quiet --init-script scripts/main.groovy --init-script scripts/main_test.groovy help`

Run only runtime installation:

`gradle --quiet --init-script scripts/main.groovy help`

Both commands use runner-provided JVM and Gradle plus Gradle's bundled Groovy runtime. No standalone Groovy executable, setup action, build file, wrapper, or downloaded dependency is required.
