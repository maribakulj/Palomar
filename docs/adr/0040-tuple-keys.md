# 0040 — Identity keys are tuples, never joined strings

- Status: Accepted
- Date: 2026-10-26
- Amends: [ADR 0016](./0016-wemi-derivation.md) (the key of a minted id),
  [ADR 0035](./0035-construct-templates.md) (`mint`)

## Context

The post-audit review (2026-10-26) found the same defect in several places.
A key made of several parts was the parts joined with `|`:
- the Work and Expression keys (creator, title, language);
- the event and time-span keys;
- a record-scoped agent;
- a CRM blank node;
- the DSL's `mint`.

So `["a|b" "c"]` and `["a" "b|c"]` were one key, and two different entities
one id: a deterministic collision, no hash needed. The OAI record id folded
every character outside `[A-Za-z0-9_-]` to `-`, so `oai:abc/a` and
`oai:abc:a` were one record. The RDF/XML reader named its fresh blank nodes
`b1, b2…` in the space of the document's own `rdf:nodeID`s, and replaced the
`xml:base` in scope by a nested relative one instead of resolving it.

## Decision

1. `model/tuple-key` encodes a tuple of parts as its printed EDN vector,
   injective over strings, keywords and numbers (a property test pins it).
   `model/mint-entity-id` takes either a string that is itself an identifier
   (an ARK, an IRI) or a vector of parts. A string key may not start with `[`,
   so the two forms never meet.
2. Every multi-part key is a vector:
   - the floor and INTERMARC Work, Expression and Manifestation keys;
   - events;
   - the record-scoped agent;
   - CRM blank nodes;
   - EAD;
   - `mint`, which takes the tuple of its parts.
3. An OAI record id keeps letters, digits and `-`, and writes every other
   character (and a first character that is not a letter) as `_` plus its
   UTF-8 bytes in hex. It is injective, reversible (`oai/record-identifier`)
   and readable as EDN.
4. RDF/XML keeps generated blank nodes (`g…`) and the document's `nodeID`s
   (`n…`) apart, and resolves a relative `xml:base` against the base in scope.

## Consequences

- Every minted id changes once, which is acceptable before 1.0. Records
  converge as before: the floor and INTERMARC still mint the same Work for
  the same creator and title.
- An application that minted ids with the old joined strings must pass
  vectors. A joined string that starts with `[` is now refused.
