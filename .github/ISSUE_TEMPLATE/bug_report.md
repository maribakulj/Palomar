---
name: Bug report
about: Something is wrong, surprising, or contradicts the documentation
title: "[bug] "
labels: ["bug"]
---

## What happened

<!-- One or two sentences. The behavior you observed. -->

## What you expected

<!-- One sentence. The behavior you wanted instead. -->

## Reproduction

<!--
The shorter, the better. A minimal record + minimal rule + a single
command line is the gold standard. If the bug only manifests with a
specific record, redact private content but keep the structural shape.
-->

```clojure
;; record(s)
{:id   :r/example
 :kind :book
 :assertions [...]}
```

```clojure
;; rule(s)
{:id    :rule/example
 :phase :validate
 :match [...]
 :produce {...}}
```

```bash
clojure -M:test  # or whatever command surfaced the bug
```

## Output

```
<!-- The text or stack trace as it appeared. -->
```

## Environment

- Palomar version: <!-- commit SHA, tag, or "main" with date -->
- Java version: <!-- e.g. Temurin 21.0.5 -->
- Clojure CLI version: <!-- output of `clojure --version` -->
- OS: <!-- e.g. macOS 14.5 / Ubuntu 24.04 -->

## Anything else

<!--
If you've already root-caused the issue (file:line), put it here. If you
have a candidate fix, even pseudocode, put it here. None of this is
required, but it shortens the round-trip.
-->
