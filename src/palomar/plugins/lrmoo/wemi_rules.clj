(ns palomar.plugins.lrmoo.wemi-rules
  "The floor WEMI chain written as `:construct` rules (ADR 0035): the data
   form of what `palomar.plugins.lrmoo.project` computes, for a record with
   one title and at most one creator.

   - the Manifestation is minted from the record id and carries the
     transcribed title, `:asserted` (it is transcription);
   - the Expression is keyed by the creator (or nothing) and the matching form
     of the uniform title, else the title; it carries that string, and the
     Manifestation embodies it;
   - the Work, only when there is a creator, is keyed the same way, carries the
     string and is realised in the Expression.

   A creator is an agent whose role is `:creator`, or, when no agent has that
   role, an agent with no role: the second rule says so with `(not …)`.

   `project` stays the production path. Three of its choices are not
   expressible in a triple-pattern language without an order-dependent
   builtin, which ADR 0002 rules out: the *first* of several titles, the
   *first* of several creators, and a single title language joining the
   Expression key; and it attaches the titles' source evidence (ADR 0031) to
   the strings. An equivalence test (`wemi-rules-test`) shows the two agree on
   every fixture record those choices do not touch."
  (:require [palomar.rules :as rules]))

(def ^:private key-title
  "The string that keys the Work and Expression: the uniform title, else the
   transcribed one."
  '(or ?ut ?t))

(def ^:private chain
  "Expression and Work for a record whose creator is `?agent`."
  [['(mint :lrmoo/F3_Manifestation (str ?r)) :lrmoo/R4_embodies (list 'mint :lrmoo/F2_Expression '?agent (list 'norm key-title))]
   [(list 'mint :lrmoo/F2_Expression '?agent (list 'norm key-title)) :lrmoo/R33_has_string key-title]
   [(list 'mint :lrmoo/F1_Work '?agent (list 'norm key-title)) :lrmoo/R3_is_realised_in (list 'mint :lrmoo/F2_Expression '?agent (list 'norm key-title))]
   [(list 'mint :lrmoo/F1_Work '?agent (list 'norm key-title)) :lrmoo/R33_has_string key-title]])

(def ^:private titled
  "The record's transcribed title and, if any, its uniform title."
  ['[?r :canon/title ?t] '(string? ?t)
   '(optional [?r :canon/uniform-title ?ut] (string? ?ut))])

(def ^:private stated-creator
  '(not [?r :canon/agent (ref ?g)] [?g :canon/role :creator]))

(def rules
  "The WEMI chain as `:infer` rules (see the ns doc)."
  [{:id      :rule.lrmoo.wemi/manifestation
    :phase   :infer
    :doc     "Every record is embodied by a Manifestation, which carries its transcribed title."
    :match   ['[?r :meta/id ?r] '(optional [?r :canon/title ?t] (string? ?t))]
    :produce {:construct ['(mint :lrmoo/F3_Manifestation (str ?r))
                          ['(mint :lrmoo/F3_Manifestation (str ?r)) :lrmoo/R33_has_string '?t {:status :asserted}]]}}

   {:id      :rule.lrmoo.wemi/stated-creator
    :phase   :infer
    :doc     "A Work and Expression keyed by the agent whose role is :creator."
    :match   (into ['[?r :meta/id ?r]] (concat titled ['[?r :canon/agent (ref ?f)] '[?f :canon/role :creator]
                                                       '[?f :canon/agent ?agent] '(string? ?agent)]))
    :produce {:construct chain}}

   {:id      :rule.lrmoo.wemi/unstated-creator
    :phase   :infer
    :doc     "With no agent of role :creator, an agent of no stated role keys the Work."
    :match   (into ['[?r :meta/id ?r]] (concat titled ['[?r :canon/agent ?agent] '(string? ?agent) stated-creator]))
    :produce {:construct chain}}

   {:id      :rule.lrmoo.wemi/no-creator
    :phase   :infer
    :doc     "With no creator, the Expression is keyed by the title alone, and there is no Work."
    :match   (into ['[?r :meta/id ?r]]
                   (concat titled [stated-creator
                                   '(not [?r :canon/agent ?a] (string? ?a))]))
    :produce {:construct [['(mint :lrmoo/F3_Manifestation (str ?r)) :lrmoo/R4_embodies (list 'mint :lrmoo/F2_Expression "" (list 'norm key-title))]
                          [(list 'mint :lrmoo/F2_Expression "" (list 'norm key-title)) :lrmoo/R33_has_string key-title]]}}])

(def compiled
  "`rules`, compiled."
  (rules/compile-rules rules))
