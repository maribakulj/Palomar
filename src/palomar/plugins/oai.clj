(ns palomar.plugins.oai
  "OAI-PMH 2.0 (ADR 0037): read the records of a harvest response and hand
   each one's metadata to the spoke that reads its format.

   An OAI-PMH response (`GetRecord` or `ListRecords`) wraps every record in a
   header — its identifier, datestamp, sets, and whether it was deleted — and a
   `<metadata>` element in one format (`oai_dc`, `marcxml`, `mods`). This
   adapter is not a spoke: it unwraps the envelope, lets the spoke of the
   record's format import the metadata, and keeps the header on the record's
   provenance (`[:provenance :source :oai]`), so a converted record still says
   which repository item it came from, beside the SHA-256 of the metadata
   document the values' XPaths point into (`:metadata-sha256`): the response
   is one document, each record's metadata another. A deleted record is
   reported (`:deleted`) all the way to `convert`, so a synchronisation can
   remove it.

   Harvesting is the caller's: `harvest` takes the function that fetches a URL,
   so Palomar opens no connection of its own and a test, a cache or a rate
   limiter can stand in for the network. It follows the resumption tokens and
   returns the pages lazily."
  (:require [clojure.data.xml :as data-xml]
            [clojure.string :as str]
            [palomar.model :as model]
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
   (Dublin Core): `:oai/<name>`. The name keeps letters, digits and `-`, and
   writes every other character as `_` and its UTF-8 bytes in hex (`:` is
   `_3A`, `_` itself `_5F`), as is a first character that is not a letter. So
   two identifiers never share a record id (`record-identifier` reads it back),
   and the keyword reads back as EDN (ADR 0034)."
  [id]
  (let [chars (map #(String. (Character/toChars (int %))) (.toArray (.codePoints (str id))))]
    (keyword "oai"
             (apply str
                    (map-indexed
                     (fn [i c]
                       (if (re-matches (if (zero? i) #"[A-Za-z]" #"[A-Za-z0-9-]") c)
                         c
                         (apply str (map #(format "_%02X" (bit-and (int %) 0xff)) (.getBytes ^String c "UTF-8")))))
                     chars)))))

(defn record-identifier
  "The OAI identifier `record-id` made `rid` from."
  [rid]
  (let [n   (name rid)
        out (java.io.ByteArrayOutputStream.)]
    (loop [i 0]
      (when (< i (count n))
        (if (= \_ (.charAt n i))
          (do (.write out (Integer/parseInt (subs n (inc i) (+ i 3)) 16)) (recur (+ i 3)))
          (do (.write out (.getBytes (subs n i (inc i)) "UTF-8")) (recur (inc i))))))
    (String. (.toByteArray out) "UTF-8")))

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
                 ;; three levels: the response (its digest, added by `convert`), the
                 ;; harvested record (its header) and the metadata document the
                 ;; values' XPaths point into (its digest)
                 {:records     (mapv #(assoc-in % [:provenance :source :oai]
                                                (assoc header :metadata-sha256 (model/sha256-hex metadata 64)))
                                     rs)
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
