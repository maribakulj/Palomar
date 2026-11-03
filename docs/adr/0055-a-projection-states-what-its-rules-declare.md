# 0055 — A projection states as facts only the relations its rules declare

- Status: Accepted
- Date: 2026-11-03
- Builds on: [ADR 0042](./0042-facts-only-by-default.md), [ADR 0052](./0052-museum-objects-from-lido.md)

## Context

The seventh post-audit review found a relation Palomar invented. A LIDO
record with a design and a production stated that the production used the
design (`P33_used_specific_technique`), although LIDO links no event to
another. Every class and property was right, so the ontology checks were
green. Every source value was written, so the fidelity audit was green too.
The audit checks what is missing, not what is added: a relation the source
does not support passes it.

The review's conclusion: positive inferences need the same scrutiny as
omissions.

## Decision

1. **A relation the source does not support is no fact.** It is a proposal
   (ADR 0042), with its own rule and a confidence below any promotion
   threshold, which a plain export withholds and a dataset keeps in its
   proposals graph. A design's use by a production is one (ADR 0052,
   amended, post-audit 7, I1).
2. **Each projection rule declares the relations it states as facts**
   (`palomar.inferences/declared`), in two kinds:
   - those it *transcribes*: the source says them, in another model (a LIDO
     production is the object's `P108i`; an EAD component is included in
     its parent);
   - those it *infers*: the source does not say them in so many words, a
     reading of this projection does, and each carries its reason. Today:
     - LIDO: an event of the object it was present at (`P12i`); who carried
       an activity out (`P14`) or took part in it (`P11`), and who gave or
       received an acquisition's title (`P23`, `P22`), by the words of its
       role; the object's materials from an event of no stated kind
       (museum-digital's practice, `P45`); a repository set not typed as
       former as the current keeper (`P50`); a picture's subjects as what
       it depicts (`P62`);
     - EAD: every name of an origination as a creator (`rico:hasCreator`),
       which RiC-O tells from an accumulator or a collector;
     - INTERMARC: the Work Palomar mints from the author's authority and
       the uniform title, which the record realises (`R3`, `R4`, ADR 0013).
     The LRMoo floor proposes its links (`R3`, `R4`) and states none.
3. **What no rule declares is found.** `palomar.inferences/undeclared` lists
   the asserted relations of a projection that no rule declares. The
   fidelity audit reports them (`:inferences {:undeclared …}`), each rule
   that derives a relation, its own and its alternatives' (`:also`),
   answering for it; and with
   them how many relations each declared inference stated (`:inferred`):
   what a user takes on trust. The test suite holds every corpus, and a LIDO
   record with every kind of event and role, to no undeclared relation. A
   new rule, or a rule that begins to state a new relation, fails until it
   says which kind it is.
4. **The same review's other unattested facts are corrected.** These are
   now reported, or stated with the right property (ADR 0052, amended,
   post-audit 7, I3):
   - a former repository given as the current keeper;
   - an object said to be present at its own commissioning or planning;
   - a seller said to have carried out an acquisition. A role is read by
     its whole words, and one who both gave and received, or neither, is
     not said to have done either.
   A former repository is told by a whole word of its `lido:type`, and one
   also said current is current; MARC writes it in `561`, custodial
   history, not as the `852` location, and reports that.

## Consequences

- An inference is visible and argued: a reviewer reads its reason in one
  place, and the audit counts how often it applied.
- The declaration is per rule and predicate, not per relation. A rule may
  still misuse a declared relation, as `:rule.lido/project` did with
  `P33`, which it now only proposes. That is for review and tests to catch.
  The registry makes the question asked, not answered.
- Mapping rules (`:normalize`) and importers (`:ingest`) transcribe by
  construction and are not listed. Entities and literal values are not
  looked at: this is about relations. A proposal a curator accepted is a
  decision of theirs (ADR 0018), not a rule's, and is not looked at either.
