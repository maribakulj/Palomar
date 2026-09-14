# 0003 — Structural vocabulary at core, documentary vocabulary in a plugin

- Status: Accepted
- Date: 2026-04-17

## Context

The core must remain agnostic to any particular metadata standard. IIIF,
Dublin Core, CIDOC CRM, MARC, Linked Art all enter Palomar as plugins;
none of them can be privileged.

At the same time, a core with *zero* vocabulary is inoperable: cross-source
rules ("every date must be ISO-8601"), readable reports ("12 records without
a title"), projection targets, and record deduplication all require *some*
commensurable vocabulary. Without one, every plugin operates in a silo and
every consumer must enumerate every plugin's native vocabulary.

So we must decide: what vocabulary, if any, lives at the core?

## Decision

Palomar distinguishes two layers of vocabulary.

**Structural vocabulary — at the core** (`palomar.model`):

- `:meta/id`
- `:meta/kind`
- `:meta/source`
- `:meta/fragment`
- `:meta/diagnostic`
- `:meta/provenance`

These describe the *shape* of a record — its identity, origin, diagnostics
attached to it — not its documentary content. They are the minimal alphabet
a generic engine needs to function at all.

**Documentary vocabulary — in a standard plugin**
(`palomar.plugins.canonical`):

- `:canon/title`
- `:canon/identifier`
- `:canon/agent`
- `:canon/date`
- `:canon/relation`
- `:canon/note`
- `:canon/digital-object`
- `:canon/loss-marker`
- `:canon/uniform-title` — *added 2026-06-06* under the growth discipline below.
  The cataloguer's controlled work title (MARC 240, MODS/UNIMARC uniform title),
  distinct from the transcribed `:canon/title`. Concrete use case: it is the
  WEMI-derivation Work key that unifies an edition's transcribed-title variants, so the
  floor projection keys the Work on it when present — a measured recall gain against
  an independent gold (`docs/eval/bibr-work-clustering.md`).

Format plugins (DC, MARC, CIDOC) may — but are not required to — declare a
mapping from their native predicates to `:canon/*`. Consumers that want
cross-format rules, generic reports, or canonical projection load the
canonical plugin alongside their format plugins.

## Alternatives considered

- **Zero vocabulary at the core.** Rejected: makes the core incapable of
  expressing generic operations, pushing all commensurability into an ad-hoc
  agreement between plugins.
- **Documentary vocabulary at the core** (title, agent, date as core
  predicates). Rejected: any closed documentary vocabulary is an ontological
  commitment. The pressure to extend it ("add place", "add event", "add
  rights") is inexhaustible, and the endpoint is a miniature Dublin Core
  baked into the core — exactly what the project rejects.

## Consequences

- The core stays authentically agnostic. No canonical documentary ontology
  ships with it.
- Commensurability is *available* (load the canonical plugin) but not
  *imposed*. Institutions with specialized models (CIDOC-only museum,
  EAD-only archive) can skip the canonical plugin entirely or replace it
  with their own.
- The canonical plugin can evolve at its own pace and version independently
  of the core.
- Format plugins are rated on their coverage of the canonical vocabulary,
  giving us an objective quality metric.
- Growth discipline: the canonical vocabulary starts at about ten predicates
  and must justify every addition through concrete use cases, not
  anticipated ones.
