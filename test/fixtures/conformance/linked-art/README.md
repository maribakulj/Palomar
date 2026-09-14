# Conformance fixtures — Linked Art JSON Schema

The **official** Linked Art validation schemas (draft 2020-12) + one example, used
by `palomar.eval.linked-art-conformance-test` to validate our Linked Art export with
the real `com.networknt/json-schema-validator` (a test-only Maven dependency).

| File | Source | Licence |
|------|--------|---------|
| `schema/*.json` (14: object, core, text, digital, concept, person, event, group, place, provenance, set, image, abstract, linked_art) | `https://linked.art/api/1.0/schema/…` (repo `linked-art/json-validator`) | Apache-2.0 |
| `example-object-mona-lisa.json` | `https://linked.art/example/object/0` | CC BY 4.0 |

The full set is committed (verbatim upstream) so the cross-file `$ref`s resolve
offline: `object.json` references `core.json#/$defs/…`, etc. They are preloaded into
the validator under their filename IRI.

## What is checked now (the real validator)

Full **draft-2020-12** validation, `$ref`-resolved across the schema set — Maven
Central is reachable, so the validator resolves normally (no vendored jars). The eval
replaced the earlier root-only check.

## The honest calibration: the schema is *stricter than real Linked Art*

The json-validator schema is `additionalProperties:false` throughout and models
`carries` / `part_of` / `member_of` as **id-only references**, while real Linked Art
(and Getty's own examples) **embed** fuller objects there. So the schema does not
accept all valid LA: validating Getty's own **Mona Lisa** against it yields errors
(`additionalProperties` on `notation`/`language`, a `const` type mismatch, a missing
ref `id`). The eval asserts that failure explicitly, then shows our output is
*cleaner* than the canonical example: zero root-level errors, and its only deviations
are `additionalProperties` on the embedded Expression under `/carries` (no
`type`/`const`/`required`/`format` errors).

## One documented normalization (at load — fixtures stay pristine)

`core.json` declares draft-2020-12 but uses the draft-7 `items:[…]` tuple in
`ContextStringOrArray`, which a strict 2020-12 validator will not compile. The eval's
`normalize-2020-12` rewrites that one keyword to `prefixItems` (the exact 2020-12
equivalent) **at load time**; the committed file is byte-for-byte upstream. The
affected branch (an array-valued `@context`) is exercised by neither our output nor
the Mona Lisa (both use the string `@context`).
