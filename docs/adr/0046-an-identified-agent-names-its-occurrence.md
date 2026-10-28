# 0046 — An identified agent names the occurrence it identifies

- Status: Accepted
- Date: 2026-10-28
- Amends: [ADR 0018](./0018-entity-resolution-at-scale.md),
  [ADR 0041](./0041-resemblance-identity-shared-node.md) (applying a link)

## Context

The second post-audit review found that reconciliation still thought in
records, not in agents. `apply-agent-links` applied **one** link per record,
the first that matched, and skipped a record outright once it held any
identified agent. A record by Marx and Engels, with both names accepted
against their ISNI, came out with Marx identified and Engels left as a bare
name. Engels was neither applied nor refused: the function's own contract,
"nothing else is applied, and every link left out is returned", was broken.

Behind it was a missing fact. An identified agent entity said *that* the
record had an identified agent, never *which* of its names it identified.
So every consumer assumed one. `reconcile-agents` labelled every identified
agent of a record with the first creator's name. Linked Art gave the
authority id to a Person only when the record had a single creator. The
`creator-identified` conformance check passed as soon as one creator was
identified.

## Decision

1. **An identified agent carries the name it identifies.** Applying a link
   writes the name on the agent entity (`:crm/P190_has_symbolic_content`).
   The RDF view already writes an entity's string as an appellation it is
   identified by (`P1_is_identified_by` → `E41_Appellation`, ADR 0022), so
   this adds no new vocabulary. `agents/identified` reads it back as
   `[{:id :iri :name}]`. A native identified agent with no name of its own
   (the INTERMARC ISNI of the 100 main entry) names the record's first
   creator, but only when it is the record's single unnamed one. Otherwise
   its name is unknown, and it labels nothing.
2. **Links apply name by name.** Every agent the record names (a creator, a
   contributor, a publisher) that is not identified yet takes the usable
   link for its name. A name the record already identifies takes none. The
   link is then listed under `:skipped` with `:already-identified`, so no
   link a record could have taken disappears without a trace.
3. **Consumers ask the name, not the record.** `reconcile-agents` labels
   each agent with its own name. Linked Art gives each Person the id of the
   agent that names it. `creator-identified` holds only when **every**
   creator is identified.

Rejected: keying the identified agent by `[record name]`. It would make the
entity local to the record, while an authority id must name one node across
the corpus (ADR 0018). The name is a statement about the entity in this
record, not part of its identity.

## Consequences

- A multi-author record reconciles all its authors, and each keeps its own
  label in every output.
- A record partly identified at the source (a native ISNI) can be completed
  by curation instead of being frozen.
- Two records may write two spellings of one authority's name. That gives
  two appellations of one node, which is what the sources say.
