# 0063 — The occurrence, the local agent and the identity; a pseudonym is a relation between two identities

- Status: Accepted
- Date: 2026-11-11
- Amends: [ADR 0058](./0058-a-mention-is-one-occurrence.md) §5 (one local
  node rule), [ADR 0041](./0041-resemblance-identity-shared-node.md)
  (name-only agents scoped to their record), [ADR 0061](./0061-an-agent-is-no-skos-concept.md)
  §1 and §3 (a fourth relation; the known inconsistency of an applied link)
- Builds on: [ADR 0059](./0059-record-identity-is-not-entity-identity.md)
  (a record is not the entity), [ADR 0057](./0057-the-identity-relation-between-persons.md)
  (the clustering)

## Context

Lot N1 gave each agent occurrence its own mention and id. The local agent
node was still minted from the record and the name (ADR 0041). Two fields
of one record that carry the same heading, "Martin, Jean" for a father and
a son, were one node. That is an identity asserted before any
reconciliation, by the name alone.

ADR 0061 §3 left a second problem. `apply-agent-links` gave an accepted
link the authority's node: an `E21_Person` whose IRI was the authority id.
When that id is a catalogue record's (a BnF ARK), the export typed the
record as the person. ADR 0059 says a record is not the entity.

Both come from one confusion: three things were one node. They are the
occurrence, the hypothetical agent it names, and the identified person.

## What the standards say

- **IFLA LRM** (2017). `LRM-E1` Res is anything. `LRM-E6` Agent is an
  entity able to act: a `LRM-E7` Person or a `LRM-E8` Collective Agent. An
  LRM Person is a real individual human being. `LRM-E9` Nomen is an
  association between an entity and a designation. In LRM a pseudonym is
  no Person: it is a Nomen of the Person who uses it. Two pseudonyms of one
  writer are two nomens of one Person.
- **RDA** keeps "bibliographic identities". A person who writes under
  several names may be recorded as several Persons, one per identity, each
  with its own access point, linked by "alternate identity" and "real
  identity" relations. The BnF and the GND do this: two authority records,
  linked by a pseudonym relation (the BnF's 331, the GND's `pseudonym` and
  `realIdentity`, read in lot N6, ADR 0059 §3).
- **CIDOC CRM** (7.1). `E39_Actor` is the class of agents; `E21_Person`
  and `E74_Group` are its subclasses. `E41_Appellation` is a name; an
  entity is identified by one through `P1_is_identified_by`. A pseudonym
  can be an `E41_Appellation` of an `E21_Person`, the LRM view. CRM has no
  property that says one person record is another's pseudonym.
- **Linked Art** models an actor (`Person`, `Group`) with its names
  (`identified_by`, each `Name` classified by its kind) and an `equivalent`
  for an external IRI of the same entity. It has no pattern for two
  identities of one person.

So LRM merges identities into one Person, and RDA keeps them apart. A
library catalogue in RDA practice states the identities and the relation.
It does not state the person behind them as a third record.

## Decision

### 1. Three levels

1. **The occurrence**: the mention. One field, one id, as in ADR 0058.
2. **The local agent**: a hypothetical person or group, minted **per
   occurrence**. It is an `E21_Person` or an `E74_Group` node of the
   record. Two occurrences never share one on the strength of the name.
3. **The identity**: what an authority record describes, or a cluster of
   local agents (ADR 0057). It is reached by reconciliation: an alignment
   a curator accepts, a cluster, or a curated identity between two local
   agents (§3). In the IR it is a `:reconcile/authority` entity (its IRI
   the authority id) or a `:reconcile/person` node. Neither is ever typed
   `E21_Person` or `E74_Group`.

### 2. The id rule

`mentions/local-agent`, `mentions/local-key` and `mentions/resolve-local`
(revised after the contradictory review of this lot):

- a name and class the record gives **once** keeps the id of the record
  and the name, `[record-id name]` (ADR 0041). So a name given once keeps
  the id it had;
- a name and class the record gives **more than once**, subjects and
  provenance included: each occurrence is keyed by its **content**,
  `[record-id name field relators dates-text name-parts n]`. `n` is an
  ordinal only among occurrences of exactly that content, in document
  order.

