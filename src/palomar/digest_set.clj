(ns palomar.digest-set
  "A set of ids kept as 64-bit digests, for a stream that must tell an id it
   has seen from one it has not without holding the ids (post-audit 7, I3).

   Each id is the first 64 bits of its SHA-256, in an open-addressing table
   of longs at most half full: about 16 bytes an id, whatever its length,
   where a set of the strings takes several times that. Two distinct ids
   share a digest with a probability of about n²/2⁶⁵, 3·10⁻⁸ for a million
   ids: a duplicate it reports may, that rarely, be none. It is mutable and
   for one thread: the stream that owns it.")

(defn create
  "An empty set."
  []
  (volatile! {:table (long-array 1024) :n 0}))

(defn- digest
  "The first 64 bits of the SHA-256 of `id`'s string, never 0 (the table's
   empty slot)."
  ^long [id]
  (let [b (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes (str id) "UTF-8"))
        d (.getLong (java.nio.ByteBuffer/wrap b))]
    (if (zero? d) 1 d)))

(defn- slot ^long [^long d ^long mask]
  (bit-and (bit-xor d (unsigned-bit-shift-right d 29)) mask))

(defn- insert!
  "Put `d` in `table`; true when it was there already."
  [^longs table ^long d]
  (let [mask (dec (alength table))]
    (loop [i (slot d mask)]
      (let [v (aget table i)]
        (cond (zero? v)  (do (aset table i d) false)
              (== v d)   true
              :else      (recur (bit-and (inc i) mask)))))))

(defn- grow ^longs [^longs table]
  (let [bigger (long-array (* 2 (alength table)))]
    (doseq [^long v table :when (not (zero? v))] (insert! bigger v))
    bigger))

(defn seen!
  "Add `id` to `s`: true when it was there already."
  [s id]
  (let [{:keys [table n]} @s
        table (if (> (* 2 (inc n)) (alength ^longs table)) (grow table) table)
        dup?  (insert! table (digest id))]
    (vreset! s {:table table :n (if dup? n (inc n))})
    dup?))

(defn size
  "How many distinct ids `s` holds."
  [s]
  (:n @s))

(defn capacity
  "The slots of `s`'s table: its memory is 8 bytes a slot."
  [s]
  (alength ^longs (:table @s)))
