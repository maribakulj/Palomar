(ns palomar.agent-equivalence
  "Record identity is not entity identity (lot N6, ADR 0059). An authority
   record has an id in its file (a BnF ARK, a GND id); the person it
   describes is another thing. Two records — in two files, or duplicates in
   one — describe one person only when **the records state it**:

   - both state one ISNI, or one VIAF id (or any other id: a Wikidata item);
   - one gives the other's id as a `sameAs` (an `owl:sameAs`, a
     `skos:exactMatch`, a MADS exact external authority, a lobid GND
     `sameAs`), in whatever spelling of its IRI.

   Nothing else is read: no name, no date, no close match. The statements of
   an item (`cluster`'s items) are its `:iri` (its own id), its `:isni` (a
   string or several, as INTERMARC's 031 gives them), its `:same-as` (IRIs:
   the GND's links to VIAF and ISNI are there too), and its pseudonym
   relations: `:pseudonym` (the records that are its pseudonyms) and
   `:real-identity` (the records whose pseudonym it is). Every id is read in
   one form (`identifier-key`); an ISNI whose check character is wrong is no
   id, and is reported.

   **Transitivity.** The equivalence is the union of what is stated: the
   records and the ids they state are a graph, and two records in one
   component are one entity. A component is **refused** — none of its
   equivalences is used, each record stays its own entity — when:
   - two of its records state ISNIs, share none, and neither names the
     other: ISNI says they are two identities, and only a chain through
     other ids joins them (`:isni-clash`). When one names the other
     directly, the pair is kept and the disagreement reported: duplicate
     ISNIs are common;
   - it holds a record and a record that the files state is its pseudonym
     (`:pseudonym-in-component`);
   - it is the pseudonym of another component, and that one its pseudonym,
     by the records' statements (`:pseudonym-crossed`): both are refused.
   Other disagreements are reported, never refused: two records stating
   disjoint ids of another scheme (VIAF, GND, LCNAF, Wikidata), two records
   of one file made one (a duplicate).

   Only what the batch holds is read: there is no network lookup and no
   store across batches."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Identifiers
;; ---------------------------------------------------------------------------

(defn- isni-check
  "The ISO 7064 MOD 11-2 check character of the 15 digits `d`."
  [d]
  (let [t (reduce (fn [t c] (* 2 (+ t (Character/digit (char c) 10)))) 0 d)
        r (mod (- 12 (mod t 11)) 11)]
    (if (= 10 r) \X (char (+ 48 r)))))

(defn- isni-digits
  "The 16 characters of ISNI-like `s` (a prefix `ISNI`, spaces and hyphens
   dropped, `x` upper-cased), or nil when they are not 15 digits and a
   digit or X."
  [s]
  (let [d (-> (str s) str/trim (str/replace #"(?i)^isni[:\s]*" "") (str/replace #"[\s-]" "") str/upper-case)]
    (when (re-matches #"\d{15}[\dX]" d) d)))

(defn- isni-identifier
  "`{:key \"isni:…\"}` for a valid ISNI, `{:invalid s}` for one whose check
   character is wrong or that is no ISNI."
  [s]
  (let [d (isni-digits s)]
    (if (and d (= (isni-check (subs d 0 15)) (.charAt ^String d 15)))
      {:key (str "isni:" d)}
      {:invalid s})))

(def ^:private end "(?=[/#?.]|$)")

(def ^:private forms
  "The id forms read, each a pattern (anchored at its start and its end)
   and the identifier of its match."
  [[(re-pattern (str "(?i)^(?:https?://)?(?:www\\.)?(?:isni\\.org|isni-url\\.oclc\\.nl)/isni/([0-9X ]+?)" end))
    (fn [[_ n] s] (let [r (isni-identifier n)] (if (:key r) r {:invalid s})))]
   [#"(?i)^isni(?:[:\s]|\d)" (fn [_ s] (isni-identifier s))]
   [(re-pattern (str "(?i)^(?:https?://)?(?:www\\.)?viaf\\.org/(?:[a-z]{2}/)?viaf/(\\d+)" end))
    (fn [[_ n] _] {:key (str "viaf:" n)})]
   [(re-pattern (str "(?i)^(?:https?://[^/]+/)?ark:/?(\\d+)/([0-9a-z]+)" end))
    (fn [[_ naan id] _] {:key (str "ark:/" naan "/" (if (= "12148" naan) (str/lower-case id) id))})]
   [(re-pattern (str "(?i)^(?:https?://)?data\\.bnf\\.fr/(?:[a-z]{2}/)?(\\d{8})" end))
    (fn [[_ n] _] {:key (str "bnf-record:" n)})]
   [(re-pattern (str "(?i)^(?:https?://)?(?:d-nb\\.info|lobid\\.org)/gnd/(\\d[0-9X-]*)" end))
    (fn [[_ n] _] {:key (str "gnd:" (str/upper-case n))})]
   [(re-pattern (str "(?i)^(?:(?:https?://)?id\\.loc\\.gov/(?:authorities/names|rwo/agents)|info:lc/authorities(?:/names)?)/([a-z]+\\d+)" end))
    (fn [[_ n] _] {:key (str "lcnaf:" (str/lower-case n))})]
   [(re-pattern (str "(?i)^(?:https?://)?(?:www\\.)?wikidata\\.org/(?:entity/|wiki/(?:Special:EntityPage/)?)(Q\\d+)" end))
    (fn [[_ n] _] {:key (str "wikidata:" (str/upper-case n))})]])

(defn identifier
  "What `s` names: `{:key k}` (see `identifier-key`), `{:invalid s}` for an
   ISNI whose check character is wrong, or nil."
  [s]
  (when-let [s (some-> s str str/trim not-empty)]
    (or (some (fn [[re f]] (when-let [m (re-find re s)] (f (if (string? m) [m] m) s))) forms)
        (when (re-matches #"\d[\d\s-]{13,}[\dXx]" s) (isni-identifier s))
        (when (re-find #"^[A-Za-z][A-Za-z0-9+.-]*:" s) {:key s}))))

(defn identifier-key
  "The one form of id `s`: `isni:` and 16 characters (from an ISNI URI, an
   `isni:` or `ISNI` string, or an ISNI as a record writes it, its check
   character valid), `viaf:N`, `ark:/NAAN/id` (the BnF catalogue,
   data.bnf.fr and n2t alike), `bnf-record:N` (a data.bnf.fr page URL),
   `gnd:id` (d-nb.info and lobid), `lcnaf:id`, `wikidata:Q..`; another IRI
   is itself; anything else is nil. Each id ends where the IRI's path does
   (`/`, `#`, `?`, `.` or the end)."
  [s]
  (:key (identifier s)))

(defn- own-keys
  "The ids item `it` is known by: its IRI's, and, for a BnF ARK, the record
   number a data.bnf.fr page URL gives (`bnf-record:N`)."
  [it]
  (when-let [k (identifier-key (:iri it))]
    (cond-> [k]
      (re-matches #"ark:/12148/cb(\d{8})[0-9a-z]" k)
      (conj (str "bnf-record:" (second (re-matches #"ark:/12148/cb(\d{8})[0-9a-z]" k)))))))

(defn- file-of
  "The file item `it` is a record of: its ARK's authority (`ark:/NAAN`),
   its id's scheme, else its IRI's host."
  [it]
  (when-let [k (first (own-keys it))]
    (or (second (re-matches #"(ark:/\d+)/.*" k))
        (second (re-matches #"(isni|viaf|gnd|lcnaf|wikidata|bnf-record):.*" k))
        (some->> (re-find #"^[A-Za-z][A-Za-z0-9+.-]*://([^/#?]+)" k) second str/lower-case)
        k)))

(defn- many [v] (if (or (nil? v) (string? v)) (keep identity [v]) v))

(defn- statements
  "What authority item `it` (id `id`) states of its entity: `{:stated
   [{:by id :states key} ..] :invalid [{:kind :invalid-identifier :by id
   :value v} ..]}`, its ISNIs and its `sameAs` ids, its own ids left out."
  [id it]
  (let [own (set (own-keys it))
        rs  (concat (map (fn [v] [v (isni-identifier v)]) (many (:isni it)))
                    (map (fn [v] [v (identifier v)]) (many (:same-as it))))]
    {:stated  (vec (distinct (for [[_ r] rs :let [k (:key r)] :when (and k (not (own k)))] {:by id :states k})))
     :invalid (vec (distinct (for [[v r] rs :when (:invalid r)] {:kind :invalid-identifier :by id :value v})))}))

(defn authority-keys
  "Every id authority entry `e` is known by or states, in `identifier-key`
   form, sorted (lot N5, the identifier lane of `palomar.candidates`): its
   own (`:iri`, else `:id`; a BnF ARK also as `bnf-record:N`), its valid
   ISNIs (`:isni`) and its `:same-as` ids. An invalid ISNI is no id."
  [e]
  (let [it {:iri (or (:iri e) (:id e)) :isni (:isni e) :same-as (:same-as e)}]
    (vec (sort (distinct (concat (own-keys it) (map :states (:stated (statements nil it)))))))))

(def ^:private noid-digits "0123456789bcdfghjkmnpqrstvwxz")

(defn bnf-ark
  "The catalogue ARK of BnF record number `n` (8 digits, as an INTERMARC
   `$3` gives it): `http://catalogue.bnf.fr/ark:/12148/cb` + `n` + its NOID
   check character, computed over `cb` + `n`. Nil for anything else."
  [n]
  (when-let [n (some->> n str str/trim (re-matches #"\d{8}"))]
    (let [s (str "cb" n)
          t (reduce + (map-indexed (fn [i c] (* (inc i) (max 0 (str/index-of noid-digits c)))) s))]
      (str "http://catalogue.bnf.fr/ark:/12148/" s (nth noid-digits (mod t 29))))))

;; ---------------------------------------------------------------------------
;; The graph
;; ---------------------------------------------------------------------------

(defn- by-str [xs] (sort-by str xs))

(defn- adjacency
  "The graph of `stated` (`{id statements}`) over `items`: nodes `[:item
   id]` and `[:key k]`; an item is joined to its own ids (no statement) and
   to each id it states."
  [items stated]
  (let [edges (for [[id it] items
                    e (concat (for [k (own-keys it)] [[:key k] nil])
                              (for [s (stated id)] [[:key (:states s)] s]))]
                [[:item id] e])]
    (reduce (fn [g [a [b s]]] (-> g (update a (fnil conj []) [b s]) (update b (fnil conj []) [a s]))) {} edges)))

(defn- component
  "The nodes reachable from `start` in graph `g`."
  [g start]
  (loop [seen #{start} todo [start]]
    (if-let [n (peek todo)]
      (let [new (remove seen (map first (g n)))]
        (recur (into seen new) (into (pop todo) new)))
      seen)))

(defn- path-statements
  "The statements on a shortest path from `a` to `b` in `g` (breadth first,
   neighbours in a fixed order), in the path's order."
  [g a b]
  (loop [frontier [a] prev {a nil}]
    (if (contains? prev b)
      (loop [n b out ()]
        (if-let [[p s] (prev n)] (recur p (if s (cons s out) out)) (vec out)))
      (let [step  (for [n frontier [m s] (by-str (g n)) :when (not (contains? prev m))] [m [n s]])
            prev' (reduce (fn [acc [m v]] (if (contains? acc m) acc (assoc acc m v))) prev step)]
        (if (= prev prev') [] (recur (vec (distinct (map first step))) prev'))))))

(defn- pairs [ms] (for [i (range (count ms)) j (range (inc i) (count ms))] [(ms i) (ms j)]))

(defn- sorted-by-str [m] (into (sorted-map-by #(compare (str %1) (str %2))) m))

;; ---------------------------------------------------------------------------
;; Equivalences
;; ---------------------------------------------------------------------------

(defn- relations
  "The pseudonym relations `items` state among themselves, `[{:by :pseudonym
   :of} ..]` (`:pseudonym` is a pseudonym of `:of`): from an item's
   `:pseudonym` and `:real-identity`, each target resolved to the item it
   names (`identifier-key`); a target outside `items` is left out."
  [items]
  (let [by-key (into {} (for [[id it] items k (own-keys it)] [k id]))
        item   #(by-key (identifier-key %))]
    (vec (distinct (concat (for [[id it] items p (many (:pseudonym it)) :let [y (item p)] :when (and y (not= y id))]
                             {:by id :pseudonym y :of id})
                           (for [[id it] items r (many (:real-identity it)) :let [y (item r)] :when (and y (not= y id))]
                             {:by id :pseudonym id :of y}))))))

(defn equivalences
  "The stated equivalences among the authority items of `items` (`{id
   item}`, `cluster`'s, those with `:authority? true`; see the ns doc):

     {:entity    {id entity}  ; the items of one accepted component, two or more
      :links     [{:a :b :basis :equivalence :evidence [{:by :states} ..]} ..]
      :conflicts [{:reason :isni-clash :members :isni :pairs :evidence}
                  {:reason :pseudonym-in-component :members :relations}
                  {:reason :pseudonym-crossed :members :with :relations} ..]
      :warnings  [{:kind :isni-disagreement :members :isni}
                  {:kind :scheme-disagreement :scheme :members :ids}
                  {:kind :same-file :file :members}
                  {:kind :invalid-identifier :by :value} ..]}

   A link joins every two items of an accepted component, with the
   statements of the shortest chain between them, or `[{:same-record-id
   k}]` when the two are one record under two spellings. Sorted, and the
   same whatever the order of `items`."
  [items]
  (let [auths  (sorted-by-str (filter (comp :authority? val) items))
        st     (into {} (for [[id it] auths] [id (statements id it)]))
        stated (update-vals st :stated)
        g      (adjacency auths stated)
        comps  (loop [left (by-str (keys auths)) seen #{} out []]
                 (if-let [id (first left)]
                   (if (seen [:item id])
                     (recur (rest left) seen out)
                     (let [c (component g [:item id])]
                       (recur (rest left) (into seen c) (conj out (vec (by-str (keep (fn [[k v]] (when (= :item k) v)) c)))))))
                   out))
        own    (fn [id] (set (own-keys (auths id))))
        keys-of (fn [id] (into (own id) (map :states (stated id))))
        scheme (fn [id s] (vec (sort (filter #(str/starts-with? % (str s ":")) (keys-of id)))))
        isnis  #(scheme % "isni")
        direct? (fn [a b] (or (some (own b) (map :states (stated a))) (some (own a) (map :states (stated b)))))
        clashes (fn [ms] (for [[a b] (pairs ms)
                               :let [x (set (isnis a)) y (set (isnis b))]
                               :when (and (seq x) (seq y) (not-any? x y))]
                           [a b]))
        comp-of (into {} (for [[i ms] (map-indexed vector comps) m ms] [m i]))
        rels    (relations auths)
        rel-of  (fn [i j] (filter #(and (= i (comp-of (:pseudonym %))) (= j (comp-of (:of %)))) rels))
        inner   (into {} (for [[i ms] (map-indexed vector comps)
                               :let [rs (vec (rel-of i i))]
                               :when (and (< 1 (count ms)) (seq rs))]
                           [i rs]))
        dirs    (set (for [r rels :let [i (comp-of (:pseudonym r)) j (comp-of (:of r))] :when (not= i j)] [i j]))
        crossed (into (sorted-map) (for [[i j] dirs :when (and (dirs [j i]) (< 1 (count (comps i))))]
                                     [i (vec (concat (rel-of i j) (rel-of j i)))]))
        partner (fn [i] (vec (by-str (distinct (mapcat (fn [[a b]] (when (= a i) (comps b))) dirs)))))
        isni-bad (into {} (for [[i ms] (map-indexed vector comps)
                                :let [cs (vec (remove (fn [[a b]] (direct? a b)) (clashes ms)))]
                                :when (seq cs)]
                            [i cs]))
        refused (into #{} (concat (keys inner) (keys crossed) (keys isni-bad)))
        good    (for [[i ms] (map-indexed vector comps) :when (and (< 1 (count ms)) (not (refused i)))] ms)
        evidence (fn [a b] (let [ps (path-statements g [:item a] [:item b])]
                             (if (seq ps) ps
                                 [{:same-record-id (first (sort (filter (own b) (own a))))}])))]
    {:entity    (into {} (for [ms good m ms] [m (first ms)]))
     :links     (vec (for [ms good [a b] (pairs ms)]
                       {:a a :b b :basis :equivalence :evidence (evidence a b)}))
     :conflicts (vec (concat
                      (for [[i cs] (sort isni-bad) :let [ms (comps i)]]
                        {:reason   :isni-clash
                         :members  ms
                         :isni     (sorted-by-str (for [m ms :let [x (isnis m)] :when (seq x)] [m x]))
                         :pairs    cs
                         :evidence (vec (distinct (mapcat (fn [[a b]] (path-statements g [:item a] [:item b])) cs)))})
                      (for [[i rs] (sort inner)]
                        {:reason :pseudonym-in-component :members (comps i) :relations rs})
                      (for [[i rs] crossed]
                        {:reason :pseudonym-crossed :members (comps i) :with (partner i) :relations rs})))
     :warnings  (vec (concat
                      (for [ms good
                            [a b] (pairs ms)
                            w (let [same-file? (and (= (file-of (auths a)) (file-of (auths b))) (not-any? (own a) (own b)))]
                                (concat
                                 (let [x (isnis a) y (isnis b)]
                                   (when (and (seq x) (seq y) (not-any? (set x) y))
                                     [{:kind :isni-disagreement :members [a b] :isni (sorted-by-str {a x b y})}]))
                                 (when-not same-file?
                                   (for [s ["viaf" "gnd" "lcnaf" "wikidata"]
                                         :let [x (scheme a s) y (scheme b s)]
                                         :when (and (seq x) (seq y) (not-any? (set x) y))]
                                     {:kind :scheme-disagreement :scheme s :members [a b] :ids (sorted-by-str {a x b y})}))
                                 (when same-file?
                                   [{:kind :same-file :file (file-of (auths a)) :members [a b]}])))]
                        w)
                      (mapcat (comp :invalid st) (keys auths))))}))

(defn stated
  "What each of authority `entries` (a pool's `[{:id :isni :same-as
   :relations} ..]`) states of its entity, `{id {:isni [..] :same-as [..]
   :pseudonym [..] :real-identity [..]}}`, ready to be merged into the
   authority items of `cluster`: its ISNIs, its `sameAs`, and its
   pseudonym relations — a GND record's own, and an INTERMARC 331 whose
   direction its second indicator states (`:target`), resolved to the
   entry of that record number."
  [entries]
  (let [by-n (into {} (for [e entries :when (and (:id e) (:record-number e))] [(:record-number e) (:id e)]))]
    (into {}
          (for [e entries :when (:id e)
                :let [rel (fn [t] (keep #(when (and (= :pseudonym (:kind %)) (= t (:target %))) (by-n (:record-number %)))
                                        (:relations e)))
                      ps  (vec (distinct (concat (many (:pseudonym e)) (rel :pseudonym))))
                      rs  (vec (distinct (concat (many (:real-identity e)) (rel :real-identity))))]]
            [(:id e) (cond-> {}
                       (seq (many (:isni e))) (assoc :isni (vec (many (:isni e))))
                       (seq (:same-as e))     (assoc :same-as (vec (:same-as e)))
                       (seq ps)               (assoc :pseudonym ps)
                       (seq rs)               (assoc :real-identity rs))]))))
