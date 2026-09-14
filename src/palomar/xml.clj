(ns palomar.xml
  "Hardened XML-parsing façade — the single entry point through which every
   Palomar importer reads untrusted documentary input.

   All XML reaches the runtime through `clojure.data.xml` (StAX under the hood).
   Two of its parsing defaults are unsafe against hostile input:

   - **Entity-expansion DoS (`billion laughs`).** DTD processing is on by
     default and internal general entities expand with *no* size bound. A small
     payload of nested entity references (`&lol9;` → ten `&lol8;` → …) expands
     to gigabytes in memory. The JDK's `jdk.xml.entityExpansionLimit` does *not*
     fire through data.xml's StAX path — verified: a five-level payload expanded
     to 100 000 characters with no error.
   - **XXE / external entities.** data.xml does *not* resolve external
     `SYSTEM`/`PUBLIC` identifiers — verified: an `&xxe;` referencing a local
     file is left unresolved, so there is no file-disclosure or SSRF surface.
     But a DTD is still the vehicle for the expansion DoS above.

   Both holes close the same way: **refuse DTDs entirely.** `:support-dtd false`
   makes the parser throw on any `<!DOCTYPE …>`. Palomar has no fixture or
   supported format that legitimately needs a DTD (verified WP-9 across every
   committed fixture), so the policy is total rather than per-format.
   `:supporting-external-entities false` is the data.xml default; it is pinned
   here explicitly so the posture is self-documenting and survives a default
   change upstream.

   Every importer MUST parse through `parse-str` / `parse` here and never call
   `clojure.data.xml/parse{,-str}` directly. SECURITY.md cites this namespace as
   the input-parsing posture; `palomar.xml-test` pins the behaviour."
  (:require [clojure.data.xml :as data-xml]))

;; data.xml / StAX option semantics:
;;   :support-dtd false               -> throw on <!DOCTYPE …>; kills the
;;                                       entity-expansion DoS *and* any
;;                                       DTD-borne XXE in one move.
;;   :supporting-external-entities    -> already false by default; pinned for
;;     false                            defence in depth and auditability.
(def ^:private safe-opts
  [:support-dtd                  false
   :supporting-external-entities false])

(defn parse-str
  "Parse XML `s` (a String) like `clojure.data.xml/parse-str`, but refuse DTDs.
   Throws on a `<!DOCTYPE …>` — see the namespace doc for why this is the whole
   input-hardening story (entity-expansion DoS + DTD-borne XXE)."
  [s]
  (apply data-xml/parse-str s safe-opts))

(defn parse
  "Parse XML from `readable` (a `Reader`/`InputStream`) like
   `clojure.data.xml/parse`, but refuse DTDs. Throws on a `<!DOCTYPE …>`.
   This is the streaming variant; see `parse-str` and the namespace doc."
  [readable]
  (apply data-xml/parse readable safe-opts))
