(ns palomar.plugins.oai
  "OAI-PMH 2.0 (ADR 0037): read the records of a harvest response and hand
   each one's metadata to the spoke that reads its format.

   An OAI-PMH response (`GetRecord` or `ListRecords`) wraps every record in a
   header — its identifier, datestamp, sets, and whether it was deleted — and a
   `<metadata>` element in one format (`oai_dc`, `marcxml`, `mods`). This
   adapter is not a spoke: it unwraps the envelope, lets the spoke of the
   record's format import the metadata, and keeps the header on the record's
   provenance (`[:provenance :source :oai]`), so a converted record still says
   which repository item it came from.

   Harvesting is the caller's: `harvest` takes the function that fetches a URL,
   so Palomar opens no connection of its own and a test, a cache or a rate
   limiter can stand in for the network. It follows the resumption tokens and
   returns the pages lazily."
  (:require [clojure.data.xml :as data-xml]
            [clojure.string :as str]
            [palomar.spokes :as spokes]
            [palomar.xml :as rx]))

(def formats
  "OAI metadata prefix → the source spoke that reads its records."
  {"oai_dc"  :dc
   "marcxml" :marc21
   "marc21"  :marc21
   "mods"    :mods})

(defn- local [tag] (when tag (name tag)))

(defn- children
  ([el] (filter map? (:content el)))
  ([el nm] (filter #(= nm (local (:tag %))) (children el))))

(defn- child [el nm] (first (children el nm)))

(defn- text [el] (some-> el :content (->> (filter string?) (apply str)) str/trim not-empty))

(defn- header
  [h]
  (cond-> {:identifier (text (child h "identifier"))
           :datestamp  (text (child h "datestamp"))}
    (seq (children h "setSpec")) (assoc :sets (mapv text (children h "setSpec")))
    (= "deleted" (get-in h [:attrs :status])) (assoc :deleted? true)))

(defn read-response
  "The records of OAI-PMH response `xml`: `{:records [{:header {…} :metadata
   xml-string-or-nil}] :resumption-token t-or-nil :error {:code :message}-or-nil}`.
   A deleted record has a header and no metadata."
  [xml]
  (let [root  (rx/parse-str xml)
        _     (when-not (= "OAI-PMH" (local (:tag root)))
                (throw (ex-info "oai: not an OAI-PMH response" {:root (local (:tag root))})))
        err   (child root "error")
        verb  (first (remove #(#{"responseDate" "request" "error"} (local (:tag %))) (children root)))
        recs  (children verb "record")
        token (child verb "resumptionToken")]
    (cond-> {:records (vec (for [r recs
                                 :let [h  (header (child r "header"))
                                       md (first (children (child r "metadata")))]]
                             {:header h :metadata (when md (data-xml/emit-str md))}))}
      (text token) (assoc :resumption-token (text token))
      err          (assoc :error {:code (get-in err [:attrs :code]) :message (text err)}))))

(defn record-id
  "A record id for OAI identifier `id`, for a spoke that needs one given
   (Dublin Core): `:oai/<id>`, its characters outside `[A-Za-z0-9-_]` folded to
   `-`, starting with a letter so it reads back as EDN (ADR 0034)."
  [id]
  (let [n (-> (str id) (str/replace #"[^A-Za-z0-9_-]+" "-") (str/replace #"^-+|-+$" ""))]
    (keyword "oai" (if (re-find #"^[A-Za-z]" n) n (str "r" n)))))

(defn import-response
  "Import every live record of OAI-PMH response `xml`, whose metadata is in
   format `prefix`, through its spoke's importer. Returns `{:records [...]
   :diagnostics [...] :deleted [identifier…] :resumption-token t}`, each record
   carrying its OAI header at `[:provenance :source :oai]`. Throws on an OAI
   error other than `noRecordsMatch`, and on a prefix no spoke reads."
  [prefix xml]
  (let [from (or (formats prefix)
                 (throw (ex-info (str "oai: no spoke reads the metadata format " (pr-str prefix))
                                 {:prefix prefix :supported (set (keys formats))})))
        {:keys [records resumption-token error]} (read-response xml)
        _    (when (and error (not= "noRecordsMatch" (:code error)))
               (throw (ex-info (str "oai: the repository answered " (:code error) ": " (:message error)) error)))
        imp  (:importer (spokes/plugin from))
        live (remove (comp :deleted? :header) records)
        outs (for [{:keys [header metadata]} live
                   :when metadata]
               (let [{rs :records ds :diagnostics} (imp (cond-> {} (= :dc from) (assoc :record-id (record-id (:identifier header))))
                                                        metadata)]
                 {:records     (mapv #(assoc-in % [:provenance :source :oai] header) rs)
                  :diagnostics ds}))]
    (cond-> {:records     (vec (mapcat :records outs))
             :diagnostics (vec (mapcat :diagnostics outs))
             :deleted     (vec (keep #(when (get-in % [:header :deleted?]) (get-in % [:header :identifier])) records))}
      resumption-token (assoc :resumption-token resumption-token))))

(defn- query [base params]
  (str base (if (str/includes? base "?") "&" "?")
       (str/join "&" (for [[k v] params]
                       (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8"))))))

(defn harvest
  "The pages of a `ListRecords` harvest of `base-url` in format `prefix`, as a
   lazy seq of `read-response` results, following each resumption token.
   `fetch` is `(fn [url] -> response-xml)`; `opts` may carry `:set`, `:from`
   and `:until`."
  ([fetch base-url prefix] (harvest fetch base-url prefix {}))
  ([fetch base-url prefix {:keys [set from until]}]
   (let [first-url (query base-url (cond-> [[:verb "ListRecords"] [:metadataPrefix prefix]]
                                     set   (conj [:set set])
                                     from  (conj [:from from])
                                     until (conj [:until until])))
         page      (fn page [url]
                     (lazy-seq
                      (let [xml (fetch url)
                            r   (read-response xml)]
                        (cons (assoc r :xml xml)
                              (when-let [t (:resumption-token r)]
                                (page (query base-url [[:verb "ListRecords"] [:resumptionToken t]])))))))]
     (page first-url))))
