(ns clj-pool-party.core
  (:import (clojure.lang Ref)
           (java.util UUID)
           (java.util.concurrent.locks ReentrantLock)
           (java.util.concurrent Semaphore)))

;;TODO add support for health check on acquire vs release
;;TODO add shutdown support

(defn gen-key []
  (str (UUID/randomUUID)))

(defn ^Ref build-pool
  ([gen-fn max-size]
   (build-pool gen-fn max-size {}))
  ([gen-fn max-size {:keys [health-check-fn close-fn]}]
   "Builds and returns a clojure.lang.Ref representing an object pool
    Params:
    gen-fn - 0-arity function that produces an object when called
    max-size - The maximum number of objects to hold in the pool
    Optional params:
    health-check-fn - 1-arity function to call when performing health checks for objects in the pool
    close-fn - 1-arity function to call when closing / destroying an object in the pool"
   (assert (pos? max-size) (str "max-size must be positive but was " max-size))
   (ref
     {:gen-fn          gen-fn
      :health-check-fn health-check-fn
      :close-fn        close-fn
      :writer-lock     (ReentrantLock.)
      :semaphore       (Semaphore. max-size)
      :objects         {}})))

(defmacro with-pool-writer-lock [^Ref pool-ref & body]
  "Executes 'body' in the context of a pool's writer lock.
   This is just intended for internal use, but I'm not going to try
   and stop you from calling it."
  `(let [^ReentrantLock lock# (-> ~pool-ref deref :writer-lock)]
     (try
       (.lock lock#)
       ~@body
       (finally
         (.unlock lock#)))))

(defmacro with-pool-semaphore [^Ref pool-ref & body]
  "Executes 'body' in the context of a pool's reader semaphore.
   Potentially N of these can run in parallel where N is max-size.
   Again, this is just intended for internal use, but I'm not going
   to try and stop you from calling it."
  `(let [^Semaphore sem# (-> ~pool-ref deref :semaphore)]
     (try
       (.acquire sem#)
       ~@body
       (finally
         (.release sem#)))))

(defn acquire-object [^Ref pool-ref]
  "Acquires an object from the pool. Re-uses an available object if present but will
  call gen-fn if no objects are available but space remains in the pool.
  This is just intended for internal use, but I'm not going to try
  and stop you from calling it."
  (with-pool-writer-lock pool-ref
    (dosync
      (if-let [[k entry] (->> @pool-ref :objects
                           (filter #(-> % second :available?))
                           (first))]
        (do
          (ref-set pool-ref (assoc-in @pool-ref [:objects k :available?] false))
          [k (:obj entry)])
        (let [k (gen-key)
              obj ((-> @pool-ref :gen-fn))]
          (ref-set pool-ref (assoc-in @pool-ref [:objects k] {:obj obj :available? false}))
          [k obj])))))

(defn release-object [^Ref pool-ref k]
  "Returns the object at key 'k' back to the pool.
  For internal use only."
  (with-pool-writer-lock pool-ref
    (dosync
      (ref-set pool-ref (assoc-in @pool-ref [:objects k :available?] true)))))

(defn with-object [^Ref pool-ref f]
  "Borrows an object from the pool, applies it to f, and returns the result."
  (with-pool-semaphore pool-ref
    (let [[k obj] (acquire-object pool-ref)]
      (try
        (f obj)
        (finally (release-object pool-ref k))))))