Inserting, removing or moving another field changes no other occurrence's
key. An edit of an occurrence, or a second occurrence of a name given once,
**orphans** the old id: no occurrence takes it. It is never re-pointed to
another occurrence. (A first version minted later occurrences from their
positional occurrence id; an inserted field then gave a removed or moved
occurrence's id to another one.)

**A local agent's IRI is not a stable identifier across re-imports.** An
edit of the record may orphan it. It must not be published as an identity.
The identity is what curation links it to: an authority, or a cluster.

Measured on the occurrences of every record (subjects and provenance
included), records counted once, against the rule before this lot:

| corpus | records | occurrences | ids changed | agent occurrences changed | records with a repeated agent name |
|-|-|-|-|-|-|
| `test/fixtures/documentary`, every spoke | 872 | 730 | 66 | 22 of 613 | 4 |
| M2–M8 eval (3 × 50 INTERMARC) | 150 | 227 | 11 | 2 of 199 | 0 |
| N3 design corpora (`records/`, not `records-test/`) | 900 | 1 278 | 70 | 64 of 1 260 | 25 |

The agent ids that change are those whose name the record also gives as a
subject, a provenance or a second agent.

Edits, measured on the 150 M2 INTERMARC records (the review's probe: each
record re-imported with one field inserted before its first 7XX; the agent
occurrences of the record before the edit, each looked up after it by its
field's content):

| edit | agent occurrences | ids changed | ids re-pointed to another occurrence |
|-|-|-|-|
| (A) a field of another name, same tag | 77 | 0 (positional rule: 49) | 0 (positional rule: 49) |
| (B) the first 7XX's name again, as a 702 | 76 | 25 | 0 (first version: 26) |
| (C) a 600 subject of a fresh name | 77 | 0 | 0 |

In (B) a name given once becomes a name given twice. Its id is orphaned,
as it must be: which of the two it was, the record no longer says by the
name.

A LIDO actor already had its occurrence's node (`lido-rules/agent-node`),
and an EAC-CPF record its described entity's: neither changes.

**A name alone does not say which occurrence it is.** A name-only proposal
(the historical engine) reaches a record that gives the name as an agent
once. It reaches no record that gives it twice: `local-agent` returns nil.
A proposal made for a mention reaches that mention's local agent, but only
when the occurrence the id names has that name and is an agent's (or the
described entity's). An id an earlier version of the record gave may name
another field now; then the name decides. When the name cannot decide
either, `align-agents` writes no claim and reports it on the record
(`:reconcile/unplaced-proposal`, `:several-occurrences`).

### 3. Two occurrences of one name are a proposal, never a fact

`mentions/same-agent-proposals` pairs the agent occurrences of one record
with exactly the same name and the same node class. Each pair is proposed
one agent unless:

- **the dates are incompatible**: both give life dates and their parsed
  profiles cannot overlap (`agent-dates/compatibility`: two births years
  apart). "1802-1870", "1802 - 1870", "1802–1870" and "18..-1870" are
  compatible. The texts are kept as evidence;
- **the name parts differ**: both state one name part (MARC21 `$b`, `$c`,
  `$q`) with two values: "Dumas, Alexandre, $c père" and "$c fils" are
  two persons. A part only one states is no conflict. The name parts are
  in the evidence;
- **the roles conflict**: both are in **one field**, both state relators,
  the relator sets differ, and they do not both give the same dates. One
  field is one MARC tag, one DC element, or, in LIDO, one event. The
  relators are the codes (`$4`), the role concept IRIs (LIDO), and the
  MARC21 `$e` terms the relator table holds (`mentions/marc-relator-terms`,
  from the Library of Congress's MARC Code List for Relators). A free term
  no table holds never conflicts. Within one field a record names one agent
  with two functions by repeating the whole heading, dates included (the
  BnF: "Bernard, Roland", 1927-2019, `$4` 0610 and 0570). One undated
  heading twice with two functions is all the record has to tell two
  homonyms apart. Across fields (a 100 and a 700; INTERMARC 723, 730, 731,
  732; two LIDO events) the format itself splits one agent's functions, so
  relators say nothing against one agent.

**Where proposals are made.** `agent-cluster/cluster-records` makes them
first, so every clustering of records writes them (and so does
`reconcile/propose-same-agents`, the same function). `palomar reconcile`
and `api/reconcile`, both engines, return them (`:same-agent-proposals`)
and print them after the decisions. `palomar reconcile clusters` gives each
as a candidate merge of the two mentions, never a join.

`reconcile/propose-same-agents` writes each proposal into the record: the
two local agents with their names, and a `:proposed`
`palomar:sameAgentAs` claim between them (`identity/same-agent`, the
fourth relation, kind `:local`). Its evidence is in its provenance
bindings: the record, the name, the two occurrences, the roles and dates
verdicts. A curator decides it:

- **accepted by a curator, that claim alone** (`identity/curated-claims`:
  `curate/decide-one`, signed), it is a must-link of the clustering
  (`agent-cluster/clustering-input`): the two local agents are one cluster.
  An acceptance by a policy over a batch (`curate/curate`, `apply-repairs
  --policy accept`, with or without `--by`) is a link for review, as a
  pending one: no one read the claim;
- **rejected**, it is a cannot-link (`:rejected-same-agent`): the two are
  never one cluster, even when both are aligned to one authority. It holds
  everywhere: two in-force claims of the two to one authority are a
  conflict for the export (`identity-check`: no `owl:sameAs` for either)
  and for `apply-agent-links` (neither applied, a conflict diagnostic on
  each, so neither identifies its agent);
- **pending**, it is a candidate for review: it joins nothing.

It is written as the internal relation in every RDF profile, never as
`owl:sameAs`, and not at all in a plain export while it is a proposal.

On the corpora above, every pair of repeated agent names gets a proposal:
4 on the fixtures, 44 on the N3 design corpora (Colette 100 and 700, both
0070; the Vogue and Musidisc labels in 723 and 730–732). One pair was a
counter-example to a first, stricter rule ("different relators in one field
are two persons"): Roland Bernard, above. The rule was changed for it.
The review's changes to the dates and roles rules move none of these
counts.

### 4. An applied link keeps the local agent

`apply-agent-links` no longer gives an accepted link the authority's node.
An applied link is an in-force `palomar:matchesAuthority` from the
occurrence's local agent to a `:reconcile/authority` entity. The local
agent keeps its node and its name. What each export writes for the claim
is ADR 0061's rule: `owl:sameAs` only for a curator's per-claim decision to
an IRI that names the entity, else the internal relation. **No authority
IRI, a catalogue record's or an entity's, is ever typed `E21_Person` or
`E74_Group` by Palomar's reconciliation.**

- A link names its local agent (`accepted-links` gives `:local`). A
  corpus-wide link (`:records :all`) applies by name only where the record
  gives the name once (else `:skipped`, `:several-occurrences`).
- A claim already in force is applied as it is. A link never decides a
  claim a curator has not decided: a pending claim is skipped
  (`:pending-here`), one for review too (`:needs-review`), a rejected one
  is never overridden (`:rejected-here`). A claim the link adds is
  `:accepted`, unsigned, so no `owl:sameAs` by itself.
- A conflict is refused: two links to one authority whose dates cannot be
  one person's, or whose local agents the clustering is given as a
  cannot-link (a rejected `sameAgentAs`). Each claim of it gets a
  `:reconcile/apply-identity-conflict` diagnostic, restated on every run.
  It is one of `identity/conflict-codes`, so the claim is no `owl:sameAs`
  and identifies no one.
- **Older records.** A record an older `apply-agent-links` wrote holds an
  `E21_Person` (or `E74_Group`) whose `:iri` is the authority id, its
  provenance the rule `:reconcile/apply-agent-link`. Once an in-force claim
  from a local agent to that authority is in the record (re-applying the
  record's own accepted links gives it), that node is removed and its
  claims retracted (`:retracted`, kept in the history). The new output no
  longer types the record IRI.

`agents/identified` reads both forms: an agent the source identifies (an
entity with an `:iri`: the INTERMARC ISNI of the 100) and a local agent an
in-force claim identifies, which no conflict names (`:via :claim`).
`reconcile-agents` groups both by the authority IRI.

### 5. Exports

- **CIDOC CRM and the RDF profiles**: the local agent is an `E21_Person`
  (or `E74_Group`) with its name through an `E41_Appellation`, and the
  claim to the authority by ADR 0061's rule. The authority IRI is untyped.
- **Linked Art**: a creator name given by two creator occurrences is two
  `Person`s, each its local agent's node. A name given once is one
  `Person` as before; when a claim identifies it, its `id` is the local
  agent's and the authority is its `equivalent` under ADR 0061's gates. A
  LIDO production already wrote one actor per occurrence.
- **MARC21**: the main entry is one occurrence. Another agent of its name
  (a contributor) is now a 700, not dropped as a double of the 100. That
  700 carries its relator, so the two fields say which is which: from a
  MARC21 source, the occurrence's `$e` and `$4`; from any other, `$e` the
  floor's role (the source's codes are of another scheme).
- **DC, MODS**: unchanged. They write the floor's names.

**Two counts.** Linked Art counts occurrences. The floor-based exports
(DC, MODS, MARC21) count distinct facts. The canonical floor is unchanged
(ADR 0058, alternatives): two identical values of one role are one floor
value (ADR 0008). So for two `dc:creator` "Martin, Jean", Linked Art writes
two actors and DC, MODS and MARC21 one name. They report it: an
`:ambiguity-collapsed` export loss on `:canon/agent`
(`export-coverage/occurrence-losses`) whenever the source gives a name and
role more often than the floor holds it.

### 6. Pseudonym, persona and person

- **An authority record describes an identity.** In RDA's sense: a
  bibliographic identity, which may or may not be the whole of a person.
  Palomar's level 3 is that identity, not an LRM Person.
- **A pseudonym relation is a stated relation between two identities**
  (the BnF's 331, the GND's `pseudonym` and `realIdentity`). It is the
  records' statement, never Palomar's inference.
- **Palomar never merges two identities into one person on its own.** A
  pseudonym relation is read only as evidence that two records are *not*
  one entity (ADR 0059 §3). Two identities become one cluster only by a
  curator's decision, as any identity does.
- **Exports write no pseudonym relation for now.** CIDOC CRM has no
  property between two persons for it; `P1_is_identified_by` to an
  `E41_Appellation` is the LRM view, which would make the two identities
  one person. Linked Art has no pattern for it. When the relation is put in
  the IR, the RDF profiles will write it as a Palomar relation, never
  `owl:sameAs`, and Linked Art will write nothing.

## What is implemented, what is only decided

Implemented in this lot (tests in `palomar.local-agents-test`, the updated
`palomar.reconcile-test`, `palomar.identity-test` and the full suite):

- the per-occurrence local agent, its content-keyed id rule, and the
  stale-mention guard (§2);
- the same-name proposal, its rule, its claim, where it is made, and its
  curation in the clustering (§3);
- the applied link on the local agent, its conflicts, its refusal to
  decide a pending claim, and the retraction of an older applied node (§4);
- the Linked Art and MARC21 changes and the floor exports' loss (§5).

Only decided:

- the pseudonym relation in the IR and its export (§6): the relation is
  read from authority files (lot N6) but not stated on Palomar's nodes;
- **LIDO actor IRIs**: a LIDO actor with an authority URI is still the node
  of that URI (`lido-rules/agent-node`, post-audit 8, K5), typed by the CRM
  view. That is the source's statement of its actor's identifier, as
  Linked Art does it, and it is out of this lot's reach. A ULAN subject IRI
  there names a concept (ADR 0061 gate 5). Moving it to an `equivalent` is
  left to a later lot;
- **an older applied node no claim covers**: an older record whose node
  has no in-force claim from a local agent (an older `:records :all` link
  never re-applied) keeps it, and it is still identified (`:via :source`)
  until the link is applied again.

## Alternatives considered

- **Mint every local agent from its occurrence id.** Simplest and fully
  positional, but it changes every node id, and an inserted field gives a
  moved occurrence's id to another (edit (A): 49 of 77).
- **Keep `[record-id name]` for the first occurrence, the occurrence id for
  the others** (this lot's first version). A same-name field inserted first
  takes the old occurrence's id (edit (B): 26 re-pointed). The content key
  orphans instead.
- **Keep one node per name and record, and split on evidence.** It asserts
  the identity first and asks for a reason to undo it. The proposal does
  the reverse, as ADR 0041 asks.
- **Different relators in one field always mean two persons.** Refuted on
  the BnF data (§3).
- **Type the authority IRI `E21` only when it names the entity** (ISNI,
  VIAF…). The IRI would still be the node of every record's agent, so two
  records' local agents would be one node, decided by the link and not by
  the clustering. `owl:sameAs` already says it, where it holds.
- **Model a pseudonym as an `E41_Appellation` of one person** (LRM). The
  catalogues keep two identities on purpose. Merging them is a decision,
  and no source Palomar reads makes it.

## Consequences

- No two occurrences share a local agent by name. A record that gives one
  name twice holds a proposal a curator can see and decide.
- No reconciliation output types an authority IRI as a person. ADR 0061's
  known inconsistency is closed for reconciliation.
- Ids change only for repeated names and for subjects that share an
  agent's name (table above). An edit orphans an id; it never re-points
  one. No eval number moves.
- A name-only link no longer reaches a record that gives the name twice.
- `agents/identified` returns `:via`; Linked Art and `conformance` read the
  claim form as identified.
