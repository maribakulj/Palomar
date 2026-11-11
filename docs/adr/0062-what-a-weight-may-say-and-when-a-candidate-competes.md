# 0062 — What a weight may say, and when a second candidate competes

- Status: Accepted
- Date: 2026-11-11
- Amends: [ADR 0018](./0018-entity-resolution-at-scale.md) (amendment M4:
  the weights and the decision), [ADR 0058](./0058-a-mention-is-one-occurrence.md)
  (amendment N4: its limits are resolved)
- Builds on: [ADR 0060](./0060-candidate-generation-is-not-ranking.md)
  (an unnamed candidate never competes)

## Context

Lot N4 found three defects of the Fellegi-Sunter model
(`palomar.agent-match`), on the hard benchmark of transcribed mentions:

1. **The variant cliff.** The form weight (heading or variant) was added
   at every name level. In the N3 model a variant weighs +5.18. A
   candidate reached through a variant whose name disagrees got that
   weight on a name weight of 0: a posterior of 0.0976. On dev, 89 of 206
   mentions had a competitor just there.
2. **Monotone flattening.** The name weights must not increase along the
   name levels. The rule took a running minimum over every level, and an
   unmeasured level weighs 0. Two unmeasured levels, `:same-tokens` and
   `:surname-typo`, capped every level below them at 0, so measured
   agreements weighed nothing: `:forename-overlap` (+4.80 measured) and
   `:surname-only` (+6.43).
3. **The absolute competing rule.** Any second candidate at or above
   review sent the mention to review as `:competing`, however far below
   the top one it was. So lifting the cap alone lost links: more namesakes
   passed review, and each sent its mention to review (165 → 99 on dev).

## Decision

1. **The form weighs only on an exact name.** Under `:form :exact-name`,
   the form weight is added only at `:exact`
   (`agent-match/form-weighed-levels`). Its evidence is that the mention
   uses that very form, a variant or a pseudonym. Not at `:initials`: a
   variant would win between equal initials matches. Not at
   `:same-tokens`: a surname and a forename may be swapped. Below, a word
   differs and the form read is only the closest one.
2. **The chain runs over the measured levels.** A level is measured when
   at least 5 pairs (`min-observed`) reached it on both sides, or 5 on one
   side with a bound that proves something (`agent-match/level-measured?`).
   Under `:chain :measured-levels`, the name weights never increase over
   the measured levels. An unmeasured level is skipped and caps none below
   it. It weighs 0; a level that says the surnames agree
   (`agent-match/surname-levels`) takes the next measured weight below it
   when that is above 0. It never weighs more than the measured level
   above it. So `:same-tokens`, which may not say the surnames agree,
   stays 0 when unmeasured. A disagreeing level still never weighs more
   than 0.
3. **A second candidate competes only when it is close.** A model may
   state a gap G in bits (`:thresholds :gap`): a finite number above 0,
   or nothing; any other value is refused when the model is read, and the
   CLI exits 2. A second candidate at or above review competes only when
   its weight is less than G bits below the top one's
   (`agent-match/competes?`). A tie always competes, whatever the gap, so a
   tie never gives two outright proposals. With no close competitor, the
   top candidate is decided on its own thresholds, and another candidate
   above propose is for review (`:review-reason :outranked`); one between
   the thresholds keeps its own review. A model with no gap keeps the
   absolute rule. An unnamed candidate (ADR 0060) never makes a named one
   compete, and is never the top the gap is measured from. The rule is in
   `reconcile/scored-candidates`, the per-mention step only.
4. **A model states its rules.** `agent-match/o4-rules` are rules 1 and 2;
   `agent-match/n4-rules` the old ones. A parameter file states `:rules`;
   one that states none predates O4 and follows `n4-rules`, and its model
   is exactly the one N4 built (same version). Rules that are not a map, or
   an unknown rule or value, are refused. Both shipped files state
   `o4-rules`; the model holds them, so its fingerprint and version name
   them.
5. **G is chosen like the thresholds.** By `n4/gap-criterion`, written
   before any number was seen: on the held-out folds of train, for each G
   of 0.5, 1, 2, 3, 4, 6, 8, 12 bits and no gap, the thresholds by N4's
   criterion; the admissible G with the highest recall; on a tie, the
   largest. The N3 model gets G = 12, by the tie rule, at propose 0.5 and
   review 0.5. The shipped M2 model's file states no gap.

## What the measurements show

`docs/eval/person-reconciliation-hard.md`, section O4. On the hard
benchmark, the three rules together make the same links as N4 on the
folds (554) and on dev (165), and no new false merge. They move one
mention (10 copies, "Pierre P. Loti-Viaud") from abstention to review on
the folds, through the chain. The variant cliff at 0.0976 is gone, but the
chain makes a new cluster of second candidates (0.10 on dev, 0.24 and 0.28
on the folds). The gap binds on one mention. Each weight fix alone
changes nothing or loses links; the relative rule alone changes nothing.
On the M2 gold held out by family, a gap frees up to 25 outright links (6
names) with no false merge; no gap is recommended there before O5.

## Alternatives considered

- **Pool an unmeasured level with a neighbour's counts.** Which neighbour
  is a choice the data does not make, and pooled counts would be stored as
  if observed. Skipping keeps the counts as measured.
- **Let an unmeasured level take the next measured weight below it, even
  when negative.** On the M2 model this makes `:initials` (unmeasured)
  weigh like `:jaccard-low` (−1.91). Floored at 0, it stays "no evidence".
- **Raise every unmeasured level.** `:same-tokens` would take
  `:forename-overlap`'s +4.80, though a swapped surname and forename is no
  surname agreement.
- **The form at `:initials` and `:same-tokens`** (the first O4 version).
  A variant then wins between equal initials matches.
- **A posterior ratio instead of a weight gap.** Near 1 a posterior ratio
  says little (0.99 against 0.999); a weight gap is the evidence itself.
- **Estimate the form conditionally on the name level.** Better, but the
  model files hold counts per feature, not joint counts. Left for later.
- **Lift the disagreeing-level clamp too** (`:forenames-differ` measured
  +2.34 on N3). A disagreement is not evidence for; the clamp stays.

## Consequences

- Both shipped files state the O4 rules; their versions change. The M2
  model's weights are unchanged. The N3 model states G = 12 and has no
  review band (0.5 / 0.5).
- A file that states no rules keeps the N4 behaviour and version.
- The M4 stress set has 4 more ties (Hetzel against Stahl), which are now
  counted as ties.
- Whether the M2 model gets a gap is left to O5.
- `reconcile explain` says when a candidate was within, or beyond, the
  gap.
